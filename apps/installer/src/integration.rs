//! Per-user OS integration. These files point directly at the immutable Electron runtime through
//! the stable `current` filesystem pointer; no Rust proxy is part of the launch path.

use std::fs;
#[cfg(windows)]
use std::path::Path;
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

/// Checks the durable integration surface without modifying it. Repair uses this alongside
/// immutable version validation so a missing shortcut, desktop file, or icon is observable as
/// degraded installation state rather than silently reported as healthy.
pub fn is_complete(paths: &InstallationPaths) -> bool {
    match paths.platform {
        Platform::Linux => linux_integration_complete(paths),
        Platform::Windows => windows_integration_complete(paths),
    }
}

fn linux_integration_complete(paths: &InstallationPaths) -> bool {
    if !current_target_is_valid(paths) {
        return false;
    }
    let executable = paths.current_desktop_executable_path();
    let icon = paths.linux_icon_path();
    let entry = paths.linux_desktop_entry_path();
    let Ok(content) = fs::read_to_string(&entry) else {
        return false;
    };
    let executable = desktop_exec_path(&executable.display().to_string());
    let icon = desktop_exec_path(&icon.display().to_string());
    content
        == format!(
            "[Desktop Entry]\nType=Application\nName=HarmoniaSuite\nExec={executable}\nTryExec={executable}\nIcon={icon}\nCategories=AudioVideo;\nTerminal=false\n"
        )
        && paths.linux_icon_path().is_file()
        && paths.current_desktop_executable_path().is_file()
}

fn current_target_is_valid(paths: &InstallationPaths) -> bool {
    crate::current_pointer::target(paths).is_ok_and(|target| target.is_some())
}

#[cfg(windows)]
fn windows_registry_complete(paths: &InstallationPaths) -> bool {
    use windows_sys::Win32::System::Registry::{
        RegCloseKey, RegOpenKeyExW, RegQueryValueExW, HKEY_CURRENT_USER, KEY_READ, REG_SZ,
    };
    let key = to_wide(paths.windows_uninstall_registry_key());
    let mut handle = std::ptr::null_mut();
    let result =
        unsafe { RegOpenKeyExW(HKEY_CURRENT_USER, key.as_ptr(), 0, KEY_READ, &mut handle) };
    if result != 0 {
        return false;
    }
    let Some(product_version) = installed_product_version(paths) else {
        return false;
    };
    let expected = [
        ("DisplayName", "HarmoniaSuite".to_owned()),
        ("DisplayVersion", product_version.clone()),
        ("Publisher", "AngelicaProject".to_owned()),
        ("InstallLocation", paths.app_root.display().to_string()),
        (
            "DisplayIcon",
            paths
                .current_desktop_executable_path()
                .display()
                .to_string(),
        ),
        (
            "UninstallString",
            format!("\"{}\" uninstall", paths.installer_binary_path().display()),
        ),
        (
            "QuietUninstallString",
            format!("\"{}\" uninstall", paths.installer_binary_path().display()),
        ),
    ];
    let values_match = expected.iter().all(|(name, expected)| {
        let name = to_wide(name);
        let mut kind = 0;
        let mut size = 0;
        let query = unsafe {
            RegQueryValueExW(
                handle,
                name.as_ptr(),
                std::ptr::null_mut(),
                &mut kind,
                std::ptr::null_mut(),
                &mut size,
            )
        };
        if query != 0 || kind != REG_SZ || size < 2 {
            return false;
        }
        let mut bytes = vec![0u8; size as usize];
        let query = unsafe {
            RegQueryValueExW(
                handle,
                name.as_ptr(),
                std::ptr::null_mut(),
                &mut kind,
                bytes.as_mut_ptr(),
                &mut size,
            )
        };
        if query != 0 || kind != REG_SZ {
            return false;
        }
        let units =
            unsafe { std::slice::from_raw_parts(bytes.as_ptr() as *const u16, size as usize / 2) };
        let actual = String::from_utf16_lossy(units)
            .trim_end_matches('\0')
            .to_owned();
        actual == expected.as_str()
    });
    unsafe { RegCloseKey(handle) };
    values_match
        && windows_shortcut_targets(
            &paths.windows_start_menu_shortcut_path(),
            &paths.current_desktop_executable_path(),
        )
}

#[cfg(windows)]
fn windows_integration_complete(paths: &InstallationPaths) -> bool {
    windows_registry_complete(paths)
}

#[cfg(not(windows))]
fn windows_integration_complete(_paths: &InstallationPaths) -> bool {
    true
}

