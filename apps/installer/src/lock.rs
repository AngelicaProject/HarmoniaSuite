use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use fs2::FileExt;
use serde::{Deserialize, Serialize};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum LockError {
    #[error("installation lock is already held at {path}: {owner}")]
    Busy { path: PathBuf, owner: String },
    #[error("installation lock I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("installation lock metadata is invalid: {0}")]
    Metadata(#[from] serde_json::Error),
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct LockOwner {
    pub pid: u32,
    pub started_at_ms: u128,
    pub operation: String,
}

#[derive(Debug)]
pub struct InstallationLock {
    file: File,
    path: PathBuf,
}

impl InstallationLock {
    pub fn acquire(path: impl AsRef<Path>, operation: &str) -> Result<Self, LockError> {
        let path = path.as_ref().to_path_buf();
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut file = OpenOptions::new().create(true).read(true).write(true).open(&path)?;
        if let Err(error) = file.try_lock_exclusive() {
            if is_lock_contention(&error) {
                return Err(LockError::Busy { path, owner: read_owner(&mut file) });
            }
            return Err(LockError::Io(error));
        }

        let owner = LockOwner {
            pid: std::process::id(),
            started_at_ms: now_ms(),
            operation: operation.to_owned(),
        };
        let encoded = serde_json::to_vec(&owner)?;
        file.set_len(0)?;
        file.seek(SeekFrom::Start(0))?;
        file.write_all(&encoded)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        Ok(Self { file, path })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for InstallationLock {
    fn drop(&mut self) {
        let _ = self.file.unlock();
    }
}

fn read_owner(file: &mut File) -> String {
    if file.seek(SeekFrom::Start(0)).is_err() {
        return "owner metadata unavailable".to_owned();
    }
    let mut content = String::new();
    if file.read_to_string(&mut content).is_err() || content.trim().is_empty() {
        return "owner metadata unavailable".to_owned();
    }
    match serde_json::from_str::<LockOwner>(&content) {
        Ok(owner) => format!("pid={} operation={}", owner.pid, owner.operation),
        Err(_) => "owner metadata invalid".to_owned(),
    }
}

fn is_lock_contention(error: &std::io::Error) -> bool {
    error.kind() == std::io::ErrorKind::WouldBlock
        || matches!(error.raw_os_error(), Some(11 | 33 | 36 | 37 | 101 | 110 | 32))
}

fn now_ms() -> u128 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn serializes_owner_and_rejects_second_holder() {
        let directory = tempdir().unwrap();
        let path = directory.path().join("install.lock");
        let first = InstallationLock::acquire(&path, "update").unwrap();
        let second = InstallationLock::acquire(&path, "repair").unwrap_err();
        match second {
            LockError::Busy { owner, .. } => {
                assert!(owner.contains("operation=update"), "owner was: {owner}");
            }
            other => panic!("expected busy lock, got {other:?}"),
        }
        drop(first);
        assert!(InstallationLock::acquire(&path, "repair").is_ok());
    }
}
