use std::collections::BTreeMap;
#[cfg(unix)]
use std::fs::File;
use std::fs::{self, OpenOptions};
use std::io;
use std::path::{Component, Path, PathBuf};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use thiserror::Error;
use uuid::Uuid;

use crate::build::{hash_directory, BuildResult, BuildStatus};
use crate::checksum::sha256_file;
use crate::lock::{InstallationLock, LockError};
use crate::paths::InstallationPaths;
use crate::process::{CommandSpec, ManagedProcess, ProcessError, ProcessRunner};
use crate::state::{
    validate_managed_path, DatabasePreState, InstallationState, OperationKind, StateError,
    StateStore, Transaction, TransactionPhase, TransactionStatus,
};
use crate::toolchain::{ToolchainKind, ToolchainStateStore};

const VERSION_METADATA_SCHEMA_VERSION: u32 = 1;
const BUILD_RESULT_SCHEMA_VERSION: u32 = 1;
const SHA256_LENGTH: usize = 64;

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct VersionComponent {
    pub path: PathBuf,
    pub sha256: String,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct RuntimeMetadata {
    pub desktop_executable: PathBuf,
    pub backend_jar: PathBuf,
    pub managed_java_binary: PathBuf,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct VersionMetadata {
    pub schema_version: u32,
    pub target_commit: String,
    pub product_version: String,
    pub created_at_ms: u128,
    pub desktop_artifact_sha256: String,
    pub backend_artifact_sha256: String,
    pub component_paths: BTreeMap<String, VersionComponent>,
    pub toolchains: BTreeMap<String, String>,
    pub runtime: RuntimeMetadata,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RuntimePaths {
    pub version_dir: PathBuf,
    pub metadata: VersionMetadata,
    pub desktop_executable: PathBuf,
    pub backend_jar: PathBuf,
    pub java_binary: PathBuf,
}

#[derive(Clone, Debug)]
pub struct ActivationConfig {
    pub java_binary: PathBuf,
    pub workspace: PathBuf,
    pub database_path: PathBuf,
    pub health_timeout: Duration,
}

impl ActivationConfig {
    pub fn for_paths(paths: &InstallationPaths, java_binary: impl Into<PathBuf>) -> Self {
        Self {
            java_binary: java_binary.into(),
            workspace: paths.user_data_root.clone(),
            database_path: paths.user_data_root.join("data").join("harmonia.db"),
            health_timeout: Duration::from_secs(30),
        }
    }
}

pub trait ActivationHooks {
    fn request_shutdown(&mut self) -> Result<(), String>;
    fn wait_for_shutdown(&mut self, timeout: Duration) -> Result<(), String>;
}

#[derive(Default)]
pub struct NoopActivationHooks;

impl ActivationHooks for NoopActivationHooks {
    fn request_shutdown(&mut self) -> Result<(), String> {
        Ok(())
    }

    fn wait_for_shutdown(&mut self, _timeout: Duration) -> Result<(), String> {
        Ok(())
    }
}

pub trait HealthChecker {
    fn check(
        &self,
        version_dir: &Path,
        metadata: &VersionMetadata,
        config: &ActivationConfig,
    ) -> Result<(), ActivationError>;
}

struct UnavailableHealthChecker;

impl HealthChecker for UnavailableHealthChecker {
    fn check(
        &self,
        _version_dir: &Path,
        _metadata: &VersionMetadata,
        _config: &ActivationConfig,
    ) -> Result<(), ActivationError> {
        Err(ActivationError::ReviewRequired(
            "crash recovery requires a managed health checker".to_owned(),
        ))
    }
}

pub struct LocalBackendHealthChecker<R> {
    runner: R,
}

impl<R> LocalBackendHealthChecker<R> {
    pub fn new(runner: R) -> Self {
        Self { runner }
    }
}

impl<R: ProcessRunner> HealthChecker for LocalBackendHealthChecker<R> {
    fn check(
        &self,
        version_dir: &Path,
        metadata: &VersionMetadata,
        config: &ActivationConfig,
    ) -> Result<(), ActivationError> {
        let jar = version_dir.join(&metadata.runtime.backend_jar);
        let instance = Uuid::new_v4().simple().to_string();
        let command = CommandSpec::new(config.java_binary.clone())
            .args([
                "-jar".to_owned(),
                jar.display().to_string(),
                "--server.address=127.0.0.1".to_owned(),
                "--server.port=0".to_owned(),
                format!("--harmonia.gateway-instance={instance}"),
                format!("--harmonia.workspace={}", config.workspace.display()),
                format!("--harmonia.db-path={}", config.database_path.display()),
            ])
            .env("HARMONIA_NO_BROWSER", "1")
            .current_dir(version_dir)
            .timeout(None);
        let mut process = self.runner.spawn(&command)?;
        let result = self.wait_for_ready(&mut process, &instance, config.health_timeout);
        let stop_result = process.stop();
        if let Err(error) = stop_result {
            return Err(error.into());
        }
        result
    }
}

impl<R: ProcessRunner> LocalBackendHealthChecker<R> {
    fn wait_for_ready(
        &self,
        process: &mut ManagedProcess,
        instance: &str,
        timeout: Duration,
    ) -> Result<(), ActivationError> {
        let client = Client::builder()
            .timeout(Duration::from_secs(1))
            .build()
            .map_err(|error| ActivationError::HealthCheck(error.to_string()))?;
        let deadline = std::time::Instant::now() + timeout;
        let mut port = None;
        while std::time::Instant::now() < deadline {
            if let Some(status) = process.try_wait()? {
                return Err(ActivationError::HealthCheck(format!(
                    "backend exited before health check (status={status}, stderr={})",
                    process.stderr_snapshot()
                )));
            }
            if port.is_none() {
                port = process
                    .stdout_snapshot()
                    .lines()
                    .find_map(|line| parse_ready_line(line, instance));
            }
            if let Some(port) = port {
                match client
                    .get(format!("http://127.0.0.1:{port}/api/status"))
                    .send()
                {
                    Ok(response) if response.status().is_success() => return Ok(()),
                    Ok(response) => {
                        if response.status().is_server_error() {
                            return Err(ActivationError::HealthCheck(format!(
                                "new backend returned {}",
                                response.status()
                            )));
                        }
                    }
                    Err(_) => {}
                }
            }
            thread::sleep(Duration::from_millis(50));
        }
        Err(ActivationError::HealthCheck(format!(
            "backend readiness timeout; stdout={}",
            process.stdout_snapshot()
        )))
    }
}

#[derive(Debug, Error)]
pub enum ActivationError {
    #[error("activation lock failed: {0}")]
    Lock(#[from] LockError),
    #[error("activation state failed: {0}")]
    State(#[from] StateError),
    #[error("activation I/O failed: {0}")]
    Io(#[from] io::Error),
    #[error("activation JSON failed: {0}")]
    Json(#[from] serde_json::Error),
    #[error("activation process failed: {0}")]
    Process(#[from] ProcessError),
    #[error("activation toolchain failed: {0}")]
    Toolchain(#[from] crate::toolchain::ToolchainError),
    #[error("unsupported BuildResult schema: {0}")]
    UnsupportedBuildSchema(u32),
    #[error("invalid activation input: {0}")]
    InvalidInput(String),
    #[error("candidate is missing or outside the managed build root: {0}")]
    InvalidCandidate(PathBuf),
    #[error("candidate artifact verification failed: {0}")]
    ArtifactVerification(String),
    #[error("health check failed: {0}")]
    HealthCheck(String),
    #[error("activation hook failed: {0}")]
    Hook(String),
    #[error("rollback requires manual review: {0}")]
    ReviewRequired(String),
}

pub struct ActivationEngine {
    paths: InstallationPaths,
}

impl ActivationEngine {
    pub fn new(paths: InstallationPaths) -> Self {
        Self { paths }
    }

    pub fn paths(&self) -> &InstallationPaths {
        &self.paths
    }

    pub fn activate<H: HealthChecker>(
        &self,
        result_path: impl AsRef<Path>,
        config: &ActivationConfig,
        hooks: &mut dyn ActivationHooks,
        checker: &H,
    ) -> Result<RuntimePaths, ActivationError> {
        let _lock = InstallationLock::acquire(self.paths.lock_path(), "phase5-activate")?;
        self.recover_locked(config, checker)?;
        let result = self.load_build_result(result_path.as_ref())?;
        let candidate = self.validate_candidate(&result)?;
        let store = StateStore::new(self.paths.clone());
        let installation = store.load_installation()?;
        let workspace =
            validate_activation_path_value(&self.paths.user_data_root, &config.workspace)?;
        let database_path =
            validate_activation_path_value(&self.paths.user_data_root, &config.database_path)?;
        let mut activation_config = config.clone();
        activation_config.workspace = workspace.clone();
        activation_config.database_path = database_path.clone();
        let mut transaction = Transaction::begin(
            store.clone(),
            OperationKind::Update,
            installation.current_commit.clone(),
            Some(result.target_commit.clone()),
            Vec::new(),
        )?;
        transaction.set_pre_activation_state(installation.clone())?;
        transaction.set_workspace_path(workspace)?;
        transaction.set_database_path(database_path)?;
        for (kind, id) in &result.toolchains {
            if let Err(error) = transaction.pin_toolchain(kind.clone(), id.clone()) {
                let reason = format!("failed to pin transaction toolchain {kind}={id}: {error}");
                let _ = transaction.mark_review_required(reason);
                return Err(error.into());
            }
        }
        let activation = self.activate_transaction(
            &result,
            &candidate,
            &activation_config,
            hooks,
            checker,
            &mut transaction,
        );
        match activation {
            Ok(runtime) => Ok(runtime),
            Err(error) => {
                if transaction.record().activation_started {
                    if !transaction.record().rollback_completed {
                        if let Err(rollback_error) =
                            self.rollback_transaction(&mut transaction, config, checker)
                        {
                            let reason = format!(
                                "{error}; rollback failed and requires review: {rollback_error}"
                            );
                            let _ = transaction.mark_review_required(reason.clone());
                            return Err(ActivationError::ReviewRequired(reason));
                        }
                    }
                } else {
                    let cleanup = transaction.cleanup_owned_paths();
                    let clear =
                        self.clear_pending_version(transaction.record().target_commit.as_deref());
                    let recovery_error = cleanup
                        .err()
                        .map(|error| error.to_string())
                        .or_else(|| clear.err().map(|error| error.to_string()));
                    if let Some(recovery_error) = recovery_error {
                        let reason = format!(
                            "pre-activation cleanup/state restoration failed and requires review: {recovery_error}"
                        );
                        let _ = transaction.mark_review_required(reason.clone());
                        return Err(ActivationError::ReviewRequired(reason));
                    }
                }
                let _ = transaction.fail(error.to_string());
                Err(error)
            }
        }
    }

    fn activate_transaction<H: HealthChecker>(
        &self,
        result: &BuildResult,
        candidate: &Path,
        config: &ActivationConfig,
        hooks: &mut dyn ActivationHooks,
        checker: &H,
        transaction: &mut Transaction,
    ) -> Result<RuntimePaths, ActivationError> {
        transaction.transition(TransactionPhase::Staging)?;
        let final_dir = self.stage_version(result, candidate, config, transaction)?;
        transaction.set_published_version(result.target_commit.clone())?;
        self.set_pending_version(&result.target_commit)?;

        transaction.transition(TransactionPhase::WaitingForShutdown)?;
        hooks.request_shutdown().map_err(ActivationError::Hook)?;
        hooks
            .wait_for_shutdown(config.health_timeout)
            .map_err(ActivationError::Hook)?;

        if let Some(snapshot) = DatabaseSnapshot::create(
            &self.paths.user_data_root,
            &config.database_path,
            &transaction.record().id,
        )? {
            transaction.mark_db_backup_required()?;
            transaction.set_db_backup_path(snapshot.directory.clone())?;
            transaction.set_database_pre_state(DatabasePreState::Snapshot(snapshot.directory))?;
        } else {
            transaction.set_database_pre_state(DatabasePreState::Absent)?;
        }

        transaction.transition(TransactionPhase::Activating)?;
        let installation = StateStore::new(self.paths.clone()).load_installation()?;
        self.switch_current(&installation, result, &final_dir)?;
        transaction.mark_current_switched()?;

        transaction.transition(TransactionPhase::HealthChecking)?;
        transaction.mark_process_started()?;
        let metadata = load_version_metadata(&final_dir.join("metadata.json"))?;
        let staged_runtime_java = self
            .paths
            .app_root
            .join(&metadata.runtime.managed_java_binary);
        let mut health_config = config.clone();
        health_config.java_binary = staged_runtime_java;
        if let Err(error) = checker.check(&final_dir, &metadata, &health_config) {
            transaction.transition(TransactionPhase::RollingBack)?;
            return Err(error);
        }
        transaction.mark_health_check_passed()?;
        let runtime = self.resolve_version(&result.target_commit)?;
        self.clear_pending_version(Some(&result.target_commit))?;
        transaction.complete()?;
        Ok(runtime)
    }

    fn stage_version(
        &self,
        result: &BuildResult,
        candidate: &Path,
        config: &ActivationConfig,
        transaction: &mut Transaction,
    ) -> Result<PathBuf, ActivationError> {
        let final_dir = self.paths.versions_dir().join(&result.target_commit);
        let staging_dir = self.paths.versions_dir().join(format!(
            "{}.staging.{}",
            result.target_commit,
            transaction.record().id
        ));
        validate_activation_path(&self.paths.versions_dir(), &final_dir)?;
        validate_activation_path(&self.paths.versions_dir(), &staging_dir)?;
        let managed_java = self.resolve_build_jdk(result, config)?;
        transaction.own_path(&staging_dir)?;
        if final_dir.exists() {
            if !self.version_matches_result(&final_dir, result)? {
                return Err(ActivationError::InvalidInput(format!(
                    "immutable version already exists but does not match {}",
                    result.target_commit
                )));
            }
            return Ok(final_dir);
        }
        transaction.own_path(&final_dir)?;
        fs::create_dir_all(&staging_dir)?;
        let desktop_source = candidate.join(&result.desktop.path);
        let backend_source = candidate.join(&result.backend.path);
        let desktop_target = staging_dir.join("desktop");
        let backend_target = staging_dir.join("backend").join("harmonia-suite.jar");
        copy_tree(&desktop_source, &desktop_target)?;
        if let Some(parent) = backend_target.parent() {
            fs::create_dir_all(parent)?;
        }
        copy_regular_file(&backend_source, &backend_target)?;
        let desktop_hash = hash_directory(&desktop_target)
            .map_err(|error| ActivationError::ArtifactVerification(error.to_string()))?;
        let backend_hash = sha256_file(&backend_target)
            .map_err(|error| ActivationError::ArtifactVerification(error.to_string()))?;
        if desktop_hash != result.desktop.sha256 || backend_hash != result.backend.sha256 {
            return Err(ActivationError::ArtifactVerification(
                "staged artifact hash differs from BuildResult".to_owned(),
            ));
        }
        let executable = desktop_target.join(if self.paths.platform.as_str() == "windows" {
            "electron.exe"
        } else {
            "electron"
        });
        if !executable.is_file() {
            return Err(ActivationError::ArtifactVerification(
                "desktop runtime executable is missing".to_owned(),
            ));
        }
        require_executable(&executable)?;
        let metadata = VersionMetadata {
            schema_version: VERSION_METADATA_SCHEMA_VERSION,
            target_commit: result.target_commit.clone(),
            product_version: result.product_version.clone(),
            created_at_ms: now_ms(),
            desktop_artifact_sha256: desktop_hash,
            backend_artifact_sha256: backend_hash,
            component_paths: BTreeMap::from([
                (
                    "desktop".to_owned(),
                    VersionComponent {
                        path: PathBuf::from("desktop"),
                        sha256: result.desktop.sha256.clone(),
                    },
                ),
                (
                    "backend".to_owned(),
                    VersionComponent {
                        path: PathBuf::from("backend/harmonia-suite.jar"),
                        sha256: result.backend.sha256.clone(),
                    },
                ),
            ]),
            toolchains: result.toolchains.clone(),
            runtime: RuntimeMetadata {
                desktop_executable: PathBuf::from("desktop").join(
                    if self.paths.platform.as_str() == "windows" {
                        "electron.exe"
                    } else {
                        "electron"
                    },
                ),
                backend_jar: PathBuf::from("backend/harmonia-suite.jar"),
                managed_java_binary: managed_java,
            },
        };
        crate::state::atomic_write_json(&staging_dir.join("metadata.json"), &metadata)?;
        sync_directory(&staging_dir)?;
        if let Some(parent) = final_dir.parent() {
            fs::create_dir_all(parent)?;
        }
        crate::state::durable_promote_directory(&staging_dir, &final_dir)?;
        Ok(final_dir)
    }

    fn resolve_build_jdk(
        &self,
        result: &BuildResult,
        config: &ActivationConfig,
    ) -> Result<PathBuf, ActivationError> {
        let jdk_id = result.toolchains.get("jdk").ok_or_else(|| {
            ActivationError::InvalidInput("BuildResult is missing jdk toolchain ID".to_owned())
        })?;
        let resolved = ToolchainStateStore::new(self.paths.clone()).resolve(jdk_id)?;
        if resolved.kind != ToolchainKind::Jdk {
            return Err(ActivationError::InvalidInput(format!(
                "BuildResult jdk ID {jdk_id:?} does not resolve to a JDK"
            )));
        }
        let java = resolved.executable("java").ok_or_else(|| {
            ActivationError::InvalidInput(format!("managed JDK {jdk_id:?} has no java executable"))
        })?;
        if !require_executable_result(java) {
            return Err(ActivationError::InvalidInput(
                "managed JDK java executable is not runnable".to_owned(),
            ));
        }
        let supplied = absolute_path(&config.java_binary)?;
        if supplied != java {
            return Err(ActivationError::InvalidInput(
                "ActivationConfig.java_binary must exactly match the BuildResult managed JDK"
                    .to_owned(),
            ));
        }
        java.strip_prefix(&self.paths.app_root)
            .map(PathBuf::from)
            .map_err(|_| {
                ActivationError::InvalidInput("managed JDK is outside app root".to_owned())
            })
    }

    fn switch_current(
        &self,
        installation: &InstallationState,
        result: &BuildResult,
        final_dir: &Path,
    ) -> Result<(), ActivationError> {
        if !self.version_matches_result(final_dir, result)? {
            return Err(ActivationError::InvalidInput(
                "final version failed completeness verification".to_owned(),
            ));
        }
        if installation.current_commit == Some(result.target_commit.clone()) {
            return Ok(());
        }
        let mut next = installation.clone();
        next.previous_commit = installation.current_commit.clone();
        next.previous_toolchains = installation.current_toolchains.clone();
        next.current_commit = Some(result.target_commit.clone());
        next.product_version = Some(result.product_version.clone());
        next.current_toolchains = result.toolchains.clone();
        next.components = BTreeMap::from([
            ("desktop".to_owned(), result.desktop.sha256.clone()),
            ("backend".to_owned(), result.backend.sha256.clone()),
        ]);
        next.activation_at_ms = Some(now_ms());
        StateStore::new(self.paths.clone()).save_installation(&next)?;
        Ok(())
    }

    fn rollback_transaction(
        &self,
        transaction: &mut Transaction,
        config: &ActivationConfig,
        checker: &impl HealthChecker,
    ) -> Result<(), ActivationError> {
        let store = StateStore::new(self.paths.clone());
        let snapshot = transaction
            .record()
            .pre_activation_state
            .clone()
            .ok_or_else(|| {
                ActivationError::ReviewRequired(
                    "transaction has no pre-activation installation snapshot".to_owned(),
                )
            })?;
        let database_path = transaction
            .record()
            .database_path
            .clone()
            .unwrap_or_else(|| config.database_path.clone());
        match transaction.record().database_pre_state.clone() {
            Some(DatabasePreState::Snapshot(backup)) => {
                DatabaseSnapshot::restore(&backup, &self.paths.user_data_root)?;
            }
            Some(DatabasePreState::Absent) => {
                remove_database_files(&self.paths.user_data_root, &database_path)?;
            }
            None => {
                if let Some(backup) = transaction.record().db_backup_path.clone() {
                    DatabaseSnapshot::restore(&backup, &self.paths.user_data_root)?;
                }
            }
        }
        store.save_installation(&snapshot)?;
        if let Some(commit) = snapshot.current_commit.as_deref() {
            let runtime = self.resolve_version(commit)?;
            let mut health_config = config.clone();
            health_config.java_binary = runtime.java_binary.clone();
            if let Err(error) =
                checker.check(&runtime.version_dir, &runtime.metadata, &health_config)
            {
                return Err(ActivationError::ReviewRequired(format!(
                    "previous version health check failed: {error}"
                )));
            }
        }
        transaction.mark_rollback_completed()?;
        Ok(())
    }

    fn set_pending_version(&self, commit: &str) -> Result<(), ActivationError> {
        let store = StateStore::new(self.paths.clone());
        let mut state = store.load_installation()?;
        state.staged_commit = Some(commit.to_owned());
        state.pending_commit = Some(commit.to_owned());
        store.save_installation(&state)?;
        Ok(())
    }

    fn clear_pending_version(&self, expected: Option<&str>) -> Result<(), ActivationError> {
        let store = StateStore::new(self.paths.clone());
        let mut state = store.load_installation()?;
        if expected.is_none()
            || state.staged_commit.as_deref() == expected
            || state.pending_commit.as_deref() == expected
        {
            state.staged_commit = None;
            state.pending_commit = None;
            store.save_installation(&state)?;
        }
        Ok(())
    }

    pub fn recover(&self) -> Result<(), ActivationError> {
        let _lock = InstallationLock::acquire(self.paths.lock_path(), "phase5-recovery")?;
        let config = ActivationConfig::for_paths(&self.paths, PathBuf::new());
        self.recover_locked(&config, &UnavailableHealthChecker)
    }

    pub fn recover_with_health_checker<H: HealthChecker>(
        &self,
        config: &ActivationConfig,
        checker: &H,
    ) -> Result<(), ActivationError> {
        let _lock = InstallationLock::acquire(self.paths.lock_path(), "phase5-recovery")?;
        self.recover_locked(config, checker)
    }

    fn recover_locked<H: HealthChecker>(
        &self,
        config: &ActivationConfig,
        checker: &H,
    ) -> Result<(), ActivationError> {
        let store = StateStore::new(self.paths.clone());
        let Some(record) = store.load_transaction()? else {
            return Ok(());
        };
        if record.status == TransactionStatus::ReviewRequired {
            return Err(ActivationError::ReviewRequired(
                record
                    .failure
                    .clone()
                    .unwrap_or_else(|| "transaction requires explicit review".to_owned()),
            ));
        }
        if record.status != TransactionStatus::Running {
            return Ok(());
        }
        let mut transaction = Transaction::resume_existing(store.clone(), record.clone());
        if !record.activation_started {
            let cleanup = transaction.cleanup_owned_paths();
            let clear = self.clear_pending_version(record.target_commit.as_deref());
            let error = cleanup
                .err()
                .map(|error| error.to_string())
                .or_else(|| clear.err().map(|error| error.to_string()));
            if let Some(error) = error {
                let reason =
                    format!("pre-activation recovery cleanup/state restoration failed: {error}");
                let _ = transaction.mark_review_required(reason.clone());
                return Err(ActivationError::ReviewRequired(reason));
            }
            transaction.fail("recovered interrupted pre-activation transaction")?;
            return Ok(());
        }
        let target = match record
            .target_commit
            .clone()
            .or_else(|| record.published_version.clone())
        {
            Some(target) => target,
            None => {
                let reason = "activation target is missing".to_owned();
                let _ = transaction.mark_review_required(reason.clone());
                return Err(ActivationError::ReviewRequired(reason));
            }
        };
        let installation = store.load_installation()?;
        if installation.current_commit.as_deref() == Some(target.as_str())
            && record.health_check_passed
        {
            if let Err(error) = self.clear_pending_version(Some(&target)) {
                let reason = format!("failed to clear pending activation state: {error}");
                let _ = transaction.mark_review_required(reason.clone());
                return Err(ActivationError::ReviewRequired(reason));
            }
            transaction.complete()?;
            return Ok(());
        }
        if record.db_backup_required && record.db_backup_path.is_none() && record.current_switched {
            let reason = "activation crossed pointer switch without a DB backup".to_owned();
            let _ = transaction.mark_review_required(reason.clone());
            return Err(ActivationError::ReviewRequired(reason));
        }
        let mut recovery_config = config.clone();
        let recovery_paths = (|| -> Result<(), ActivationError> {
            if let Some(workspace) = record.workspace_path.clone() {
                recovery_config.workspace =
                    validate_activation_path_value(&self.paths.user_data_root, &workspace)?;
            }
            if let Some(database_path) = record.database_path.clone() {
                recovery_config.database_path =
                    validate_activation_path_value(&self.paths.user_data_root, &database_path)?;
            }
            Ok(())
        })();
        if let Err(error) = recovery_paths {
            let reason = format!("journaled runtime data path validation failed: {error}");
            let _ = transaction.mark_review_required(reason.clone());
            return Err(ActivationError::ReviewRequired(reason));
        }
        if let Err(error) = self.rollback_transaction(&mut transaction, &recovery_config, checker) {
            let reason = format!("recovery rollback failed: {error}");
            let _ = transaction.mark_review_required(reason.clone());
            return Err(ActivationError::ReviewRequired(reason));
        }
        transaction.fail("recovered activation by rolling back to previous version")?;
        Ok(())
    }

    fn load_build_result(&self, path: &Path) -> Result<BuildResult, ActivationError> {
        let path = absolute_path(path)?;
        validate_activation_path(&self.paths.build_results_dir(), &path)?;
        let result: BuildResult = serde_json::from_slice(&fs::read(path)?)?;
        if result.schema_version != BUILD_RESULT_SCHEMA_VERSION {
            return Err(ActivationError::UnsupportedBuildSchema(
                result.schema_version,
            ));
        }
        if result.status != BuildStatus::Completed {
            return Err(ActivationError::InvalidInput(
                "BuildResult is not Completed".to_owned(),
            ));
        }
        validate_sha(&result.target_commit)?;
        if result.product_version.trim().is_empty() {
            return Err(ActivationError::InvalidInput(
                "BuildResult product_version is empty".to_owned(),
            ));
        }
        Ok(result)
    }

    fn validate_candidate(&self, result: &BuildResult) -> Result<PathBuf, ActivationError> {
        let checkout = if result.checkout_dir.is_absolute() {
            return Err(ActivationError::InvalidCandidate(
                result.checkout_dir.clone(),
            ));
        } else {
            self.paths.app_root.join(&result.checkout_dir)
        };
        let candidate_root = self
            .paths
            .build_dir()
            .join("candidates")
            .join(&result.target_commit);
        validate_activation_path(&candidate_root, &checkout)?;
        if !checkout.is_dir() {
            return Err(ActivationError::InvalidCandidate(checkout));
        }
        verify_artifact(
            &checkout,
            &result.frontend.path,
            &result.frontend.sha256,
            false,
        )?;
        verify_artifact(
            &checkout,
            &result.backend.path,
            &result.backend.sha256,
            true,
        )?;
        verify_artifact(
            &checkout,
            &result.desktop.path,
            &result.desktop.sha256,
            false,
        )?;
        Ok(checkout)
    }

    fn version_matches_result(
        &self,
        version_dir: &Path,
        result: &BuildResult,
    ) -> Result<bool, ActivationError> {
        validate_activation_path(&self.paths.versions_dir(), version_dir)?;
        if !version_dir.is_dir() {
            return Ok(false);
        }
        let metadata = match load_version_metadata(&version_dir.join("metadata.json")) {
            Ok(metadata) => metadata,
            Err(_) => return Ok(false),
        };
        if metadata.target_commit != result.target_commit
            || metadata.product_version != result.product_version
            || metadata.desktop_artifact_sha256 != result.desktop.sha256
            || metadata.backend_artifact_sha256 != result.backend.sha256
            || metadata.toolchains != result.toolchains
        {
            return Ok(false);
        }
        Ok(self.version_complete(version_dir, &metadata))
    }

    pub fn resolve_current(&self) -> Result<Option<RuntimePaths>, ActivationError> {
        let state = StateStore::new(self.paths.clone()).load_installation()?;
        state
            .current_commit
            .map(|commit| self.resolve_version(&commit))
            .transpose()
    }

    pub fn resolve_version(&self, commit: &str) -> Result<RuntimePaths, ActivationError> {
        validate_sha(commit)?;
        let version_dir = self.paths.versions_dir().join(commit);
        validate_activation_path(&self.paths.versions_dir(), &version_dir)?;
        let metadata = load_version_metadata(&version_dir.join("metadata.json"))?;
        if metadata.target_commit != commit {
            return Err(ActivationError::InvalidInput(
                "version metadata target does not match its directory".to_owned(),
            ));
        }
        if !self.version_complete(&version_dir, &metadata) {
            return Err(ActivationError::InvalidInput(format!(
                "version {commit} is incomplete or tampered"
            )));
        }
        Ok(RuntimePaths {
            desktop_executable: version_dir.join(&metadata.runtime.desktop_executable),
            backend_jar: version_dir.join(&metadata.runtime.backend_jar),
            java_binary: self
                .paths
                .app_root
                .join(&metadata.runtime.managed_java_binary),
            version_dir,
            metadata,
        })
    }

    fn version_complete(&self, version_dir: &Path, metadata: &VersionMetadata) -> bool {
        metadata.component_paths.values().all(|component| {
            if !safe_relative(&component.path) || !valid_hash(&component.sha256) {
                return false;
            }
            let path = version_dir.join(&component.path);
            let actual = if path.is_dir() {
                hash_directory(&path).ok()
            } else {
                sha256_file(&path).ok()
            };
            actual
                .map(|hash| hash == component.sha256.to_ascii_lowercase())
                .unwrap_or(false)
        }) && safe_relative(&metadata.runtime.desktop_executable)
            && safe_relative(&metadata.runtime.backend_jar)
            && version_dir
                .join(&metadata.runtime.desktop_executable)
                .is_file()
            && require_executable_result(&version_dir.join(&metadata.runtime.desktop_executable))
            && version_dir.join(&metadata.runtime.backend_jar).is_file()
            && validate_version_jdk(&self.paths, metadata).is_ok()
            && hash_directory(&version_dir.join("desktop"))
                .map(|hash| hash == metadata.desktop_artifact_sha256)
                .unwrap_or(false)
            && sha256_file(version_dir.join(&metadata.runtime.backend_jar))
                .map(|hash| hash == metadata.backend_artifact_sha256)
                .unwrap_or(false)
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct DatabaseManifest {
    database_relative: PathBuf,
    files: Vec<DatabaseFileManifest>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct DatabaseFileManifest {
    path: PathBuf,
    size: u64,
    sha256: String,
}

struct DatabaseSnapshot;

impl DatabaseSnapshot {
    fn create(
        user_data_root: &Path,
        database_path: &Path,
        transaction_id: &str,
    ) -> Result<Option<SnapshotInfo>, ActivationError> {
        let sidecars = [
            PathBuf::from(format!("{}-wal", database_path.display())),
            PathBuf::from(format!("{}-shm", database_path.display())),
        ];
        if !database_path.exists() && sidecars.iter().all(|path| !path.exists()) {
            return Ok(None);
        }
        if !database_path.is_file() {
            return Err(ActivationError::InvalidInput(
                "database sidecar exists without a regular primary database".to_owned(),
            ));
        }
        validate_activation_path(user_data_root, database_path)?;
        let database_relative = database_path
            .strip_prefix(user_data_root)
            .map_err(|_| {
                ActivationError::InvalidInput(
                    "database path must be inside the user-data root".to_owned(),
                )
            })?
            .to_path_buf();
        if !safe_relative(&database_relative) {
            return Err(ActivationError::InvalidInput(
                "database path is not a safe relative path".to_owned(),
            ));
        }
        let directory = user_data_root
            .join("backups")
            .join("installer")
            .join(transaction_id);
        if directory.exists() {
            return Err(ActivationError::InvalidInput(
                "database backup directory already exists".to_owned(),
            ));
        }
        validate_activation_path(user_data_root, &directory)?;
        fs::create_dir_all(&directory)?;
        let mut files = Vec::new();
        for suffix in ["", "-wal", "-shm"] {
            let source = if suffix.is_empty() {
                database_path.to_path_buf()
            } else {
                PathBuf::from(format!("{}{}", database_path.display(), suffix))
            };
            validate_activation_path(user_data_root, &source)?;
            if source.is_file() {
                let name = source.file_name().ok_or_else(|| {
                    io::Error::new(io::ErrorKind::InvalidInput, "database filename")
                })?;
                let destination = directory.join(name);
                copy_regular_file(&source, &destination)?;
                let copied_metadata = fs::metadata(&destination)?;
                let sha256 = sha256_file(&destination)
                    .map_err(|error| ActivationError::ReviewRequired(error.to_string()))?;
                files.push(DatabaseFileManifest {
                    path: PathBuf::from(name),
                    size: copied_metadata.len(),
                    sha256,
                });
            }
        }
        if files.is_empty() {
            return Err(ActivationError::InvalidInput(
                "database disappeared before snapshot".to_owned(),
            ));
        }
        crate::state::atomic_write_json(
            &directory.join("manifest.json"),
            &DatabaseManifest {
                database_relative,
                files,
            },
        )?;
        sync_directory(&directory)?;
        Ok(Some(SnapshotInfo { directory }))
    }

    fn restore(directory: &Path, user_data_root: &Path) -> Result<(), ActivationError> {
        validate_activation_path(user_data_root, directory)?;
        let manifest_path = directory.join("manifest.json");
        validate_activation_path(directory, &manifest_path)?;
        let manifest: DatabaseManifest = serde_json::from_slice(&fs::read(manifest_path)?)?;
        if !safe_relative(&manifest.database_relative) {
            return Err(ActivationError::ReviewRequired(
                "database backup manifest contains unsafe database path".to_owned(),
            ));
        }
        let database_path = user_data_root.join(&manifest.database_relative);
        validate_activation_path(user_data_root, &database_path)?;
        let database_parent = database_path.parent().ok_or_else(|| {
            ActivationError::ReviewRequired("database backup path has no parent".to_owned())
        })?;
        fs::create_dir_all(database_parent)?;
        let database_name = database_path.file_name().ok_or_else(|| {
            ActivationError::ReviewRequired("database backup path has no filename".to_owned())
        })?;
        let allowed_names = [
            PathBuf::from(database_name.to_os_string()),
            PathBuf::from(format!("{}-wal", database_name.to_string_lossy())),
            PathBuf::from(format!("{}-shm", database_name.to_string_lossy())),
        ];
        let mut names = std::collections::BTreeSet::new();
        for file in &manifest.files {
            if !safe_relative(&file.path)
                || file.path.components().count() != 1
                || !allowed_names.iter().any(|allowed| &file.path == allowed)
                || !names.insert(file.path.clone())
                || !valid_hash(&file.sha256)
            {
                return Err(ActivationError::ReviewRequired(
                    "database backup manifest contains unsafe path".to_owned(),
                ));
            }
            let source = directory.join(&file.path);
            validate_activation_path(directory, &source)?;
            let metadata = fs::symlink_metadata(&source).map_err(|error| {
                ActivationError::ReviewRequired(format!(
                    "database backup file is missing: {} ({error})",
                    source.display()
                ))
            })?;
            if metadata.file_type().is_symlink()
                || !metadata.is_file()
                || metadata.len() != file.size
            {
                return Err(ActivationError::ReviewRequired(format!(
                    "database backup file metadata mismatch: {}",
                    source.display()
                )));
            }
            let actual = sha256_file(&source)
                .map_err(|error| ActivationError::ReviewRequired(error.to_string()))?;
            if actual != file.sha256.to_ascii_lowercase() {
                return Err(ActivationError::ReviewRequired(format!(
                    "database backup checksum mismatch: {}",
                    source.display()
                )));
            }
        }
        let database_name = PathBuf::from(database_name.to_os_string());
        if !names.contains(&database_name) {
            return Err(ActivationError::ReviewRequired(
                "database backup does not contain the primary database".to_owned(),
            ));
        }

        let restore_staging = database_parent.join(format!(
            ".{}.restore.{}",
            database_name.to_string_lossy(),
            Uuid::new_v4()
        ));
        validate_activation_path(user_data_root, &restore_staging)?;
        fs::create_dir_all(&restore_staging)?;
        let restore_result = (|| -> Result<(), ActivationError> {
            for file in &manifest.files {
                let staged = restore_staging.join(&file.path);
                copy_regular_file(&directory.join(&file.path), &staged)?;
                let staged_metadata = fs::metadata(&staged)?;
                let staged_hash = sha256_file(&staged)
                    .map_err(|error| ActivationError::ReviewRequired(error.to_string()))?;
                if staged_metadata.len() != file.size
                    || staged_hash != file.sha256.to_ascii_lowercase()
                {
                    return Err(ActivationError::ReviewRequired(
                        "staged database restore failed integrity verification".to_owned(),
                    ));
                }
            }
            for file in &manifest.files {
                let staged = restore_staging.join(&file.path);
                let destination = database_parent.join(&file.path);
                validate_activation_path(user_data_root, &destination)?;
                crate::state::durable_replace_file(&staged, &destination)?;
            }
            for suffix in ["-wal", "-shm"] {
                let name = format!("{}{suffix}", database_name.to_string_lossy());
                if !names.contains(Path::new(&name)) {
                    let sidecar = database_parent.join(&name);
                    validate_activation_path(user_data_root, &sidecar)?;
                    match fs::remove_file(sidecar) {
                        Ok(()) => {}
                        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                        Err(error) => return Err(error.into()),
                    }
                }
            }
            sync_directory(database_parent)?;
            Ok(())
        })();
        let cleanup = fs::remove_dir_all(&restore_staging);
        if let Err(error) = restore_result {
            if let Err(cleanup_error) = cleanup {
                return Err(ActivationError::ReviewRequired(format!(
                    "database restore failed and staging cleanup failed: {error}; {cleanup_error}"
                )));
            }
            return Err(error);
        }
        cleanup?;
        Ok(())
    }
}

struct SnapshotInfo {
    directory: PathBuf,
}

fn load_version_metadata(path: &Path) -> Result<VersionMetadata, ActivationError> {
    let metadata: VersionMetadata = serde_json::from_slice(&fs::read(path)?)?;
    if metadata.schema_version != VERSION_METADATA_SCHEMA_VERSION {
        return Err(ActivationError::InvalidInput(
            "unsupported version metadata schema".to_owned(),
        ));
    }
    validate_sha(&metadata.target_commit)?;
    if !safe_relative(&metadata.runtime.desktop_executable)
        || !safe_relative(&metadata.runtime.backend_jar)
        || !safe_relative(&metadata.runtime.managed_java_binary)
        || metadata
            .component_paths
            .values()
            .any(|component| !safe_relative(&component.path) || !valid_hash(&component.sha256))
        || !valid_hash(&metadata.desktop_artifact_sha256)
        || !valid_hash(&metadata.backend_artifact_sha256)
    {
        return Err(ActivationError::InvalidInput(
            "version metadata contains unsafe paths or hashes".to_owned(),
        ));
    }
    Ok(metadata)
}

fn verify_artifact(
    root: &Path,
    relative: &Path,
    expected: &str,
    file_required: bool,
) -> Result<(), ActivationError> {
    if !safe_relative(relative) || !valid_hash(expected) {
        return Err(ActivationError::ArtifactVerification(
            "artifact path or hash is invalid".to_owned(),
        ));
    }
    let path = root.join(relative);
    let actual = if file_required {
        sha256_file(&path)
            .map_err(|error| ActivationError::ArtifactVerification(error.to_string()))?
    } else {
        hash_directory(&path)
            .map_err(|error| ActivationError::ArtifactVerification(error.to_string()))?
    };
    if actual != expected.to_ascii_lowercase() {
        return Err(ActivationError::ArtifactVerification(format!(
            "{} hash mismatch",
            path.display()
        )));
    }
    Ok(())
}

fn copy_tree(source: &Path, destination: &Path) -> Result<(), ActivationError> {
    let metadata = fs::symlink_metadata(source)?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(ActivationError::ArtifactVerification(format!(
            "runtime payload is not a regular directory: {}",
            source.display()
        )));
    }
    fs::create_dir_all(destination)?;
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let child_source = entry.path();
        let child_destination = destination.join(entry.file_name());
        let child_metadata = fs::symlink_metadata(&child_source)?;
        if child_metadata.file_type().is_symlink() {
            return Err(ActivationError::ArtifactVerification(format!(
                "runtime payload contains a symlink: {}",
                child_source.display()
            )));
        }
        if child_metadata.is_dir() {
            copy_tree(&child_source, &child_destination)?;
        } else if child_metadata.is_file() {
            copy_regular_file(&child_source, &child_destination)?;
        } else {
            return Err(ActivationError::ArtifactVerification(format!(
                "runtime payload contains a special file: {}",
                child_source.display()
            )));
        }
    }
    Ok(())
}

fn copy_regular_file(source: &Path, destination: &Path) -> Result<(), ActivationError> {
    let metadata = fs::symlink_metadata(source)?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(ActivationError::ArtifactVerification(format!(
            "not a regular file: {}",
            source.display()
        )));
    }
    if let Some(parent) = destination.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::copy(source, destination)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(
            destination,
            fs::Permissions::from_mode(metadata.permissions().mode()),
        )?;
    }
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .open(destination)?;
    file.sync_all()?;
    Ok(())
}

fn validate_sha(value: &str) -> Result<(), ActivationError> {
    if value.len() != 40 || !value.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(ActivationError::InvalidInput(
            "target commit must be a full 40-character SHA".to_owned(),
        ));
    }
    Ok(())
}

fn valid_hash(value: &str) -> bool {
    value.len() == SHA256_LENGTH && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn safe_relative(path: &Path) -> bool {
    !path.is_absolute()
        && path
            .components()
            .all(|component| matches!(component, Component::Normal(_)))
}

fn require_executable(path: &Path) -> Result<(), ActivationError> {
    if require_executable_result(path) {
        Ok(())
    } else {
        Err(ActivationError::ArtifactVerification(format!(
            "desktop runtime is not executable: {}",
            path.display()
        )))
    }
}

fn require_executable_result(path: &Path) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::metadata(path)
            .map(|metadata| metadata.permissions().mode() & 0o111 != 0)
            .unwrap_or(false)
    }
    #[cfg(not(unix))]
    {
        path.is_file()
    }
}

fn validate_version_jdk(
    paths: &InstallationPaths,
    metadata: &VersionMetadata,
) -> Result<(), ActivationError> {
    let jdk_id = metadata.toolchains.get("jdk").ok_or_else(|| {
        ActivationError::InvalidInput("version metadata is missing jdk ID".to_owned())
    })?;
    let resolved = ToolchainStateStore::new(paths.clone()).resolve(jdk_id)?;
    if resolved.kind != ToolchainKind::Jdk {
        return Err(ActivationError::InvalidInput(
            "version metadata jdk ID is not a JDK".to_owned(),
        ));
    }
    let java = resolved.executable("java").ok_or_else(|| {
        ActivationError::InvalidInput("managed JDK has no java executable".to_owned())
    })?;
    if !require_executable_result(java) {
        return Err(ActivationError::InvalidInput(
            "managed JDK java executable is not runnable".to_owned(),
        ));
    }
    let metadata_java = paths.app_root.join(&metadata.runtime.managed_java_binary);
    if metadata_java != java {
        return Err(ActivationError::InvalidInput(
            "version metadata Java path does not match its JDK record".to_owned(),
        ));
    }
    Ok(())
}

fn absolute_path(path: &Path) -> Result<PathBuf, ActivationError> {
    if path.is_absolute() {
        Ok(path.to_path_buf())
    } else {
        Ok(std::env::current_dir()?.join(path))
    }
}

fn validate_activation_path(root: &Path, path: &Path) -> Result<(), ActivationError> {
    validate_activation_path_value(root, path).map(|_| ())
}

fn validate_activation_path_value(root: &Path, path: &Path) -> Result<PathBuf, ActivationError> {
    let root = absolute_path(root)?;
    let path = absolute_path(path)?;
    validate_managed_path(&root, &path)
        .map(|_| path)
        .map_err(ActivationError::State)
}

fn remove_database_files(
    user_data_root: &Path,
    database_path: &Path,
) -> Result<(), ActivationError> {
    for suffix in ["", "-wal", "-shm"] {
        let path = if suffix.is_empty() {
            database_path.to_path_buf()
        } else {
            PathBuf::from(format!("{}{}", database_path.display(), suffix))
        };
        let path = validate_activation_path_value(user_data_root, &path)?;
        match fs::symlink_metadata(&path) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
                return Err(ActivationError::ReviewRequired(format!(
                    "database pre-state path is not a regular file: {}",
                    path.display()
                )));
            }
            Ok(_) => fs::remove_file(path)?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
}

fn sync_directory(path: &Path) -> io::Result<()> {
    #[cfg(unix)]
    {
        File::open(path)?.sync_all()?;
    }
    #[cfg(windows)]
    let _ = path;
    Ok(())
}

fn parse_ready_line(line: &str, expected_instance: &str) -> Option<u16> {
    let prefix = format!("HARMONIA_GATEWAY_READY instance={expected_instance} port=");
    let value = line.trim().strip_prefix(&prefix)?.parse::<u16>().ok()?;
    (value > 0).then_some(value)
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
    use crate::paths::{Platform, TargetArchitecture};
    use crate::toolchain::{ArchiveFormat, ToolchainRecord, ToolchainState};
    use tempfile::tempdir;

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

    #[test]
    fn validates_full_commit_and_safe_relative_paths() {
        assert!(validate_sha(&"a".repeat(40)).is_ok());
        assert!(validate_sha("short").is_err());
        assert!(safe_relative(Path::new("desktop/main.js")));
        assert!(!safe_relative(Path::new("../outside")));
        assert!(!safe_relative(Path::new("/outside")));
    }

    #[test]
    fn database_snapshot_is_unique_and_restorable() {
        let root = tempdir().unwrap();
        let user_data = root.path().join("user-data");
        let database = user_data.join("workspace/state.sqlite");
        fs::create_dir_all(database.parent().unwrap()).unwrap();
        fs::write(&database, b"before").unwrap();
        fs::write(
            PathBuf::from(format!("{}-wal", database.display())),
            b"wal-before",
        )
        .unwrap();
        fs::write(root.path().join("ignored"), b"ignored").unwrap();
        let snapshot = DatabaseSnapshot::create(&user_data, &database, "tx-1")
            .unwrap()
            .unwrap();
        fs::write(&database, b"after").unwrap();
        fs::write(
            PathBuf::from(format!("{}-wal", database.display())),
            b"wal-after",
        )
        .unwrap();
        DatabaseSnapshot::restore(&snapshot.directory, &user_data).unwrap();
        assert_eq!(fs::read(&database).unwrap(), b"before");
        assert_eq!(
            fs::read(PathBuf::from(format!("{}-wal", database.display()))).unwrap(),
            b"wal-before"
        );
        assert!(DatabaseSnapshot::create(&user_data, &database, "tx-1").is_err());
    }

    #[test]
    fn corrupt_database_snapshot_fails_before_live_db_write() {
        let root = tempdir().unwrap();
        let user_data = root.path().join("user-data");
        let database = user_data.join("data/harmonia.db");
        fs::create_dir_all(database.parent().unwrap()).unwrap();
        fs::write(&database, b"before").unwrap();
        let snapshot = DatabaseSnapshot::create(&user_data, &database, "tx-corrupt")
            .unwrap()
            .unwrap();
        fs::write(&database, b"live-after").unwrap();
        let manifest_path = snapshot.directory.join("manifest.json");
        let mut manifest: DatabaseManifest =
            serde_json::from_slice(&fs::read(&manifest_path).unwrap()).unwrap();
        manifest.files[0].sha256 = "0".repeat(SHA256_LENGTH);
        fs::write(&manifest_path, serde_json::to_vec(&manifest).unwrap()).unwrap();
        assert!(matches!(
            DatabaseSnapshot::restore(&snapshot.directory, &user_data),
            Err(ActivationError::ReviewRequired(_))
        ));
        assert_eq!(fs::read(&database).unwrap(), b"live-after");
    }

    #[test]
    fn non_default_database_path_is_journaled_and_used_by_health_check() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let custom_database = paths.user_data_root.join("workspace/custom.sqlite");
        fs::create_dir_all(custom_database.parent().unwrap()).unwrap();
        fs::write(&custom_database, b"database").unwrap();
        let (result_path, _) = fixture_result(&paths, &"a".repeat(40), "tx-db");
        let mut config = fixture_config(&paths);
        config.database_path = custom_database.clone();
        let mut hooks = NoopActivationHooks;
        ActivationEngine::new(paths.clone())
            .activate(
                result_path,
                &config,
                &mut hooks,
                &DatabasePathHealthChecker {
                    expected: custom_database.clone(),
                },
            )
            .unwrap();
        let transaction = StateStore::new(paths.clone())
            .load_transaction()
            .unwrap()
            .unwrap();
        assert_eq!(transaction.database_path, Some(custom_database.clone()));
        let backup = transaction.db_backup_path.unwrap();
        let manifest: DatabaseManifest =
            serde_json::from_slice(&fs::read(backup.join("manifest.json")).unwrap()).unwrap();
        assert_eq!(
            manifest.database_relative,
            custom_database
                .strip_prefix(&paths.user_data_root)
                .unwrap()
                .to_path_buf()
        );
    }

    #[test]
    fn recovery_uses_journaled_workspace_instead_of_new_caller_config() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let engine = ActivationEngine::new(paths.clone());
        let mut hooks = NoopActivationHooks;
        let (first_path, first) = fixture_result(&paths, &"a".repeat(40), "tx-a");
        engine
            .activate(
                first_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap();

        let store = StateStore::new(paths.clone());
        let custom_workspace = paths.user_data_root.join("workspace/custom");
        fs::create_dir_all(&custom_workspace).unwrap();
        let mut transaction = Transaction::begin(
            store.clone(),
            OperationKind::Update,
            Some(first.target_commit.clone()),
            Some("b".repeat(40)),
            Vec::new(),
        )
        .unwrap();
        transaction
            .set_pre_activation_state(store.load_installation().unwrap())
            .unwrap();
        transaction
            .set_workspace_path(custom_workspace.clone())
            .unwrap();
        transaction.mark_activation_started().unwrap();
        drop(transaction);

        let caller_config = fixture_config(&paths);
        engine
            .recover_with_health_checker(
                &caller_config,
                &WorkspaceHealthChecker {
                    expected: custom_workspace,
                },
            )
            .unwrap();
    }

    #[test]
    fn managed_candidate_path_rejects_traversal() {
        let root = tempdir().unwrap();
        let managed = root.path().join("build/candidates/commit");
        let escaped = root.path().join("build/candidates/commit/../outside");
        assert!(validate_activation_path(&managed, &escaped).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn managed_version_path_rejects_symlink_ancestor() {
        use std::os::unix::fs::symlink;

        let root = tempdir().unwrap();
        let managed = root.path().join("versions");
        let external = root.path().join("external");
        fs::create_dir_all(&managed).unwrap();
        fs::create_dir_all(&external).unwrap();
        symlink(&external, managed.join("alias")).unwrap();
        let escaped = managed.join("alias/version");
        assert!(validate_activation_path(&managed, &escaped).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn managed_roots_reject_symlink_ancestors_above_each_root() {
        use std::os::unix::fs::symlink;

        let root = tempdir().unwrap();
        let external = root.path().join("external");
        fs::create_dir_all(&external).unwrap();
        let link = root.path().join("managed-link");
        symlink(&external, &link).unwrap();

        for relative in [
            PathBuf::from("build/candidates/commit/tx/commit"),
            PathBuf::from("versions/commit"),
            PathBuf::from("user-data/backups/installer/tx"),
        ] {
            let managed_root = link.join(relative.parent().unwrap());
            let candidate = link.join(relative);
            assert!(validate_activation_path(&managed_root, &candidate).is_err());
        }
    }

    struct FixtureHealthChecker {
        healthy: bool,
        fail_commit: Option<String>,
    }

    struct DatabasePathHealthChecker {
        expected: PathBuf,
    }

    struct WorkspaceHealthChecker {
        expected: PathBuf,
    }

    impl HealthChecker for WorkspaceHealthChecker {
        fn check(
            &self,
            _version_dir: &Path,
            _metadata: &VersionMetadata,
            config: &ActivationConfig,
        ) -> Result<(), ActivationError> {
            if config.workspace == self.expected {
                Ok(())
            } else {
                Err(ActivationError::HealthCheck(format!(
                    "unexpected workspace: {}",
                    config.workspace.display()
                )))
            }
        }
    }

    struct CreatesDatabaseThenFails {
        failing_commit: String,
        database: PathBuf,
    }

    impl HealthChecker for CreatesDatabaseThenFails {
        fn check(
            &self,
            _version_dir: &Path,
            metadata: &VersionMetadata,
            _config: &ActivationConfig,
        ) -> Result<(), ActivationError> {
            if metadata.target_commit == self.failing_commit {
                fs::create_dir_all(self.database.parent().unwrap()).unwrap();
                fs::write(&self.database, b"created-by-failed-backend").unwrap();
                fs::write(
                    PathBuf::from(format!("{}-wal", self.database.display())),
                    b"wal",
                )
                .unwrap();
                fs::write(
                    PathBuf::from(format!("{}-shm", self.database.display())),
                    b"shm",
                )
                .unwrap();
                return Err(ActivationError::HealthCheck(
                    "failed backend created a database".to_owned(),
                ));
            }
            assert!(!self.database.exists());
            assert!(!PathBuf::from(format!("{}-wal", self.database.display())).exists());
            assert!(!PathBuf::from(format!("{}-shm", self.database.display())).exists());
            Ok(())
        }
    }

    impl HealthChecker for DatabasePathHealthChecker {
        fn check(
            &self,
            _version_dir: &Path,
            _metadata: &VersionMetadata,
            config: &ActivationConfig,
        ) -> Result<(), ActivationError> {
            if config.database_path == self.expected {
                Ok(())
            } else {
                Err(ActivationError::HealthCheck(format!(
                    "unexpected database path: {}",
                    config.database_path.display()
                )))
            }
        }
    }

    impl HealthChecker for FixtureHealthChecker {
        fn check(
            &self,
            _version_dir: &Path,
            metadata: &VersionMetadata,
            _config: &ActivationConfig,
        ) -> Result<(), ActivationError> {
            if self.healthy && self.fail_commit.as_deref() != Some(metadata.target_commit.as_str())
            {
                Ok(())
            } else {
                Err(ActivationError::HealthCheck(
                    "fixture backend is unhealthy".to_owned(),
                ))
            }
        }
    }

    fn fixture_result(
        paths: &InstallationPaths,
        commit: &str,
        transaction_id: &str,
    ) -> (PathBuf, BuildResult) {
        let checkout = paths
            .build_dir()
            .join("candidates")
            .join(commit)
            .join(transaction_id)
            .join(commit);
        let frontend = checkout.join("frontend/dist");
        let backend = checkout.join("target/harmonia-suite.jar");
        let desktop = checkout.join("apps/desktop/artifacts/linux-x64");
        fs::create_dir_all(&frontend).unwrap();
        fs::create_dir_all(backend.parent().unwrap()).unwrap();
        fs::create_dir_all(desktop.join("resources/app/dist")).unwrap();
        fs::write(frontend.join("index.html"), b"frontend").unwrap();
        fs::write(&backend, b"backend").unwrap();
        fs::write(desktop.join("electron"), b"electron").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(desktop.join("electron"), fs::Permissions::from_mode(0o755))
                .unwrap();
        }
        fs::write(desktop.join("resources/app/package.json"), b"{}\n").unwrap();
        fs::write(desktop.join("resources/app/dist/main.js"), b"main").unwrap();
        let frontend_hash = hash_directory(&frontend).unwrap();
        let backend_hash = sha256_file(&backend).unwrap();
        let desktop_hash = hash_directory(&desktop).unwrap();
        let checkout_dir = checkout
            .strip_prefix(&paths.app_root)
            .unwrap()
            .to_path_buf();
        let result = BuildResult {
            schema_version: BUILD_RESULT_SCHEMA_VERSION,
            status: BuildStatus::Completed,
            target_commit: commit.to_owned(),
            product_version: format!("0.1.{}", &commit[..4]),
            checkout_dir,
            toolchains: BTreeMap::from([
                ("jdk".to_owned(), "jdk-test".to_owned()),
                ("node".to_owned(), "node-test".to_owned()),
            ]),
            frontend: crate::build::BuildArtifact {
                path: PathBuf::from("frontend/dist"),
                sha256: frontend_hash,
            },
            backend: crate::build::BuildArtifact {
                path: PathBuf::from("target/harmonia-suite.jar"),
                sha256: backend_hash,
            },
            desktop: crate::build::BuildArtifact {
                path: PathBuf::from("apps/desktop/artifacts/linux-x64"),
                sha256: desktop_hash,
            },
            started_at_ms: 1,
            finished_at_ms: 2,
            duration_ms: 1,
        };
        let result_path = paths
            .build_results_dir()
            .join(format!("{transaction_id}.json"));
        fs::create_dir_all(result_path.parent().unwrap()).unwrap();
        fs::write(&result_path, serde_json::to_vec_pretty(&result).unwrap()).unwrap();
        (result_path, result)
    }

    fn fixture_config(paths: &InstallationPaths) -> ActivationConfig {
        let java = paths.app_root.join("toolchain/jdk/bin/java");
        fs::create_dir_all(java.parent().unwrap()).unwrap();
        fs::write(&java, b"managed-java").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&java, fs::Permissions::from_mode(0o755)).unwrap();
        }
        let mut state = ToolchainState::default();
        state.records.insert(
            "jdk-test".to_owned(),
            ToolchainRecord {
                id: "jdk-test".to_owned(),
                kind: ToolchainKind::Jdk,
                version: "test".to_owned(),
                platform: paths.platform,
                architecture: paths.architecture.clone(),
                url: "https://example.invalid/jdk-test.tar.gz".to_owned(),
                sha256: "a".repeat(64),
                archive: ArchiveFormat::TarGz,
                home_dir: PathBuf::from("."),
                install_dir: PathBuf::from("toolchain/jdk"),
                executables: BTreeMap::from([(String::from("java"), PathBuf::from("bin/java"))]),
                installed_at_ms: 1,
            },
        );
        ToolchainStateStore::new(paths.clone())
            .save(&state)
            .unwrap();
        ActivationConfig::for_paths(paths, java)
    }

    #[test]
    fn successful_activation_publishes_immutable_runtime_and_updates_pointers() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let (result_path, result) = fixture_result(&paths, &"a".repeat(40), "tx-a");
        let engine = ActivationEngine::new(paths.clone());
        let mut hooks = NoopActivationHooks;
        let runtime = engine
            .activate(
                result_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap();
        let state = StateStore::new(paths.clone()).load_installation().unwrap();
        assert_eq!(state.current_commit, Some(result.target_commit.clone()));
        assert_eq!(state.previous_commit, None);
        assert_eq!(state.staged_commit, None);
        assert_eq!(state.pending_commit, None);
        assert_eq!(
            runtime.backend_jar,
            paths
                .versions_dir()
                .join(&result.target_commit)
                .join("backend/harmonia-suite.jar")
        );
        assert!(runtime.desktop_executable.is_file());
        assert!(!runtime.version_dir.join("frontend").exists());
        assert!(!runtime.version_dir.starts_with(&paths.user_data_root));
        assert!(!runtime.version_dir.join("source").exists());
    }

    #[test]
    fn build_result_jdk_must_match_supplied_managed_java() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let (result_path, mut result) = fixture_result(&paths, &"a".repeat(40), "tx-a");
        let mut config = fixture_config(&paths);
        let state_store = ToolchainStateStore::new(paths.clone());
        let mut state = state_store.load().unwrap();
        let template = state.records["jdk-test"].clone();
        for (id, relative) in [("jdk-a", "toolchain/jdk-a"), ("jdk-b", "toolchain/jdk-b")] {
            let java = paths.app_root.join(relative).join("bin/java");
            fs::create_dir_all(java.parent().unwrap()).unwrap();
            fs::write(&java, b"managed-java").unwrap();
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                fs::set_permissions(&java, fs::Permissions::from_mode(0o755)).unwrap();
            }
            let mut record = template.clone();
            record.id = id.to_owned();
            record.install_dir = PathBuf::from(relative);
            state.records.insert(id.to_owned(), record);
        }
        state_store.save(&state).unwrap();
        result
            .toolchains
            .insert("jdk".to_owned(), "jdk-b".to_owned());
        fs::write(&result_path, serde_json::to_vec_pretty(&result).unwrap()).unwrap();
        config.java_binary = paths.app_root.join("toolchain/jdk-a/bin/java");
        let mut hooks = NoopActivationHooks;
        let error = ActivationEngine::new(paths.clone())
            .activate(
                result_path,
                &config,
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap_err();
        assert!(
            matches!(error, ActivationError::InvalidInput(message) if message.contains("exactly match"))
        );
    }

    #[test]
    fn missing_build_result_jdk_fails_closed() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let (result_path, mut result) = fixture_result(&paths, &"a".repeat(40), "tx-a");
        let _config = fixture_config(&paths);
        result.toolchains.remove("jdk");
        fs::write(&result_path, serde_json::to_vec_pretty(&result).unwrap()).unwrap();
        let mut hooks = NoopActivationHooks;
        let error = ActivationEngine::new(paths.clone())
            .activate(
                result_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap_err();
        assert!(
            matches!(error, ActivationError::InvalidInput(message) if message.contains("missing jdk"))
        );
    }

    #[cfg(unix)]
    #[test]
    fn resolve_version_rejects_desktop_runtime_without_execute_bit() {
        use std::os::unix::fs::PermissionsExt;

        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let (result_path, result) = fixture_result(&paths, &"a".repeat(40), "tx-a");
        let engine = ActivationEngine::new(paths.clone());
        let mut hooks = NoopActivationHooks;
        engine
            .activate(
                result_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap();
        let runtime = engine.resolve_version(&result.target_commit).unwrap();
        fs::set_permissions(
            &runtime.desktop_executable,
            fs::Permissions::from_mode(0o644),
        )
        .unwrap();
        assert!(engine.resolve_version(&result.target_commit).is_err());
    }

    #[test]
    fn failed_health_check_rolls_back_and_keeps_failed_version_for_diagnostics() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let (first_path, first) = fixture_result(&paths, &"a".repeat(40), "tx-a");
        let engine = ActivationEngine::new(paths.clone());
        let mut hooks = NoopActivationHooks;
        engine
            .activate(
                first_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap();
        let (second_path, second) = fixture_result(&paths, &"b".repeat(40), "tx-b");
        let error = engine
            .activate(
                second_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: Some(second.target_commit.clone()),
                },
            )
            .unwrap_err();
        assert!(matches!(error, ActivationError::HealthCheck(_)));
        let state = StateStore::new(paths.clone()).load_installation().unwrap();
        assert_eq!(state.current_commit, Some(first.target_commit));
        assert_eq!(state.staged_commit, None);
        assert_eq!(state.pending_commit, None);
        assert!(paths.versions_dir().join(second.target_commit).is_dir());
        assert_eq!(
            engine
                .resolve_current()
                .unwrap()
                .unwrap()
                .metadata
                .target_commit,
            "a".repeat(40)
        );
    }

    #[test]
    fn absent_database_pre_state_is_restored_before_previous_health_check() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let (first_path, first) = fixture_result(&paths, &"a".repeat(40), "tx-a");
        let engine = ActivationEngine::new(paths.clone());
        let mut hooks = NoopActivationHooks;
        engine
            .activate(
                first_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap();

        let (second_path, second) = fixture_result(&paths, &"b".repeat(40), "tx-b");
        let database = paths.user_data_root.join("data/harmonia.db");
        let error = engine
            .activate(
                second_path,
                &fixture_config(&paths),
                &mut hooks,
                &CreatesDatabaseThenFails {
                    failing_commit: second.target_commit.clone(),
                    database: database.clone(),
                },
            )
            .unwrap_err();
        assert!(matches!(error, ActivationError::HealthCheck(_)));
        assert_eq!(
            StateStore::new(paths.clone())
                .load_installation()
                .unwrap()
                .current_commit,
            Some(first.target_commit)
        );
        assert!(!database.exists());
        assert!(!PathBuf::from(format!("{}-wal", database.display())).exists());
        assert!(!PathBuf::from(format!("{}-shm", database.display())).exists());
    }

    #[test]
    fn rollback_failure_is_durable_and_blocks_next_activation() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let engine = ActivationEngine::new(paths.clone());
        let mut hooks = NoopActivationHooks;
        let (first_path, first) = fixture_result(&paths, &"a".repeat(40), "tx-a");
        engine
            .activate(
                first_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap();
        let (second_path, second) = fixture_result(&paths, &"b".repeat(40), "tx-b");
        let error = engine
            .activate(
                second_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: false,
                    fail_commit: None,
                },
            )
            .unwrap_err();
        assert!(matches!(error, ActivationError::ReviewRequired(_)));
        assert_eq!(
            StateStore::new(paths.clone())
                .load_transaction()
                .unwrap()
                .unwrap()
                .status,
            TransactionStatus::ReviewRequired
        );

        let (third_path, _) = fixture_result(&paths, &"c".repeat(40), "tx-c");
        let blocked = engine.activate(
            third_path,
            &fixture_config(&paths),
            &mut hooks,
            &FixtureHealthChecker {
                healthy: true,
                fail_commit: None,
            },
        );
        assert!(matches!(blocked, Err(ActivationError::ReviewRequired(_))));
        assert_eq!(
            StateStore::new(paths)
                .load_installation()
                .unwrap()
                .current_commit,
            Some(first.target_commit)
        );
        assert_eq!(second.target_commit, "b".repeat(40));
    }

    #[test]
    fn rollback_restores_exact_pre_activation_state_snapshot() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let engine = ActivationEngine::new(paths.clone());
        let mut hooks = NoopActivationHooks;
        let (p_path, p) = fixture_result(&paths, &"d".repeat(40), "tx-p");
        engine
            .activate(
                p_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap();
        let (a_path, a) = fixture_result(&paths, &"a".repeat(40), "tx-a");
        engine
            .activate(
                a_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap();
        let store = StateStore::new(paths.clone());
        let before = store.load_installation().unwrap();
        assert_eq!(before.current_commit, Some(a.target_commit.clone()));
        assert_eq!(before.previous_commit, Some(p.target_commit));

        let (b_path, b) = fixture_result(&paths, &"b".repeat(40), "tx-b");
        let error = engine
            .activate(
                b_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: Some(b.target_commit.clone()),
                },
            )
            .unwrap_err();
        assert!(matches!(error, ActivationError::HealthCheck(_)));
        assert_eq!(store.load_installation().unwrap(), before);
    }

    #[test]
    fn recovery_with_missing_target_persists_review_required_and_blocks_activation() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let engine = ActivationEngine::new(paths.clone());
        let config = fixture_config(&paths);
        let store = StateStore::new(paths.clone());
        let mut transaction =
            Transaction::begin(store.clone(), OperationKind::Update, None, None, Vec::new())
                .unwrap();
        transaction.mark_activation_started().unwrap();
        drop(transaction);

        let error = engine
            .recover_with_health_checker(
                &config,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap_err();
        assert!(matches!(error, ActivationError::ReviewRequired(_)));
        assert_eq!(
            store.load_transaction().unwrap().unwrap().status,
            TransactionStatus::ReviewRequired
        );

        let (result_path, _) = fixture_result(&paths, &"a".repeat(40), "tx-a");
        let mut hooks = NoopActivationHooks;
        assert!(matches!(
            engine.activate(
                result_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            ),
            Err(ActivationError::ReviewRequired(_))
        ));
    }

    #[cfg(unix)]
    #[test]
    fn recovery_cleanup_failure_is_persisted_as_review_required() {
        use std::os::unix::fs::symlink;

        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let engine = ActivationEngine::new(paths.clone());
        let config = fixture_config(&paths);
        let outside = root.path().join("outside");
        fs::create_dir_all(&outside).unwrap();
        let link = paths.build_dir().join("external");
        fs::create_dir_all(link.parent().unwrap()).unwrap();
        symlink(&outside, &link).unwrap();
        let owned = link.join("partial");
        let store = StateStore::new(paths.clone());
        let mut transaction = Transaction::begin(
            store.clone(),
            OperationKind::Update,
            None,
            Some("a".repeat(40)),
            vec![owned],
        )
        .unwrap();
        transaction.transition(TransactionPhase::Staging).unwrap();
        drop(transaction);

        let error = engine
            .recover_with_health_checker(
                &config,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap_err();
        assert!(matches!(error, ActivationError::ReviewRequired(_)));
        assert_eq!(
            store.load_transaction().unwrap().unwrap().status,
            TransactionStatus::ReviewRequired
        );
    }

    #[test]
    fn tampered_candidate_is_rejected_before_staging() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let (result_path, result) = fixture_result(&paths, &"c".repeat(40), "tx-c");
        fs::write(
            paths
                .app_root
                .join(&result.checkout_dir)
                .join("target/harmonia-suite.jar"),
            b"tampered",
        )
        .unwrap();
        let engine = ActivationEngine::new(paths.clone());
        let mut hooks = NoopActivationHooks;
        assert!(matches!(
            engine.activate(
                result_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None
                },
            ),
            Err(ActivationError::ArtifactVerification(_))
        ));
        assert!(!paths.versions_dir().join(result.target_commit).exists());
    }

    #[test]
    fn recovery_cleans_pre_switch_staging_and_rolls_back_post_switch_state() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        let engine = ActivationEngine::new(paths.clone());
        let store = StateStore::new(paths.clone());
        let staging = paths.versions_dir().join("staging-test");
        fs::create_dir_all(&staging).unwrap();
        fs::write(staging.join("partial"), b"partial").unwrap();
        let mut transaction = Transaction::begin(
            store.clone(),
            OperationKind::Update,
            None,
            Some("d".repeat(40)),
            Vec::new(),
        )
        .unwrap();
        transaction.own_path(&staging).unwrap();
        drop(transaction);
        engine.recover().unwrap();
        assert!(!staging.exists());
        assert_eq!(
            store.load_transaction().unwrap().unwrap().status,
            TransactionStatus::Failed
        );

        let (first_path, first) = fixture_result(&paths, &"e".repeat(40), "tx-e");
        let mut hooks = NoopActivationHooks;
        engine
            .activate(
                first_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap();
        let (_, second) = fixture_result(&paths, &"f".repeat(40), "tx-f");
        let candidate = paths.app_root.join(&second.checkout_dir);
        let mut crashed = Transaction::begin(
            store.clone(),
            OperationKind::Update,
            Some(first.target_commit.clone()),
            Some(second.target_commit.clone()),
            Vec::new(),
        )
        .unwrap();
        crashed
            .set_pre_activation_state(store.load_installation().unwrap())
            .unwrap();
        crashed
            .set_database_path(fixture_config(&paths).database_path)
            .unwrap();
        crashed.transition(TransactionPhase::Staging).unwrap();
        engine
            .stage_version(&second, &candidate, &fixture_config(&paths), &mut crashed)
            .unwrap();
        crashed
            .set_published_version(second.target_commit.clone())
            .unwrap();
        crashed
            .transition(TransactionPhase::WaitingForShutdown)
            .unwrap();
        crashed.transition(TransactionPhase::Activating).unwrap();
        let mut switched = store.load_installation().unwrap();
        switched.current_commit = Some(second.target_commit.clone());
        store.save_installation(&switched).unwrap();
        crashed.mark_current_switched().unwrap();
        drop(crashed);
        engine
            .recover_with_health_checker(
                &fixture_config(&paths),
                &FixtureHealthChecker {
                    healthy: true,
                    fail_commit: None,
                },
            )
            .unwrap();
        let recovered = store.load_installation().unwrap();
        assert_eq!(recovered.current_commit, Some(first.target_commit));
        assert!(paths.versions_dir().join(second.target_commit).is_dir());
    }
}