#[cfg(windows)]
fn windows_shortcut_targets(path: &Path, target: &Path) -> bool {
    use std::mem::transmute;
    use windows_sys::core::GUID;
    use windows_sys::Win32::System::Com::{
        CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_INPROC_SERVER,
        COINIT_APARTMENTTHREADED,
    };

    #[repr(C)]
    struct ComObject {
        vtable: *const usize,
    }
    type QueryInterface =
        unsafe extern "system" fn(*mut ComObject, *const GUID, *mut *mut std::ffi::c_void) -> i32;
    type Release = unsafe extern "system" fn(*mut ComObject) -> u32;
    type Load = unsafe extern "system" fn(*mut ComObject, *const u16, u32) -> i32;
    type GetPath =
        unsafe extern "system" fn(*mut ComObject, *mut u16, i32, *mut std::ffi::c_void, u32) -> i32;

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
    let init = unsafe { CoInitializeEx(std::ptr::null(), COINIT_APARTMENTTHREADED as u32) };
    if init < 0 {
        return false;
    }
    let mut shell: *mut ComObject = std::ptr::null_mut();
    let created = unsafe {
        CoCreateInstance(
            &clsid_shell_link,
            std::ptr::null_mut(),
            CLSCTX_INPROC_SERVER,
            &iid_shell_link,
            &mut shell as *mut _ as *mut *mut std::ffi::c_void,
        )
    };
    if created < 0 || shell.is_null() {
        unsafe { CoUninitialize() };
        return false;
    }
    let query: QueryInterface = unsafe { transmute::<usize, QueryInterface>(*((*shell).vtable)) };
    let mut persist: *mut ComObject = std::ptr::null_mut();
    let queried = unsafe {
        query(
            shell,
            &iid_persist_file,
            &mut persist as *mut _ as *mut *mut std::ffi::c_void,
        )
    };
    if queried < 0 || persist.is_null() {
        let release: Release = unsafe { transmute::<usize, Release>(*((*shell).vtable.add(2))) };
        unsafe {
            release(shell);
            CoUninitialize();
        }
        return false;
    }
    let load: Load = unsafe { transmute::<usize, Load>(*((*persist).vtable.add(5))) };
    let loaded = unsafe { load(persist, to_wide(path).as_ptr(), 0) };
    let mut value = vec![0u16; 32768];
    let get_path: GetPath = unsafe { transmute::<usize, GetPath>(*((*shell).vtable.add(3))) };
    let got_path = if loaded >= 0 {
        unsafe {
            get_path(
                shell,
                value.as_mut_ptr(),
                value.len() as i32,
                std::ptr::null_mut(),
                0,
            )
        }
    } else {
        -1
    };
    let release_persist: Release =
        unsafe { transmute::<usize, Release>(*((*persist).vtable.add(2))) };
    let release_shell: Release = unsafe { transmute::<usize, Release>(*((*shell).vtable.add(2))) };
    unsafe {
        release_persist(persist);
        release_shell(shell);
        CoUninitialize();
    }
    if got_path < 0 {
        return false;
    }
    let length = value
        .iter()
        .position(|unit| *unit == 0)
        .unwrap_or(value.len());
    fs::canonicalize(String::from_utf16_lossy(&value[..length])).ok()
        == fs::canonicalize(target).ok()
}

#[cfg(windows)]
fn installed_product_version(paths: &InstallationPaths) -> Option<String> {
    crate::state::StateStore::new(paths.clone())
        .load_installation()
        .ok()
        .and_then(|state| state.product_version)
        .or_else(|| {
            fs::read(paths.installer_binary_metadata_path())
                .ok()
                .and_then(|bytes| {
                    serde_json::from_slice::<crate::helper::InstallerHelperMetadata>(&bytes)
                        .ok()
                        .map(|metadata| metadata.product_version)
                })
        })
        .filter(|value| !value.trim().is_empty())
}

fn install_linux(paths: &InstallationPaths) -> Result<(), IntegrationError> {
    if !current_target_is_valid(paths) {
        return Err(IntegrationError::InvalidPath(paths.current_pointer_path()));
    }
    let executable = paths.current_desktop_executable_path();
    let icon = paths.linux_icon_path();
    let entry = paths.linux_desktop_entry_path();
    for path in [&executable, &icon, &entry] {
        if !path.is_absolute() {
            return Err(IntegrationError::InvalidPath(path.to_path_buf()));
        }
    }
    if !executable.is_file() {
        return Err(IntegrationError::InvalidPath(executable));
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
        "[Desktop Entry]\nType=Application\nName=HarmoniaSuite\nExec={}\nTryExec={}\nIcon={}\nCategories=AudioVideo;\nTerminal=false\n",
        desktop_exec_path(&executable.display().to_string()),
        desktop_exec_path(&executable.display().to_string()),
        desktop_exec_path(&icon.display().to_string()),
    );
    crate::state::atomic_write_bytes(&entry, content.as_bytes())?;
    Ok(())
}

