//! Exact-commit repair orchestration.
//!
//! Repair never follows a moving branch. A pre-transition exact commit is rejected until the
//! existing installer has first performed a signed rolling update to a direct-capable transition
//! payload. Once that payload exists, repair uses the trusted current commit and hands the
//! verified result to ActivationEngine's immutable replacement primitive.

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

const ROLLING_UPDATE_REQUIRED_FIRST: &str =
    "rolling application update required first: install a PR44-or-later transition commit before repair migration";

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
        if !version_contains_direct_desktop_payload(&paths, &commit) {
            return Ok(RepairResult {
                operation_id: "repair".to_owned(),
                target_commit: Some(commit),
                toolchains: installation.current_toolchains,
                status: RepairStatus::RepairRequired {
                    reason: ROLLING_UPDATE_REQUIRED_FIRST.to_owned(),
                },
            });
        }
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

fn version_contains_direct_desktop_payload(paths: &InstallationPaths, commit: &str) -> bool {
    if commit.len() != 40 || !commit.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return false;
    }
    paths
        .versions_dir()
        .join(commit)
        .join("desktop")
        .join(paths.desktop_executable_name())
        .is_file()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::activation::{ActivationConfig, VersionMetadata};
    use crate::build::hash_directory;
    use crate::checksum::sha256_file;
    use crate::download::{DownloadError, DownloadReceipt, DownloadRequest};
    use crate::paths::{Platform, TargetArchitecture};
    use crate::process::{CommandSpec, ProcessError, ProcessOutput, ProcessRunner};
    use crate::toolchain::{
        ArchiveFormat, ToolchainDescriptor, ToolchainKind, ToolchainRecord, ToolchainState,
    };
    use crate::{DownloadClient, StateStore};
    use std::collections::BTreeMap;
    use std::fs;
    use std::path::{Path, PathBuf};
    use tempfile::tempdir;

    struct NoopDownloader;

    impl DownloadClient for NoopDownloader {
        fn download(&self, _request: &DownloadRequest) -> Result<DownloadReceipt, DownloadError> {
            unreachable!("repair test must not download an artifact")
        }
    }

    struct NoopRunner;

    impl ProcessRunner for NoopRunner {
        fn run(&self, _command: &CommandSpec) -> Result<ProcessOutput, ProcessError> {
            unreachable!("repair test must not run a build command")
        }
    }

    struct HealthyChecker;

    impl HealthChecker for HealthyChecker {
        fn check(
            &self,
            _version_dir: &Path,
            _metadata: &VersionMetadata,
            _config: &ActivationConfig,
        ) -> Result<(), ActivationError> {
            Ok(())
        }
    }

    fn paths(root: &Path) -> InstallationPaths {
        InstallationPaths {
            platform: Platform::Linux,
            architecture: TargetArchitecture::X64,
            app_root: root.join("app"),
            user_data_root: root.join("user-data"),
            state_root: root.join("state"),
            cache_root: root.join("cache"),
        }
    }

    fn descriptor(kind: ToolchainKind) -> ToolchainDescriptor {
        let executable = match kind {
            ToolchainKind::Jdk => ("java", "bin/java"),
            ToolchainKind::Node => ("node", "bin/node"),
        };
        ToolchainDescriptor::new(
            kind,
            "test",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://example.invalid/toolchain.tar.gz",
            "a".repeat(64),
            ArchiveFormat::TarGz,
        )
        .home_dir(".")
        .executable(executable.0, executable.1)
    }

    fn prepare_installation(paths: &InstallationPaths, commit: &str, direct_payload: bool) {
        let store = StateStore::new(paths.clone());
        store.initialize().unwrap();
        let mut installation = store.load_installation().unwrap();
        installation.product_version = Some("1.0.12-SNAPSHOT".to_owned());
        installation.current_commit = Some(commit.to_owned());
        installation.current_toolchains =
            BTreeMap::from([("jdk".to_owned(), "jdk-transition".to_owned())]);
        store.save_installation(&installation).unwrap();

        fs::create_dir_all(paths.bin_dir()).unwrap();
        fs::write(paths.legacy_launcher_path(), b"legacy Rust proxy").unwrap();

        let version = paths.versions_dir().join(commit);
        let desktop = version.join("desktop");
        fs::create_dir_all(&desktop).unwrap();
        fs::write(
            desktop.join(paths.compatibility_desktop_executable_name()),
            b"legacy Electron payload",
        )
        .unwrap();
        if direct_payload {
            fs::write(
                desktop.join(paths.desktop_executable_name()),
                b"Electron payload",
            )
            .unwrap();
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                fs::set_permissions(
                    desktop.join(paths.desktop_executable_name()),
                    fs::Permissions::from_mode(0o755),
                )
                .unwrap();
                fs::set_permissions(
                    desktop.join(paths.compatibility_desktop_executable_name()),
                    fs::Permissions::from_mode(0o755),
                )
                .unwrap();
            }
            let backend = version.join("backend/harmonia-suite.jar");
            fs::create_dir_all(backend.parent().unwrap()).unwrap();
            fs::write(&backend, b"backend").unwrap();
            let java = paths.app_root.join("toolchain/jdk/bin/java");
            fs::create_dir_all(java.parent().unwrap()).unwrap();
            fs::write(&java, b"managed java").unwrap();
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                fs::set_permissions(&java, fs::Permissions::from_mode(0o755)).unwrap();
            }
            let desktop_hash = hash_directory(&desktop).unwrap();
            let backend_hash = sha256_file(&backend).unwrap();
            let metadata = VersionMetadata {
                schema_version: 1,
                target_commit: commit.to_owned(),
                product_version: "1.0.12-SNAPSHOT".to_owned(),
                created_at_ms: 1,
                desktop_artifact_sha256: desktop_hash.clone(),
                backend_artifact_sha256: backend_hash.clone(),
                component_paths: BTreeMap::from([
                    (
                        "desktop".to_owned(),
                        crate::activation::VersionComponent {
                            path: PathBuf::from("desktop"),
                            sha256: desktop_hash,
                        },
                    ),
                    (
                        "backend".to_owned(),
                        crate::activation::VersionComponent {
                            path: PathBuf::from("backend/harmonia-suite.jar"),
                            sha256: backend_hash,
                        },
                    ),
                ]),
                toolchains: BTreeMap::from([("jdk".to_owned(), "jdk-transition".to_owned())]),
                runtime: crate::activation::RuntimeMetadata {
                    desktop_executable: PathBuf::from("desktop")
                        .join(paths.desktop_executable_name()),
                    backend_jar: PathBuf::from("backend/harmonia-suite.jar"),
                    managed_java_binary: PathBuf::from("toolchain/jdk/bin/java"),
                },
            };
            fs::write(
                version.join("metadata.json"),
                serde_json::to_vec_pretty(&metadata).unwrap(),
            )
            .unwrap();
            let toolchain = ToolchainRecord {
                id: "jdk-transition".to_owned(),
                kind: ToolchainKind::Jdk,
                version: "test".to_owned(),
                platform: Platform::Linux,
                architecture: TargetArchitecture::X64,
                url: "https://example.invalid/jdk.tar.gz".to_owned(),
                sha256: "a".repeat(64),
                archive: ArchiveFormat::TarGz,
                home_dir: PathBuf::from("."),
                install_dir: PathBuf::from("toolchain/jdk"),
                executables: BTreeMap::from([("java".to_owned(), PathBuf::from("bin/java"))]),
                installed_at_ms: 1,
            };
            ToolchainStateStore::new(paths.clone())
                .save(&ToolchainState {
                    schema_version: 1,
                    records: BTreeMap::from([(toolchain.id.clone(), toolchain)]),
                    pending_gc: Vec::new(),
                })
                .unwrap();
        }
    }

    fn repair(paths: InstallationPaths) -> RepairResult {
        RepairEngine::new(paths, NoopDownloader, NoopRunner, None)
            .repair_with_descriptors(
                descriptor(ToolchainKind::Jdk),
                descriptor(ToolchainKind::Node),
                &HealthyChecker,
            )
            .unwrap()
    }

    #[test]
    fn legacy_exact_commit_requires_rolling_update_before_repair_migration() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let commit = "a".repeat(40);
        prepare_installation(&paths, &commit, false);

        let result = repair(paths.clone());

        assert!(matches!(
            result.status,
            RepairStatus::RepairRequired { reason }
                if reason.contains("rolling application update required first")
        ));
        assert!(paths.legacy_launcher_path().is_file());
        assert!(!paths.current_pointer_path().exists());
    }

    #[test]
    fn transition_payload_is_migrated_before_legacy_proxy_is_removed() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let commit = "b".repeat(40);
        prepare_installation(&paths, &commit, true);

        let result = repair(paths.clone());

        assert!(matches!(result.status, RepairStatus::Repaired { .. }));
        assert_eq!(
            crate::current_pointer::target(&paths).unwrap(),
            Some(commit.clone())
        );
        assert!(paths.current_desktop_executable_path().is_file());
        assert!(paths
            .versions_dir()
            .join(&commit)
            .join("desktop")
            .join(paths.compatibility_desktop_executable_name())
            .is_file());
        assert!(!paths.legacy_launcher_path().exists());
        let desktop_entry = paths.linux_desktop_entry_path();
        let entry = fs::read_to_string(desktop_entry).unwrap();
        let executable = paths
            .current_desktop_executable_path()
            .display()
            .to_string()
            .replace('\\', "\\\\");
        assert!(entry.contains(&format!("Exec=\"{executable}\"")));

        let retry = repair(paths.clone());
        assert!(matches!(retry.status, RepairStatus::Healthy { .. }));
        assert!(!paths.legacy_launcher_path().exists());
    }
}
