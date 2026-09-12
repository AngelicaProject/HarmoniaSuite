//! Durable publication of the setup helper used by installed desktop IPC.

use std::env;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use uuid::Uuid;

use crate::checksum::sha256_file;
use crate::paths::InstallationPaths;

const HELPER_METADATA_SCHEMA_VERSION: u32 = 1;

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct InstallerHelperMetadata {
    pub schema_version: u32,
    pub platform: String,
    pub architecture: String,
    pub size: u64,
    pub sha256: String,
}

#[derive(Debug, Error)]
pub enum HelperError {
    #[error("cannot determine installer helper executable: {0}")]
    CurrentExecutable(#[from] std::io::Error),
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
}

pub fn publish_installer_helper(paths: &InstallationPaths) -> Result<PathBuf, HelperError> {
    let source = env::current_exe()?;
    if !source.is_file() {
        return Err(HelperError::InvalidPath(source));
    }
    let destination = paths.installer_binary_path();
    if destination.exists() {
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
    if let Err(error) = crate::state::durable_promote_file(&staging, &destination) {
        let _ = fs::remove_file(&staging);
        if matches!(error, crate::state::StateError::Io(ref value) if value.kind() == std::io::ErrorKind::AlreadyExists)
        {
            verify_existing(paths, &source, &destination)?;
            return Ok(destination);
        }
        return Err(error.into());
    }
    crate::state::atomic_write_json(&paths.installer_binary_metadata_path(), &metadata)?;
    set_executable(&destination)?;
    Ok(destination)
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
    })
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
        let destination = publish_installer_helper(&paths).unwrap();
        assert!(destination.is_file());
        assert!(paths.installer_binary_metadata_path().is_file());
        assert!(matches!(
            publish_installer_helper(&paths),
            Ok(path) if path == destination
        ));
    }
}
