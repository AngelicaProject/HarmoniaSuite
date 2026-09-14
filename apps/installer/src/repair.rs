//! Exact-commit repair orchestration.
//!
//! Repair never follows a moving branch. It uses the trusted current commit from
//! installation state, rebuilds it through the existing BuildPipeline, and hands
//! the verified result to ActivationEngine's immutable replacement primitive.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::activation::{
    ActivationConfig, ActivationEngine, ActivationError, HealthChecker, LocalBackendHealthChecker,
};
use crate::build::{BuildConfig, BuildError, BuildPipeline};
use crate::catalog::production_descriptors;
use crate::desktop_control::DesktopShutdownHooks;
use crate::diagnostics::DiagnosticLogger;
use crate::download::DownloadClient;
use crate::helper::{publish_installer_helper, remove_legacy_launcher, validate_published_binary};
use crate::integration;
use crate::lock::{InstallationLock, LockError};
use crate::paths::InstallationPaths;
use crate::process::{ProcessRunner, SystemProcessRunner};
use crate::state::{StateError, StateStore, TransactionStatus};
use crate::toolchain::{ToolchainError, ToolchainStateStore};
use crate::DEFAULT_REMOTE_URL;

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum RepairStatus {
    Healthy {
        commit: String,
        version_dir: PathBuf,
    },
    Repaired {
        commit: String,
        version_dir: PathBuf,
    },
    RepairRequired {
        reason: String,
    },
    ReviewRequired {
        reason: String,
    },
    BuildFailed {
        reason: String,
    },
    ActivationFailed {
        reason: String,
    },
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct RepairResult {
    pub operation_id: String,
    pub target_commit: Option<String>,
    pub toolchains: BTreeMap<String, String>,
    pub status: RepairStatus,
}

#[derive(Debug, Error)]
pub enum RepairError {
    #[error("repair lock failed: {0}")]
    Lock(#[from] LockError),
    #[error("repair state failed: {0}")]
    State(#[from] StateError),
    #[error("repair build failed: {0}")]
    Build(#[from] BuildError),
    #[error("repair activation failed: {0}")]
    Activation(#[from] ActivationError),
    #[error("repair toolchain failed: {0}")]
    Toolchain(#[from] ToolchainError),
    #[error("repair diagnostics failed: {0}")]
    Diagnostics(#[from] crate::diagnostics::DiagnosticError),
}

pub struct RepairEngine<D, P> {
    paths: InstallationPaths,
    downloader: D,
    runner: P,
    logger: Option<DiagnosticLogger>,
}

impl<D, P> RepairEngine<D, P> {
    pub fn new(
        paths: InstallationPaths,
        downloader: D,
        runner: P,
        logger: Option<DiagnosticLogger>,
    ) -> Self {
        Self {
            paths,
            downloader,
            runner,
            logger,
        }
    }
}

impl<D: DownloadClient, P: ProcessRunner> RepairEngine<D, P> {
    pub fn repair(self) -> Result<RepairResult, RepairError> {
        let (jdk, node) = production_descriptors(self.paths.platform, &self.paths.architecture)?;
        let checker = LocalBackendHealthChecker::new(SystemProcessRunner::default());
        self.repair_with_descriptors(jdk, node, &checker)
    }

    pub(crate) fn repair_with_descriptors<H: HealthChecker>(
        self,
        jdk: crate::toolchain::ToolchainDescriptor,
        node: crate::toolchain::ToolchainDescriptor,
        checker: &H,
    ) -> Result<RepairResult, RepairError> {
        let RepairEngine {
            paths,
            downloader,
            runner,
            logger,
        } = self;
        let lock = InstallationLock::acquire(paths.lock_path(), "phase9-repair")?;
        let store = StateStore::new(paths.clone());
        store.initialize()?;
        if let Some(transaction) = store.load_transaction()? {
            if transaction.status == TransactionStatus::ReviewRequired {
                return Ok(RepairResult {
                    operation_id: transaction.id,
                    target_commit: transaction.target_commit,
                    toolchains: transaction.toolchain_refs,
                    status: RepairStatus::ReviewRequired {
                        reason: transaction
                            .failure
                            .unwrap_or_else(|| "transaction requires explicit review".to_owned()),
                    },
                });
            }
        }
        let activation = ActivationEngine::new(paths.clone());
        let recovery_config = ActivationConfig::for_paths(&paths, PathBuf::new());
        if let Err(error) = activation.recover_with_lock(&recovery_config, checker, &lock) {
            return Ok(RepairResult {
                operation_id: "recovery".to_owned(),
                target_commit: None,
                toolchains: BTreeMap::new(),
                status: RepairStatus::ReviewRequired {
                    reason: error.to_string(),
                },
            });
        }
        let installation = store.load_installation()?;
        let Some(commit) = installation.current_commit.clone() else {
            return Ok(RepairResult {
                operation_id: "repair".to_owned(),
                target_commit: None,
                toolchains: BTreeMap::new(),
                status: RepairStatus::RepairRequired {
                    reason: "no installed current version exists".to_owned(),
                },
            });
        };
        if let Ok(Some(runtime)) = activation.ensure_current_pointer_with_lock(&lock) {
            let direct_payload_complete = paths.current_desktop_executable_path().is_file();
            let surface_complete = validate_published_binary(
                &paths,
                &paths.installer_binary_path(),
                &paths.installer_binary_metadata_path(),
            )
            .is_ok()
                && direct_payload_complete
                && integration::is_complete(&paths)
                && legacy_launcher_is_absent(&paths);
            if surface_complete {
                return Ok(RepairResult {
                    operation_id: "repair".to_owned(),
                    target_commit: Some(commit),
                    toolchains: runtime.metadata.toolchains,
                    status: RepairStatus::Healthy {
                        commit: runtime.metadata.target_commit,
                        version_dir: runtime.version_dir,
                    },
                });
            }
            if direct_payload_complete {
                if let Err(error) = publish_installer_helper(&paths)
                    .and_then(|_| {
                        integration::install(&paths, false).map_err(|error| {
                            crate::helper::HelperError::Io(std::io::Error::other(error.to_string()))
                        })
                    })
                    .and_then(|_| remove_legacy_launcher(&paths))
                {
                    return Ok(RepairResult {
                        operation_id: "repair".to_owned(),
                        target_commit: Some(commit),
                        toolchains: runtime.metadata.toolchains,
                        status: RepairStatus::RepairRequired {
                            reason: format!("installed integration surface is incomplete: {error}"),
                        },
                    });
                }
                return Ok(RepairResult {
                    operation_id: "repair".to_owned(),
                    target_commit: Some(commit),
                    toolchains: runtime.metadata.toolchains,
                    status: RepairStatus::Repaired {
                        commit: runtime.metadata.target_commit,
                        version_dir: runtime.version_dir,
                    },
                });
            }
        }

        let operation_id = uuid::Uuid::new_v4().simple().to_string();
        let config =
            BuildConfig::fresh(DEFAULT_REMOTE_URL, jdk, node).with_target_commit(commit.clone());
        let pipeline = BuildPipeline::new(paths.clone(), downloader, runner, logger);
        let build = match pipeline.run_with_lock(&config, &lock) {
            Ok(result) => result,
            Err(error) => {
                return Ok(RepairResult {
                    operation_id,
                    target_commit: Some(commit),
                    toolchains: BTreeMap::new(),
                    status: RepairStatus::BuildFailed {
                        reason: error.to_string(),
                    },
                })
            }
        };
        let result_path = pipeline.result_path_for(&build)?;
        let java = resolve_build_java(&paths, &build)?;
        let activation_config = ActivationConfig::for_paths(&paths, java);
        let mut hooks = DesktopShutdownHooks::new(paths.clone(), true);
        match activation.repair_current_with_lock(
            &result_path,
            &activation_config,
            &mut hooks,
            checker,
            &lock,
        ) {
            Ok(runtime) => {
                if let Err(error) = publish_installer_helper(&paths)
                    .and_then(|_| {
                        integration::install(&paths, false).map_err(|error| {
                            crate::helper::HelperError::Io(std::io::Error::other(error.to_string()))
                        })
                    })
                    .and_then(|_| remove_legacy_launcher(&paths))
                {
                    return Ok(RepairResult {
                        operation_id,
                        target_commit: Some(build.target_commit),
                        toolchains: build.toolchains,
                        status: RepairStatus::ActivationFailed {
                            reason: format!(
                                "direct desktop/OS integration publication failed: {error}"
                            ),
                        },
                    });
                }
                let _ = activation.collect_old_versions_with_lock(&lock);
                Ok(RepairResult {
                    operation_id,
                    target_commit: Some(build.target_commit),
                    toolchains: build.toolchains,
                    status: RepairStatus::Repaired {
                        commit: runtime.metadata.target_commit,
                        version_dir: runtime.version_dir,
                    },
                })
            }
            Err(error @ ActivationError::ReviewRequired(_)) => Ok(RepairResult {
                operation_id,
                target_commit: Some(build.target_commit),
                toolchains: build.toolchains,
                status: RepairStatus::ReviewRequired {
                    reason: error.to_string(),
                },
            }),
            Err(error) => Ok(RepairResult {
                operation_id,
                target_commit: Some(build.target_commit),
                toolchains: build.toolchains,
                status: RepairStatus::ActivationFailed {
                    reason: error.to_string(),
                },
            }),
        }
    }
}

fn resolve_build_java(
    paths: &InstallationPaths,
    result: &crate::build::BuildResult,
) -> Result<PathBuf, RepairError> {
    let id = result
        .toolchains
        .get("jdk")
        .ok_or_else(|| ToolchainError::InvalidState("BuildResult is missing jdk".to_owned()))?;
    let jdk = ToolchainStateStore::new(paths.clone()).resolve(id)?;
    if jdk.kind != crate::toolchain::ToolchainKind::Jdk {
        return Err(ToolchainError::InvalidState("BuildResult jdk is not a JDK".to_owned()).into());
    }
    jdk.executable("java")
        .map(Path::to_path_buf)
        .ok_or_else(|| ToolchainError::MissingExecutable {
            name: "java".to_owned(),
            root: jdk.root,
        })
        .map_err(RepairError::from)
}

fn legacy_launcher_is_absent(paths: &InstallationPaths) -> bool {
    let legacy = paths.legacy_launcher_path();
    let metadata = legacy.with_file_name(format!(
        "{}.json",
        legacy
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or_default()
    ));
    [legacy, metadata]
        .iter()
        .all(|path| match fs::symlink_metadata(path) {
            Ok(_) => false,
            Err(error) => error.kind() == std::io::ErrorKind::NotFound,
        })
}
