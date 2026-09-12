use std::collections::BTreeMap;
use std::ffi::OsString;
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
    InstallationState, OperationKind, StateError, StateStore, Transaction, TransactionPhase,
    TransactionStatus,
};

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
        self.recover_locked()?;
        let result = self.load_build_result(result_path.as_ref())?;
        let candidate = self.validate_candidate(&result)?;
        let store = StateStore::new(self.paths.clone());
        let installation = store.load_installation()?;
        let mut transaction = Transaction::begin(
            store.clone(),
            OperationKind::Update,
            installation.current_commit.clone(),
            Some(result.target_commit.clone()),
            Vec::new(),
        )?;
        let activation = self.activate_transaction(
            &result,
            &candidate,
            config,
            hooks,
            checker,
            &mut transaction,
        );
        match activation {
            Ok(runtime) => Ok(runtime),
            Err(error) => {
                if transaction.record().activation_started
                    && !transaction.record().rollback_completed
                {
                    if let Err(rollback_error) =
                        self.rollback_transaction(&mut transaction, config, &installation)
                    {
                        let reason = format!(
                            "{error}; rollback failed and requires review: {rollback_error}"
                        );
                        let _ = transaction.fail(reason.clone());
                        return Err(ActivationError::ReviewRequired(reason));
                    }
                } else {
                    let _ = transaction.cleanup_owned_paths();
                    let _ =
                        self.clear_pending_version(transaction.record().target_commit.as_deref());
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
        let final_dir = self.stage_version(result, candidate, transaction)?;
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
            transaction.set_db_backup_path(snapshot.directory)?;
        }

        transaction.transition(TransactionPhase::Activating)?;
        let installation = StateStore::new(self.paths.clone()).load_installation()?;
        self.switch_current(&installation, result, &final_dir)?;
        transaction.mark_current_switched()?;

        transaction.transition(TransactionPhase::HealthChecking)?;
        transaction.mark_process_started()?;
        if let Err(error) = checker.check(
            &final_dir,
            &load_version_metadata(&final_dir.join("metadata.json"))?,
            config,
        ) {
            transaction.transition(TransactionPhase::RollingBack)?;
            self.rollback_transaction(transaction, config, &installation)?;
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
        transaction: &mut Transaction,
    ) -> Result<PathBuf, ActivationError> {
        let final_dir = self.paths.versions_dir().join(&result.target_commit);
        let staging_dir = self.paths.versions_dir().join(format!(
            "{}.staging.{}",
            result.target_commit,
            transaction.record().id
        ));
        validate_owned_path(&self.paths.versions_dir(), &final_dir)?;
        validate_owned_path(&self.paths.versions_dir(), &staging_dir)?;
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
            },
        };
        crate::state::atomic_write_json(&staging_dir.join("metadata.json"), &metadata)?;
        sync_directory(&staging_dir)?;
        if let Some(parent) = final_dir.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::rename(&staging_dir, &final_dir)?;
        sync_directory(&self.paths.versions_dir())?;
        Ok(final_dir)
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
        _config: &ActivationConfig,
        installation_before: &InstallationState,
    ) -> Result<(), ActivationError> {
        let store = StateStore::new(self.paths.clone());
        let mut current = store.load_installation()?;
        let old_commit = transaction
            .record()
            .current_commit
            .clone()
            .or_else(|| installation_before.current_commit.clone());
        if let Some(backup) = transaction.record().db_backup_path.clone() {
            DatabaseSnapshot::restore(&backup, &self.paths.user_data_root)?;
        }
        match old_commit {
            Some(commit) => {
                let runtime = self.resolve_version(&commit)?;
                current.current_commit = Some(commit);
                current.product_version = Some(runtime.metadata.product_version.clone());
                current.current_toolchains = runtime.metadata.toolchains.clone();
                current.components = runtime
                    .metadata
                    .component_paths
                    .iter()
                    .map(|(name, component)| (name.clone(), component.sha256.clone()))
                    .collect();
            }
            None => {
                current.current_commit = None;
                current.product_version = None;
                current.current_toolchains.clear();
                current.components.clear();
            }
        }
        current.previous_commit = None;
        current.previous_toolchains.clear();
        current.staged_commit = None;
        current.pending_commit = None;
        current.activation_at_ms = Some(now_ms());
        store.save_installation(&current)?;
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
        self.recover_locked()
    }

    fn recover_locked(&self) -> Result<(), ActivationError> {
        let store = StateStore::new(self.paths.clone());
        let Some(record) = store.load_transaction()? else {
            return Ok(());
        };
        if record.status != TransactionStatus::Running {
            return Ok(());
        }
        let mut transaction = Transaction::resume_existing(store.clone(), record.clone());
        if !record.activation_started {
            transaction.cleanup_owned_paths()?;
            self.clear_pending_version(record.target_commit.as_deref())?;
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
                let _ = transaction.fail(reason.clone());
                return Err(ActivationError::ReviewRequired(reason));
            }
        };
        let installation = store.load_installation()?;
        if installation.current_commit.as_deref() == Some(target.as_str())
            && record.health_check_passed
        {
            self.clear_pending_version(Some(&target))?;
            transaction.complete()?;
            return Ok(());
        }
        if record.db_backup_required && record.db_backup_path.is_none() && record.current_switched {
            let reason = "activation crossed pointer switch without a DB backup".to_owned();
            let _ = transaction.fail(reason.clone());
            return Err(ActivationError::ReviewRequired(reason));
        }
        let config = ActivationConfig::for_paths(&self.paths, PathBuf::new());
        if let Err(error) = self.rollback_transaction(&mut transaction, &config, &installation) {
            let reason = format!("recovery rollback failed: {error}");
            let _ = transaction.fail(reason.clone());
            return Err(ActivationError::ReviewRequired(reason));
        }
        transaction.fail("recovered activation by rolling back to previous version")?;
        Ok(())
    }

    fn load_build_result(&self, path: &Path) -> Result<BuildResult, ActivationError> {
        let path = absolute_path(path)?;
        validate_owned_path(&self.paths.build_results_dir(), &path)?;
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
        validate_owned_path(&candidate_root, &checkout)?;
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
        validate_owned_path(&self.paths.versions_dir(), version_dir)?;
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
        validate_owned_path(&self.paths.versions_dir(), &version_dir)?;
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
            && version_dir.join(&metadata.runtime.backend_jar).is_file()
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
    files: Vec<PathBuf>,
}

