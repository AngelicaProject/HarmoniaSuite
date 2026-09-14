//! Crash-recoverable publication of the stable `current` version pointer.
//!
//! Unix uses a relative symlink. Windows uses a directory junction so the pointer works for a
//! normal per-user installation without requiring Developer Mode or administrator privileges.

use std::fs;
use std::io;
use std::path::{Component, Path};

use thiserror::Error;

use crate::paths::InstallationPaths;
use crate::state::validate_managed_path;

#[derive(Debug, Error)]
pub enum CurrentPointerError {
    #[error("current pointer I/O failed: {0}")]
    Io(#[from] io::Error),
    #[error("current pointer target is invalid: {0}")]
    Invalid(String),
    #[error("current pointer state failed: {0}")]
    State(#[from] crate::state::StateError),
}

pub fn switch(paths: &InstallationPaths, target_commit: &str) -> Result<(), CurrentPointerError> {
    let target = managed_version_target(paths, target_commit)?;

    fs::create_dir_all(&paths.app_root)?;
    cleanup_orphaned_pointer_artifacts(paths)?;
    let pointer = paths.current_pointer_path();
    let temporary = paths
        .app_root
        .join(format!(".current.{}.tmp", uuid::Uuid::new_v4().simple()));
    validate_managed_path(&paths.app_root, &temporary)?;
    remove_pointer_if_present(&temporary)?;
    create_pointer(&temporary, &target)?;
    replace_pointer(&temporary, &pointer)?;
    #[cfg(unix)]
    fs::File::open(&paths.app_root)?.sync_all()?;
    validate_target(paths, target_commit)?;
    Ok(())
}

pub fn ensure(paths: &InstallationPaths, target_commit: &str) -> Result<(), CurrentPointerError> {
    match target(paths) {
        Ok(Some(current)) if current == target_commit => Ok(()),
        Ok(_) => switch(paths, target_commit),
        Err(CurrentPointerError::Invalid(_)) => switch(paths, target_commit),
        Err(error) => Err(error),
    }
}

pub fn target(paths: &InstallationPaths) -> Result<Option<String>, CurrentPointerError> {
    validate_managed_path(&paths.app_root, &paths.versions_dir())?;
    let pointer = paths.current_pointer_path();
    let metadata = match fs::symlink_metadata(&pointer) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    if !is_pointer(&metadata) {
        return Err(CurrentPointerError::Invalid(format!(
            "current is not a managed filesystem pointer: {}",
            pointer.display()
        )));
    }
    let canonical_versions = canonical_versions_dir(paths)?;
    let canonical_target = fs::canonicalize(&pointer).map_err(|error| {
        if error.kind() == io::ErrorKind::NotFound {
            CurrentPointerError::Invalid("current pointer is dangling".to_owned())
        } else {
            CurrentPointerError::Io(error)
        }
    })?;
    let relative = canonical_target
        .strip_prefix(&canonical_versions)
        .map_err(|_| CurrentPointerError::Invalid("current points outside versions".to_owned()))?;
    let mut components = relative.components();
    let Some(Component::Normal(commit)) = components.next() else {
        return Err(CurrentPointerError::Invalid(
            "current does not point to a version directory".to_owned(),
        ));
    };
    if components.next().is_some() || !canonical_target.is_dir() {
        return Err(CurrentPointerError::Invalid(
            "current does not point to exactly one version directory".to_owned(),
        ));
    }
    let commit = commit.to_string_lossy().to_string();
    validate_commit(&commit)?;
    Ok(Some(commit))
}

pub fn remove(paths: &InstallationPaths) -> Result<(), CurrentPointerError> {
    remove_pointer_if_present(&paths.current_pointer_path())
}

fn cleanup_orphaned_pointer_artifacts(
    paths: &InstallationPaths,
) -> Result<(), CurrentPointerError> {
    if !paths.app_root.is_dir() {
        return Ok(());
    }
    for entry in fs::read_dir(&paths.app_root)? {
        let entry = entry?;
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if (name.starts_with(".current.") || name.starts_with(".current-old-"))
            && name.ends_with(".tmp")
        {
            let path = entry.path();
            validate_managed_path(&paths.app_root, &path)?;
            remove_pointer_if_present(&path)?;
        }
    }
    Ok(())
}

fn validate_target(paths: &InstallationPaths, expected: &str) -> Result<(), CurrentPointerError> {
    match target(paths)? {
        Some(actual) if actual == expected => Ok(()),
        Some(actual) => Err(CurrentPointerError::Invalid(format!(
            "current points to {actual}, expected {expected}"
        ))),
        None => Err(CurrentPointerError::Invalid(
            "current pointer was not published".to_owned(),
        )),
    }
}

fn managed_version_target(
    paths: &InstallationPaths,
    target_commit: &str,
) -> Result<std::path::PathBuf, CurrentPointerError> {
    validate_commit(target_commit)?;
    let versions = paths.versions_dir();
    let target = versions.join(target_commit);
    validate_managed_path(&versions, &target)?;
    let canonical_versions = canonical_versions_dir(paths)?;
    let canonical_target = fs::canonicalize(&target).map_err(|error| {
        if error.kind() == io::ErrorKind::NotFound {
            CurrentPointerError::Invalid(format!(
                "version directory does not exist: {}",
                target.display()
            ))
        } else {
            CurrentPointerError::Io(error)
        }
    })?;
    if !canonical_target.is_dir()
        || !is_direct_child(&canonical_versions, &canonical_target)
        || canonical_target.file_name().and_then(|name| name.to_str()) != Some(target_commit)
    {
        return Err(CurrentPointerError::Invalid(
            "version directory is not a direct managed version".to_owned(),
        ));
    }
    Ok(canonical_target)
}

fn canonical_versions_dir(
    paths: &InstallationPaths,
) -> Result<std::path::PathBuf, CurrentPointerError> {
    let canonical_app_root = fs::canonicalize(&paths.app_root)?;
    let canonical_versions = fs::canonicalize(paths.versions_dir())?;
    if !is_direct_child(&canonical_app_root, &canonical_versions)
        || canonical_versions
            .file_name()
            .and_then(|name| name.to_str())
            != Some("versions")
    {
        return Err(CurrentPointerError::Invalid(
            "versions directory is outside the managed application root".to_owned(),
        ));
    }
    Ok(canonical_versions)
}

fn is_direct_child(root: &Path, child: &Path) -> bool {
    child.parent() == Some(root) && child.file_name().is_some()
}

fn validate_commit(commit: &str) -> Result<(), CurrentPointerError> {
    if commit.len() != 40 || !commit.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(CurrentPointerError::Invalid(
            "target commit must be a full 40-character SHA".to_owned(),
        ));
    }
    Ok(())
}

fn remove_pointer_if_present(path: &Path) -> Result<(), CurrentPointerError> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    if !is_pointer(&metadata) {
        return Err(CurrentPointerError::Invalid(format!(
            "refusing to remove non-pointer current path: {}",
            path.display()
        )));
    }
    #[cfg(unix)]
    fs::remove_file(path)?;
    #[cfg(windows)]
    fs::remove_dir(path)?;
    Ok(())
}

