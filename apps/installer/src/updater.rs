//! Production rolling updater orchestration.
//!
//! This module owns only the update operation boundary.  Building, toolchain installation,
//! immutable candidate publication, activation, rollback and detached launch remain delegated to
//! the Phase 4/5 engines.

use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use uuid::Uuid;

use crate::activation::{
    ActivationConfig, ActivationEngine, ActivationError, ActivationHooks, HealthChecker,
};
use crate::bootstrap::{runtime_launch_spec, wait_for_runtime_launch_ack, DEFAULT_REMOTE_URL};
use crate::build::{BuildConfig, BuildError, BuildPipeline};
use crate::detached::{DetachedLaunch, DetachedLaunchError, DetachedLauncher};
use crate::diagnostics::{DiagnosticError, DiagnosticLogger};
use crate::download::DownloadClient;
use crate::lock::{InstallationLock, LockError};
use crate::manifest::{
    production_manifest_urls, HttpManifestFetcher, ManifestError, ManifestFetcher,
    ManifestVerifier, SignedManifest,
};
use crate::paths::InstallationPaths;
use crate::process::ProcessRunner;
use crate::state::{OperationKind, StateError, StateStore, TransactionPhase, TransactionStatus};
use crate::toolchain::{ToolchainError, ToolchainStateStore};

