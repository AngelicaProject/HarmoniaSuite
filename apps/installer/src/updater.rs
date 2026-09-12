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
use crate::bootstrap::{runtime_launch_spec, wait_for_runtime_launch_ack};
use crate::build::{BuildConfig, BuildError, BuildPipeline};
use crate::detached::{DetachedLaunchError, DetachedLauncher};
use crate::diagnostics::{DiagnosticError, DiagnosticLogger};
use crate::download::DownloadClient;
use crate::lock::{InstallationLock, LockError};
use crate::manifest::{
    HttpManifestFetcher, ManifestError, ManifestFetcher, ManifestVerifier, SignedManifest,
    DEFAULT_MANIFEST_SIGNATURE_URL, DEFAULT_MANIFEST_URL,
};
use crate::paths::InstallationPaths;
use crate::process::ProcessRunner;
use crate::state::{StateError, StateStore};
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
    Restarting,
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
    launch_ack_path: Option<PathBuf>,
    launch_nonce: Option<String>,
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
            launch_ack_path: Some(paths.bootstrap_ack_path(&operation_id, &nonce)),
            launch_nonce: Some(nonce),
            failure: None,
        }
    }
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
        self.update_with_sources(
            &fetcher,
            &verifier,
            DEFAULT_MANIFEST_URL,
            DEFAULT_MANIFEST_SIGNATURE_URL,
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
        let manifest = self.fetch_and_verify(fetcher, verifier, manifest_url, signature_url)?;
        self.check_replay(&store, &manifest)?;
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
        hooks: &mut A,
        checker: &H,
        launcher: &L,
    ) -> Result<UpdateResult, UpdateError> {
        if lock.path() != self.paths.lock_path() {
            return Err(UpdateError::Active("invalid installation lock".to_owned()));
        }
        let store = StateStore::new(self.paths.clone());
        store.initialize()?;
        let activation = ActivationEngine::new(self.paths.clone());
        let recovery_config = ActivationConfig::for_paths(&self.paths, PathBuf::new());
        activation.recover_with_lock(&recovery_config, checker, lock)?;
        if let Some(result) = self.reconcile_old_journal(launcher)? {
            return Ok(result);
        }

        let mut journal = UpdateJournal::new(&self.paths);
        persist_journal(&self.paths, &journal)?;
        journal.phase = UpdateJournalPhase::Checking;
        persist_journal(&self.paths, &journal)?;

        let signed = match self.fetch_and_verify(fetcher, verifier, manifest_url, signature_url) {
            Ok(value) => value,
            Err(error) => {
                journal.status = UpdateJournalStatus::Failed;
                journal.phase = UpdateJournalPhase::Failed;
                journal.failure = Some(error.to_string());
                finish_journal(&self.paths, &mut journal)?;
                return Err(error.into());
            }
        };
        if let Err(error) = self.check_replay(&store, &signed) {
            journal.status = UpdateJournalStatus::ReviewRequired;
            journal.phase = UpdateJournalPhase::ReviewRequired;
            journal.failure = Some(error.to_string());
            finish_journal(&self.paths, &mut journal)?;
            return Err(error);
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
        let config = BuildConfig::new(
            crate::bootstrap::DEFAULT_REMOTE_URL,
            manifest.product_version.clone(),
            jdk,
            node,
        )
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
                return Ok(result_from_journal(
                    &journal,
                    UpdateStatus::UpdateBuildFailed {
                        reason: error.to_string(),
                    },
                ));
            }
        };
        if build.target_commit != manifest.target_commit {
            return Err(UpdateError::TrustFailure(
                "build result target differs from signed manifest".to_owned(),
            ));
        }
        journal.phase = UpdateJournalPhase::Activating;
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
        let runtime = match activation.activate_with_lock(
            result_path,
            &activation_config,
            hooks,
            checker,
            lock,
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
        let launch_error = launcher.launch(&launch).err();
        if let Some(error) = launch_error.as_ref() {
            if !matches!(error, DetachedLaunchError::ExitedEarly { .. }) {
                return self.finish_launch_failure(&mut journal, &runtime, error.to_string());
            }
        }
        if let Err(error) = wait_for_runtime_launch_ack(
            &self.paths,
            &ack_path,
            &journal.operation_id,
            &runtime.metadata.target_commit,
            &nonce,
            LAUNCH_ACK_TIMEOUT,
        ) {
            let reason = launch_error
                .map(|launch| format!("{launch}; {error}"))
                .unwrap_or_else(|| error.to_string());
            return self.finish_launch_failure(&mut journal, &runtime, reason);
        }
        journal.launch_handoff_completed = true;
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

    fn finish_launch_failure(
        &self,
        journal: &mut UpdateJournal,
        runtime: &crate::activation::RuntimePaths,
        reason: String,
    ) -> Result<UpdateResult, UpdateError> {
        journal.status = UpdateJournalStatus::Failed;
        journal.phase = UpdateJournalPhase::Failed;
        journal.failure = Some(reason.clone());
        finish_journal(&self.paths, journal)?;
        Ok(result_from_journal(
            journal,
            UpdateStatus::LaunchFailed {
                reason,
                version_dir: runtime.version_dir.clone(),
            },
        ))
    }

    fn reconcile_old_journal<L: DetachedLauncher>(
        &self,
        launcher: &L,
    ) -> Result<Option<UpdateResult>, UpdateError> {
        let path = self.paths.update_operation_path();
        if !path.is_file() {
            return Ok(None);
        }
        let journal: UpdateJournal = serde_json::from_slice(&fs::read(path)?)?;
        if journal.schema_version != UPDATE_JOURNAL_SCHEMA_VERSION {
            return Err(UpdateError::TrustFailure(
                "unsupported updater journal schema".to_owned(),
            ));
        }
        if journal.status == UpdateJournalStatus::ReviewRequired {
            return Err(UpdateError::Active(journal.operation_id));
        }
        if journal.status != UpdateJournalStatus::Running {
            return Ok(None);
        }
        if journal.activation_completed {
            let activation = ActivationEngine::new(self.paths.clone());
            let runtime = activation
                .resolve_current()?
                .ok_or_else(|| UpdateError::Active(journal.operation_id.clone()))?;
            let ack_path = journal
                .launch_ack_path
                .clone()
                .ok_or_else(|| UpdateError::Active(journal.operation_id.clone()))?;
            let nonce = journal
                .launch_nonce
                .clone()
                .ok_or_else(|| UpdateError::Active(journal.operation_id.clone()))?;
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
            let launch_error = launcher.launch(&launch).err();
            if let Some(error) = launch_error.as_ref() {
                if !matches!(error, DetachedLaunchError::ExitedEarly { .. }) {
                    return self
                        .finish_launch_failure(&mut resumed, &runtime, error.to_string())
                        .map(Some);
                }
            }
            if let Err(error) = wait_for_runtime_launch_ack(
                &self.paths,
                &ack_path,
                &resumed.operation_id,
                &runtime.metadata.target_commit,
                &nonce,
                LAUNCH_ACK_TIMEOUT,
            ) {
                let reason = launch_error
                    .map(|launch| format!("{launch}; {error}"))
                    .unwrap_or_else(|| error.to_string());
                return self
                    .finish_launch_failure(&mut resumed, &runtime, reason)
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
        if let Some(minimum) = signed.manifest.min_installer_version.as_deref() {
            if !version_at_least(env!("CARGO_PKG_VERSION"), minimum) {
                return Err(ManifestError::UpdaterUpgradeRequired(minimum.to_owned()));
            }
        }
        crate::state::atomic_write_bytes(&self.paths.update_manifest_cache_path(), &manifest)
            .map_err(|error| ManifestError::Io(std::io::Error::other(error.to_string())))?;
        crate::state::atomic_write_bytes(
            &self.paths.update_manifest_signature_cache_path(),
            &signature,
        )
        .map_err(|error| ManifestError::Io(std::io::Error::other(error.to_string())))?;
        Ok(signed)
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

fn version_at_least(current: &str, minimum: &str) -> bool {
    let parse = |value: &str| {
        value
            .split('.')
            .map(|part| {
                part.split(['-', '+'])
                    .next()
                    .unwrap_or(part)
                    .parse::<u64>()
                    .unwrap_or(0)
            })
            .collect::<Vec<_>>()
    };
    let current = parse(current);
    let minimum = parse(minimum);
    let length = current.len().max(minimum.len());
    for index in 0..length {
        let left = current.get(index).copied().unwrap_or(0);
        let right = minimum.get(index).copied().unwrap_or(0);
        if left != right {
            return left > right;
        }
    }
    true
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
    use crate::manifest::{ManifestSignature, ManifestVerifier};
    use crate::paths::{Platform, TargetArchitecture};
    use ed25519_dalek::SigningKey;
    use std::collections::BTreeMap;

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
        let _ = (
            key,
            verifier,
            ManifestSignature {
                schema_version: 1,
                key_id: "test".to_owned(),
                signature_hex: String::new(),
            },
            TargetArchitecture::X64,
        );
        assert_eq!(state.accepted_manifest_generation, Some(9));
    }
}