#[cfg(unix)]
fn create_pointer(link: &Path, target: &Path) -> Result<(), CurrentPointerError> {
    use std::os::unix::fs::symlink;
    symlink(
        Path::new("versions").join(target.file_name().unwrap()),
        link,
    )?;
    Ok(())
}

#[cfg(windows)]
fn create_pointer(link: &Path, target: &Path) -> Result<(), CurrentPointerError> {
    use std::process::{Command, Stdio};
    let status = Command::new("cmd.exe")
        .args(["/d", "/c", "mklink", "/J"])
        .arg(link)
        .arg(target)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()?;
    if !status.success() {
        return Err(CurrentPointerError::Io(io::Error::other(format!(
            "mklink /J failed with status {status}"
        ))));
    }
    Ok(())
}

fn replace_pointer(temporary: &Path, pointer: &Path) -> Result<(), CurrentPointerError> {
    #[cfg(unix)]
    {
        if let Ok(metadata) = fs::symlink_metadata(pointer) {
            if !is_pointer(&metadata) {
                return Err(CurrentPointerError::Invalid(format!(
                    "refusing to replace non-pointer current path: {}",
                    pointer.display()
                )));
            }
        }
        fs::rename(temporary, pointer)?;
    }
    #[cfg(windows)]
    {
        let existing = match fs::symlink_metadata(pointer) {
            Ok(metadata) => {
                if !is_pointer(&metadata) {
                    return Err(CurrentPointerError::Invalid(format!(
                        "refusing to replace non-pointer current path: {}",
                        pointer.display()
                    )));
                }
                true
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => false,
            Err(error) => return Err(error.into()),
        };
        let backup = pointer.with_file_name(format!(
            ".current-old-{}.tmp",
            uuid::Uuid::new_v4().simple()
        ));
        if existing {
            fs::rename(pointer, &backup)?;
        }
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::{MoveFileExW, MOVEFILE_WRITE_THROUGH};
        let source: Vec<u16> = temporary
            .as_os_str()
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        let destination: Vec<u16> = pointer
            .as_os_str()
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        let result = unsafe {
            MoveFileExW(
                source.as_ptr(),
                destination.as_ptr(),
                MOVEFILE_WRITE_THROUGH,
            )
        };
        if result == 0 {
            if existing {
                let _ = fs::rename(&backup, pointer);
            }
            return Err(io::Error::last_os_error().into());
        }
        if existing {
            remove_pointer_if_present(&backup)?;
        }
    }
    Ok(())
}

#[cfg(unix)]
fn is_pointer(metadata: &fs::Metadata) -> bool {
    metadata.file_type().is_symlink()
}

#[cfg(windows)]
fn is_pointer(metadata: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    use windows_sys::Win32::Storage::FileSystem::FILE_ATTRIBUTE_REPARSE_POINT;
    metadata.file_type().is_symlink()
        || metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::paths::{Platform, TargetArchitecture};
    use tempfile::tempdir;

    fn paths(root: &Path) -> InstallationPaths {
        InstallationPaths {
            platform: Platform::Linux,
            architecture: TargetArchitecture::X64,
            app_root: root.join("app"),
            user_data_root: root.join("data"),
            state_root: root.join("state"),
            cache_root: root.join("cache"),
        }
    }

    #[test]
    fn creates_and_switches_a_managed_relative_pointer() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let a = "a".repeat(40);
        let b = "b".repeat(40);
        fs::create_dir_all(paths.versions_dir().join(&a)).unwrap();
        fs::create_dir_all(paths.versions_dir().join(&b)).unwrap();
        switch(&paths, &a).unwrap();
        assert_eq!(target(&paths).unwrap(), Some(a));
        switch(&paths, &b).unwrap();
        assert_eq!(target(&paths).unwrap(), Some(b));
    }

    #[cfg(unix)]
    #[test]
    fn rejects_a_pointer_outside_versions() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        fs::create_dir_all(paths.versions_dir()).unwrap();
        fs::create_dir_all(root.path().join("outside")).unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(root.path().join("outside"), paths.current_pointer_path())
            .unwrap();
        assert!(matches!(
            target(&paths),
            Err(CurrentPointerError::Invalid(_))
        ));
    }

    #[cfg(unix)]
    #[test]
    fn rejects_a_version_directory_that_resolves_outside_versions() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let commit = "a".repeat(40);
        fs::create_dir_all(paths.versions_dir()).unwrap();
        fs::create_dir_all(root.path().join("outside")).unwrap();
        std::os::unix::fs::symlink(
            root.path().join("outside"),
            paths.versions_dir().join(&commit),
        )
        .unwrap();

        assert!(switch(&paths, &commit).is_err());
        assert!(!paths.current_pointer_path().exists());
    }

    #[test]
    fn remove_is_idempotent() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        remove(&paths).unwrap();
        assert!(!paths.current_pointer_path().exists());
    }

    #[test]
    fn ensure_recreates_a_missing_or_stale_pointer() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let a = "a".repeat(40);
        let b = "b".repeat(40);
        fs::create_dir_all(paths.versions_dir().join(&a)).unwrap();
        fs::create_dir_all(paths.versions_dir().join(&b)).unwrap();

        ensure(&paths, &a).unwrap();
        assert_eq!(target(&paths).unwrap(), Some(a.clone()));
        ensure(&paths, &b).unwrap();
        assert_eq!(target(&paths).unwrap(), Some(b));
    }

    #[cfg(unix)]
    #[test]
    fn rejects_a_non_pointer_current_path() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let commit = "a".repeat(40);
        fs::create_dir_all(paths.versions_dir().join(&commit)).unwrap();
        fs::create_dir_all(&paths.app_root).unwrap();
        fs::write(paths.current_pointer_path(), b"not a pointer").unwrap();

        assert!(matches!(
            switch(&paths, &commit),
            Err(CurrentPointerError::Invalid(_))
        ));
    }
}