struct DatabaseSnapshot;

impl DatabaseSnapshot {
    fn create(
        user_data_root: &Path,
        database_path: &Path,
        transaction_id: &str,
    ) -> Result<Option<SnapshotInfo>, ActivationError> {
        if !database_path.exists() {
            return Ok(None);
        }
        validate_owned_path(user_data_root, database_path)?;
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
        fs::create_dir_all(&directory)?;
        let mut files = Vec::new();
        for suffix in ["", "-wal", "-shm"] {
            let source = if suffix.is_empty() {
                database_path.to_path_buf()
            } else {
                PathBuf::from(format!("{}{}", database_path.display(), suffix))
            };
            if source.is_file() {
                let name = source.file_name().ok_or_else(|| {
                    io::Error::new(io::ErrorKind::InvalidInput, "database filename")
                })?;
                let destination = directory.join(name);
                copy_regular_file(&source, &destination)?;
                files.push(PathBuf::from(name));
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
        validate_owned_path(user_data_root, directory)?;
        let manifest: DatabaseManifest =
            serde_json::from_slice(&fs::read(directory.join("manifest.json"))?)?;
        if !safe_relative(&manifest.database_relative) {
            return Err(ActivationError::ReviewRequired(
                "database backup manifest contains unsafe database path".to_owned(),
            ));
        }
        let database_path = user_data_root.join(&manifest.database_relative);
        validate_owned_path(user_data_root, &database_path)?;
        let database_parent = database_path.parent().ok_or_else(|| {
            ActivationError::ReviewRequired("database backup path has no parent".to_owned())
        })?;
        fs::create_dir_all(database_parent)?;
        let database_name = database_path.file_name().ok_or_else(|| {
            ActivationError::ReviewRequired("database backup path has no filename".to_owned())
        })?;
        let allowed_names = [
            database_name.to_os_string(),
            OsString::from(format!("{}-wal", database_name.to_string_lossy())),
            OsString::from(format!("{}-shm", database_name.to_string_lossy())),
        ];
        for file in &manifest.files {
            if !safe_relative(file)
                || file.components().count() != 1
                || !allowed_names
                    .iter()
                    .any(|allowed| file == Path::new(allowed))
            {
                return Err(ActivationError::ReviewRequired(
                    "database backup manifest contains unsafe path".to_owned(),
                ));
            }
            let destination = database_parent.join(file);
            validate_owned_path(user_data_root, &destination)?;
            copy_regular_file(&directory.join(file), &destination)?;
        }
        for suffix in ["-wal", "-shm"] {
            let name = format!("{}{suffix}", database_name.to_string_lossy());
            if !manifest.files.iter().any(|file| file == Path::new(&name)) {
                let sidecar = database_parent.join(&name);
                validate_owned_path(user_data_root, &sidecar)?;
                match fs::remove_file(sidecar) {
                    Ok(()) => {}
                    Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                    Err(error) => return Err(error.into()),
                }
            }
        }
        sync_directory(database_parent)?;
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
    let file = OpenOptions::new().read(true).open(destination)?;
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

fn absolute_path(path: &Path) -> Result<PathBuf, ActivationError> {
    if path.is_absolute() {
        Ok(path.to_path_buf())
    } else {
        Ok(std::env::current_dir()?.join(path))
    }
}

fn validate_owned_path(root: &Path, path: &Path) -> Result<(), ActivationError> {
    let root = absolute_path(root)?;
    let path = absolute_path(path)?;
    if !path.starts_with(&root) || has_link_ancestor(&root, &path)? {
        return Err(ActivationError::InvalidCandidate(path));
    }
    Ok(())
}

fn has_link_ancestor(root: &Path, path: &Path) -> io::Result<bool> {
    let relative = path
        .strip_prefix(root)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "path outside root"))?;
    if !root.is_absolute() || !path.is_absolute() {
        return Ok(true);
    }
    for ancestor in path.ancestors() {
        match fs::symlink_metadata(ancestor) {
            Ok(_) if is_link_or_reparse(ancestor)? => return Ok(true),
            Ok(_) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }
    }
    let mut current = root.to_path_buf();
    for component in relative.components() {
        if !matches!(component, Component::Normal(_)) {
            return Ok(true);
        }
        current.push(component.as_os_str());
        match fs::symlink_metadata(&current) {
            Ok(_) if is_link_or_reparse(&current)? => return Ok(true),
            Ok(_) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => break,
            Err(error) => return Err(error),
        }
    }
    Ok(false)
}

fn is_link_or_reparse(path: &Path) -> io::Result<bool> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() {
        return Ok(true);
    }
    is_reparse_point(path)
}

#[cfg(not(windows))]
fn is_reparse_point(_path: &Path) -> io::Result<bool> {
    Ok(false)
}

#[cfg(windows)]
fn is_reparse_point(path: &Path) -> io::Result<bool> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{
        GetFileAttributesW, FILE_ATTRIBUTE_REPARSE_POINT, INVALID_FILE_ATTRIBUTES,
    };
    let wide: Vec<u16> = path
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let attributes = unsafe { GetFileAttributesW(wide.as_ptr()) };
    if attributes == INVALID_FILE_ATTRIBUTES {
        return Err(io::Error::last_os_error());
    }
    Ok(attributes & FILE_ATTRIBUTE_REPARSE_POINT != 0)
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
    fn managed_candidate_path_rejects_traversal() {
        let root = tempdir().unwrap();
        let managed = root.path().join("build/candidates/commit");
        let escaped = root.path().join("build/candidates/commit/../outside");
        assert!(validate_owned_path(&managed, &escaped).is_err());
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
        assert!(validate_owned_path(&managed, &escaped).is_err());
    }

