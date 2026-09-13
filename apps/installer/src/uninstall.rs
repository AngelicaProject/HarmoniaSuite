//! Per-user uninstall orchestration. Runtime data is never part of the default
//! removal set; explicit user-data removal is a separate, validated action.

use std::env;
use std::fs;
use std::path::Path;

use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::desktop_control::{cleanup_shutdown_coordination, DesktopShutdownHooks};
use crate::diagnostics::{DiagnosticError, DiagnosticLogger};
use crate::helper::schedule_self_uninstall;
use crate::integration;
use crate::lock::{InstallationLock, LockError};
use crate::paths::InstallationPaths;
use crate::state::{validate_managed_path, StateError, StateStore, TransactionStatus};
use crate::ActivationHooks;

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum UninstallStatus {
    Uninstalled { user_data_preserved: bool },
    AlreadyUninstalled { user_data_preserved: bool },
    ReviewRequired { reason: String },
    Failed { reason: String },
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct UninstallResult {
    pub status: UninstallStatus,
}

#[derive(Debug, Error)]
pub enum UninstallError {
    #[error("uninstall lock failed: {0}")]
    Lock(#[from] LockError),
    #[error("uninstall state failed: {0}")]
    State(#[from] StateError),
    #[error("uninstall I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("uninstall desktop shutdown failed: {0}")]
    Desktop(String),
    #[error("uninstall helper failed: {0}")]
    Helper(#[from] crate::helper::HelperError),
    #[error("uninstall diagnostics failed: {0}")]
    Diagnostics(#[from] DiagnosticError),
}

pub fn uninstall(
    paths: InstallationPaths,
    remove_user_data: bool,
) -> Result<UninstallResult, UninstallError> {
    let managed_exists = paths.managed_paths().iter().any(|path| path.exists());
    if !managed_exists {
        let user_data_exists = paths.user_data_root.exists();
        if remove_user_data {
            remove_user_root(&paths.user_data_root)?;
        }
        return Ok(UninstallResult {
            status: if remove_user_data && user_data_exists {
                UninstallStatus::Uninstalled {
                    user_data_preserved: false,
                }
            } else {
                UninstallStatus::AlreadyUninstalled {
                    user_data_preserved: !remove_user_data,
                }
            },
        });
    }
    let lock = InstallationLock::acquire(paths.lock_path(), "phase9-uninstall")?;
    let store = StateStore::new(paths.clone());
    let logger = DiagnosticLogger::open(
        env::temp_dir()
            .join("HarmoniaSuite")
            .join("uninstall.jsonl"),
    )?;
    logger.log(
        "info",
        "uninstall.started",
        [
            (
                "remove_user_data".to_owned(),
                serde_json::json!(remove_user_data),
            ),
            (
                "platform".to_owned(),
                serde_json::json!(paths.platform.as_str()),
            ),
        ],
    )?;
    let review_required = if let Some(transaction) = store.load_transaction()? {
        if transaction.status == TransactionStatus::ReviewRequired {
            if remove_user_data {
                return Ok(UninstallResult {
                    status: UninstallStatus::ReviewRequired {
                        reason: "user-data removal is blocked while transaction state is ambiguous"
                            .to_owned(),
                    },
                });
            }
            true
        } else {
            false
        }
    } else {
        false
    };
    if !review_required {
        if let Err(error) = store.recover_pre_activation() {
            return Ok(UninstallResult {
                status: UninstallStatus::ReviewRequired {
                    reason: format!("uninstall blocked by incomplete transaction: {error}"),
                },
            });
        }
    }

    let mut shutdown = DesktopShutdownHooks::new(paths.clone(), true);
    shutdown
        .request_shutdown()
        .map_err(UninstallError::Desktop)?;
    shutdown
        .wait_for_shutdown(std::time::Duration::from_secs(30))
        .map_err(UninstallError::Desktop)?;
    cleanup_shutdown_coordination(&paths)
        .map_err(|error| UninstallError::Desktop(error.to_string()))?;
    integration::remove(&paths)
        .map_err(|error| UninstallError::Io(std::io::Error::other(error.to_string())))?;

    let roots = [
        paths.app_root.clone(),
        paths.state_root.clone(),
        paths.cache_root.clone(),
    ];
    let mut any_exists = roots.iter().any(|path| path.exists());
    if remove_user_data {
        any_exists |= paths.user_data_root.exists();
    }

    let current_exe = env::current_exe().ok();
    if current_exe
        .as_deref()
        .is_some_and(|path| path.starts_with(&paths.app_root))
    {
        schedule_self_uninstall(&paths, remove_user_data, &lock)?;
        let result = UninstallResult {
            status: UninstallStatus::Uninstalled {
                user_data_preserved: !remove_user_data,
            },
        };
        logger.log(
            "info",
            "uninstall.completed",
            [("status".to_owned(), serde_json::json!("Uninstalled"))],
        )?;
        return Ok(result);
    }

    for root in roots {
        remove_owned_root(&root)?;
    }
    if remove_user_data {
        remove_user_root(&paths.user_data_root)?;
    }
    let result = UninstallResult {
        status: if any_exists {
            UninstallStatus::Uninstalled {
                user_data_preserved: !remove_user_data,
            }
        } else {
            UninstallStatus::AlreadyUninstalled {
                user_data_preserved: !remove_user_data,
            }
        },
    };
    logger.log(
        "info",
        "uninstall.completed",
        [(
            "status".to_owned(),
            serde_json::json!(format!("{:?}", result.status)),
        )],
    )?;
    Ok(result)
}

fn remove_owned_root(root: &Path) -> Result<(), UninstallError> {
    if !root.exists() {
        return Ok(());
    }
    let parent = root
        .parent()
        .ok_or_else(|| StateError::UnsafeRecoveryPath(root.to_path_buf()))?;
    validate_managed_path(parent, root)?;
    fs::remove_dir_all(root)?;
    Ok(())
}

fn remove_user_root(root: &Path) -> Result<(), UninstallError> {
    if !root.exists() {
        return Ok(());
    }
    let parent = root
        .parent()
        .ok_or_else(|| StateError::UnsafeRecoveryPath(root.to_path_buf()))?;
    validate_managed_path(parent, root)?;
    fs::remove_dir_all(root)?;
    Ok(())
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
            app_root: root.join("application"),
            user_data_root: root.join("user data"),
            state_root: root.join("state"),
            cache_root: root.join("cache"),
        }
    }

    #[test]
    fn default_uninstall_preserves_user_data() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        StateStore::new(paths.clone()).initialize().unwrap();
        fs::write(paths.app_root.join("owned.txt"), b"owned").unwrap();
        fs::create_dir_all(&paths.user_data_root).unwrap();
        fs::write(paths.user_data_root.join("db"), b"data").unwrap();
        let result = uninstall(paths.clone(), false).unwrap();
        assert!(matches!(result.status, UninstallStatus::Uninstalled { .. }));
        assert!(!paths.app_root.exists());
        assert!(paths.user_data_root.join("db").is_file());
    }

    #[test]
    fn explicit_uninstall_can_remove_user_data() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        StateStore::new(paths.clone()).initialize().unwrap();
        fs::create_dir_all(&paths.user_data_root).unwrap();
        fs::write(paths.user_data_root.join("db"), b"data").unwrap();
        uninstall(paths.clone(), true).unwrap();
        assert!(!paths.user_data_root.exists());
    }
}
