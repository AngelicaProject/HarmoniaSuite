use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::json;
use thiserror::Error;
use uuid::Uuid;

use crate::activation::{
    ActivationConfig, ActivationEngine, ActivationError, HealthChecker, LocalBackendHealthChecker,
    NoopActivationHooks,
};
use crate::build::{BuildConfig, BuildError, BuildPipeline, BuildResult};
use crate::catalog::production_descriptors;
use crate::detached::{DetachedLaunchSpec, DetachedLauncher};
use crate::diagnostics::{DiagnosticError, DiagnosticLogger};
use crate::download::DownloadClient;
use crate::lock::{InstallationLock, LockError};
use crate::paths::InstallationPaths;
use crate::process::{ProcessRunner, SystemProcessRunner};
use crate::state::{validate_managed_path, OperationKind, StateError, StateStore};
use crate::toolchain::{ToolchainError, ToolchainStateStore};

pub const DEFAULT_REMOTE_URL: &str = "https://github.com/AngelicaProject/HarmoniaSuite.git";
const BOOTSTRAP_JOURNAL_SCHEMA_VERSION: u32 = 3;
const LAUNCH_ACK_SCHEMA_VERSION: u32 = 1;
const LAUNCH_ACK_TIMEOUT: Duration = Duration::from_secs(30);
const LAUNCH_ACK_AFTER_EARLY_EXIT_TIMEOUT: Duration = Duration::from_secs(3);

fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}
pub const DEFAULT_PRODUCT_VERSION: &str = "1.0.11-SNAPSHOT";

#[derive(Clone, Debug)]
pub struct BootstrapOptions {
    remote_url: String,
    product_version: String,
}

