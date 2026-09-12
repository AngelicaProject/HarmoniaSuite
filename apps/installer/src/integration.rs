//! Per-user OS integration. These files are derived from stable launcher paths;
//! no mutable checkout or system-wide registration is used.

use std::fs;
use std::path::PathBuf;

use thiserror::Error;

use crate::paths::{InstallationPaths, Platform};
use crate::state::validate_managed_path;

const ICON_SVG: &str = include_str!("../assets/harmoniasuite.svg");

#[derive(Debug, Error)]
pub enum IntegrationError {
    #[error("OS integration I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("OS integration path is invalid: {0}")]
    InvalidPath(PathBuf),
    #[error("OS integration state failed: {0}")]
    State(#[from] crate::state::StateError),
    #[cfg(windows)]
    #[error("Windows registry operation failed: {0}")]
    Registry(u32),
}

pub fn install(paths: &InstallationPaths, desktop_shortcut: bool) -> Result<(), IntegrationError> {
    match paths.platform {
        Platform::Linux => install_linux(paths),
        Platform::Windows => install_windows(paths, desktop_shortcut),
    }
}

pub fn remove(paths: &InstallationPaths) -> Result<(), IntegrationError> {
    let files = match paths.platform {
        Platform::Linux => vec![paths.linux_desktop_entry_path(), paths.linux_icon_path()],
        Platform::Windows => vec![
            paths.windows_start_menu_shortcut_path(),
            paths.windows_desktop_shortcut_path(),
        ],
    };
    for path in files {
        let parent = path
            .parent()
            .ok_or_else(|| IntegrationError::InvalidPath(path.clone()))?;
        validate_managed_path(parent, &path)
            .map_err(|_| IntegrationError::InvalidPath(path.clone()))?;
        match fs::remove_file(path) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
    }
    #[cfg(windows)]
    remove_windows_registry(paths)?;
    Ok(())
}

fn install_linux(paths: &InstallationPaths) -> Result<(), IntegrationError> {
    let launcher = paths.stable_launcher_path();
    let icon = paths.linux_icon_path();
    let entry = paths.linux_desktop_entry_path();
    for path in [&launcher, &icon, &entry] {
        if !path.is_absolute() {
            return Err(IntegrationError::InvalidPath(path.to_path_buf()));
        }
    }
    validate_managed_path(
        entry
            .parent()
            .and_then(std::path::Path::parent)
            .ok_or_else(|| IntegrationError::InvalidPath(entry.clone()))?,
        &entry,
    )
    .map_err(|_| IntegrationError::InvalidPath(entry.clone()))?;
    fs::create_dir_all(
        icon.parent()
            .ok_or_else(|| IntegrationError::InvalidPath(icon.clone()))?,
    )?;
    crate::state::atomic_write_bytes(&icon, ICON_SVG.as_bytes())?;
    fs::create_dir_all(
        entry
            .parent()
            .ok_or_else(|| IntegrationError::InvalidPath(entry.clone()))?,
    )?;
    let content = format!(
        "[Desktop Entry]\nType=Application\nName=HarmoniaSuite\nExec={} %U\nTryExec={}\nIcon={}\nCategories=AudioVideo;\nTerminal=false\n",
        escape_desktop_field(&launcher.display().to_string()),
        escape_desktop_field(&launcher.display().to_string()),
        escape_desktop_field(&icon.display().to_string()),
    );
    crate::state::atomic_write_bytes(&entry, content.as_bytes())?;
    Ok(())
}

#[cfg(windows)]
fn install_windows(
    paths: &InstallationPaths,
    desktop_shortcut: bool,
) -> Result<(), IntegrationError> {
    write_windows_shortcut(
        &paths.windows_start_menu_shortcut_path(),
        &paths.stable_launcher_path(),
    )?;
    if desktop_shortcut {
        write_windows_shortcut(
            &paths.windows_desktop_shortcut_path(),
            &paths.stable_launcher_path(),
        )?;
    }
    write_windows_registry(paths)
}

#[cfg(not(windows))]
fn install_windows(
    _paths: &InstallationPaths,
    _desktop_shortcut: bool,
) -> Result<(), IntegrationError> {
    Ok(())
}

#[cfg(windows)]
fn write_windows_shortcut(
    path: &std::path::Path,
    target: &std::path::Path,
) -> Result<(), IntegrationError> {
    use std::mem::transmute;
    use windows_sys::core::GUID;
    use windows_sys::Win32::System::Com::{
        CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_INPROC_SERVER,
        COINIT_APARTMENTTHREADED,
    };
    let parent = path
        .parent()
        .ok_or_else(|| IntegrationError::InvalidPath(path.to_path_buf()))?;
    validate_managed_path(parent, path)
        .map_err(|_| IntegrationError::InvalidPath(path.to_path_buf()))?;
    fs::create_dir_all(parent)?;
    let result = unsafe { CoInitializeEx(std::ptr::null(), COINIT_APARTMENTTHREADED as u32) };
    if result < 0 {
        return Err(IntegrationError::Registry(result as u32));
    }

    #[repr(C)]
    struct ComObject {
        vtable: *const usize,
    }
    type QueryInterface =
        unsafe extern "system" fn(*mut ComObject, *const GUID, *mut *mut std::ffi::c_void) -> i32;
    type SetPath = unsafe extern "system" fn(*mut ComObject, *const u16) -> i32;
    type SetDescription = unsafe extern "system" fn(*mut ComObject, *const u16) -> i32;
    type Save = unsafe extern "system" fn(*mut ComObject, *const u16, i32) -> i32;
    type Release = unsafe extern "system" fn(*mut ComObject) -> u32;

    let clsid_shell_link = GUID {
        data1: 0x00021401,
        data2: 0,
        data3: 0,
        data4: [0xc0, 0, 0, 0, 0, 0, 0, 0x46],
    };
    let iid_shell_link = GUID {
        data1: 0x000214f9,
        data2: 0,
        data3: 0,
        data4: [0xc0, 0, 0, 0, 0, 0, 0, 0x46],
    };
    let iid_persist_file = GUID {
        data1: 0x0000010b,
        data2: 0,
        data3: 0,
        data4: [0xc0, 0, 0, 0, 0, 0, 0, 0x46],
    };
    let mut shell: *mut ComObject = std::ptr::null_mut();
    let create = unsafe {
        CoCreateInstance(
            &clsid_shell_link,
            std::ptr::null_mut(),
            CLSCTX_INPROC_SERVER,
            &iid_shell_link,
            &mut shell as *mut _ as *mut *mut std::ffi::c_void,
        )
    };
    if create < 0 || shell.is_null() {
        unsafe { CoUninitialize() };
        return Err(IntegrationError::Registry(create as u32));
    }
    let mut persist: *mut ComObject = std::ptr::null_mut();
    let query: QueryInterface = unsafe { transmute((*shell.vtable)[0]) };
    let query_result = unsafe {
        query(
            shell,
            &iid_persist_file,
            &mut persist as *mut _ as *mut *mut std::ffi::c_void,
        )
    };
    let target_wide = to_wide(target);
    let path_wide = to_wide(path);
    let set_path: SetPath = unsafe { transmute((*shell.vtable)[20]) };
    let set_description: SetDescription = unsafe { transmute((*shell.vtable)[7]) };
    let save: Save = if !persist.is_null() {
        unsafe { transmute((*persist.vtable)[6]) }
    } else {
        unsafe { Release(shell) };
        unsafe { CoUninitialize() };
        return Err(IntegrationError::Registry(query_result as u32));
    };
    let path_result = unsafe { set_path(shell, target_wide.as_ptr()) };
    let description_result = unsafe { set_description(shell, to_wide("HarmoniaSuite").as_ptr()) };
    let save_result = if path_result >= 0 && description_result >= 0 {
        unsafe { save(persist, path_wide.as_ptr(), 1) }
    } else {
        path_result
    };
    unsafe {
        Release(persist);
        Release(shell);
        CoUninitialize();
    }
    if save_result < 0 {
        return Err(IntegrationError::Registry(save_result as u32));
    }
    Ok(())
}

#[cfg(windows)]
fn write_windows_registry(paths: &InstallationPaths) -> Result<(), IntegrationError> {
    use windows_sys::Win32::System::Registry::{
        RegCloseKey, RegCreateKeyExW, RegSetValueExW, HKEY_CURRENT_USER, KEY_WRITE,
        REG_OPTION_NON_VOLATILE, REG_SZ,
    };
    let key = to_wide(paths.windows_uninstall_registry_key());
    let mut handle = std::ptr::null_mut();
    let result = unsafe {
        RegCreateKeyExW(
            HKEY_CURRENT_USER,
            key.as_ptr(),
            0,
            std::ptr::null(),
            REG_OPTION_NON_VOLATILE,
            KEY_WRITE,
            std::ptr::null(),
            &mut handle,
            std::ptr::null_mut(),
        )
    };
    if result != 0 {
        return Err(IntegrationError::Registry(result));
    }
    let values = [
        ("DisplayName", "HarmoniaSuite".to_owned()),
        (
            "DisplayIcon",
            paths.stable_launcher_path().display().to_string(),
        ),
        (
            "UninstallString",
            format!("\"{}\" uninstall", paths.installer_binary_path().display()),
        ),
    ];
    for (name, value) in values {
        let name = to_wide(name);
        let value = to_wide(value);
        let result = unsafe {
            RegSetValueExW(
                handle,
                name.as_ptr(),
                0,
                REG_SZ,
                value.as_ptr() as *const u8,
                (value.len() * 2) as u32,
            )
        };
        if result != 0 {
            unsafe {
                RegCloseKey(handle);
            }
            return Err(IntegrationError::Registry(result));
        }
    }
    unsafe {
        RegCloseKey(handle);
    }
    Ok(())
}

#[cfg(windows)]
fn remove_windows_registry(paths: &InstallationPaths) -> Result<(), IntegrationError> {
    use windows_sys::Win32::System::Registry::{RegDeleteTreeW, HKEY_CURRENT_USER};
    let key = to_wide(paths.windows_uninstall_registry_key());
    let result = unsafe { RegDeleteTreeW(HKEY_CURRENT_USER, key.as_ptr()) };
    if result != 0 && result != 2 {
        return Err(IntegrationError::Registry(result));
    }
    Ok(())
}

#[cfg(windows)]
fn to_wide(value: impl AsRef<std::ffi::OsStr>) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    value
        .as_ref()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect()
}

fn escape_desktop_field(value: &str) -> String {
    value.replace('\\', "\\\\").replace(' ', "\\ ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::paths::{Platform, TargetArchitecture};
    use tempfile::tempdir;

    fn paths(root: &std::path::Path) -> InstallationPaths {
        InstallationPaths {
            platform: Platform::Linux,
            architecture: TargetArchitecture::X64,
            app_root: root.join("share with spaces").join("harmonia-suite"),
            user_data_root: root.join("data"),
            state_root: root.join("state"),
            cache_root: root.join("cache"),
        }
    }

    #[test]
    fn linux_desktop_entry_uses_stable_launcher_and_icon() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        fs::create_dir_all(paths.bin_dir()).unwrap();
        fs::write(paths.stable_launcher_path(), b"launcher").unwrap();
        install(&paths, false).unwrap();
        let entry = fs::read_to_string(paths.linux_desktop_entry_path()).unwrap();
        assert!(entry.contains("Name=HarmoniaSuite"));
        assert!(entry.contains(&paths.stable_launcher_path().display().to_string()));
        assert!(paths.linux_icon_path().is_file());
        remove(&paths).unwrap();
        assert!(!paths.linux_desktop_entry_path().exists());
    }
}
