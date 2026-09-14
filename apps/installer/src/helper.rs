//! Durable publication of the setup helper used by installed desktop IPC.

use std::env;
use std::fs::{self, OpenOptions};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use uuid::Uuid;

use crate::checksum::sha256_file;
use crate::detached::{DetachedLaunchSpec, DetachedLauncher, SystemDetachedLauncher};
use crate::paths::InstallationPaths;
use crate::state::validate_managed_path;

const HELPER_METADATA_SCHEMA_VERSION: u32 = 1;

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct InstallerHelperMetadata {
    pub schema_version: u32,
    pub platform: String,
    pub architecture: String,
    pub size: u64,
    pub sha256: String,
    #[serde(default)]
    pub product_version: String,
}

#[derive(Debug, Error)]
pub enum HelperError {
    #[error("installer helper path is invalid: {0}")]
    InvalidPath(PathBuf),
    #[error("installer helper I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("installer helper checksum failed: {0}")]
    Checksum(#[from] crate::checksum::ChecksumError),
    #[error("installer helper metadata failed: {0}")]
    Json(#[from] serde_json::Error),
    #[error("installer helper cannot be replaced while it is in use: {0}")]
    AlreadyInstalled(PathBuf),
    #[error("installer helper state failed: {0}")]
    State(#[from] crate::state::StateError),
    #[error("cleanup helper argument is invalid")]
    InvalidCleanupArgument,
}

pub fn publish_installer_helper(paths: &InstallationPaths) -> Result<PathBuf, HelperError> {
    let source = env::current_exe()?;
    if !source.is_file() {
        return Err(HelperError::InvalidPath(source));
    }
    let destination = paths.installer_binary_path();
    if destination.exists() && same_file_path(&source, &destination) {
        verify_existing(paths, &source, &destination)?;
        return Ok(destination);
    }
    fs::create_dir_all(paths.bin_dir())?;
    let staging = paths
        .bin_dir()
        .join(format!(".installer-helper.{}.tmp", Uuid::new_v4().simple()));
    {
        let mut output = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&staging)?;
        let mut input = fs::File::open(&source)?;
        std::io::copy(&mut input, &mut output)?;
        output.sync_all()?;
    }
    set_executable(&staging)?;
    let metadata = helper_metadata(paths, &staging)?;
    if let Err(error) = crate::state::durable_replace_file(&staging, &destination) {
        let _ = fs::remove_file(&staging);
        return Err(error.into());
    }
    crate::state::atomic_write_json(&paths.installer_binary_metadata_path(), &metadata)?;
    set_executable(&destination)?;
    Ok(destination)
}

pub fn validate_published_binary(
    paths: &InstallationPaths,
    binary: &Path,
    metadata_path: &Path,
) -> Result<(), HelperError> {
    if !binary.is_file() || !metadata_path.is_file() {
        return Err(HelperError::InvalidPath(binary.to_path_buf()));
    }
    let metadata: InstallerHelperMetadata = serde_json::from_slice(&fs::read(metadata_path)?)?;
    let actual_size = fs::metadata(binary)?.len();
    let actual_sha = sha256_file(binary)?;
    if metadata.schema_version != HELPER_METADATA_SCHEMA_VERSION
        || metadata.platform != paths.platform.as_str()
        || metadata.architecture != paths.architecture.as_str()
        || metadata.size != actual_size
        || metadata.sha256 != actual_sha
        || metadata.product_version != current_product_version(paths)?
    {
        return Err(HelperError::AlreadyInstalled(binary.to_path_buf()));
    }
    ensure_executable(binary)
}

/// Hand off deletion of an installed setup binary to a copy that can outlive
/// the parent process. No shell is involved and the helper derives all roots
/// from InstallationPaths before deleting anything.
pub fn schedule_self_uninstall(
    paths: &InstallationPaths,
    remove_user_data: bool,
    _lock: &crate::lock::InstallationLock,
) -> Result<(), HelperError> {
    let source = env::current_exe()?;
    let nonce = Uuid::new_v4().simple().to_string();
    let helper = env::temp_dir().join(format!("HarmoniaSuite-cleanup-{nonce}.exe"));
    if paths.is_managed_path(&helper) {
        return Err(HelperError::InvalidPath(helper));
    }
    fs::create_dir_all(
        helper
            .parent()
            .ok_or_else(|| HelperError::InvalidPath(helper.clone()))?,
    )?;
    copy_to_staging(&source, &helper)?;
    set_executable(&helper)?;
    let spec = DetachedLaunchSpec::new(helper.clone())
        .args(["__cleanup"])
        .env(
            "HARMONIA_CLEANUP_PARENT_PID",
            std::process::id().to_string(),
        )
        .env(
            "HARMONIA_CLEANUP_REMOVE_USER_DATA",
            if remove_user_data { "1" } else { "0" },
        );
    let spec = spec.env("HARMONIA_CLEANUP_NONCE", &nonce);
    if let Err(error) = SystemDetachedLauncher.launch(&spec) {
        let _ = fs::remove_file(&helper);
        return Err(HelperError::Io(std::io::Error::other(error.to_string())));
    }
    Ok(())
}

/// Entry point for the copied self-cleanup image. It is intentionally not a
/// public CLI operation and accepts no paths from the caller.
pub fn run_cleanup_helper() -> Result<(), HelperError> {
    let parent = env::var("HARMONIA_CLEANUP_PARENT_PID")
        .ok()
        .and_then(|value| value.parse::<u32>().ok())
        .ok_or(HelperError::InvalidCleanupArgument)?;
    let remove_user_data = env::var("HARMONIA_CLEANUP_REMOVE_USER_DATA").as_deref() == Ok("1");
    let nonce = env::var("HARMONIA_CLEANUP_NONCE")
        .ok()
        .filter(|value| value.len() == 32 && value.bytes().all(|byte| byte.is_ascii_hexdigit()))
        .ok_or(HelperError::InvalidCleanupArgument)?;
    while process_alive(parent) {
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    let paths = InstallationPaths::current()
        .map_err(|error| HelperError::Io(std::io::Error::other(error.to_string())))?;
    let helper = env::current_exe()?;
    let expected_name = format!("HarmoniaSuite-cleanup-{nonce}.exe");
    if helper.file_name().and_then(|value| value.to_str()) != Some(expected_name.as_str())
        || paths.is_managed_path(&helper)
    {
        return Err(HelperError::InvalidCleanupArgument);
    }
    remove_root_safely(&paths.app_root)?;
    if paths.state_root != paths.app_root {
        remove_root_safely(&paths.state_root)?;
    }
    if paths.cache_root != paths.app_root && paths.cache_root != paths.state_root {
        remove_root_safely(&paths.cache_root)?;
    }
    if remove_user_data {
        remove_root_safely(&paths.user_data_root)?;
    }
    let _ = fs::remove_file(helper);
    Ok(())
}

fn remove_root_safely(root: &Path) -> Result<(), HelperError> {
    if !root.exists() {
        return Ok(());
    }
    let parent = root
        .parent()
        .ok_or_else(|| HelperError::InvalidPath(root.to_path_buf()))?;
    validate_managed_path(parent, root)?;
    fs::remove_dir_all(root)?;
    Ok(())
}

#[cfg(unix)]
fn process_alive(pid: u32) -> bool {
    unsafe {
        libc::kill(pid as libc::pid_t, 0) == 0
            || std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
    }
}

#[cfg(windows)]
fn process_alive(pid: u32) -> bool {
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::Threading::{OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION};
    let handle = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
    if handle.is_null() {
        false
    } else {
        unsafe { CloseHandle(handle) };
        true
    }
}

fn copy_to_staging(source: &Path, staging: &Path) -> Result<(), HelperError> {
    let mut output = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(staging)?;
    let mut input = fs::File::open(source)?;
    std::io::copy(&mut input, &mut output)?;
    output.sync_all()?;
    Ok(())
}

fn verify_existing(
    paths: &InstallationPaths,
    source: &Path,
    path: &Path,
) -> Result<(), HelperError> {
    if !path.is_file() {
        return Err(HelperError::InvalidPath(path.to_path_buf()));
    }
    let actual_size = fs::metadata(path)?.len();
    let actual_sha = sha256_file(path)?;
    let source_matches =
        actual_size == fs::metadata(source)?.len() && actual_sha == sha256_file(source)?;
    let metadata = match fs::read(paths.installer_binary_metadata_path()) {
        Ok(bytes) => serde_json::from_slice::<InstallerHelperMetadata>(&bytes)?,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound && source_matches => {
            let metadata = InstallerHelperMetadata {
                schema_version: HELPER_METADATA_SCHEMA_VERSION,
                platform: paths.platform.as_str().to_owned(),
                architecture: paths.architecture.as_str().to_owned(),
                size: actual_size,
                sha256: actual_sha.clone(),
                product_version: current_product_version(paths)?,
            };
            crate::state::atomic_write_json(&paths.installer_binary_metadata_path(), &metadata)?;
            metadata
        }
        Err(error) => return Err(error.into()),
    };
    if !source_matches
        || metadata.schema_version != HELPER_METADATA_SCHEMA_VERSION
        || metadata.platform != paths.platform.as_str()
        || metadata.architecture != paths.architecture.as_str()
        || metadata.size != actual_size
        || metadata.sha256 != actual_sha
    {
        return Err(HelperError::AlreadyInstalled(path.to_path_buf()));
    }
    if metadata.product_version != current_product_version(paths)? {
        let refreshed = helper_metadata(paths, path)?;
        crate::state::atomic_write_json(&paths.installer_binary_metadata_path(), &refreshed)?;
    }
    ensure_executable(path)?;
    Ok(())
}

fn helper_metadata(
    paths: &InstallationPaths,
    path: &Path,
) -> Result<InstallerHelperMetadata, HelperError> {
    Ok(InstallerHelperMetadata {
        schema_version: HELPER_METADATA_SCHEMA_VERSION,
        platform: paths.platform.as_str().to_owned(),
        architecture: paths.architecture.as_str().to_owned(),
        size: fs::metadata(path)?.len(),
        sha256: sha256_file(path)?,
        product_version: current_product_version(paths)?,
    })
}

fn current_product_version(paths: &InstallationPaths) -> Result<String, HelperError> {
    crate::state::StateStore::new(paths.clone())
        .load_installation()?
        .product_version
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| {
            HelperError::Io(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "installation product version is missing",
            ))
        })
}

fn same_file_path(left: &Path, right: &Path) -> bool {
    fs::canonicalize(left).ok() == fs::canonicalize(right).ok()
}

#[cfg(unix)]
fn set_executable(path: &Path) -> Result<(), HelperError> {
    use std::os::unix::fs::PermissionsExt;
    let mut permissions = fs::metadata(path)?.permissions();
    permissions.set_mode(0o755);
    fs::set_permissions(path, permissions)?;
    Ok(())
}

#[cfg(windows)]
fn set_executable(_path: &Path) -> Result<(), HelperError> {
    Ok(())
}

#[cfg(unix)]
fn ensure_executable(path: &Path) -> Result<(), HelperError> {
    use std::os::unix::fs::PermissionsExt;
    if fs::metadata(path)?.permissions().mode() & 0o111 == 0 {
        return Err(HelperError::InvalidPath(path.to_path_buf()));
    }
    Ok(())
}

#[cfg(windows)]
fn ensure_executable(_path: &Path) -> Result<(), HelperError> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::paths::{Platform, TargetArchitecture};
    use tempfile::tempdir;

    #[test]
    fn publishes_verified_helper_and_does_not_replace_it() {
        let root = tempdir().unwrap();
        let paths = InstallationPaths {
            platform: Platform::Linux,
            architecture: TargetArchitecture::X64,
            app_root: root.path().join("app"),
            user_data_root: root.path().join("data"),
            state_root: root.path().join("state"),
            cache_root: root.path().join("cache"),
        };
        let store = crate::state::StateStore::new(paths.clone());
        store.initialize().unwrap();
        let mut installation = store.load_installation().unwrap();
        installation.product_version = Some("1.0.12-SNAPSHOT".to_owned());
        store.save_installation(&installation).unwrap();
        let destination = publish_installer_helper(&paths).unwrap();
        assert!(destination.is_file());
        assert!(paths.installer_binary_metadata_path().is_file());
        assert!(matches!(
            publish_installer_helper(&paths),
            Ok(path) if path == destination
        ));
    }
}