impl Default for BootstrapOptions {
    fn default() -> Self {
        Self {
            remote_url: DEFAULT_REMOTE_URL.to_owned(),
            product_version: DEFAULT_PRODUCT_VERSION.to_owned(),
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum BootstrapStatus {
    Installed {
        commit: String,
        product_version: String,
        version_dir: PathBuf,
    },
    AlreadyInstalled {
        commit: String,
        version_dir: PathBuf,
    },
    RepairRequired {
        commit: String,
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
    LaunchFailed {
        reason: String,
        version_dir: PathBuf,
    },
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct BootstrapResult {
    pub operation_id: String,
    pub target_commit: Option<String>,
    pub product_version: String,
    pub platform: String,
    pub architecture: String,
    pub toolchains: BTreeMap<String, String>,
    pub phase: String,
    pub duration_ms: u128,
    pub activation_succeeded: bool,
    pub launch_succeeded: bool,
    pub status: BootstrapStatus,
}

#[derive(Debug, Error)]
pub enum BootstrapError {
    #[error("bootstrap lock failed: {0}")]
    Lock(#[from] LockError),
    #[error("bootstrap state failed: {0}")]
    State(#[from] StateError),
    #[error("bootstrap I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("bootstrap JSON failed: {0}")]
    Json(#[from] serde_json::Error),
    #[error("unsupported bootstrap journal schema: {0}")]
    UnsupportedJournalSchema(u32),
    #[error("bootstrap diagnostics failed: {0}")]
    Diagnostics(#[from] DiagnosticError),
    #[error("bootstrap toolchain catalog failed: {0}")]
    Toolchain(#[from] ToolchainError),
    #[error("bootstrap build failed: {0}")]
    Build(#[from] BuildError),
    #[error("desktop launch acknowledgement path is invalid: {0}")]
    InvalidLaunchAckPath(PathBuf),
    #[error("desktop launch acknowledgement timed out: {0}")]
    LaunchHandshakeTimeout(PathBuf),
}

fn new_bootstrap_journal(
    paths: &InstallationPaths,
    options: &BootstrapOptions,
    operation_id: String,
) -> BootstrapJournal {
    let launch_nonce = Uuid::new_v4().simple().to_string();
    BootstrapJournal {
        schema_version: BOOTSTRAP_JOURNAL_SCHEMA_VERSION,
        operation_id: operation_id.clone(),
        operation: OperationKind::Install,
        status: BootstrapJournalStatus::Running,
        started_at_ms: now_ms(),
        finished_at_ms: None,
        phase: BootstrapJournalPhase::Recovering,
        target_commit: None,
        product_version: options.product_version.clone(),
        toolchains: BTreeMap::new(),
        activation_completed: false,
        launch_attempted: false,
        launch_handoff_completed: false,
        launch_ack_path: Some(paths.bootstrap_ack_path(&operation_id, &launch_nonce)),
        launch_nonce: Some(launch_nonce),
        failure: None,
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct LaunchAcknowledgement {
    schema_version: u32,
    operation_id: String,
    target_commit: String,
    nonce: String,
    acknowledged_at_ms: u128,
}

fn ensure_launch_identity(
    paths: &InstallationPaths,
    journal: &mut BootstrapJournal,
) -> Result<(), BootstrapError> {
    if journal.launch_nonce.is_none() || journal.launch_ack_path.is_none() {
        let nonce = Uuid::new_v4().simple().to_string();
        journal.launch_nonce = Some(nonce.clone());
        journal.launch_ack_path = Some(paths.bootstrap_ack_path(&journal.operation_id, &nonce));
    }
    let path = journal
        .launch_ack_path
        .as_ref()
        .expect("launch ack path initialized");
    validate_managed_path(&paths.state_root, path)?;
    Ok(())
}

fn load_bootstrap_journal(
    paths: &InstallationPaths,
) -> Result<Option<BootstrapJournal>, BootstrapError> {
    let path = paths.bootstrap_operation_path();
    if !path.is_file() {
        return Ok(None);
    }
    let mut journal: BootstrapJournal = serde_json::from_slice(&std::fs::read(path)?)?;
    if journal.schema_version > BOOTSTRAP_JOURNAL_SCHEMA_VERSION {
        return Err(BootstrapError::UnsupportedJournalSchema(
            journal.schema_version,
        ));
    }
    journal.schema_version = BOOTSTRAP_JOURNAL_SCHEMA_VERSION;
    Ok(Some(journal))
}

fn persist_bootstrap_journal(
    paths: &InstallationPaths,
    journal: &BootstrapJournal,
) -> Result<(), BootstrapError> {
    crate::state::atomic_write_json(&paths.bootstrap_operation_path(), journal)?;
    Ok(())
}

fn read_matching_launch_ack(
    paths: &InstallationPaths,
    journal: &BootstrapJournal,
    target_commit: &str,
) -> Result<bool, BootstrapError> {
    ensure_launch_identity_path(paths, journal)?;
    let Some(path) = journal.launch_ack_path.as_ref() else {
        return Ok(false);
    };
    if !path.is_file() {
        return Ok(false);
    }
    let document = match fs::read(path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(error.into()),
    };
    let Ok(ack) = serde_json::from_slice::<LaunchAcknowledgement>(&document) else {
        return Ok(false);
    };
    Ok(ack.schema_version == LAUNCH_ACK_SCHEMA_VERSION
        && ack.operation_id == journal.operation_id
        && ack.target_commit == target_commit
        && Some(&ack.nonce) == journal.launch_nonce.as_ref())
}

fn ensure_launch_identity_path(
    paths: &InstallationPaths,
    journal: &BootstrapJournal,
) -> Result<(), BootstrapError> {
    let Some(path) = journal.launch_ack_path.as_ref() else {
        return Err(BootstrapError::InvalidLaunchAckPath(
            paths.bootstrap_ack_dir(),
        ));
    };
    validate_managed_path(&paths.state_root, path)?;
    Ok(())
}

fn wait_for_launch_ack(
    paths: &InstallationPaths,
    journal: &BootstrapJournal,
    target_commit: &str,
    timeout: Duration,
) -> Result<(), BootstrapError> {
    ensure_launch_identity_path(paths, journal)?;
    let deadline = Instant::now() + timeout;
    loop {
        if read_matching_launch_ack(paths, journal, target_commit)? {
            return Ok(());
        }
        if Instant::now() >= deadline {
            return Err(BootstrapError::LaunchHandshakeTimeout(
                journal
                    .launch_ack_path
                    .clone()
                    .expect("launch ack path validated"),
            ));
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}

fn desktop_launch_spec(
    paths: &InstallationPaths,
    runtime: &crate::activation::RuntimePaths,
    journal: &BootstrapJournal,
) -> DetachedLaunchSpec {
    let mut spec = DetachedLaunchSpec::new(runtime.desktop_executable.clone())
        .current_dir(runtime.version_dir.clone())
        .env("HARMONIA_RUNTIME_MODE", "installed")
        .env(
            "HARMONIA_ACTIVE_VERSION_DIR",
            runtime.version_dir.display().to_string(),
        )
        .env(
            "HARMONIA_BACKEND_JAR",
            runtime.backend_jar.display().to_string(),
        )
        .env(
            "HARMONIA_JAVA_BINARY",
            runtime.java_binary.display().to_string(),
        )
        .env(
            "HARMONIA_USER_DATA_ROOT",
            paths.user_data_root.display().to_string(),
        )
        .env(
            "HARMONIA_WORKSPACE",
            paths.user_data_root.display().to_string(),
        )
        .env(
            "HARMONIA_INSTALL_STATE_ROOT",
            paths.state_root.display().to_string(),
        );
    if let (Some(ack_path), Some(nonce)) = (&journal.launch_ack_path, &journal.launch_nonce) {
        spec = spec
            .env("HARMONIA_LAUNCH_ACK", ack_path.display().to_string())
            .env("HARMONIA_LAUNCH_OPERATION_ID", journal.operation_id.clone())
            .env(
                "HARMONIA_LAUNCH_COMMIT",
                runtime.metadata.target_commit.clone(),
            )
            .env("HARMONIA_LAUNCH_NONCE", nonce.clone());
    }
    spec
}

fn reconcile_running_activation<L: DetachedLauncher>(
    paths: &InstallationPaths,
    options: &BootstrapOptions,
    mut journal: BootstrapJournal,
    activation: &ActivationEngine,
    launcher: &L,
    logger: Option<&DiagnosticLogger>,
) -> Result<BootstrapResult, BootstrapError> {
    let started_at_ms = journal.started_at_ms;
    let operation_id = journal.operation_id.clone();
    let runtime = match activation.resolve_current() {
        Ok(Some(runtime)) => runtime,
        Ok(None) => {
            let reason =
                "bootstrap journal says activation completed but current version is missing";
            journal.failure = Some(reason.to_owned());
            return finish_result(
                paths,
                logger,
                &operation_id,
                options,
                started_at_ms,
                journal,
                BootstrapStatus::ReviewRequired {
                    reason: reason.to_owned(),
                },
                None,
                BTreeMap::new(),
                false,
                false,
            );
        }
        Err(error) => {
            let reason = format!("cannot resolve current after completed activation: {error}");
            journal.failure = Some(reason.clone());
            let target_commit = journal.target_commit.clone();
            let toolchains = journal.toolchains.clone();
            return finish_result(
                paths,
                logger,
                &operation_id,
                options,
                started_at_ms,
                journal,
                BootstrapStatus::ReviewRequired { reason },
                target_commit,
                toolchains,
                false,
                false,
            );
        }
    };

    journal.target_commit = Some(runtime.metadata.target_commit.clone());
    ensure_launch_identity(paths, &mut journal)?;
    if journal.product_version.is_empty() {
        journal.product_version = runtime.metadata.product_version.clone();
    }
    if journal.toolchains.is_empty() {
        journal.toolchains = runtime.metadata.toolchains.clone();
    }
    journal.phase = BootstrapJournalPhase::Launching;
    persist_bootstrap_journal(paths, &journal)?;
    if read_matching_launch_ack(paths, &journal, &runtime.metadata.target_commit)? {
        journal.launch_handoff_completed = true;
        return finish_result(
            paths,
            logger,
            &operation_id,
            options,
            started_at_ms,
            journal,
            BootstrapStatus::Installed {
                commit: runtime.metadata.target_commit.clone(),
                product_version: runtime.metadata.product_version,
                version_dir: runtime.version_dir,
            },
            Some(runtime.metadata.target_commit),
            runtime.metadata.toolchains,
            true,
            true,
        );
    }
    let launch = desktop_launch_spec(paths, &runtime, &journal);
    journal.launch_attempted = true;
    persist_bootstrap_journal(paths, &journal)?;
    let launch_error = launcher.launch(&launch).err();
    if let Some(error) = launch_error.as_ref() {
        if !matches!(
            error,
            crate::detached::DetachedLaunchError::ExitedEarly { .. }
        ) {
            let reason = error.to_string();
            journal.failure = Some(reason.clone());
            return finish_result(
                paths,
                logger,
                &operation_id,
                options,
                started_at_ms,
                journal,
                BootstrapStatus::LaunchFailed {
                    reason,
                    version_dir: runtime.version_dir,
                },
                Some(runtime.metadata.target_commit),
                runtime.metadata.toolchains,
                true,
                false,
            );
        }
    }
    let timeout = if matches!(
        launch_error.as_ref(),
        Some(crate::detached::DetachedLaunchError::ExitedEarly { .. })
    ) {
        LAUNCH_ACK_AFTER_EARLY_EXIT_TIMEOUT
    } else {
        LAUNCH_ACK_TIMEOUT
    };
    if let Err(ack_error) =
        wait_for_launch_ack(paths, &journal, &runtime.metadata.target_commit, timeout)
    {
        let reason = launch_error
            .map(|error| format!("{error}; {ack_error}"))
            .unwrap_or_else(|| ack_error.to_string());
        journal.failure = Some(reason.clone());
        return finish_result(
            paths,
            logger,
            &operation_id,
            options,
            started_at_ms,
            journal,
            BootstrapStatus::LaunchFailed {
                reason,
                version_dir: runtime.version_dir,
            },
            Some(runtime.metadata.target_commit),
            runtime.metadata.toolchains,
            true,
            false,
        );
    }
    journal.launch_handoff_completed = true;
    finish_result(
        paths,
        logger,
        &operation_id,
        options,
        started_at_ms,
        journal,
        BootstrapStatus::Installed {
            commit: runtime.metadata.target_commit.clone(),
            product_version: runtime.metadata.product_version,
            version_dir: runtime.version_dir,
        },
        Some(runtime.metadata.target_commit),
        runtime.metadata.toolchains,
        true,
        true,
    )
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "PascalCase")]
enum BootstrapJournalStatus {
    Running,
    Completed,
    Failed,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "PascalCase")]
enum BootstrapJournalPhase {
    Recovering,
    Detecting,
    Building,
    Activating,
    Launching,
    Completed,
    Failed,
}

impl Default for BootstrapJournalPhase {
    fn default() -> Self {
        Self::Recovering
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct BootstrapJournal {
    schema_version: u32,
    operation_id: String,
    operation: OperationKind,
    status: BootstrapJournalStatus,
    started_at_ms: u128,
    finished_at_ms: Option<u128>,
    #[serde(default)]
    phase: BootstrapJournalPhase,
    target_commit: Option<String>,
    #[serde(default)]
    product_version: String,
    #[serde(default)]
    toolchains: BTreeMap<String, String>,
    #[serde(default)]
    activation_completed: bool,
    #[serde(default)]
    launch_attempted: bool,
    #[serde(default)]
    launch_handoff_completed: bool,
    #[serde(default)]
    launch_ack_path: Option<PathBuf>,
    #[serde(default)]
    launch_nonce: Option<String>,
    failure: Option<String>,
}

pub struct BootstrapInstaller<D, P, L> {
    paths: InstallationPaths,
    downloader: D,
    runner: P,
    launcher: L,
    logger: Option<DiagnosticLogger>,
}

impl<D, P, L> BootstrapInstaller<D, P, L> {
    pub fn new(
        paths: InstallationPaths,
        downloader: D,
        runner: P,
        launcher: L,
        logger: Option<DiagnosticLogger>,
    ) -> Self {
        Self {
            paths,
            downloader,
            runner,
            launcher,
            logger,
        }
    }
}

impl<D: DownloadClient, P: ProcessRunner, L: DetachedLauncher> BootstrapInstaller<D, P, L> {
    pub fn install(self, options: BootstrapOptions) -> Result<BootstrapResult, BootstrapError> {
        let (jdk, node) = production_descriptors(self.paths.platform, &self.paths.architecture)?;
        let health_checker = LocalBackendHealthChecker::new(SystemProcessRunner::default());
        self.install_with_descriptors(options, jdk, node, &health_checker)
    }

    /// Test and controlled-fixture seam. Production callers must use
    /// `install`, which always selects the checked-in catalog.
    pub(crate) fn install_with_descriptors<H: HealthChecker>(
        self,
        options: BootstrapOptions,
        jdk: crate::toolchain::ToolchainDescriptor,
        node: crate::toolchain::ToolchainDescriptor,
        checker: &H,
    ) -> Result<BootstrapResult, BootstrapError> {
        let BootstrapInstaller {
            paths,
            downloader,
            runner,
            launcher,
            logger,
        } = self;
        // The parent directory is state-owned; opening the lock creates only
        // that directory before any recovery or install decisions.
        let lock = InstallationLock::acquire(paths.lock_path(), "phase6-install")?;
        let store = StateStore::new(paths.clone());
        let activation = ActivationEngine::new(paths.clone());
        let recovery_config = ActivationConfig::for_paths(&paths, PathBuf::new());
        let previous_journal = load_bootstrap_journal(&paths)?;
        if let Err(error) = activation.recover_with_lock(&recovery_config, checker, &lock) {
            let journal = previous_journal.unwrap_or_else(|| {
                new_bootstrap_journal(&paths, &options, Uuid::new_v4().simple().to_string())
            });
            let operation_id = journal.operation_id.clone();
            let started_at_ms = journal.started_at_ms;
            let status = if matches!(&error, ActivationError::ReviewRequired(_)) {
                BootstrapStatus::ReviewRequired {
                    reason: error.to_string(),
                }
            } else {
                BootstrapStatus::ReviewRequired {
                    reason: format!("recovery failed closed: {error}"),
                }
            };
            return finish_result(
                &paths,
                logger.as_ref(),
                &operation_id,
                &options,
                started_at_ms,
                journal,
                status,
                None,
                BTreeMap::new(),
                false,
                false,
            );
        }

        if let Some(previous) = previous_journal {
            if previous.status == BootstrapJournalStatus::Running
                && !previous.launch_handoff_completed
                && (previous.activation_completed
                    || activation_target_is_current(&activation, &previous))
            {
                return reconcile_running_activation(
                    &paths,
                    &options,
                    previous,
                    &activation,
                    &launcher,
                    logger.as_ref(),
                );
            }
            if previous.status == BootstrapJournalStatus::Running {
                let mut interrupted = previous;
                interrupted.status = BootstrapJournalStatus::Failed;
                interrupted.phase = BootstrapJournalPhase::Failed;
                interrupted.finished_at_ms = Some(now_ms());
                interrupted.failure =
                    Some("interrupted before activation; low-level recovery completed".to_owned());
                persist_bootstrap_journal(&paths, &interrupted)?;
            }
        }

        let operation_id = Uuid::new_v4().simple().to_string();
        let started_at_ms = now_ms();
        let mut journal = new_bootstrap_journal(&paths, &options, operation_id.clone());
        persist_bootstrap_journal(&paths, &journal)?;

        store.initialize()?;
        journal.phase = BootstrapJournalPhase::Detecting;
        persist_bootstrap_journal(&paths, &journal)?;
        let installation = store.load_installation()?;
        if let Some(commit) = installation.current_commit.clone() {
            match activation.resolve_current() {
                Ok(Some(runtime)) => {
                    let existing_commit = commit.clone();
                    let status = BootstrapStatus::AlreadyInstalled {
                        commit,
                        version_dir: runtime.version_dir,
                    };
                    return finish_result(
                        &paths,
                        logger.as_ref(),
                        &operation_id,
                        &options,
                        started_at_ms,
                        journal,
                        status,
                        Some(existing_commit),
                        installation.current_toolchains,
                        false,
                        false,
                    );
                }
                Ok(None) => {}
                Err(error) => {
                    let status = BootstrapStatus::RepairRequired {
                        commit,
                        reason: error.to_string(),
                    };
                    return finish_result(
                        &paths,
                        logger.as_ref(),
                        &operation_id,
                        &options,
                        started_at_ms,
                        journal,
                        status,
                        None,
                        installation.current_toolchains,
                        false,
                        false,
                    );
                }
            }
        }

        journal.phase = BootstrapJournalPhase::Building;
        persist_bootstrap_journal(&paths, &journal)?;
        let build_config = BuildConfig::new(
            options.remote_url.clone(),
            options.product_version.clone(),
            jdk,
            node,
        );
        let pipeline = BuildPipeline::new(paths.clone(), downloader, runner, logger.clone());
        let build = match pipeline.run_with_lock(&build_config, &lock) {
            Ok(result) => result,
            Err(error) => {
                journal.failure = Some(error.to_string());
                let status = BootstrapStatus::BuildFailed {
                    reason: error.to_string(),
                };
                return finish_result(
                    &paths,
                    logger.as_ref(),
                    &operation_id,
                    &options,
                    started_at_ms,
                    journal,
                    status,
                    None,
                    BTreeMap::new(),
                    false,
                    false,
                );
            }
        };
        journal.target_commit = Some(build.target_commit.clone());
        journal.product_version = build.product_version.clone();
        journal.toolchains = build.toolchains.clone();
        ensure_launch_identity(&paths, &mut journal)?;
        journal.phase = BootstrapJournalPhase::Activating;
        persist_bootstrap_journal(&paths, &journal)?;
        let result_path = pipeline.result_path_for(&build)?;
        let toolchain_ids = build.toolchains.clone();
        let java_binary = match resolve_build_java(&paths, &build) {
            Ok(path) => path,
            Err(error) => {
                journal.failure = Some(error.to_string());
                return finish_result(
                    &paths,
                    logger.as_ref(),
                    &operation_id,
                    &options,
                    started_at_ms,
                    journal,
                    BootstrapStatus::ActivationFailed {
                        reason: error.to_string(),
                    },
                    Some(build.target_commit),
                    toolchain_ids,
                    false,
                    false,
                );
            }
        };
        let activation_config = ActivationConfig::for_paths(&paths, java_binary);
        let mut hooks = NoopActivationHooks;
        let runtime = match activation.activate_with_lock(
            &result_path,
            &activation_config,
            &mut hooks,
            checker,
            &lock,
        ) {
            Ok(runtime) => runtime,
            Err(error) => {
                journal.failure = Some(error.to_string());
                let status = match &error {
                    ActivationError::ReviewRequired(_) => BootstrapStatus::ReviewRequired {
                        reason: error.to_string(),
                    },
                    _ => BootstrapStatus::ActivationFailed {
                        reason: error.to_string(),
                    },
                };
                return finish_result(
                    &paths,
                    logger.as_ref(),
                    &operation_id,
                    &options,
                    started_at_ms,
                    journal,
                    status,
                    Some(build.target_commit),
                    toolchain_ids,
                    false,
                    false,
                );
            }
        };

        journal.activation_completed = true;
        journal.phase = BootstrapJournalPhase::Launching;
        persist_bootstrap_journal(&paths, &journal)?;
        if read_matching_launch_ack(&paths, &journal, &build.target_commit)? {
            journal.launch_handoff_completed = true;
            let status = BootstrapStatus::Installed {
                commit: build.target_commit.clone(),
                product_version: build.product_version.clone(),
                version_dir: runtime.version_dir,
            };
            return finish_result(
                &paths,
                logger.as_ref(),
                &operation_id,
                &options,
                started_at_ms,
                journal,
                status,
                Some(build.target_commit),
                toolchain_ids,
                true,
                true,
            );
        }
        let launch = desktop_launch_spec(&paths, &runtime, &journal);
        journal.launch_attempted = true;
        persist_bootstrap_journal(&paths, &journal)?;
        let launch_error = launcher.launch(&launch).err();
        if let Some(error) = launch_error.as_ref() {
            if !matches!(
                error,
                crate::detached::DetachedLaunchError::ExitedEarly { .. }
            ) {
                let reason = error.to_string();
                journal.failure = Some(reason.clone());
                return finish_result(
                    &paths,
                    logger.as_ref(),
                    &operation_id,
                    &options,
                    started_at_ms,
                    journal,
                    BootstrapStatus::LaunchFailed {
                        reason,
                        version_dir: runtime.version_dir,
                    },
                    Some(build.target_commit),
                    toolchain_ids,
                    true,
                    false,
                );
            }
        }
        let timeout = if matches!(
            launch_error.as_ref(),
            Some(crate::detached::DetachedLaunchError::ExitedEarly { .. })
        ) {
            LAUNCH_ACK_AFTER_EARLY_EXIT_TIMEOUT
        } else {
            LAUNCH_ACK_TIMEOUT
        };
        if let Err(ack_error) = wait_for_launch_ack(&paths, &journal, &build.target_commit, timeout)
        {
            let reason = launch_error
                .map(|error| format!("{error}; {ack_error}"))
                .unwrap_or_else(|| ack_error.to_string());
            journal.failure = Some(reason.clone());
            let status = BootstrapStatus::LaunchFailed {
                reason,
                version_dir: runtime.version_dir,
            };
            return finish_result(
                &paths,
                logger.as_ref(),
                &operation_id,
                &options,
                started_at_ms,
                journal,
                status,
                Some(build.target_commit),
                toolchain_ids,
                true,
                false,
            );
        }

        journal.launch_handoff_completed = true;
        let status = BootstrapStatus::Installed {
            commit: build.target_commit.clone(),
            product_version: build.product_version.clone(),
            version_dir: runtime.version_dir,
        };
        finish_result(
            &paths,
            logger.as_ref(),
            &operation_id,
            &options,
            started_at_ms,
            journal,
            status,
            Some(build.target_commit),
            toolchain_ids,
            true,
            true,
        )
    }
}

fn activation_target_is_current(activation: &ActivationEngine, journal: &BootstrapJournal) -> bool {
    let Some(target) = journal.target_commit.as_deref() else {
        return false;
    };
    activation
        .resolve_current()
        .ok()
        .flatten()
        .is_some_and(|runtime| runtime.metadata.target_commit == target)
}

// This is the single terminalization boundary for the bootstrap journal and
// result contract; keeping all terminal fields together makes every exit path
// durable and machine-readable.
#[allow(clippy::too_many_arguments)]
fn finish_result(
    paths: &InstallationPaths,
    logger: Option<&DiagnosticLogger>,
    operation_id: &str,
    options: &BootstrapOptions,
    started_at_ms: u128,
    mut journal: BootstrapJournal,
    status: BootstrapStatus,
    target_commit: Option<String>,
    toolchains: BTreeMap<String, String>,
    activation_succeeded: bool,
    launch_succeeded: bool,
) -> Result<BootstrapResult, BootstrapError> {
    let terminal = if matches!(
        &status,
        BootstrapStatus::Installed { .. } | BootstrapStatus::AlreadyInstalled { .. }
    ) {
        BootstrapJournalStatus::Completed
    } else {
        BootstrapJournalStatus::Failed
    };
    journal.status = terminal;
    journal.phase = if journal.status == BootstrapJournalStatus::Completed {
        BootstrapJournalPhase::Completed
    } else {
        BootstrapJournalPhase::Failed
    };
    journal.finished_at_ms = Some(now_ms());
    journal.target_commit = target_commit.clone();
    journal.toolchains = toolchains.clone();
    journal.activation_completed |= activation_succeeded;
    journal.launch_handoff_completed |= launch_succeeded;
    if journal.product_version.is_empty() {
        journal.product_version = options.product_version.clone();
    }
    if journal.failure.is_none() {
        journal.failure = match &status {
            BootstrapStatus::Installed { .. } | BootstrapStatus::AlreadyInstalled { .. } => None,
            BootstrapStatus::RepairRequired { reason, .. }
            | BootstrapStatus::ReviewRequired { reason }
            | BootstrapStatus::BuildFailed { reason }
            | BootstrapStatus::ActivationFailed { reason }
            | BootstrapStatus::LaunchFailed { reason, .. } => Some(reason.clone()),
        };
    }
    crate::state::atomic_write_json(&paths.bootstrap_operation_path(), &journal)?;
    if let Some(logger) = logger {
        logger.log(
            if launch_succeeded { "info" } else { "error" },
            "bootstrap.completed",
            [
                ("operation_id".to_owned(), json!(operation_id)),
                ("target_commit".to_owned(), json!(target_commit.clone())),
                (
                    "product_version".to_owned(),
                    json!(journal.product_version.clone()),
                ),
                ("platform".to_owned(), json!(paths.platform.as_str())),
                (
                    "architecture".to_owned(),
                    json!(paths.architecture.as_str()),
                ),
                ("toolchains".to_owned(), json!(toolchains.clone())),
                ("status".to_owned(), json!(format!("{status:?}"))),
                (
                    "activation_succeeded".to_owned(),
                    json!(activation_succeeded),
                ),
                ("launch_succeeded".to_owned(), json!(launch_succeeded)),
                (
                    "duration_ms".to_owned(),
                    json!(now_ms().saturating_sub(started_at_ms)),
                ),
            ],
        )?;
    }
    Ok(BootstrapResult {
        operation_id: operation_id.to_owned(),
        target_commit,
        product_version: journal.product_version,
        platform: paths.platform.as_str().to_owned(),
        architecture: paths.architecture.as_str().to_owned(),
        toolchains,
        phase: status_phase(&status).to_owned(),
        duration_ms: now_ms().saturating_sub(started_at_ms),
        activation_succeeded,
        launch_succeeded,
        status,
    })
}

fn status_phase(status: &BootstrapStatus) -> &'static str {
    match status {
        BootstrapStatus::ReviewRequired { .. } => "Recovering",
        BootstrapStatus::AlreadyInstalled { .. } | BootstrapStatus::RepairRequired { .. } => {
            "Detecting"
        }
        BootstrapStatus::BuildFailed { .. } => "Building",
        BootstrapStatus::ActivationFailed { .. } => "Activating",
        BootstrapStatus::LaunchFailed { .. } => "Launching",
        BootstrapStatus::Installed { .. } => "Completed",
    }
}

fn resolve_build_java(
    paths: &InstallationPaths,
    result: &BuildResult,
) -> Result<PathBuf, BootstrapError> {
    let jdk_id = result
        .toolchains
        .get("jdk")
        .ok_or_else(|| ToolchainError::InvalidState("BuildResult is missing jdk".to_owned()))?;
    let jdk = ToolchainStateStore::new(paths.clone()).resolve(jdk_id)?;
    if jdk.kind != crate::toolchain::ToolchainKind::Jdk {
        return Err(ToolchainError::InvalidState(format!(
            "BuildResult jdk ID {jdk_id:?} is not a JDK"
        ))
        .into());
    }
    jdk.executable("java")
        .map(Path::to_path_buf)
        .ok_or_else(|| ToolchainError::MissingExecutable {
            name: "java".to_owned(),
            root: jdk.root,
        })
        .map_err(BootstrapError::from)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::download::{
        DownloadClient, DownloadError, DownloadReceipt, DownloadRequest, HttpDownloader,
    };
    use crate::paths::{PathEnvironment, Platform, TargetArchitecture};
    use crate::process::{CommandSpec, ProcessError, ProcessOutput, ProcessRunner};
    use crate::toolchain::ToolchainManager;
    use crate::{DetachedLaunch, DetachedLaunchError, DetachedLaunchSpec, DetachedLauncher};
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use git2::{IndexAddOption, Repository, Signature};
    use sha2::{Digest, Sha256};
    use std::collections::BTreeMap;
    use std::fs;
    use std::io;
    use std::path::{Path, PathBuf};
    use std::sync::{Arc, Mutex};
    use tar::{Builder, Header};
    use tempfile::tempdir;

    #[derive(Clone, Default)]
    struct NoopDownloader;

    impl crate::download::DownloadClient for NoopDownloader {
        fn download(&self, _request: &DownloadRequest) -> Result<DownloadReceipt, DownloadError> {
            Err(DownloadError::Transport(
                "bootstrap test did not download".to_owned(),
            ))
        }
    }

    #[derive(Clone, Default)]
    struct NoopRunner;

    impl ProcessRunner for NoopRunner {
        fn run(&self, command: &CommandSpec) -> Result<ProcessOutput, ProcessError> {
            Err(ProcessError::Spawn {
                program: command.program.display().to_string(),
                source: io::Error::other("bootstrap test did not run a process"),
            })
        }
    }

    #[derive(Clone, Default)]
    struct NoopLauncher;

    impl DetachedLauncher for NoopLauncher {
        fn launch(&self, spec: &DetachedLaunchSpec) -> Result<DetachedLaunch, DetachedLaunchError> {
            Err(DetachedLaunchError::InvalidPath(spec.program.clone()))
        }
    }

    fn test_paths(root: &Path) -> InstallationPaths {
        InstallationPaths {
            platform: Platform::Linux,
            architecture: TargetArchitecture::X64,
            app_root: root.join("app"),
            user_data_root: root.join("user-data"),
            state_root: root.join("state"),
            cache_root: root.join("cache"),
        }
    }

    fn no_op_installer(
        paths: InstallationPaths,
    ) -> BootstrapInstaller<NoopDownloader, NoopRunner, NoopLauncher> {
        BootstrapInstaller::new(paths, NoopDownloader, NoopRunner, NoopLauncher, None)
    }

    #[test]
    fn default_options_are_public_repository_and_product_version() {
        let options = BootstrapOptions::default();
        assert_eq!(options.remote_url, DEFAULT_REMOTE_URL);
        assert_eq!(options.product_version, DEFAULT_PRODUCT_VERSION);
    }

    #[test]
    fn production_paths_are_user_scoped() {
        #[cfg(windows)]
        {
            let environment = PathEnvironment {
                home: Some(PathBuf::from("C:\\Users\\test")),
                local_app_data: Some(PathBuf::from("C:\\Users\\test\\AppData\\Local")),
                app_data: Some(PathBuf::from("C:\\Users\\test\\AppData\\Roaming")),
                ..PathEnvironment::default()
            };
            let paths = InstallationPaths::for_environment(
                Platform::Windows,
                TargetArchitecture::X64,
                &environment,
            )
            .unwrap();
            assert!(paths.app_root.starts_with("C:\\Users\\test"));
            assert!(!paths.app_root.starts_with("C:\\Users\\Public"));
        }

        #[cfg(not(windows))]
        {
            let environment = PathEnvironment {
                home: Some(PathBuf::from("/home/test")),
                xdg_data_home: Some(PathBuf::from("/home/test/.local/share")),
                xdg_state_home: Some(PathBuf::from("/home/test/.local/state")),
                xdg_cache_home: Some(PathBuf::from("/home/test/.cache")),
                ..PathEnvironment::default()
            };
            let paths = InstallationPaths::for_environment(
                Platform::Linux,
                TargetArchitecture::X64,
                &environment,
            )
            .unwrap();
            assert!(paths.app_root.starts_with("/home/test"));
            assert!(!paths.app_root.starts_with("/tmp"));
        }
    }

    #[test]
    fn broken_current_returns_repair_required_without_building_or_cleanup() {
        let root = tempdir().unwrap();
        let paths = test_paths(root.path());
        let store = StateStore::new(paths.clone());
        let mut installation = store.load_installation().unwrap();
        installation.current_commit = Some("a".repeat(40));
        store.save_installation(&installation).unwrap();
        fs::create_dir_all(&paths.user_data_root).unwrap();
        let user_file = paths.user_data_root.join("keep.txt");
        fs::write(&user_file, b"keep").unwrap();

        let result = no_op_installer(paths.clone())
            .install(BootstrapOptions::default())
            .unwrap();
        assert!(matches!(
            result.status,
            BootstrapStatus::RepairRequired { .. }
        ));
        assert_eq!(fs::read(&user_file).unwrap(), b"keep");
        assert!(!paths.build_dir().join("staging").exists());
    }

    #[test]
    fn review_required_blocks_fresh_install() {
        let root = tempdir().unwrap();
        let paths = test_paths(root.path());
        let store = StateStore::new(paths.clone());
        let mut transaction =
            crate::Transaction::begin(store, crate::OperationKind::Install, None, None, Vec::new())
                .unwrap();
        transaction
            .mark_review_required("manual recovery required")
            .unwrap();

        let result = no_op_installer(paths)
            .install(BootstrapOptions::default())
            .unwrap();
        assert!(matches!(
            result.status,
            BootstrapStatus::ReviewRequired { .. }
        ));
    }

    #[test]
    fn concurrent_lock_is_not_reentrant() {
        let root = tempdir().unwrap();
        let paths = test_paths(root.path());
        let _lock = InstallationLock::acquire(paths.lock_path(), "test-owner").unwrap();
        let result = no_op_installer(paths).install(BootstrapOptions::default());
        assert!(matches!(
            result,
            Err(BootstrapError::Lock(LockError::Busy { .. }))
        ));
    }

    #[derive(Clone)]
    struct FixtureDownloader {
        archives: BTreeMap<String, Vec<u8>>,
    }

    impl DownloadClient for FixtureDownloader {
        fn download(&self, request: &DownloadRequest) -> Result<DownloadReceipt, DownloadError> {
            let bytes = self.archives.get(&request.url).ok_or_else(|| {
                DownloadError::Transport(format!("missing fixture archive {}", request.url))
            })?;
            fs::create_dir_all(request.destination.parent().unwrap()).unwrap();
            fs::write(&request.destination, bytes).unwrap();
            Ok(DownloadReceipt {
                path: request.destination.clone(),
                bytes: bytes.len() as u64,
                sha256: sha256(bytes),
                resumed: false,
            })
        }
    }

    #[derive(Clone, Copy, Default)]
    struct FixtureBuildRunner;

    impl ProcessRunner for FixtureBuildRunner {
        fn run(&self, command: &CommandSpec) -> Result<ProcessOutput, ProcessError> {
            let directory = command.current_dir.as_ref().unwrap();
            let build_command = command.args == ["run", "build"]
                || command
                    .args
                    .iter()
                    .any(|argument| argument.contains("run") && argument.contains("build"));
            let build_command = build_command
                || command
                    .args
                    .windows(2)
                    .any(|args| args[0] == "run" && args[1] == "build");
            let package_command = command.args == ["run", "package"]
                || command
                    .args
                    .iter()
                    .any(|argument| argument.contains("run") && argument.contains("package"));
            let package_command = package_command
                || command
                    .args
                    .windows(2)
                    .any(|args| args[0] == "run" && args[1] == "package");
            let maven_command = command.program.file_name().and_then(|value| value.to_str())
                == Some("mvnw")
                || command
                    .args
                    .iter()
                    .any(|argument| argument.contains("mvnw"));
            if build_command {
                if directory.ends_with("frontend") {
                    fs::create_dir_all(directory.join("dist")).unwrap();
                    fs::write(directory.join("dist/index.html"), b"frontend").unwrap();
                } else {
                    fs::create_dir_all(directory.join("dist")).unwrap();
                    fs::write(directory.join("dist/main.js"), b"desktop").unwrap();
                }
            } else if package_command {
                let payload = directory.join("artifacts/linux-x64");
                fs::create_dir_all(payload.join("resources/app/dist")).unwrap();
                fs::write(payload.join("electron"), b"electron").unwrap();
                fs::write(payload.join("resources/app/package.json"), b"{}").unwrap();
                fs::write(payload.join("resources/app/dist/main.js"), b"desktop").unwrap();
                fs::create_dir_all(payload.join("frontend/dist")).unwrap();
                fs::write(payload.join("frontend/dist/index.html"), b"frontend").unwrap();
                #[cfg(unix)]
                {
                    use std::os::unix::fs::PermissionsExt;
                    fs::set_permissions(
                        payload.join("electron"),
                        fs::Permissions::from_mode(0o755),
                    )
                    .unwrap();
                }
            } else if maven_command {
                fs::create_dir_all(directory.join("target")).unwrap();
                fs::write(directory.join("target/harmonia-suite.jar"), b"backend").unwrap();
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

    #[derive(Clone, Default)]
    struct RecordingLauncher {
        last: Arc<Mutex<Option<DetachedLaunchSpec>>>,
        fail: bool,
        early_exit: bool,
    }

    impl DetachedLauncher for RecordingLauncher {
        fn launch(&self, spec: &DetachedLaunchSpec) -> Result<DetachedLaunch, DetachedLaunchError> {
            *self.last.lock().unwrap() = Some(spec.clone());
            if self.fail {
                return Err(DetachedLaunchError::Spawn {
                    program: spec.program.display().to_string(),
                    source: io::Error::other("controlled launch failure"),
                });
            }
            let ack_path = spec.environment.get("HARMONIA_LAUNCH_ACK").unwrap();
            let operation_id = spec
                .environment
                .get("HARMONIA_LAUNCH_OPERATION_ID")
                .unwrap();
            let target_commit = spec.environment.get("HARMONIA_LAUNCH_COMMIT").unwrap();
            let nonce = spec.environment.get("HARMONIA_LAUNCH_NONCE").unwrap();
            let acknowledgement = LaunchAcknowledgement {
                schema_version: LAUNCH_ACK_SCHEMA_VERSION,
                operation_id: operation_id.clone(),
                target_commit: target_commit.clone(),
                nonce: nonce.clone(),
                acknowledged_at_ms: now_ms(),
            };
            crate::state::atomic_write_json(Path::new(ack_path), &acknowledgement).unwrap();
            if self.early_exit {
                return Err(DetachedLaunchError::ExitedEarly {
                    program: spec.program.display().to_string(),
                    status: Some(0),
                });
            }
            Ok(DetachedLaunch { process_id: 7 })
        }
    }

    struct HealthyFixture;

    impl HealthChecker for HealthyFixture {
        fn check(
            &self,
            _version_dir: &Path,
            _metadata: &crate::VersionMetadata,
            _config: &ActivationConfig,
        ) -> Result<(), ActivationError> {
            Ok(())
        }
    }

    struct UnhealthyFixture;

    impl HealthChecker for UnhealthyFixture {
        fn check(
            &self,
            _version_dir: &Path,
            _metadata: &crate::VersionMetadata,
            _config: &ActivationConfig,
        ) -> Result<(), ActivationError> {
            Err(ActivationError::HealthCheck(
                "controlled unhealthy backend".to_owned(),
            ))
        }
    }

    struct FixtureDefinition {
        paths: InstallationPaths,
        remote_url: String,
        target: String,
        jdk: crate::ToolchainDescriptor,
        node: crate::ToolchainDescriptor,
        downloader: FixtureDownloader,
    }

    fn fixture_definition(root: &Path) -> FixtureDefinition {
        let paths = test_paths(root);
        let remote_path = root.join("remote");
        let repository = Repository::init(&remote_path).unwrap();
        for (relative, contents) in [
            ("frontend/package.json", br#"{"scripts":{"build":"vite build"}}"#.as_slice()),
            ("frontend/package-lock.json", br#"{"lockfileVersion":3}"#.as_slice()),
            (
                "apps/desktop/package.json",
                br#"{"scripts":{"build":"tsc","package":"node package"}}"#.as_slice(),
            ),
            (
                "apps/desktop/package-lock.json",
                br#"{"lockfileVersion":3}"#.as_slice(),
            ),
            ("pom.xml", b"<project/>".as_slice()),
            (
                ".mvn/wrapper/maven-wrapper.properties",
                b"distributionType=only-script\ndistributionSha256Sum=0000000000000000000000000000000000000000000000000000000000000000\n".as_slice(),
            ),
            ("mvnw", b"#!/bin/sh\n".as_slice()),
        ] {
            let path = remote_path.join(relative);
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(path, contents).unwrap();
        }
        let mut index = repository.index().unwrap();
        index.add_all(["."], IndexAddOption::DEFAULT, None).unwrap();
        let tree_id = index.write_tree().unwrap();
        let tree = repository.find_tree(tree_id).unwrap();
        let signature = Signature::now("Harmonia test", "test@example.invalid").unwrap();
        let target = repository
            .commit(
                Some("refs/heads/main"),
                &signature,
                &signature,
                "bootstrap fixture",
                &tree,
                &[],
            )
            .unwrap()
            .to_string();
        let remote_url = reqwest::Url::from_file_path(&remote_path)
            .unwrap()
            .to_string();
        let jdk_archive = tar_gz(&[("jdk-21/bin/java", b"java", 0o100755)]);
        let node_archive = tar_gz(&[
            ("node-24/bin/node", b"node", 0o100755),
            ("node-24/bin/npm", b"npm", 0o100755),
        ]);
        let jdk_url = "https://fixture.invalid/jdk.tar.gz".to_owned();
        let node_url = "https://fixture.invalid/node.tar.gz".to_owned();
        let jdk = crate::ToolchainDescriptor::new(
            crate::ToolchainKind::Jdk,
            "21.0.1",
            Platform::Linux,
            TargetArchitecture::X64,
            jdk_url.clone(),
            sha256(&jdk_archive),
            crate::ArchiveFormat::TarGz,
        )
        .home_dir("jdk-21")
        .executable("java", "jdk-21/bin/java");
        let node = crate::ToolchainDescriptor::new(
            crate::ToolchainKind::Node,
            "24.15.0",
            Platform::Linux,
            TargetArchitecture::X64,
            node_url.clone(),
            sha256(&node_archive),
            crate::ArchiveFormat::TarGz,
        )
        .home_dir("node-24")
        .executable("node", "node-24/bin/node")
        .executable("npm", "node-24/bin/npm");
        FixtureDefinition {
            paths,
            remote_url,
            target,
            jdk,
            node,
            downloader: FixtureDownloader {
                archives: BTreeMap::from([(jdk_url, jdk_archive), (node_url, node_archive)]),
            },
        }
    }

    #[test]
    fn controlled_local_git_fixture_completes_fresh_install_and_handoff() {
        let root = tempdir().unwrap();
        let workspace = root.path().join("install root with spaces");
        fs::create_dir_all(&workspace).unwrap();
        let paths = test_paths(&workspace);
        let remote_path = workspace.join("remote");
        let repository = Repository::init(&remote_path).unwrap();
        fs::create_dir_all(&paths.user_data_root).unwrap();
        let legacy_file = paths.user_data_root.join("legacy data.txt");
        fs::write(&legacy_file, b"preserve").unwrap();
        for (relative, contents) in [
            ("frontend/package.json", br#"{"scripts":{"build":"vite build"}}"#.as_slice()),
            ("frontend/package-lock.json", br#"{"lockfileVersion":3}"#.as_slice()),
            ("apps/desktop/package.json", br#"{"scripts":{"build":"tsc","package":"node package"}}"#.as_slice()),
            ("apps/desktop/package-lock.json", br#"{"lockfileVersion":3}"#.as_slice()),
            ("pom.xml", b"<project/>".as_slice()),
            (
                ".mvn/wrapper/maven-wrapper.properties",
                b"distributionType=only-script\ndistributionSha256Sum=0000000000000000000000000000000000000000000000000000000000000000\n".as_slice(),
            ),
            ("mvnw", b"#!/bin/sh\n".as_slice()),
        ] {
            let path = remote_path.join(relative);
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(path, contents).unwrap();
        }
        let mut index = repository.index().unwrap();
        index.add_all(["."], IndexAddOption::DEFAULT, None).unwrap();
        let tree_id = index.write_tree().unwrap();
        let tree = repository.find_tree(tree_id).unwrap();
        let signature = Signature::now("Harmonia test", "test@example.invalid").unwrap();
        let target = repository
            .commit(
                Some("refs/heads/main"),
                &signature,
                &signature,
                "bootstrap fixture",
                &tree,
                &[],
            )
            .unwrap()
            .to_string();
        let remote_url = reqwest::Url::from_file_path(&remote_path)
            .unwrap()
            .to_string();
        let jdk_archive = tar_gz(&[("jdk-21/bin/java", b"java", 0o100755)]);
        let node_archive = tar_gz(&[
            ("node-24/bin/node", b"node", 0o100755),
            ("node-24/bin/npm", b"npm", 0o100755),
        ]);
        let jdk_url = "https://fixture.invalid/jdk.tar.gz".to_owned();
        let node_url = "https://fixture.invalid/node.tar.gz".to_owned();
        let jdk = crate::ToolchainDescriptor::new(
            crate::ToolchainKind::Jdk,
            "21.0.1",
            Platform::Linux,
            TargetArchitecture::X64,
            jdk_url.clone(),
            sha256(&jdk_archive),
            crate::ArchiveFormat::TarGz,
        )
        .home_dir("jdk-21")
        .executable("java", "jdk-21/bin/java");
        let node = crate::ToolchainDescriptor::new(
            crate::ToolchainKind::Node,
            "24.15.0",
            Platform::Linux,
            TargetArchitecture::X64,
            node_url.clone(),
            sha256(&node_archive),
            crate::ArchiveFormat::TarGz,
        )
        .home_dir("node-24")
        .executable("node", "node-24/bin/node")
        .executable("npm", "node-24/bin/npm");
        let restart_jdk = jdk.clone();
        let restart_node = node.clone();
        let launcher = RecordingLauncher::default();
        let result = BootstrapInstaller::new(
            paths.clone(),
            FixtureDownloader {
                archives: BTreeMap::from([(jdk_url, jdk_archive), (node_url, node_archive)]),
            },
            FixtureBuildRunner,
            launcher.clone(),
            None,
        )
        .install_with_descriptors(
            BootstrapOptions {
                remote_url,
                product_version: "fixture".to_owned(),
            },
            jdk,
            node,
            &HealthyFixture,
        )
        .unwrap();
        assert!(matches!(result.status, BootstrapStatus::Installed { .. }));
        assert_eq!(
            StateStore::new(paths.clone())
                .load_installation()
                .unwrap()
                .current_commit
                .as_deref(),
            Some(target.as_str())
        );
        let launch = launcher.last.lock().unwrap().clone().unwrap();
        assert_eq!(
            launch.environment.get("HARMONIA_RUNTIME_MODE"),
            Some(&"installed".to_owned())
        );
        assert_eq!(
            launch.environment.get("HARMONIA_USER_DATA_ROOT"),
            Some(&paths.user_data_root.display().to_string())
        );
        assert_eq!(
            launch.environment.get("HARMONIA_WORKSPACE"),
            Some(&paths.user_data_root.display().to_string())
        );
        assert_eq!(
            launch.environment.get("HARMONIA_INSTALL_STATE_ROOT"),
            Some(&paths.state_root.display().to_string())
        );
        assert!(launch.environment.contains_key("HARMONIA_LAUNCH_ACK"));
        assert!(launch
            .environment
            .contains_key("HARMONIA_LAUNCH_OPERATION_ID"));
        assert!(launch.environment.contains_key("HARMONIA_LAUNCH_COMMIT"));
        assert!(launch.environment.contains_key("HARMONIA_LAUNCH_NONCE"));
        assert!(launch.program.is_file());
        assert_eq!(fs::read(&legacy_file).unwrap(), b"preserve");

        let journal_path = paths.bootstrap_operation_path();
        let mut journal: BootstrapJournal =
            serde_json::from_slice(&fs::read(&journal_path).unwrap()).unwrap();
        let original_operation_id = journal.operation_id.clone();
        journal.status = BootstrapJournalStatus::Running;
        journal.phase = BootstrapJournalPhase::Activating;
        journal.finished_at_ms = None;
        journal.activation_completed = true;
        journal.launch_attempted = false;
        journal.launch_handoff_completed = false;
        journal.failure = None;
        persist_bootstrap_journal(&paths, &journal).unwrap();

        let resumed_launcher = RecordingLauncher::default();
        let resumed = BootstrapInstaller::new(
            paths.clone(),
            NoopDownloader,
            NoopRunner,
            resumed_launcher.clone(),
            None,
        )
        .install_with_descriptors(
            BootstrapOptions::default(),
            restart_jdk,
            restart_node,
            &HealthyFixture,
        )
        .unwrap();
        assert!(matches!(resumed.status, BootstrapStatus::Installed { .. }));
        assert_eq!(resumed.operation_id, original_operation_id);
        assert!(resumed.launch_succeeded);
        let terminal: BootstrapJournal =
            serde_json::from_slice(&fs::read(journal_path).unwrap()).unwrap();
        assert_eq!(terminal.status, BootstrapJournalStatus::Completed);
        assert_eq!(terminal.phase, BootstrapJournalPhase::Completed);
        assert!(terminal.launch_handoff_completed);
        assert!(resumed_launcher.last.lock().unwrap().is_none());
        assert!(journal.launch_ack_path.unwrap().is_file());
    }

    #[test]
    fn first_install_activation_failure_preserves_no_install_state_and_user_data() {
        let root = tempdir().unwrap();
        let fixture = fixture_definition(root.path());
        fs::create_dir_all(&fixture.paths.user_data_root).unwrap();
        let user_file = fixture.paths.user_data_root.join("keep.txt");
        fs::write(&user_file, b"keep").unwrap();

        let result = BootstrapInstaller::new(
            fixture.paths.clone(),
            fixture.downloader,
            FixtureBuildRunner,
            RecordingLauncher::default(),
            None,
        )
        .install_with_descriptors(
            BootstrapOptions {
                remote_url: fixture.remote_url,
                product_version: "fixture".to_owned(),
            },
            fixture.jdk,
            fixture.node,
            &UnhealthyFixture,
        )
        .unwrap();

        assert!(matches!(
            result.status,
            BootstrapStatus::ActivationFailed { .. }
        ));
        assert!(StateStore::new(fixture.paths.clone())
            .load_installation()
            .unwrap()
            .current_commit
            .is_none());
        assert_eq!(fs::read(user_file).unwrap(), b"keep");
    }

    #[test]
    fn early_secondary_exit_is_successful_when_primary_acknowledges() {
        let root = tempdir().unwrap();
        let fixture = fixture_definition(root.path());
        let launcher = RecordingLauncher {
            early_exit: true,
            ..RecordingLauncher::default()
        };
        let result = BootstrapInstaller::new(
            fixture.paths.clone(),
            fixture.downloader,
            FixtureBuildRunner,
            launcher,
            None,
        )
        .install_with_descriptors(
            BootstrapOptions {
                remote_url: fixture.remote_url,
                product_version: "fixture".to_owned(),
            },
            fixture.jdk,
            fixture.node,
            &HealthyFixture,
        )
        .unwrap();

        assert!(matches!(result.status, BootstrapStatus::Installed { .. }));
        assert!(result.launch_succeeded);
    }

    #[test]
    fn stale_launch_ack_is_ignored() {
        let root = tempdir().unwrap();
        let paths = test_paths(root.path());
        let mut journal =
            new_bootstrap_journal(&paths, &BootstrapOptions::default(), "operation".to_owned());
        let ack_path = journal.launch_ack_path.clone().unwrap();
        let nonce = journal.launch_nonce.clone().unwrap();
        let wrong = LaunchAcknowledgement {
            schema_version: LAUNCH_ACK_SCHEMA_VERSION,
            operation_id: "other-operation".to_owned(),
            target_commit: "a".repeat(40),
            nonce,
            acknowledged_at_ms: now_ms(),
        };
        crate::state::atomic_write_json(&ack_path, &wrong).unwrap();
        journal.target_commit = Some("a".repeat(40));
        assert!(!read_matching_launch_ack(&paths, &journal, &"a".repeat(40)).unwrap());
    }

    #[test]
    fn missing_launch_ack_fails_closed_after_timeout() {
        let root = tempdir().unwrap();
        let paths = test_paths(root.path());
        let journal =
            new_bootstrap_journal(&paths, &BootstrapOptions::default(), "operation".to_owned());
        let error =
            wait_for_launch_ack(&paths, &journal, &"a".repeat(40), Duration::from_millis(1))
                .unwrap_err();
        assert!(matches!(error, BootstrapError::LaunchHandshakeTimeout(_)));
    }

    #[test]
    fn launch_failure_keeps_successfully_activated_version_healthy() {
        let root = tempdir().unwrap();
        let fixture = fixture_definition(root.path());
        let launcher = RecordingLauncher {
            fail: true,
            ..RecordingLauncher::default()
        };
        let result = BootstrapInstaller::new(
            fixture.paths.clone(),
            fixture.downloader,
            FixtureBuildRunner,
            launcher,
            None,
        )
        .install_with_descriptors(
            BootstrapOptions {
                remote_url: fixture.remote_url,
                product_version: "fixture".to_owned(),
            },
            fixture.jdk,
            fixture.node,
            &HealthyFixture,
        )
        .unwrap();

        assert!(matches!(
            result.status,
            BootstrapStatus::LaunchFailed { .. }
        ));
        let installation = StateStore::new(fixture.paths.clone())
            .load_installation()
            .unwrap();
        assert_eq!(
            installation.current_commit.as_deref(),
            Some(fixture.target.as_str())
        );
        assert!(ActivationEngine::new(fixture.paths)
            .resolve_current()
            .unwrap()
            .is_some());
    }

    fn tar_gz(entries: &[(&str, &[u8], u32)]) -> Vec<u8> {
        let mut encoder = GzEncoder::new(Vec::new(), Compression::fast());
        {
            let mut archive = Builder::new(&mut encoder);
            for (name, contents, mode) in entries {
                let mut header = Header::new_gnu();
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

    fn sha256(bytes: &[u8]) -> String {
        let mut digest = Sha256::new();
        digest.update(bytes);
        digest
            .finalize()
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect()
    }

    /// This is intentionally ignored because it downloads the pinned official
    /// archives. CI runs it in a cache-aware job on both supported platforms.
    #[test]
    #[ignore]
    fn production_toolchain_smoke() {
        let cache_root = std::env::var_os("HARMONIA_TOOLCHAIN_SMOKE_ROOT")
            .map(PathBuf::from)
            .unwrap_or_else(|| tempdir().unwrap().keep());
        let platform = Platform::current().unwrap();
        let environment = match platform {
            Platform::Windows => PathEnvironment {
                home: Some(cache_root.join("home")),
                local_app_data: Some(cache_root.join("local")),
                app_data: Some(cache_root.join("roaming")),
                ..PathEnvironment::default()
            },
            Platform::Linux => PathEnvironment {
                home: Some(cache_root.join("home")),
                xdg_data_home: Some(cache_root.join("data")),
                xdg_state_home: Some(cache_root.join("state")),
                xdg_cache_home: Some(cache_root.join("cache")),
                ..PathEnvironment::default()
            },
        };
        let paths =
            InstallationPaths::for_environment(platform, TargetArchitecture::X64, &environment)
                .unwrap();
        let (jdk, node) = production_descriptors(platform, &TargetArchitecture::X64).unwrap();
        let manager = ToolchainManager::new(paths, HttpDownloader::default());
        let jdk = manager.ensure(&jdk).unwrap();
        let node = manager.ensure(&node).unwrap();
        let environment = manager
            .environment(&[jdk.id.clone(), node.id.clone()])
            .unwrap();
        let runner = SystemProcessRunner::default();
        for executable in [
            jdk.executable("java").unwrap(),
            node.executable("node").unwrap(),
            node.executable("npm").unwrap(),
        ] {
            let command = {
                #[cfg(windows)]
                {
                    if executable
                        .extension()
                        .is_some_and(|extension| extension.eq_ignore_ascii_case("cmd"))
                    {
                        let cmd = PathBuf::from(std::env::var_os("SystemRoot").unwrap())
                            .join("System32")
                            .join("cmd.exe");
                        environment.apply_to(CommandSpec::new(cmd).args([
                            "/D".to_owned(),
                            "/S".to_owned(),
                            "/C".to_owned(),
                            "call".to_owned(),
                            executable.display().to_string(),
                            "--version".to_owned(),
                        ]))
                    } else {
                        environment
                            .apply_to(CommandSpec::new(executable.to_path_buf()).arg("--version"))
                    }
                }
                #[cfg(not(windows))]
                {
                    environment
                        .apply_to(CommandSpec::new(executable.to_path_buf()).arg("--version"))
                }
            };
            let output = runner.run(&command).unwrap();
            assert!(output.success(), "toolchain smoke failed: {output:?}");
        }
    }
}