const UPDATE_JOURNAL_SCHEMA_VERSION: u32 = 1;
const LAUNCH_ACK_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum UpdateStatus {
    UpToDate {
        current_commit: Option<String>,
    },
    UpdateAvailable {
        target_commit: String,
        product_version: String,
    },
    UpdaterUpgradeRequired {
        minimum_version: String,
    },
    Updated {
        commit: String,
        version_dir: PathBuf,
    },
    UpdateBuildFailed {
        reason: String,
    },
    ActivationFailed {
        reason: String,
    },
    LaunchFailed {
        reason: String,
        version_dir: PathBuf,
    },
    RollbackCompleted {
        commit: Option<String>,
    },
    ReviewRequired {
        reason: String,
    },
    TrustFailure {
        reason: String,
    },
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct UpdateResult {
    pub operation_id: String,
    pub target_commit: Option<String>,
    pub product_version: Option<String>,
    pub toolchains: BTreeMap<String, String>,
    pub phase: String,
    pub duration_ms: u128,
    pub status: UpdateStatus,
}

#[derive(Debug, Error)]
pub enum UpdateError {
    #[error("update lock failed: {0}")]
    Lock(#[from] LockError),
    #[error("update state failed: {0}")]
    State(#[from] StateError),
    #[error("update I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("update JSON failed: {0}")]
    Json(#[from] serde_json::Error),
    #[error("update diagnostics failed: {0}")]
    Diagnostics(#[from] DiagnosticError),
    #[error("update manifest failed: {0}")]
    Manifest(#[from] ManifestError),
    #[error("update build failed: {0}")]
    Build(#[from] BuildError),
    #[error("update activation failed: {0}")]
    Activation(#[from] ActivationError),
    #[error("update toolchain failed: {0}")]
    Toolchain(#[from] ToolchainError),
    #[error("update launch failed: {0}")]
    Launch(String),
    #[error("update trust policy rejected the manifest: {0}")]
    TrustFailure(String),
    #[error("an update is already in progress: {0}")]
    Active(String),
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "PascalCase")]
enum UpdateJournalStatus {
    Running,
    Completed,
    Failed,
    ReviewRequired,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "PascalCase")]
enum UpdateJournalPhase {
    Recovering,
    Checking,
    ResolvingTarget,
    Preparing,
    Activating,
    WaitingForShutdown,
    Restarting,
    RollingBack,
    Completed,
    Failed,
    ReviewRequired,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct UpdateJournal {
    schema_version: u32,
    operation_id: String,
    status: UpdateJournalStatus,
    phase: UpdateJournalPhase,
    started_at_ms: u128,
    finished_at_ms: Option<u128>,
    target_commit: Option<String>,
    product_version: Option<String>,
    toolchains: BTreeMap<String, String>,
    manifest_generation: Option<u64>,
    manifest_sha256: Option<String>,
    manifest_key_id: Option<String>,
    activation_completed: bool,
    launch_attempted: bool,
    launch_handoff_completed: bool,
    #[serde(default)]
    rollback_completed: bool,
    #[serde(default)]
    rollback_commit: Option<String>,
    launch_ack_path: Option<PathBuf>,
    launch_nonce: Option<String>,
    #[serde(default)]
    activation_transaction_id: Option<String>,
    failure: Option<String>,
}

impl UpdateJournal {
    fn new(paths: &InstallationPaths) -> Self {
        let operation_id = Uuid::new_v4().simple().to_string();
        let nonce = Uuid::new_v4().simple().to_string();
        Self {
            schema_version: UPDATE_JOURNAL_SCHEMA_VERSION,
            operation_id: operation_id.clone(),
            status: UpdateJournalStatus::Running,
            phase: UpdateJournalPhase::Recovering,
            started_at_ms: now_ms(),
            finished_at_ms: None,
            target_commit: None,
            product_version: None,
            toolchains: BTreeMap::new(),
            manifest_generation: None,
            manifest_sha256: None,
            manifest_key_id: None,
            activation_completed: false,
            launch_attempted: false,
            launch_handoff_completed: false,
            rollback_completed: false,
            rollback_commit: None,
            launch_ack_path: Some(paths.bootstrap_ack_path(&operation_id, &nonce)),
            launch_nonce: Some(nonce),
            activation_transaction_id: None,
            failure: None,
        }
    }
}

fn ensure_minimum_installer_version(
    minimum: Option<&str>,
    current_version: &str,
) -> Result<(), ManifestError> {
    let Some(minimum) = minimum else {
        return Ok(());
    };
    let current = semver::Version::parse(current_version)
        .map_err(|_| ManifestError::InvalidMinimumInstallerVersion(current_version.to_owned()))?;
    let required = semver::Version::parse(minimum)
        .map_err(|_| ManifestError::InvalidMinimumInstallerVersion(minimum.to_owned()))?;
    if current < required {
        return Err(ManifestError::UpdaterUpgradeRequired(minimum.to_owned()));
    }
    Ok(())
}

pub struct UpdateEngine<D, P> {
    paths: InstallationPaths,
    downloader: D,
    runner: P,
    logger: Option<DiagnosticLogger>,
}

impl<D, P> UpdateEngine<D, P> {
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

impl<D: DownloadClient, P: ProcessRunner> UpdateEngine<D, P> {
    /// Production update entrypoint. Manifest URLs and the keyring are product controlled.
    pub fn update<H: HealthChecker, A: ActivationHooks, L: DetachedLauncher>(
        &self,
        hooks: &mut A,
        checker: &H,
        launcher: &L,
    ) -> Result<UpdateResult, UpdateError> {
        let fetcher = HttpManifestFetcher::default();
        let verifier = ManifestVerifier::production();
        let (manifest_url, signature_url) = production_manifest_urls(self.paths.platform);
        self.update_with_sources(
            &fetcher,
            &verifier,
            &manifest_url,
            &signature_url,
            hooks,
            checker,
            launcher,
        )
    }

    /// Controlled fixture seam used by integration tests. It is deliberately not exposed by the
    /// production CLI, so arbitrary repositories and keys cannot become a runtime override.
    #[allow(clippy::too_many_arguments)]
    pub fn update_with_sources<
        F: ManifestFetcher,
        H: HealthChecker,
        A: ActivationHooks,
        L: DetachedLauncher,
    >(
        &self,
        fetcher: &F,
        verifier: &ManifestVerifier,
        manifest_url: &str,
        signature_url: &str,
        hooks: &mut A,
        checker: &H,
        launcher: &L,
    ) -> Result<UpdateResult, UpdateError> {
        let lock = InstallationLock::acquire(self.paths.lock_path(), "phase7-update")?;
        self.update_locked(
            &lock,
            fetcher,
            verifier,
            manifest_url,
            signature_url,
            DEFAULT_REMOTE_URL,
            hooks,
            checker,
            launcher,
        )
    }

    #[cfg(test)]
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn update_with_sources_for_test<
        F: ManifestFetcher,
        H: HealthChecker,
        A: ActivationHooks,
        L: DetachedLauncher,
    >(
        &self,
        fetcher: &F,
        verifier: &ManifestVerifier,
        manifest_url: &str,
        signature_url: &str,
        remote_url: &str,
        hooks: &mut A,
        checker: &H,
        launcher: &L,
    ) -> Result<UpdateResult, UpdateError> {
        let lock = InstallationLock::acquire(self.paths.lock_path(), "phase7-update-test")?;
        self.update_locked(
            &lock,
            fetcher,
            verifier,
            manifest_url,
            signature_url,
            remote_url,
            hooks,
            checker,
            launcher,
        )
    }

    pub fn check_for_update<F: ManifestFetcher>(
        &self,
        fetcher: &F,
        verifier: &ManifestVerifier,
        manifest_url: &str,
        signature_url: &str,
    ) -> Result<UpdateResult, UpdateError> {
        let lock = InstallationLock::acquire(self.paths.lock_path(), "phase7-check")?;
        let store = StateStore::new(self.paths.clone());
        store.initialize()?;
        let manifest = match self.fetch_and_verify(fetcher, verifier, manifest_url, signature_url) {
            Ok(value) => value,
            Err(error) if matches!(error, ManifestError::UpdaterUpgradeRequired(_)) => {
                return Ok(UpdateResult {
                    operation_id: String::new(),
                    target_commit: None,
                    product_version: None,
                    toolchains: BTreeMap::new(),
                    phase: "Checking".to_owned(),
                    duration_ms: 0,
                    status: UpdateStatus::UpdaterUpgradeRequired {
                        minimum_version: match error {
                            ManifestError::UpdaterUpgradeRequired(value) => value,
                            _ => unreachable!(),
                        },
                    },
                });
            }
            Err(error) if error.is_trust_failure() => {
                return Ok(UpdateResult {
                    operation_id: String::new(),
                    target_commit: None,
                    product_version: None,
                    toolchains: BTreeMap::new(),
                    phase: "Checking".to_owned(),
                    duration_ms: 0,
                    status: UpdateStatus::TrustFailure {
                        reason: error.to_string(),
                    },
                });
            }
            Err(error) => return Err(error.into()),
        };
        if let Err(error) = self.check_replay(&store, &manifest) {
            return Ok(UpdateResult {
                operation_id: String::new(),
                target_commit: Some(manifest.manifest.target_commit),
                product_version: Some(manifest.manifest.product_version),
                toolchains: BTreeMap::new(),
                phase: "Checking".to_owned(),
                duration_ms: 0,
                status: UpdateStatus::TrustFailure {
                    reason: error.to_string(),
                },
            });
        }
        let current = store.load_installation()?.current_commit;
        let status = if current.as_deref() == Some(manifest.manifest.target_commit.as_str()) {
            UpdateStatus::UpToDate {
                current_commit: current,
            }
        } else {
            UpdateStatus::UpdateAvailable {
                target_commit: manifest.manifest.target_commit.clone(),
                product_version: manifest.manifest.product_version.clone(),
            }
        };
        drop(lock);
        Ok(UpdateResult {
            operation_id: String::new(),
            target_commit: Some(manifest.manifest.target_commit),
            product_version: Some(manifest.manifest.product_version),
            toolchains: BTreeMap::new(),
            phase: "Checking".to_owned(),
            duration_ms: 0,
            status,
        })
    }

    #[allow(clippy::too_many_arguments)]
    fn update_locked<
        F: ManifestFetcher,
        H: HealthChecker,
        A: ActivationHooks,
        L: DetachedLauncher,
    >(
        &self,
        lock: &InstallationLock,
        fetcher: &F,
        verifier: &ManifestVerifier,
        manifest_url: &str,
        signature_url: &str,
        remote_url: &str,
        hooks: &mut A,
        checker: &H,
        launcher: &L,
    ) -> Result<UpdateResult, UpdateError> {
        if lock.path() != self.paths.lock_path() {
            return Err(UpdateError::Active("invalid installation lock".to_owned()));
        }
        let store = StateStore::new(self.paths.clone());
        store.initialize()?;
        self.publish_update_acceptance()?;
        let activation = ActivationEngine::new(self.paths.clone());
        let recovery_config = ActivationConfig::for_paths(&self.paths, PathBuf::new());
        activation.recover_with_lock(&recovery_config, checker, lock)?;
        if let Some(result) = self.reconcile_old_journal(lock, checker, launcher)? {
            return Ok(result);
        }

        let mut journal = UpdateJournal::new(&self.paths);
        persist_journal(&self.paths, &journal)?;
        journal.phase = UpdateJournalPhase::Checking;
        persist_journal(&self.paths, &journal)?;

        let signed = match self.fetch_and_verify(fetcher, verifier, manifest_url, signature_url) {
            Ok(value) => value,
            Err(error) if matches!(error, ManifestError::UpdaterUpgradeRequired(_)) => {
                journal.status = UpdateJournalStatus::Failed;
                journal.phase = UpdateJournalPhase::Failed;
                journal.failure = Some(error.to_string());
                finish_journal(&self.paths, &mut journal)?;
                return Ok(result_from_journal(
                    &journal,
                    UpdateStatus::UpdaterUpgradeRequired {
                        minimum_version: match error {
                            ManifestError::UpdaterUpgradeRequired(value) => value,
                            _ => unreachable!(),
                        },
                    },
                ));
            }
            Err(error) if error.is_trust_failure() => {
                journal.status = UpdateJournalStatus::Failed;
                journal.phase = UpdateJournalPhase::Failed;
                journal.failure = Some(error.to_string());
                finish_journal(&self.paths, &mut journal)?;
                return Ok(result_from_journal(
                    &journal,
                    UpdateStatus::TrustFailure {
                        reason: error.to_string(),
                    },
                ));
            }
            Err(error) => {
                journal.status = UpdateJournalStatus::Failed;
                journal.phase = UpdateJournalPhase::Failed;
                journal.failure = Some(error.to_string());
                finish_journal(&self.paths, &mut journal)?;
                return Err(error.into());
            }
        };
        if let Err(error) = self.check_replay(&store, &signed) {
            journal.status = UpdateJournalStatus::Failed;
            journal.phase = UpdateJournalPhase::Failed;
            journal.failure = Some(error.to_string());
            finish_journal(&self.paths, &mut journal)?;
            return Ok(result_from_journal(
                &journal,
                UpdateStatus::TrustFailure {
                    reason: error.to_string(),
                },
            ));
        }
        self.accept_manifest(&store, &signed)?;
        let manifest = &signed.manifest;
        journal.target_commit = Some(manifest.target_commit.clone());
        journal.product_version = Some(manifest.product_version.clone());
        journal.manifest_generation = Some(manifest.generation);
        journal.manifest_sha256 = Some(signed.manifest_sha256.clone());
        journal.manifest_key_id = Some(signed.key_id.clone());
        journal.phase = UpdateJournalPhase::ResolvingTarget;
        persist_journal(&self.paths, &journal)?;

        let installation = store.load_installation()?;
        if installation.current_commit.as_deref() == Some(manifest.target_commit.as_str()) {
            journal.status = UpdateJournalStatus::Completed;
            journal.phase = UpdateJournalPhase::Completed;
            finish_journal(&self.paths, &mut journal)?;
            return Ok(result_from_journal(
                &journal,
                UpdateStatus::UpToDate {
                    current_commit: installation.current_commit,
                },
            ));
        }

        let (jdk, node) = (manifest.jdk.clone(), manifest.node.clone());
        journal.phase = UpdateJournalPhase::Preparing;
        journal.toolchains =
            BTreeMap::from([("jdk".to_owned(), jdk.id()), ("node".to_owned(), node.id())]);
        persist_journal(&self.paths, &journal)?;
        let config = BuildConfig::new(remote_url, manifest.product_version.clone(), jdk, node)
            .with_target_commit(manifest.target_commit.clone());
        let pipeline = BuildPipeline::new(
            self.paths.clone(),
            &self.downloader,
            &self.runner,
            self.logger.clone(),
        );
        let build = match pipeline.run_with_lock(&config, lock) {
            Ok(result) => result,
            Err(error) => {
                journal.status = UpdateJournalStatus::Failed;
                journal.phase = UpdateJournalPhase::Failed;
                journal.failure = Some(error.to_string());
                finish_journal(&self.paths, &mut journal)?;
                let status = if matches!(
                    &error,
                    BuildError::CanonicalVersionMismatch { .. }
                        | BuildError::CanonicalComponentVersionMismatch { .. }
                        | BuildError::CanonicalVersion(..)
                ) {
                    UpdateStatus::TrustFailure {
                        reason: error.to_string(),
                    }
                } else {
                    UpdateStatus::UpdateBuildFailed {
                        reason: error.to_string(),
                    }
                };
                return Ok(result_from_journal(&journal, status));
            }
        };
        if build.target_commit != manifest.target_commit {
            return Err(UpdateError::TrustFailure(
                "build result target differs from signed manifest".to_owned(),
            ));
        }
        if build.product_version != manifest.product_version {
            return Err(UpdateError::TrustFailure(
                "build result application version differs from signed manifest".to_owned(),
            ));
        }
        journal.phase = UpdateJournalPhase::WaitingForShutdown;
        journal.toolchains = build.toolchains.clone();
        persist_journal(&self.paths, &journal)?;
        let java_id = build
            .toolchains
            .get("jdk")
            .ok_or_else(|| UpdateError::TrustFailure("build result has no JDK ID".to_owned()))?;
        let java = ToolchainStateStore::new(self.paths.clone()).resolve(java_id)?;
        let java_binary = java
            .executable("java")
            .ok_or_else(|| {
                UpdateError::TrustFailure("managed JDK has no java executable".to_owned())
            })?
            .to_path_buf();
        let activation_config = ActivationConfig::for_paths(&self.paths, java_binary);
        let result_path = pipeline.result_path_for(&build)?;
        let runtime = match activation.activate_with_lock_for_operation(
            result_path,
            &activation_config,
            hooks,
            checker,
            lock,
            Some(&journal.operation_id),
        ) {
            Ok(runtime) => runtime,
            Err(error) => {
                journal.status = if matches!(error, ActivationError::ReviewRequired(_)) {
                    UpdateJournalStatus::ReviewRequired
                } else {
                    UpdateJournalStatus::Failed
                };
                journal.phase = if matches!(error, ActivationError::ReviewRequired(_)) {
                    UpdateJournalPhase::ReviewRequired
                } else {
                    UpdateJournalPhase::Failed
                };
                journal.failure = Some(error.to_string());
                finish_journal(&self.paths, &mut journal)?;
                let status = if matches!(error, ActivationError::ReviewRequired(_)) {
                    UpdateStatus::ReviewRequired {
                        reason: error.to_string(),
                    }
                } else {
                    UpdateStatus::ActivationFailed {
                        reason: error.to_string(),
                    }
                };
                return Ok(result_from_journal(&journal, status));
            }
        };
        journal.activation_transaction_id = StateStore::new(self.paths.clone())
            .load_transaction()?
            .map(|record| record.id);
        journal.activation_completed = true;
        journal.phase = UpdateJournalPhase::Restarting;
        journal.launch_attempted = true;
        persist_journal(&self.paths, &journal)?;
        let ack_path = journal
            .launch_ack_path
            .clone()
            .ok_or_else(|| UpdateError::Launch("missing launch acknowledgement path".to_owned()))?;
        let nonce = journal
            .launch_nonce
            .clone()
            .ok_or_else(|| UpdateError::Launch("missing launch nonce".to_owned()))?;
        let launch = runtime_launch_spec(
            &self.paths,
            &runtime,
            &journal.operation_id,
            &ack_path,
            &nonce,
        );
        let launch_result = launcher.launch(&launch);
        let launch_handle = launch_result.as_ref().ok().cloned();
        let launch_error = launch_result.as_ref().err().map(ToString::to_string);
        if let Some(error) = launch_result.as_ref().err() {
            if !matches!(error, DetachedLaunchError::ExitedEarly { .. }) {
                return self.finish_launch_failure(
                    &mut journal,
                    &runtime,
                    error.to_string(),
                    launch_handle,
                    checker,
                    lock,
                    launcher,
                );
            }
        }
        let launch_timeout = if matches!(
            launch_result.as_ref(),
            Err(DetachedLaunchError::ExitedEarly { .. })
        ) {
            Duration::from_secs(3)
        } else {
            LAUNCH_ACK_TIMEOUT
        };
        if let Err(error) = wait_for_runtime_launch_ack(
            &self.paths,
            &ack_path,
            &journal.operation_id,
            &runtime.metadata.target_commit,
            &nonce,
            launch_timeout,
        ) {
            let reason = launch_error
                .map(|launch| format!("{launch}; {error}"))
                .unwrap_or_else(|| error.to_string());
            return self.finish_launch_failure(
                &mut journal,
                &runtime,
                reason,
                launch_handle,
                checker,
                lock,
                launcher,
            );
        }
        journal.launch_handoff_completed = true;
        let _ = activation.collect_old_versions_with_lock(lock);
        journal.status = UpdateJournalStatus::Completed;
        journal.phase = UpdateJournalPhase::Completed;
        finish_journal(&self.paths, &mut journal)?;
        Ok(result_from_journal(
            &journal,
            UpdateStatus::Updated {
                commit: runtime.metadata.target_commit,
                version_dir: runtime.version_dir,
            },
        ))
    }

    #[allow(clippy::too_many_arguments)]
    fn finish_launch_failure<H: HealthChecker, L: DetachedLauncher>(
        &self,
        journal: &mut UpdateJournal,
        runtime: &crate::activation::RuntimePaths,
        reason: String,
        launch: Option<DetachedLaunch>,
        checker: &H,
        lock: &InstallationLock,
        launcher: &L,
    ) -> Result<UpdateResult, UpdateError> {
        if let Some(launch) = launch.as_ref() {
            let _ = launcher.terminate(launch);
        }
        journal.phase = UpdateJournalPhase::RollingBack;
        journal.failure = Some(reason.clone());
        persist_journal(&self.paths, journal)?;
        let activation = ActivationEngine::new(self.paths.clone());
        let config = ActivationConfig::for_paths(&self.paths, runtime.java_binary.clone());
        let previous = match activation.rollback_completed_with_lock(&config, checker, lock) {
            Ok(runtime) => runtime,
            Err(error) => {
                journal.status = UpdateJournalStatus::ReviewRequired;
                journal.phase = UpdateJournalPhase::ReviewRequired;
                journal.failure = Some(format!("{reason}; rollback failed: {error}"));
                finish_journal(&self.paths, journal)?;
                return Ok(result_from_journal(
                    journal,
                    UpdateStatus::ReviewRequired {
                        reason: journal.failure.clone().unwrap_or_default(),
                    },
                ));
            }
        };
        let Some(previous) = previous else {
            journal.rollback_completed = true;
            journal.rollback_commit = None;
            journal.status = UpdateJournalStatus::Completed;
            journal.phase = UpdateJournalPhase::Completed;
            finish_journal(&self.paths, journal)?;
            return Ok(result_from_journal(
                journal,
                UpdateStatus::RollbackCompleted { commit: None },
            ));
        };
        journal.rollback_completed = true;
        journal.rollback_commit = Some(previous.metadata.target_commit.clone());
        journal.launch_attempted = true;
        journal.launch_handoff_completed = false;
        let nonce = Uuid::new_v4().simple().to_string();
        let ack_path = self.paths.bootstrap_ack_path(&journal.operation_id, &nonce);
        journal.launch_ack_path = Some(ack_path.clone());
        journal.launch_nonce = Some(nonce.clone());
        persist_journal(&self.paths, journal)?;
        let launch = runtime_launch_spec(
            &self.paths,
            &previous,
            &journal.operation_id,
            &ack_path,
            &nonce,
        );
        let launch_result = launcher.launch(&launch);
        let launch_handle = launch_result.as_ref().ok().cloned();
        if let Some(error) = launch_result.as_ref().err() {
            if !matches!(error, DetachedLaunchError::ExitedEarly { .. }) {
                if let Some(launch) = launch_handle.as_ref() {
                    let _ = launcher.terminate(launch);
                }
                journal.status = UpdateJournalStatus::ReviewRequired;
                journal.phase = UpdateJournalPhase::ReviewRequired;
                journal.failure = Some(format!(
                    "rollback completed but previous launch failed: {error}"
                ));
                finish_journal(&self.paths, journal)?;
                return Ok(result_from_journal(
                    journal,
                    UpdateStatus::ReviewRequired {
                        reason: journal.failure.clone().unwrap_or_default(),
                    },
                ));
            }
        }
        let timeout = if matches!(
            launch_result.as_ref(),
            Err(DetachedLaunchError::ExitedEarly { .. })
        ) {
            Duration::from_secs(3)
        } else {
            LAUNCH_ACK_TIMEOUT
        };
        if let Err(error) = wait_for_runtime_launch_ack(
            &self.paths,
            &ack_path,
            &journal.operation_id,
            &previous.metadata.target_commit,
            &nonce,
            timeout,
        ) {
            if let Some(launch) = launch_handle.as_ref() {
                let _ = launcher.terminate(launch);
            }
            journal.status = UpdateJournalStatus::ReviewRequired;
            journal.phase = UpdateJournalPhase::ReviewRequired;
            journal.failure = Some(format!(
                "rollback completed but previous launch failed: {error}"
            ));
            finish_journal(&self.paths, journal)?;
            return Ok(result_from_journal(
                journal,
                UpdateStatus::ReviewRequired {
                    reason: journal.failure.clone().unwrap_or_default(),
                },
            ));
        }
        journal.launch_handoff_completed = true;
        journal.status = UpdateJournalStatus::Completed;
        journal.phase = UpdateJournalPhase::Completed;
        finish_journal(&self.paths, journal)?;
        Ok(result_from_journal(
            journal,
            UpdateStatus::RollbackCompleted {
                commit: Some(previous.metadata.target_commit),
            },
        ))
    }

    fn reconcile_old_journal<H: HealthChecker, L: DetachedLauncher>(
        &self,
        lock: &InstallationLock,
        checker: &H,
        launcher: &L,
    ) -> Result<Option<UpdateResult>, UpdateError> {
        let path = self.paths.update_operation_path();
        if !path.is_file() {
            return Ok(None);
        }
        let mut journal: UpdateJournal = serde_json::from_slice(&fs::read(path)?)?;
        if journal.schema_version != UPDATE_JOURNAL_SCHEMA_VERSION {
            return Err(UpdateError::TrustFailure(
                "unsupported updater journal schema".to_owned(),
            ));
        }
        if journal.status == UpdateJournalStatus::ReviewRequired {
            let reason = journal
                .failure
                .clone()
                .unwrap_or_else(|| "updater journal requires review".to_owned());
            return Ok(Some(result_from_journal(
                &journal,
                UpdateStatus::ReviewRequired { reason },
            )));
        }
        if journal.status != UpdateJournalStatus::Running {
            return Ok(None);
        }
        if !journal.activation_completed {
            let activation = ActivationEngine::new(self.paths.clone());
            let current = StateStore::new(self.paths.clone())
                .load_installation()?
                .current_commit;
            let target = journal.target_commit.as_deref();
            if current.as_deref() == target {
                let runtime = activation.resolve_current().ok().flatten();
                let record = StateStore::new(self.paths.clone()).load_transaction()?;
                let proven = target
                    .zip(runtime.as_ref())
                    .zip(record.as_ref())
                    .is_some_and(|((target, runtime), record)| {
                        runtime.metadata.target_commit == target
                            && record.operation == OperationKind::Update
                            && record.external_operation_id.as_deref()
                                == Some(journal.operation_id.as_str())
                            && journal
                                .activation_transaction_id
                                .as_deref()
                                .is_none_or(|id| id == record.id)
                            && record.target_commit.as_deref() == Some(target)
                            && record.published_version.as_deref() == Some(target)
                            && record.current_switched
                            && record.health_check_passed
                            && record.status == TransactionStatus::Completed
                            && record.phase == TransactionPhase::Completed
                    });
                if proven {
                    journal.activation_completed = true;
                    journal.activation_transaction_id = record.map(|value| value.id);
                    journal.phase = UpdateJournalPhase::Restarting;
                    persist_journal(&self.paths, &journal)?;
                } else {
                    journal.status = UpdateJournalStatus::ReviewRequired;
                    journal.phase = UpdateJournalPhase::ReviewRequired;
                    journal.failure = Some(
                        "current target is not fully verified as this completed activation"
                            .to_owned(),
                    );
                    finish_journal(&self.paths, &mut journal)?;
                    return Ok(Some(result_from_journal(
                        &journal,
                        UpdateStatus::ReviewRequired {
                            reason: journal.failure.clone().unwrap_or_default(),
                        },
                    )));
                }
            }
        }
        if journal.activation_completed {
            let activation = ActivationEngine::new(self.paths.clone());
            let runtime = activation.resolve_current()?;
            if runtime.is_none() {
                let low_level_rollback = StateStore::new(self.paths.clone())
                    .load_transaction()?
                    .is_some_and(|record| record.rollback_completed);
                if low_level_rollback {
                    let mut resumed = journal;
                    resumed.rollback_completed = true;
                    resumed.rollback_commit = None;
                    resumed.status = UpdateJournalStatus::Completed;
                    resumed.phase = UpdateJournalPhase::Completed;
                    finish_journal(&self.paths, &mut resumed)?;
                    return Ok(Some(result_from_journal(
                        &resumed,
                        UpdateStatus::RollbackCompleted { commit: None },
                    )));
                }
                return Err(UpdateError::Active(journal.operation_id.clone()));
            }
            let runtime = runtime.expect("checked above");
            if journal.rollback_completed || journal.phase == UpdateJournalPhase::RollingBack {
                let mut resumed = journal;
                return self
                    .resume_rollback(&mut resumed, lock, checker, launcher)
                    .map(Some);
            }
            if journal.target_commit.as_deref() != Some(runtime.metadata.target_commit.as_str()) {
                let low_level_rollback = StateStore::new(self.paths.clone())
                    .load_transaction()?
                    .is_some_and(|record| record.rollback_completed);
                if low_level_rollback {
                    let mut resumed = journal;
                    resumed.phase = UpdateJournalPhase::RollingBack;
                    resumed.rollback_completed = true;
                    return self
                        .resume_rollback(&mut resumed, lock, checker, launcher)
                        .map(Some);
                }
                let mut resumed = journal;
                resumed.status = UpdateJournalStatus::ReviewRequired;
                resumed.phase = UpdateJournalPhase::ReviewRequired;
                resumed.failure = Some("active version does not match updater target".to_owned());
                finish_journal(&self.paths, &mut resumed)?;
                return Ok(Some(result_from_journal(
                    &resumed,
                    UpdateStatus::ReviewRequired {
                        reason: resumed.failure.clone().unwrap_or_default(),
                    },
                )));
            }
            let ack_path = journal
                .launch_ack_path
                .clone()
                .ok_or_else(|| UpdateError::Active(journal.operation_id.clone()))?;
            let nonce = journal
                .launch_nonce
                .clone()
                .ok_or_else(|| UpdateError::Active(journal.operation_id.clone()))?;
            if wait_for_runtime_launch_ack(
                &self.paths,
                &ack_path,
                &journal.operation_id,
                &runtime.metadata.target_commit,
                &nonce,
                Duration::ZERO,
            )
            .is_ok()
            {
                let mut resumed = journal;
                resumed.launch_handoff_completed = true;
                resumed.status = UpdateJournalStatus::Completed;
                resumed.phase = UpdateJournalPhase::Completed;
                finish_journal(&self.paths, &mut resumed)?;
                return Ok(Some(result_from_journal(
                    &resumed,
                    UpdateStatus::Updated {
                        commit: runtime.metadata.target_commit,
                        version_dir: runtime.version_dir,
                    },
                )));
            }
            let mut resumed = journal;
            resumed.phase = UpdateJournalPhase::Restarting;
            resumed.launch_attempted = true;
            persist_journal(&self.paths, &resumed)?;
            let launch = runtime_launch_spec(
                &self.paths,
                &runtime,
                &resumed.operation_id,
                &ack_path,
                &nonce,
            );
            let launch_result = launcher.launch(&launch);
            let launch_handle = launch_result.as_ref().ok().cloned();
            let launch_error = launch_result.as_ref().err().map(ToString::to_string);
            if let Some(error) = launch_result.as_ref().err() {
                if !matches!(error, DetachedLaunchError::ExitedEarly { .. }) {
                    return self
                        .finish_launch_failure(
                            &mut resumed,
                            &runtime,
                            error.to_string(),
                            launch_handle,
                            checker,
                            lock,
                            launcher,
                        )
                        .map(Some);
                }
            }
            let launch_timeout = if matches!(
                launch_result.as_ref(),
                Err(DetachedLaunchError::ExitedEarly { .. })
            ) {
                Duration::from_secs(3)
            } else {
                LAUNCH_ACK_TIMEOUT
            };
            if let Err(error) = wait_for_runtime_launch_ack(
                &self.paths,
                &ack_path,
                &resumed.operation_id,
                &runtime.metadata.target_commit,
                &nonce,
                launch_timeout,
            ) {
                let reason = launch_error
                    .map(|launch| format!("{launch}; {error}"))
                    .unwrap_or_else(|| error.to_string());
                return self
                    .finish_launch_failure(
                        &mut resumed,
                        &runtime,
                        reason,
                        launch_handle,
                        checker,
                        lock,
                        launcher,
                    )
                    .map(Some);
            }
            resumed.launch_handoff_completed = true;
            resumed.status = UpdateJournalStatus::Completed;
            resumed.phase = UpdateJournalPhase::Completed;
            finish_journal(&self.paths, &mut resumed)?;
            return Ok(Some(result_from_journal(
                &resumed,
                UpdateStatus::Updated {
                    commit: runtime.metadata.target_commit,
                    version_dir: runtime.version_dir,
                },
            )));
        }
        let mut failed = journal;
        failed.status = UpdateJournalStatus::Failed;
        failed.phase = UpdateJournalPhase::Failed;
        failed.failure =
            Some("interrupted before activation; low-level recovery completed".to_owned());
        finish_journal(&self.paths, &mut failed)?;
        Ok(None)
    }

    fn resume_rollback<H: HealthChecker, L: DetachedLauncher>(
        &self,
        journal: &mut UpdateJournal,
        lock: &InstallationLock,
        checker: &H,
        launcher: &L,
    ) -> Result<UpdateResult, UpdateError> {
        let activation = ActivationEngine::new(self.paths.clone());
        let runtime = activation
            .resolve_current()?
            .ok_or_else(|| UpdateError::Active(journal.operation_id.clone()))?;
        let reason = journal
            .failure
            .clone()
            .unwrap_or_else(|| "resuming rollback".to_owned());
        self.finish_launch_failure(journal, &runtime, reason, None, checker, lock, launcher)
    }

    fn fetch_and_verify<F: ManifestFetcher>(
        &self,
        fetcher: &F,
        verifier: &ManifestVerifier,
        manifest_url: &str,
        signature_url: &str,
    ) -> Result<SignedManifest, ManifestError> {
        let manifest = fetcher.fetch(manifest_url)?;
        let signature = fetcher.fetch(signature_url)?;
        let signed = verifier.verify(
            &manifest,
            &signature,
            self.paths.platform,
            &self.paths.architecture,
        )?;
        ensure_minimum_installer_version(
            signed.manifest.min_installer_version.as_deref(),
            env!("CARGO_PKG_VERSION"),
        )?;
        crate::state::atomic_write_bytes(&self.paths.update_manifest_cache_path(), &manifest)
            .map_err(|error| ManifestError::Io(std::io::Error::other(error.to_string())))?;
        crate::state::atomic_write_bytes(
            &self.paths.update_manifest_signature_cache_path(),
            &signature,
        )
        .map_err(|error| ManifestError::Io(std::io::Error::other(error.to_string())))?;
        Ok(signed)
    }

    fn publish_update_acceptance(&self) -> Result<(), UpdateError> {
        let Some(value) = std::env::var_os("HARMONIA_UPDATE_ACCEPT_PATH") else {
            return Ok(());
        };
        let path = PathBuf::from(value);
        if path != self.paths.update_acceptance_path() {
            return Err(UpdateError::TrustFailure(
                "update acceptance path is outside the managed state root".to_owned(),
            ));
        }
        crate::state::atomic_write_json(
            &path,
            &serde_json::json!({
                "accepted": true,
                "operationId": Uuid::new_v4().simple().to_string(),
                "acceptedAtMs": now_ms(),
            }),
        )?;
        Ok(())
    }

    fn check_replay(&self, store: &StateStore, signed: &SignedManifest) -> Result<(), UpdateError> {
        let state = store.load_installation()?;
        if let Some(generation) = state.accepted_manifest_generation {
            if signed.manifest.generation < generation {
                return Err(UpdateError::TrustFailure(format!(
                    "manifest generation {} is lower than accepted generation {generation}",
                    signed.manifest.generation
                )));
            }
            if signed.manifest.generation == generation
                && state.accepted_manifest_sha256.as_deref()
                    != Some(signed.manifest_sha256.as_str())
            {
                return Err(UpdateError::TrustFailure(
                    "same manifest generation has different signed content".to_owned(),
                ));
            }
        }
        Ok(())
    }

    fn accept_manifest(
        &self,
        store: &StateStore,
        signed: &SignedManifest,
    ) -> Result<(), UpdateError> {
        let mut state = store.load_installation()?;
        if state
            .accepted_manifest_generation
            .is_none_or(|value| value <= signed.manifest.generation)
        {
            state.accepted_manifest_generation = Some(signed.manifest.generation);
            state.accepted_manifest_sha256 = Some(signed.manifest_sha256.clone());
            state.accepted_manifest_key_id = Some(signed.key_id.clone());
            store.save_installation(&state)?;
        }
        Ok(())
    }
}

fn persist_journal(paths: &InstallationPaths, journal: &UpdateJournal) -> Result<(), UpdateError> {
    crate::state::atomic_write_json(&paths.update_operation_path(), journal)?;
    Ok(())
}

fn finish_journal(
    paths: &InstallationPaths,
    journal: &mut UpdateJournal,
) -> Result<(), UpdateError> {
    journal.finished_at_ms = Some(now_ms());
    persist_journal(paths, journal)
}

fn result_from_journal(journal: &UpdateJournal, status: UpdateStatus) -> UpdateResult {
    UpdateResult {
        operation_id: journal.operation_id.clone(),
        target_commit: journal.target_commit.clone(),
        product_version: journal.product_version.clone(),
        toolchains: journal.toolchains.clone(),
        phase: format!("{:?}", journal.phase),
        duration_ms: journal
            .finished_at_ms
            .unwrap_or_else(now_ms)
            .saturating_sub(journal.started_at_ms),
        status,
    }
}

fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::download::{DownloadClient, DownloadError, DownloadReceipt, DownloadRequest};
    use crate::manifest::{ManifestFetcher, ManifestSignature, ManifestVerifier, RollingManifest};
    use crate::paths::{Platform, TargetArchitecture};
    use crate::process::{CommandSpec, ProcessError, ProcessOutput, ProcessRunner};
    use crate::toolchain::{ArchiveFormat, ToolchainDescriptor, ToolchainKind};
    use ed25519_dalek::{Signer, SigningKey};
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use git2::{Oid, Repository, Signature};
    use std::collections::BTreeMap;
    use std::fs;
    use std::io;
    use std::path::{Path, PathBuf};
    use std::sync::{Arc, Mutex};
    use tar::Builder;
    use tempfile::{tempdir, TempDir};

    #[derive(Clone, Default)]
    struct NoopDownloader;

    impl DownloadClient for NoopDownloader {
        fn download(&self, request: &DownloadRequest) -> Result<DownloadReceipt, DownloadError> {
            Err(DownloadError::Transport(format!(
                "unexpected download in updater check: {}",
                request.url
            )))
        }
    }

    #[derive(Clone, Default)]
    struct NoopRunner;

    impl ProcessRunner for NoopRunner {
        fn run(&self, command: &CommandSpec) -> Result<ProcessOutput, ProcessError> {
            Err(ProcessError::Spawn {
                program: command.program.display().to_string(),
                source: io::Error::other("unexpected process in updater check"),
            })
        }
    }

    struct FixtureFetcher {
        manifest: Vec<u8>,
        signature: Vec<u8>,
    }

    impl ManifestFetcher for FixtureFetcher {
        fn fetch(&self, url: &str) -> Result<Vec<u8>, ManifestError> {
            if url.ends_with(".sig") {
                Ok(self.signature.clone())
            } else {
                Ok(self.manifest.clone())
            }
        }
    }

    fn fixture_manifest(target_commit: &str) -> RollingManifest {
        let descriptor = |kind| {
            ToolchainDescriptor::new(
                kind,
                "fixture",
                Platform::Linux,
                TargetArchitecture::X64,
                "https://fixture.invalid/tool.tar.gz",
                "0000000000000000000000000000000000000000000000000000000000000000",
                ArchiveFormat::TarGz,
            )
            .home_dir("root")
            .executable(
                if kind == ToolchainKind::Jdk {
                    "java"
                } else {
                    "node"
                },
                "root/bin/tool",
            )
        };
        RollingManifest {
            schema_version: 1,
            channel: "rolling".to_owned(),
            generation: 1,
            product_version: "1.2.3".to_owned(),
            target_commit: target_commit.to_owned(),
            min_installer_version: None,
            jdk: descriptor(ToolchainKind::Jdk),
            node: descriptor(ToolchainKind::Node),
        }
    }

    fn fixture_fetcher(manifest: &RollingManifest, key: &SigningKey) -> FixtureFetcher {
        let bytes = serde_json::to_vec(manifest).unwrap();
        let signature = key.sign(&bytes);
        FixtureFetcher {
            manifest: bytes,
            signature: serde_json::to_vec(&ManifestSignature {
                schema_version: 1,
                key_id: "test".to_owned(),
                signature_hex: signature
                    .to_bytes()
                    .iter()
                    .map(|byte| format!("{byte:02x}"))
                    .collect(),
            })
            .unwrap(),
        }
    }

    #[derive(Clone, Copy)]
    enum AbFailurePoint {
        Frontend,
        Backend,
        Desktop,
    }

    #[derive(Clone, Default)]
    struct AbBuildRunner {
        failure: Option<AbFailurePoint>,
        calls: Arc<Mutex<Vec<CommandSpec>>>,
    }

    impl ProcessRunner for AbBuildRunner {
        fn run(&self, command: &CommandSpec) -> Result<ProcessOutput, ProcessError> {
            self.calls.lock().unwrap().push(command.clone());
            let directory = command
                .current_dir
                .as_ref()
                .ok_or_else(|| ProcessError::Spawn {
                    program: command.program.display().to_string(),
                    source: io::Error::other("fixture command has no working directory"),
                })?;
            let is_build = command
                .args
                .windows(2)
                .any(|args| args[0] == "run" && args[1] == "build")
                || command
                    .args
                    .iter()
                    .any(|arg| arg.contains("run") && arg.contains("build"));
            let is_package = command
                .args
                .windows(2)
                .any(|args| args[0] == "run" && args[1] == "package")
                || command
                    .args
                    .iter()
                    .any(|arg| arg.contains("run") && arg.contains("package"));
            let is_maven = command
                .program
                .file_name()
                .and_then(|value| value.to_str())
                .is_some_and(|value| value.starts_with("mvnw"))
                || command.args.iter().any(|arg| arg.contains("mvnw"));
            let failure = match self.failure {
                Some(AbFailurePoint::Frontend) => {
                    directory.ends_with(Path::new("frontend")) && is_build
                }
                Some(AbFailurePoint::Backend) => is_maven,
                Some(AbFailurePoint::Desktop) => {
                    directory.ends_with(Path::new("desktop")) && is_build
                }
                None => false,
            };
            if failure {
                return Ok(ProcessOutput {
                    status: Some(17),
                    stdout: String::new(),
                    stderr: "controlled A/B build failure".to_owned(),
                    duration_ms: 1,
                    timed_out: false,
                });
            }
            if is_maven && command.args.iter().any(|arg| arg == "help:evaluate") {
                let marker = fs::read_to_string(directory.join("build-marker.txt"))?;
                return Ok(ProcessOutput {
                    status: Some(0),
                    stdout: format!("{}\n", ab_version(marker.trim())),
                    stderr: String::new(),
                    duration_ms: 1,
                    timed_out: false,
                });
            }
            if is_build {
                let output = directory.join("dist");
                fs::create_dir_all(&output)?;
                fs::write(
                    output.join(if directory.ends_with(Path::new("frontend")) {
                        "index.html"
                    } else {
                        "main.js"
                    }),
                    b"controlled build",
                )?;
            } else if is_package {
                let platform = if cfg!(windows) {
                    "windows-x64"
                } else {
                    "linux-x64"
                };
                let payload = directory.join("artifacts").join(platform);
                fs::create_dir_all(payload.join("resources/app/dist"))?;
                let runtime = payload.join(if cfg!(windows) {
                    "HarmoniaSuite.exe"
                } else {
                    "harmonia-suite"
                });
                fs::write(&runtime, b"controlled electron")?;
                fs::write(payload.join("resources/app/package.json"), b"{}")?;
                fs::write(payload.join("resources/app/dist/main.js"), b"desktop")?;
                fs::create_dir_all(payload.join("frontend/dist"))?;
                fs::write(payload.join("frontend/dist/index.html"), b"frontend")?;
                #[cfg(unix)]
                {
                    use std::os::unix::fs::PermissionsExt;
                    fs::set_permissions(&runtime, fs::Permissions::from_mode(0o755))?;
                }
            } else if is_maven {
                fs::create_dir_all(directory.join("target"))?;
                fs::write(
                    directory.join("target/harmonia-suite.jar"),
                    b"controlled backend",
                )?;
            }
            Ok(ProcessOutput {
                status: Some(0),
                stdout: String::new(),
                stderr: String::new(),
                duration_ms: 1,
                timed_out: false,
            })
        }
    }

    #[derive(Clone)]
    struct AbFixtureDownloader {
        archives: BTreeMap<String, Vec<u8>>,
    }

    impl DownloadClient for AbFixtureDownloader {
        fn download(&self, request: &DownloadRequest) -> Result<DownloadReceipt, DownloadError> {
            let bytes = self.archives.get(&request.url).ok_or_else(|| {
                DownloadError::Transport(format!("missing A/B archive {}", request.url))
            })?;
            fs::create_dir_all(request.destination.parent().unwrap())?;
            fs::write(&request.destination, bytes)?;
            Ok(DownloadReceipt {
                path: request.destination.clone(),
                bytes: bytes.len() as u64,
                sha256: bytes_sha256(bytes),
                resumed: false,
            })
        }
    }

    struct AbFixture {
        _root: TempDir,
        paths: crate::InstallationPaths,
        remote_url: String,
        a: String,
        b: String,
        c: String,
        jdk: ToolchainDescriptor,
        node: ToolchainDescriptor,
        downloader: AbFixtureDownloader,
        key: SigningKey,
        verifier: ManifestVerifier,
    }

    #[derive(Default)]
    struct RecordingHooks {
        events: Arc<Mutex<Vec<&'static str>>>,
    }

    impl ActivationHooks for RecordingHooks {
        fn request_shutdown(&mut self) -> Result<(), String> {
            self.events.lock().unwrap().push("request");
            Ok(())
        }

        fn wait_for_shutdown(&mut self, _timeout: Duration) -> Result<(), String> {
            self.events.lock().unwrap().push("wait");
            Ok(())
        }
    }

    struct AbHealthChecker {
        fail_commit: Option<String>,
        checks: Arc<Mutex<Vec<String>>>,
    }

    impl HealthChecker for AbHealthChecker {
        fn check(
            &self,
            _version_dir: &Path,
            metadata: &crate::VersionMetadata,
            _config: &crate::ActivationConfig,
        ) -> Result<(), ActivationError> {
            self.checks
                .lock()
                .unwrap()
                .push(metadata.target_commit.clone());
            if self.fail_commit.as_deref() == Some(metadata.target_commit.as_str()) {
                return Err(ActivationError::HealthCheck(
                    "controlled A/B health failure".to_owned(),
                ));
            }
            Ok(())
        }
    }

    #[derive(Default)]
    struct AbLauncher {
        fail_commits: Mutex<std::collections::BTreeSet<String>>,
        launches: Mutex<Vec<String>>,
    }

    impl DetachedLauncher for AbLauncher {
        fn launch(
            &self,
            spec: &crate::DetachedLaunchSpec,
        ) -> Result<DetachedLaunch, DetachedLaunchError> {
            let target = spec
                .environment
                .get("HARMONIA_LAUNCH_COMMIT")
                .cloned()
                .unwrap();
            self.launches.lock().unwrap().push(target.clone());
            if self.fail_commits.lock().unwrap().contains(&target) {
                return Err(DetachedLaunchError::Spawn {
                    program: spec.program.display().to_string(),
                    source: io::Error::other("controlled A/B launch failure"),
                });
            }
            let ack_path = PathBuf::from(spec.environment.get("HARMONIA_LAUNCH_ACK").unwrap());
            crate::state::atomic_write_json(
                &ack_path,
                &serde_json::json!({
                    "schemaVersion": 1,
                    "operationId": spec.environment.get("HARMONIA_LAUNCH_OPERATION_ID").unwrap(),
                    "targetCommit": target,
                    "nonce": spec.environment.get("HARMONIA_LAUNCH_NONCE").unwrap(),
                    "acknowledgedAtMs": 1,
                }),
            )
            .unwrap();
            Ok(DetachedLaunch { process_id: 7 })
        }
    }

    fn ab_fixture() -> AbFixture {
        let root = tempdir().unwrap();
        let platform = if cfg!(windows) {
            Platform::Windows
        } else {
            Platform::Linux
        };
        let paths = crate::InstallationPaths {
            platform,
            architecture: TargetArchitecture::X64,
            app_root: root.path().join("app root with spaces"),
            user_data_root: root.path().join("user data with spaces"),
            state_root: root.path().join("state with spaces"),
            cache_root: root.path().join("cache with spaces"),
        };
        let remote_path = root.path().join("controlled-remote.git");
        let remote = Repository::init_bare(&remote_path).unwrap();
        let a = ab_commit(&remote, None, "A");
        let b = ab_commit(&remote, Some(&a), "B");
        let c = ab_commit(&remote, Some(&b), "C");
        let remote_url = reqwest::Url::from_file_path(&remote_path)
            .unwrap()
            .to_string();

        let jdk_archive = ab_tar_gz(&[("jdk-21/bin/java", b"managed java", 0o100755)]);
        let node_archive = ab_tar_gz(&[
            ("node-24/bin/node", b"managed node", 0o100755),
            ("node-24/bin/npm", b"managed npm", 0o100755),
        ]);
        let jdk_url = "https://fixture.invalid/jdk-21.tar.gz".to_owned();
        let node_url = "https://fixture.invalid/node-24.tar.gz".to_owned();
        let jdk = ToolchainDescriptor::new(
            ToolchainKind::Jdk,
            "21.0.1",
            platform,
            TargetArchitecture::X64,
            jdk_url.clone(),
            bytes_sha256(&jdk_archive),
            ArchiveFormat::TarGz,
        )
        .home_dir("jdk-21")
        .executable("java", "jdk-21/bin/java");
        let node = ToolchainDescriptor::new(
            ToolchainKind::Node,
            "24.15.0",
            platform,
            TargetArchitecture::X64,
            node_url.clone(),
            bytes_sha256(&node_archive),
            ArchiveFormat::TarGz,
        )
        .home_dir("node-24")
        .executable("node", "node-24/bin/node")
        .executable("npm", "node-24/bin/npm");
        let key = SigningKey::from_bytes(&[31u8; 32]);
        let verifier = ManifestVerifier::with_keys(BTreeMap::from([(
            "test".to_owned(),
            key.verifying_key().to_bytes(),
        )]));
        AbFixture {
            _root: root,
            paths,
            remote_url,
            a,
            b,
            c,
            jdk,
            node,
            downloader: AbFixtureDownloader {
                archives: BTreeMap::from([(jdk_url, jdk_archive), (node_url, node_archive)]),
            },
            key,
            verifier,
        }
    }

    fn ab_manifest(fixture: &AbFixture, target: &str, generation: u64) -> RollingManifest {
        RollingManifest {
            schema_version: 1,
            channel: "rolling".to_owned(),
            generation,
            product_version: format!("1.0.{generation}"),
            target_commit: target.to_owned(),
            min_installer_version: None,
            jdk: fixture.jdk.clone(),
            node: fixture.node.clone(),
        }
    }

    fn ab_paths_and_state(fixture: &AbFixture, current: &str) {
        let store = StateStore::new(fixture.paths.clone());
        store.initialize().unwrap();
        let mut state = store.load_installation().unwrap();
        state.current_commit = Some(current.to_owned());
        state.current_toolchains = BTreeMap::from([
            ("jdk".to_owned(), fixture.jdk.id()),
            ("node".to_owned(), fixture.node.id()),
        ]);
        store.save_installation(&state).unwrap();
    }

    fn ab_update(
        fixture: &AbFixture,
        target: &str,
        generation: u64,
        runner: AbBuildRunner,
        hooks: &mut RecordingHooks,
        health: &AbHealthChecker,
        launcher: &AbLauncher,
    ) -> UpdateResult {
        let manifest = ab_manifest(fixture, target, generation);
        let fetcher = fixture_fetcher(&manifest, &fixture.key);
        UpdateEngine::new(
            fixture.paths.clone(),
            fixture.downloader.clone(),
            runner,
            None,
        )
        .update_with_sources_for_test(
            &fetcher,
            &fixture.verifier,
            "https://fixture.invalid/manifest",
            "https://fixture.invalid/manifest.sig",
            &fixture.remote_url,
            hooks,
            health,
            launcher,
        )
        .unwrap()
    }

    fn ab_commit(repository: &Repository, parent: Option<&str>, marker: &str) -> String {
        let product_version = ab_version(marker);
        let frontend_package = format!(r#"{{"version":"{product_version}"}}"#);
        let desktop_package = format!(r#"{{"version":"{product_version}"}}"#);
        let frontend = ab_tree(
            repository,
            &[
                ("package.json", frontend_package.as_bytes()),
                ("package-lock.json", b"{}"),
            ],
        );
        let desktop = ab_tree(
            repository,
            &[
                ("package.json", desktop_package.as_bytes()),
                ("package-lock.json", b"{}"),
            ],
        );
        let apps = ab_children(repository, &[("desktop", desktop)]);
        let wrapper = ab_tree(
            repository,
            &[(
                "maven-wrapper.properties",
                b"distributionType=only-script\ndistributionSha256Sum=0000000000000000000000000000000000000000000000000000000000000000\n",
            )],
        );
        let dot_mvn = ab_children(repository, &[("wrapper", wrapper)]);
        let mut root = repository.treebuilder(None).unwrap();
        root.insert("frontend", frontend, 0o040000).unwrap();
        root.insert("apps", apps, 0o040000).unwrap();
        root.insert(".mvn", dot_mvn, 0o040000).unwrap();
        let pom = format!(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <modelVersion>4.0.0</modelVersion>\n  <groupId>com.example</groupId>\n  <artifactId>fixture</artifactId>\n  <version>{}</version>\n</project>\n",
            ab_version(marker)
        );
        root.insert(
            "pom.xml",
            repository.blob(pom.as_bytes()).unwrap(),
            0o100644,
        )
        .unwrap();
        let versions = format!(
            r#"{{"schemaVersion":1,"productVersion":"{product_version}","installerVersion":"0.1.3","minimumInstallerVersion":"0.1.3"}}"#
        );
        root.insert(
            "versions.json",
            repository.blob(versions.as_bytes()).unwrap(),
            0o100644,
        )
        .unwrap();
        root.insert(
            "build-marker.txt",
            repository.blob(marker.as_bytes()).unwrap(),
            0o100644,
        )
        .unwrap();
        root.insert(
            "mvnw",
            repository.blob(b"#!/bin/sh\nexit 0\n").unwrap(),
            0o100755,
        )
        .unwrap();
        root.insert(
            "mvnw.cmd",
            repository.blob(b"@echo off\r\nexit /b 0\r\n").unwrap(),
            0o100755,
        )
        .unwrap();
        let tree = repository.find_tree(root.write().unwrap()).unwrap();
        let signature = Signature::now("Harmonia A/B", "ab@example.invalid").unwrap();
        let parent_commit = parent.map(|value| {
            repository
                .find_commit(Oid::from_str(value).unwrap())
                .unwrap()
        });
        let parents = parent_commit.iter().collect::<Vec<_>>();
        repository
            .commit(
                Some("refs/heads/main"),
                &signature,
                &signature,
                marker,
                &tree,
                &parents,
            )
            .unwrap()
            .to_string()
    }

    fn ab_tree(repository: &Repository, files: &[(&str, &[u8])]) -> Oid {
        let mut tree = repository.treebuilder(None).unwrap();
        for (name, contents) in files {
            tree.insert(name, repository.blob(contents).unwrap(), 0o100644)
                .unwrap();
        }
        tree.write().unwrap()
    }

    fn ab_version(marker: &str) -> &'static str {
        match marker {
            "A" => "1.0.0",
            "B" => "1.0.1",
            "C" => "1.0.2",
            _ => "1.0.0",
        }
    }

    fn ab_children(repository: &Repository, children: &[(&str, Oid)]) -> Oid {
        let mut tree = repository.treebuilder(None).unwrap();
        for (name, child) in children {
            tree.insert(name, *child, 0o040000).unwrap();
        }
        tree.write().unwrap()
    }

    fn ab_tar_gz(entries: &[(&str, &[u8], u32)]) -> Vec<u8> {
        let mut encoder = GzEncoder::new(Vec::new(), Compression::fast());
        {
            let mut archive = Builder::new(&mut encoder);
            for (name, contents, mode) in entries {
                let mut header = tar::Header::new_gnu();
                header.set_path(name).unwrap();
                header.set_mode(*mode);
                header.set_size(contents.len() as u64);
                header.set_cksum();
                archive.append(&header, *contents).unwrap();
            }
            archive.finish().unwrap();
        }
        encoder.finish().unwrap()
    }

    fn bytes_sha256(bytes: &[u8]) -> String {
        use sha2::{Digest, Sha256};
        let mut digest = Sha256::new();
        digest.update(bytes);
        digest
            .finalize()
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect()
    }

    #[test]
    fn controlled_ab_update_builds_exact_signed_b_and_shutdowns_only_at_activation() {
        let fixture = ab_fixture();
        ab_paths_and_state(&fixture, &fixture.a);
        let runner = AbBuildRunner::default();
        let hooks = &mut RecordingHooks::default();
        let health = AbHealthChecker {
            fail_commit: None,
            checks: Arc::new(Mutex::new(Vec::new())),
        };
        let launcher = AbLauncher::default();
        let result = ab_update(&fixture, &fixture.b, 1, runner, hooks, &health, &launcher);

        assert!(
            matches!(result.status, UpdateStatus::Updated { .. }),
            "unexpected update status: {:?}",
            result.status
        );
        let state = StateStore::new(fixture.paths.clone())
            .load_installation()
            .unwrap();
        assert_eq!(state.current_commit.as_deref(), Some(fixture.b.as_str()));
        assert_eq!(state.previous_commit.as_deref(), Some(fixture.a.as_str()));
        assert_eq!(*hooks.events.lock().unwrap(), vec!["request", "wait"]);
        assert_eq!(*launcher.launches.lock().unwrap(), vec![fixture.b.clone()]);
    }

    #[test]
    fn exact_signed_b_is_built_after_main_moves_to_c_and_up_to_date_skips_build() {
        let fixture = ab_fixture();
        ab_paths_and_state(&fixture, &fixture.a);
        let runner = AbBuildRunner::default();
        let hooks = &mut RecordingHooks::default();
        let health = AbHealthChecker {
            fail_commit: None,
            checks: Arc::new(Mutex::new(Vec::new())),
        };
        let launcher = AbLauncher::default();
        let result = ab_update(&fixture, &fixture.b, 1, runner, hooks, &health, &launcher);
        assert!(
            matches!(result.status, UpdateStatus::Updated { .. }),
            "unexpected update status: {:?}",
            result.status
        );
        assert_eq!(result.target_commit.as_deref(), Some(fixture.b.as_str()));

        let store = StateStore::new(fixture.paths.clone());
        let key = &fixture.key;
        let verifier = &fixture.verifier;
        let manifest = ab_manifest(&fixture, &fixture.b, 1);
        let fetcher = fixture_fetcher(&manifest, key);
        let current = UpdateEngine::new(fixture.paths.clone(), NoopDownloader, NoopRunner, None)
            .check_for_update(
                &fetcher,
                verifier,
                "https://fixture.invalid/manifest",
                "https://fixture.invalid/manifest.sig",
            )
            .unwrap();
        assert!(matches!(current.status, UpdateStatus::UpToDate { .. }));
        assert_eq!(
            store.load_installation().unwrap().current_commit.as_deref(),
            Some(fixture.b.as_str())
        );
        assert_ne!(fixture.b, fixture.c);
    }

    #[test]
    fn restart_infers_completed_activation_before_updater_journal_persist() {
        let fixture = ab_fixture();
        ab_paths_and_state(&fixture, &fixture.a);
        let health = AbHealthChecker {
            fail_commit: None,
            checks: Arc::new(Mutex::new(Vec::new())),
        };
        let mut hooks = RecordingHooks::default();
        let first = ab_update(
            &fixture,
            &fixture.b,
            1,
            AbBuildRunner::default(),
            &mut hooks,
            &health,
            &AbLauncher::default(),
        );
        assert!(matches!(first.status, UpdateStatus::Updated { .. }));

        let journal_path = fixture.paths.update_operation_path();
        let mut journal: UpdateJournal =
            serde_json::from_slice(&fs::read(&journal_path).unwrap()).unwrap();
        let record = StateStore::new(fixture.paths.clone())
            .load_transaction()
            .unwrap()
            .unwrap();
        assert_eq!(
            record.external_operation_id.as_deref(),
            Some(journal.operation_id.as_str())
        );
        if let Some(path) = journal.launch_ack_path.as_ref() {
            let _ = fs::remove_file(path);
        }
        journal.status = UpdateJournalStatus::Running;
        journal.phase = UpdateJournalPhase::Activating;
        journal.activation_completed = false;
        journal.launch_attempted = false;
        journal.launch_handoff_completed = false;
        journal.finished_at_ms = None;
        crate::state::atomic_write_json(&journal_path, &journal).unwrap();

        let runner = AbBuildRunner::default();
        let calls = runner.calls.clone();
        let resumed = ab_update(
            &fixture,
            &fixture.b,
            1,
            runner,
            &mut hooks,
            &health,
            &AbLauncher::default(),
        );
        assert!(matches!(resumed.status, UpdateStatus::Updated { .. }));
        assert!(calls.lock().unwrap().is_empty(), "resume must not rebuild");
        assert_eq!(
            StateStore::new(fixture.paths.clone())
                .load_installation()
                .unwrap()
                .current_commit
                .as_deref(),
            Some(fixture.b.as_str())
        );
        let terminal: UpdateJournal =
            serde_json::from_slice(&fs::read(journal_path).unwrap()).unwrap();
        assert_eq!(terminal.status, UpdateJournalStatus::Completed);
        assert!(terminal.activation_completed);
        assert!(terminal.launch_handoff_completed);
    }

    #[test]
    fn current_target_without_matching_low_level_operation_requires_review() {
        let fixture = ab_fixture();
        ab_paths_and_state(&fixture, &fixture.a);
        let health = AbHealthChecker {
            fail_commit: None,
            checks: Arc::new(Mutex::new(Vec::new())),
        };
        let mut hooks = RecordingHooks::default();
        let first = ab_update(
            &fixture,
            &fixture.b,
            1,
            AbBuildRunner::default(),
            &mut hooks,
            &health,
            &AbLauncher::default(),
        );
        assert!(matches!(first.status, UpdateStatus::Updated { .. }));

        let journal_path = fixture.paths.update_operation_path();
        let mut journal: UpdateJournal =
            serde_json::from_slice(&fs::read(&journal_path).unwrap()).unwrap();
        journal.status = UpdateJournalStatus::Running;
        journal.phase = UpdateJournalPhase::Activating;
        journal.activation_completed = false;
        journal.operation_id = "unrelated-operation".to_owned();
        journal.finished_at_ms = None;
        crate::state::atomic_write_json(&journal_path, &journal).unwrap();

        let resumed = ab_update(
            &fixture,
            &fixture.b,
            1,
            AbBuildRunner::default(),
            &mut hooks,
            &health,
            &AbLauncher::default(),
        );
        assert!(matches!(
            resumed.status,
            UpdateStatus::ReviewRequired { .. }
        ));
    }

    #[test]
    fn build_failure_leaves_a_and_never_requests_desktop_shutdown() {
        for failure in [
            AbFailurePoint::Frontend,
            AbFailurePoint::Backend,
            AbFailurePoint::Desktop,
        ] {
            let fixture = ab_fixture();
            ab_paths_and_state(&fixture, &fixture.a);
            let hooks = &mut RecordingHooks::default();
            let health = AbHealthChecker {
                fail_commit: None,
                checks: Arc::new(Mutex::new(Vec::new())),
            };
            let launcher = AbLauncher::default();
            let result = ab_update(
                &fixture,
                &fixture.b,
                1,
                AbBuildRunner {
                    failure: Some(failure),
                    ..Default::default()
                },
                hooks,
                &health,
                &launcher,
            );
            assert!(
                matches!(result.status, UpdateStatus::UpdateBuildFailed { .. }),
                "unexpected build failure status: {:?}",
                result.status
            );
            assert!(hooks.events.lock().unwrap().is_empty());
            assert!(!fixture.paths.desktop_shutdown_request_path().exists());
            assert_eq!(
                StateStore::new(fixture.paths.clone())
                    .load_installation()
                    .unwrap()
                    .current_commit
                    .as_deref(),
                Some(fixture.a.as_str())
            );
        }
    }

    #[test]
    fn activation_failure_restores_a_database_and_current_pointer() {
        let fixture = ab_fixture();
        ab_paths_and_state(&fixture, &fixture.a);
        let healthy = AbHealthChecker {
            fail_commit: None,
            checks: Arc::new(Mutex::new(Vec::new())),
        };
        let mut hooks = RecordingHooks::default();
        let first = ab_update(
            &fixture,
            &fixture.b,
            1,
            AbBuildRunner::default(),
            &mut hooks,
            &healthy,
            &AbLauncher::default(),
        );
        assert!(
            matches!(first.status, UpdateStatus::Updated { .. }),
            "unexpected first update status: {:?}",
            first.status
        );
        let database = fixture.paths.user_data_root.join("data/core/harmonia.db");
        fs::create_dir_all(database.parent().unwrap()).unwrap();
        fs::write(&database, b"database-before-c").unwrap();

        let failing = AbHealthChecker {
            fail_commit: Some(fixture.c.clone()),
            checks: Arc::new(Mutex::new(Vec::new())),
        };
        let second = ab_update(
            &fixture,
            &fixture.c,
            2,
            AbBuildRunner::default(),
            &mut hooks,
            &failing,
            &AbLauncher::default(),
        );
        assert!(matches!(
            second.status,
            UpdateStatus::ActivationFailed { .. }
        ));
        assert_eq!(fs::read(&database).unwrap(), b"database-before-c");
        assert_eq!(
            StateStore::new(fixture.paths.clone())
                .load_installation()
                .unwrap()
                .current_commit
                .as_deref(),
            Some(fixture.b.as_str())
        );
    }

    #[test]
    fn desktop_launch_failure_rolls_back_to_previous_ack_and_review_blocks_retry() {
        let fixture = ab_fixture();
        ab_paths_and_state(&fixture, &fixture.a);
        let health = AbHealthChecker {
            fail_commit: None,
            checks: Arc::new(Mutex::new(Vec::new())),
        };
        let mut hooks = RecordingHooks::default();
        let first = ab_update(
            &fixture,
            &fixture.b,
            1,
            AbBuildRunner::default(),
            &mut hooks,
            &health,
            &AbLauncher::default(),
        );
        assert!(
            matches!(first.status, UpdateStatus::Updated { .. }),
            "unexpected first update status: {:?}",
            first.status
        );

        let launcher = AbLauncher::default();
        launcher
            .fail_commits
            .lock()
            .unwrap()
            .insert(fixture.c.clone());
        let rolled_back = ab_update(
            &fixture,
            &fixture.c,
            2,
            AbBuildRunner::default(),
            &mut hooks,
            &health,
            &launcher,
        );
        assert!(matches!(
            rolled_back.status,
            UpdateStatus::RollbackCompleted { .. }
        ));
        assert_eq!(
            StateStore::new(fixture.paths.clone())
                .load_installation()
                .unwrap()
                .current_commit
                .as_deref(),
            Some(fixture.b.as_str())
        );

        let blocked_launcher = AbLauncher::default();
        blocked_launcher
            .fail_commits
            .lock()
            .unwrap()
            .extend([fixture.c.clone(), fixture.b.clone()]);
        let review = ab_update(
            &fixture,
            &fixture.c,
            2,
            AbBuildRunner::default(),
            &mut hooks,
            &health,
            &blocked_launcher,
        );
        assert!(
            matches!(review.status, UpdateStatus::ReviewRequired { .. }),
            "unexpected review status: {:?}",
            review.status
        );

        let restart = ab_update(
            &fixture,
            &fixture.b,
            4,
            AbBuildRunner::default(),
            &mut hooks,
            &health,
            &AbLauncher::default(),
        );
        assert!(matches!(
            restart.status,
            UpdateStatus::ReviewRequired { .. }
        ));
    }

    #[test]
    fn concurrent_update_is_rejected_by_the_global_lock() {
        let fixture = ab_fixture();
        ab_paths_and_state(&fixture, &fixture.a);
        let lock = InstallationLock::acquire(fixture.paths.lock_path(), "test-owner").unwrap();
        let manifest = ab_manifest(&fixture, &fixture.b, 1);
        let fetcher = fixture_fetcher(&manifest, &fixture.key);
        let mut hooks = RecordingHooks::default();
        let health = AbHealthChecker {
            fail_commit: None,
            checks: Arc::new(Mutex::new(Vec::new())),
        };
        let result = UpdateEngine::new(fixture.paths.clone(), NoopDownloader, NoopRunner, None)
            .update_with_sources_for_test(
                &fetcher,
                &fixture.verifier,
                "https://fixture.invalid/manifest",
                "https://fixture.invalid/manifest.sig",
                &fixture.remote_url,
                &mut hooks,
                &health,
                &AbLauncher::default(),
            );
        drop(lock);
        assert!(matches!(
            result,
            Err(UpdateError::Lock(LockError::Busy { .. }))
        ));
    }

    #[test]
    fn replay_protection_rejects_lower_generation_and_conflicting_same_generation() {
        let root = tempfile::tempdir().unwrap();
        let paths = InstallationPaths {
            platform: Platform::Linux,
            architecture: TargetArchitecture::X64,
            app_root: root.path().join("app"),
            user_data_root: root.path().join("data"),
            state_root: root.path().join("state"),
            cache_root: root.path().join("cache"),
        };
        let store = StateStore::new(paths.clone());
        store.initialize().unwrap();
        let mut state = store.load_installation().unwrap();
        state.accepted_manifest_generation = Some(9);
        state.accepted_manifest_sha256 = Some("a".repeat(64));
        store.save_installation(&state).unwrap();
        let key = SigningKey::from_bytes(&[4u8; 32]);
        let verifier = ManifestVerifier::with_keys(BTreeMap::from([(
            "test".to_owned(),
            key.verifying_key().to_bytes(),
        )]));
        let mut lower = fixture_manifest("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        lower.generation = 8;
        let engine = UpdateEngine::new(paths.clone(), NoopDownloader, NoopRunner, None);
        let lower_result = engine
            .check_for_update(
                &fixture_fetcher(&lower, &key),
                &verifier,
                "https://fixture/manifest",
                "https://fixture/manifest.sig",
            )
            .unwrap();
        assert!(matches!(
            lower_result.status,
            UpdateStatus::TrustFailure { .. }
        ));

        let mut conflicting = lower;
        conflicting.generation = 9;
        let conflicting_result = engine
            .check_for_update(
                &fixture_fetcher(&conflicting, &key),
                &verifier,
                "https://fixture/manifest",
                "https://fixture/manifest.sig",
            )
            .unwrap();
        assert!(matches!(
            conflicting_result.status,
            UpdateStatus::TrustFailure { .. }
        ));
    }

    #[test]
    fn controlled_ab_check_distinguishes_available_up_to_date_and_trust_outcomes() {
        let root = tempfile::tempdir().unwrap();
        let paths = InstallationPaths {
            platform: Platform::Linux,
            architecture: TargetArchitecture::X64,
            app_root: root.path().join("app"),
            user_data_root: root.path().join("data"),
            state_root: root.path().join("state"),
            cache_root: root.path().join("cache"),
        };
        let store = StateStore::new(paths.clone());
        store.initialize().unwrap();
        let a = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let b = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        let mut installation = store.load_installation().unwrap();
        installation.current_commit = Some(a.to_owned());
        store.save_installation(&installation).unwrap();
        let key = SigningKey::from_bytes(&[11u8; 32]);
        let verifier = ManifestVerifier::with_keys(BTreeMap::from([(
            "test".to_owned(),
            key.verifying_key().to_bytes(),
        )]));
        let engine = UpdateEngine::new(paths.clone(), NoopDownloader, NoopRunner, None);
        let manifest = fixture_manifest(b);
        let fetcher = fixture_fetcher(&manifest, &key);
        let available = engine
            .check_for_update(
                &fetcher,
                &verifier,
                "https://fixture/manifest",
                "https://fixture/manifest.sig",
            )
            .unwrap();
        assert!(matches!(
            available.status,
            UpdateStatus::UpdateAvailable { .. }
        ));

        installation.current_commit = Some(b.to_owned());
        store.save_installation(&installation).unwrap();
        let current = engine
            .check_for_update(
                &fetcher,
                &verifier,
                "https://fixture/manifest",
                "https://fixture/manifest.sig",
            )
            .unwrap();
        assert!(matches!(current.status, UpdateStatus::UpToDate { .. }));

        let mut wrong_channel = manifest.clone();
        wrong_channel.channel = "stable".to_owned();
        let trust = engine
            .check_for_update(
                &fixture_fetcher(&wrong_channel, &key),
                &verifier,
                "https://fixture/manifest",
                "https://fixture/manifest.sig",
            )
            .unwrap();
        assert!(matches!(trust.status, UpdateStatus::TrustFailure { .. }));

        let mut invalid_signature = fixture_fetcher(&manifest, &key);
        let last = invalid_signature.signature.len() - 3;
        invalid_signature.signature[last] = if invalid_signature.signature[last] == b'0' {
            b'1'
        } else {
            b'0'
        };
        let trust = engine
            .check_for_update(
                &invalid_signature,
                &verifier,
                "https://fixture/manifest",
                "https://fixture/manifest.sig",
            )
            .unwrap();
        assert!(matches!(trust.status, UpdateStatus::TrustFailure { .. }));
    }

    #[test]
    fn minimum_installer_floor_rejects_012_and_accepts_013() {
        assert!(ensure_minimum_installer_version(Some("0.1.3"), "0.1.3").is_ok());
        assert!(matches!(
            ensure_minimum_installer_version(Some("0.1.3"), "0.1.2"),
            Err(ManifestError::UpdaterUpgradeRequired(minimum)) if minimum == "0.1.3"
        ));
    }

    #[test]
    fn minimum_version_outcomes_are_machine_readable() {
        let root = tempfile::tempdir().unwrap();
        let paths = InstallationPaths {
            platform: Platform::Linux,
            architecture: TargetArchitecture::X64,
            app_root: root.path().join("app"),
            user_data_root: root.path().join("data"),
            state_root: root.path().join("state"),
            cache_root: root.path().join("cache"),
        };
        StateStore::new(paths.clone()).initialize().unwrap();
        let key = SigningKey::from_bytes(&[12u8; 32]);
        let verifier = ManifestVerifier::with_keys(BTreeMap::from([(
            "test".to_owned(),
            key.verifying_key().to_bytes(),
        )]));
        let mut manifest = fixture_manifest("cccccccccccccccccccccccccccccccccccccccc");
        manifest.min_installer_version = Some("999.0.0".to_owned());
        let result = UpdateEngine::new(paths, NoopDownloader, NoopRunner, None)
            .check_for_update(
                &fixture_fetcher(&manifest, &key),
                &verifier,
                "https://fixture/manifest",
                "https://fixture/manifest.sig",
            )
            .unwrap();
        assert!(matches!(
            result.status,
            UpdateStatus::UpdaterUpgradeRequired { .. }
        ));
    }
}