#[cfg(windows)]
fn install_windows(
    paths: &InstallationPaths,
    desktop_shortcut: bool,
) -> Result<(), IntegrationError> {
    if !current_target_is_valid(paths) || !paths.current_desktop_executable_path().is_file() {
        return Err(IntegrationError::InvalidPath(
            paths.current_desktop_executable_path(),
        ));
    }
    write_windows_shortcut(
        &paths.windows_start_menu_shortcut_path(),
        &paths.current_desktop_executable_path(),
    )?;
    if desktop_shortcut {
        write_windows_shortcut(
            &paths.windows_desktop_shortcut_path(),
            &paths.current_desktop_executable_path(),
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
    let query: QueryInterface =
        unsafe { transmute::<usize, QueryInterface>(*((*shell).vtable.add(0))) };
    let query_result = unsafe {
        query(
            shell,
            &iid_persist_file,
            &mut persist as *mut _ as *mut *mut std::ffi::c_void,
        )
    };
    let target_wide = to_wide(target);
    let path_wide = to_wide(path);
    let set_path: SetPath = unsafe { transmute::<usize, SetPath>(*((*shell).vtable.add(20))) };
    let set_description: SetDescription =
        unsafe { transmute::<usize, SetDescription>(*((*shell).vtable.add(7))) };
    let release: Release = unsafe { transmute::<usize, Release>(*((*shell).vtable.add(2))) };
    let save: Save = if !persist.is_null() {
        unsafe { transmute::<usize, Save>(*((*persist).vtable.add(6))) }
    } else {
        unsafe { release(shell) };
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
        release(persist);
        release(shell);
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
        RegCloseKey, RegCreateKeyExW, RegSetValueExW, HKEY_CURRENT_USER, KEY_WRITE, REG_DWORD,
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
    let product_version = installed_product_version(paths).ok_or_else(|| {
        IntegrationError::Io(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "installation product version is missing",
        ))
    })?;
    let values = [
        ("DisplayName", "HarmoniaSuite".to_owned()),
        ("DisplayVersion", product_version),
        ("Publisher", "AngelicaProject".to_owned()),
        ("InstallLocation", paths.app_root.display().to_string()),
        (
            "DisplayIcon",
            paths
                .current_desktop_executable_path()
                .display()
                .to_string(),
        ),
        (
            "UninstallString",
            format!("\"{}\" uninstall", paths.installer_binary_path().display()),
        ),
        (
            "QuietUninstallString",
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
    let no_modify = to_wide("NoModify");
    let no_modify_value: u32 = 1;
    let result = unsafe {
        RegSetValueExW(
            handle,
            no_modify.as_ptr(),
            0,
            REG_DWORD,
            &no_modify_value as *const u32 as *const u8,
            std::mem::size_of::<u32>() as u32,
        )
    };
    if result != 0 {
        unsafe { RegCloseKey(handle) };
        return Err(IntegrationError::Registry(result));
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

fn desktop_exec_path(value: &str) -> String {
    format!("\"{}\"", value.replace('\\', "\\\\").replace('"', "\\\""))
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
    fn linux_desktop_entry_uses_direct_current_electron_and_icon() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let commit = "a".repeat(40);
        let desktop = paths.versions_dir().join(&commit).join("desktop");
        fs::create_dir_all(&desktop).unwrap();
        fs::write(desktop.join(paths.desktop_executable_name()), b"electron").unwrap();
        crate::current_pointer::switch(&paths, &commit).unwrap();
        install(&paths, false).unwrap();
        let entry = fs::read_to_string(paths.linux_desktop_entry_path()).unwrap();
        assert!(entry.contains("Name=HarmoniaSuite"));
        assert!(entry.contains(&desktop_exec_path(
            &paths
                .current_desktop_executable_path()
                .display()
                .to_string(),
        )));
        assert!(!entry.contains("%U"));
        assert!(paths.linux_icon_path().is_file());
        assert!(is_complete(&paths));
        fs::write(
            paths.linux_desktop_entry_path(),
            "[Desktop Entry]\nExec=wrong\n",
        )
        .unwrap();
        assert!(!is_complete(&paths));
        install(&paths, false).unwrap();
        assert!(is_complete(&paths));
        remove(&paths).unwrap();
        assert!(!paths.linux_desktop_entry_path().exists());
    }
}