    struct FixtureHealthChecker {
        healthy: bool,
    }

    impl HealthChecker for FixtureHealthChecker {
        fn check(
            &self,
            _version_dir: &Path,
            _metadata: &VersionMetadata,
            _config: &ActivationConfig,
        ) -> Result<(), ActivationError> {
            if self.healthy {
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
        ActivationConfig::for_paths(paths, paths.app_root.join("toolchain/jdk/bin/java"))
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
                &FixtureHealthChecker { healthy: true },
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
                &FixtureHealthChecker { healthy: true },
            )
            .unwrap();
        let (second_path, second) = fixture_result(&paths, &"b".repeat(40), "tx-b");
        let error = engine
            .activate(
                second_path,
                &fixture_config(&paths),
                &mut hooks,
                &FixtureHealthChecker { healthy: false },
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
                &FixtureHealthChecker { healthy: true },
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
                &FixtureHealthChecker { healthy: true },
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
        crashed.transition(TransactionPhase::Staging).unwrap();
        engine
            .stage_version(&second, &candidate, &mut crashed)
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
        engine.recover().unwrap();
        let recovered = store.load_installation().unwrap();
        assert_eq!(recovered.current_commit, Some(first.target_commit));
        assert!(paths.versions_dir().join(second.target_commit).is_dir());
    }
}
