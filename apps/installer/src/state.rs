use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Component, Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use uuid::Uuid;

use crate::diagnostics::{DiagnosticError, DiagnosticLogger};
use crate::paths::InstallationPaths;

#[cfg(not(windows))]
use std::fs::File;

const STATE_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Error)]
pub enum StateError {
    #[error("installation state I/O failed: {0}")]
    Io(#[from] io::Error),
    #[error("installation state JSON failed: {0}")]
    Json(#[from] serde_json::Error),
    #[error("installation diagnostics failed: {0}")]
    Diagnostics(#[from] DiagnosticError),
    #[error("unsupported installation state schema: {0}")]
    UnsupportedSchema(u64),
    #[error("transaction schema_version is missing or invalid")]
    InvalidTransactionSchema,
    #[error("invalid transaction transition from {from:?} to {to:?}")]
    InvalidTransition {
        from: TransactionPhase,
        to: TransactionPhase,
    },
    #[error("recovery refused to remove unowned or unsafe path: {0}")]
    UnsafeRecoveryPath(PathBuf),
    #[error("pre-activation recovery cleanup failed for transaction {transaction_id}: {reason}")]
    RecoveryCleanupFailed {
        transaction_id: String,
        reason: String,
    },
    #[error("transaction {0} is still running")]
    ActiveTransaction(String),
    #[error("transaction {0} requires explicit review before another operation")]
    ReviewRequiredTransaction(String),
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum OperationKind {
    Install,
    Repair,
    Update,
    Reinstall,
    Rollback,
    Uninstall,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum TransactionPhase {
    Idle,
    ResolvingTarget,
    PreparingToolchain,
    FetchingSource,
    PreparingWorktree,
    BuildingFrontend,
    BuildingBackend,
    BuildingDesktop,
    Verifying,
    Staging,
    WaitingForShutdown,
    Activating,
    HealthChecking,
    RollingBack,
    Completed,
    Failed,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum TransactionStatus {
    Running,
    Completed,
    Failed,
    ReviewRequired,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub enum DatabasePreState {
    Absent,
    Snapshot(PathBuf),
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct InstallationState {
    pub schema_version: u32,
    pub product_version: Option<String>,
    pub current_commit: Option<String>,
    pub previous_commit: Option<String>,
    pub channel: String,
    pub platform: String,
    pub arch: String,
    pub components: BTreeMap<String, String>,
    #[serde(default)]
    pub current_toolchains: BTreeMap<String, String>,
    #[serde(default)]
    pub previous_toolchains: BTreeMap<String, String>,
    #[serde(default)]
    pub staged_commit: Option<String>,
    #[serde(default)]
    pub pending_commit: Option<String>,
    #[serde(default)]
    pub activation_at_ms: Option<u128>,
}

impl InstallationState {
    pub fn for_paths(paths: &InstallationPaths) -> Self {
        Self {
            schema_version: STATE_SCHEMA_VERSION,
            product_version: None,
            current_commit: None,
            previous_commit: None,
            channel: "rolling-main".to_owned(),
            platform: paths.platform.as_str().to_owned(),
            arch: paths.architecture.as_str().to_owned(),
            components: BTreeMap::new(),
            current_toolchains: BTreeMap::new(),
            previous_toolchains: BTreeMap::new(),
            staged_commit: None,
            pending_commit: None,
            activation_at_ms: None,
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct TransactionRecord {
    pub schema_version: u32,
    pub id: String,
    pub operation: OperationKind,
    pub phase: TransactionPhase,
    pub status: TransactionStatus,
    pub started_at_ms: u128,
    pub finished_at_ms: Option<u128>,
    pub current_commit: Option<String>,
    pub target_commit: Option<String>,
    pub owned_paths: Vec<PathBuf>,
    pub activation_started: bool,
    pub failure: Option<String>,
    #[serde(default)]
    pub toolchain_refs: BTreeMap<String, String>,
    #[serde(default)]
    pub published_version: Option<String>,
    #[serde(default)]
    pub current_switched: bool,
    #[serde(default)]
    pub new_process_started: bool,
    #[serde(default)]
    pub health_check_passed: bool,
    #[serde(default)]
    pub db_backup_path: Option<PathBuf>,
    #[serde(default)]
    pub db_backup_required: bool,
    #[serde(default)]
    pub rollback_completed: bool,
    #[serde(default)]
    pub database_path: Option<PathBuf>,
    #[serde(default)]
    pub workspace_path: Option<PathBuf>,
    #[serde(default)]
    pub database_pre_state: Option<DatabasePreState>,
    #[serde(default)]
    pub pre_activation_state: Option<InstallationState>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RecoveryAction {
    None,
    Resume {
        transaction_id: String,
        phase: TransactionPhase,
    },
    ReviewRequired {
        transaction_id: String,
        phase: TransactionPhase,
    },
}

#[derive(Clone, Debug)]
pub struct StateStore {
    paths: InstallationPaths,
    logger: Option<DiagnosticLogger>,
}

impl StateStore {
    pub fn new(paths: InstallationPaths) -> Self {
        Self {
            paths,
            logger: None,
        }
    }

    pub fn with_logger(mut self, logger: DiagnosticLogger) -> Self {
        self.logger = Some(logger);
        self
    }

    pub fn paths(&self) -> &InstallationPaths {
        &self.paths
    }

    pub fn initialize(&self) -> Result<(), StateError> {
        for path in self.paths.managed_paths() {
            fs::create_dir_all(path)?;
        }
        fs::create_dir_all(self.paths.diagnostics_dir())?;
        Ok(())
    }

    pub fn load_installation(&self) -> Result<InstallationState, StateError> {
        let path = self.paths.install_state_path();
        if !path.is_file() {
            return Ok(InstallationState::for_paths(&self.paths));
        }
        let state: InstallationState = serde_json::from_slice(&fs::read(path)?)?;
        if state.schema_version != STATE_SCHEMA_VERSION {
            return Err(StateError::UnsupportedSchema(state.schema_version as u64));
        }
        Ok(state)
    }

    pub fn save_installation(&self, state: &InstallationState) -> Result<(), StateError> {
        if state.schema_version != STATE_SCHEMA_VERSION {
            return Err(StateError::UnsupportedSchema(state.schema_version as u64));
        }
        self.initialize()?;
        atomic_write_json(&self.paths.install_state_path(), state)
    }

    pub fn load_transaction(&self) -> Result<Option<TransactionRecord>, StateError> {
        let path = self.paths.transaction_path();
        if !path.is_file() {
            return Ok(None);
        }
        let document: serde_json::Value = serde_json::from_slice(&fs::read(path)?)?;
        let schema_version = document
            .get("schema_version")
            .and_then(serde_json::Value::as_u64)
            .ok_or(StateError::InvalidTransactionSchema)?;
        if schema_version != STATE_SCHEMA_VERSION as u64 {
            return Err(StateError::UnsupportedSchema(schema_version));
        }
        let transaction: TransactionRecord = serde_json::from_value(document)?;
        Ok(Some(transaction))
    }

    pub fn protected_toolchain_ids(&self) -> Result<BTreeSet<String>, StateError> {
        let installation = self.load_installation()?;
        let mut protected = installation
            .current_toolchains
            .values()
            .chain(installation.previous_toolchains.values())
            .cloned()
            .collect::<BTreeSet<_>>();
        if let Some(transaction) = self.load_transaction()? {
            if matches!(
                transaction.status,
                TransactionStatus::Running | TransactionStatus::ReviewRequired
            ) {
                protected.extend(transaction.toolchain_refs.values().cloned());
                if let Some(pre_activation) = transaction.pre_activation_state {
                    protected.extend(pre_activation.current_toolchains.values().cloned());
                    protected.extend(pre_activation.previous_toolchains.values().cloned());
                }
            }
        }
        Ok(protected)
    }

    pub fn recovery_action(&self) -> Result<RecoveryAction, StateError> {
        let Some(transaction) = self.load_transaction()? else {
            return Ok(RecoveryAction::None);
        };
        if transaction.status == TransactionStatus::ReviewRequired {
            return Ok(RecoveryAction::ReviewRequired {
                transaction_id: transaction.id,
                phase: transaction.phase,
            });
        }
        if transaction.status != TransactionStatus::Running
            || matches!(
                transaction.phase,
                TransactionPhase::Completed | TransactionPhase::Failed
            )
        {
            return Ok(RecoveryAction::None);
        }
        if transaction.activation_started
            || matches!(
                transaction.phase,
                TransactionPhase::Activating
                    | TransactionPhase::HealthChecking
                    | TransactionPhase::RollingBack
            )
        {
            return Ok(RecoveryAction::ReviewRequired {
                transaction_id: transaction.id,
                phase: transaction.phase,
            });
        }
        Ok(RecoveryAction::Resume {
            transaction_id: transaction.id,
            phase: transaction.phase,
        })
    }

    /// Finish a transaction left running by a process crash before activation.
    ///
    /// Only exact paths journaled by the transaction are considered. A cleanup error is
    /// persisted as a failed transaction before being returned, so a stale Running record
    /// cannot permanently prevent the next operation. Activation transactions remain blocked
    /// for explicit review because their ownership may have crossed the activation boundary.
    pub fn recover_pre_activation(&self) -> Result<(), StateError> {
        let Some(mut transaction) = self.load_transaction()? else {
            return Ok(());
        };
        if transaction.status == TransactionStatus::ReviewRequired {
            return Err(StateError::ReviewRequiredTransaction(transaction.id));
        }
        if transaction.status != TransactionStatus::Running
            || transaction.phase == TransactionPhase::Completed
            || transaction.phase == TransactionPhase::Failed
        {
            return Ok(());
        }
        if transaction.activation_started
            || matches!(
                transaction.phase,
                TransactionPhase::Activating
                    | TransactionPhase::HealthChecking
                    | TransactionPhase::RollingBack
            )
        {
            return Err(StateError::ActiveTransaction(transaction.id));
        }

        let transaction_id = transaction.id.clone();
        let owned_paths = transaction.owned_paths.clone();
        let mut cleanup_error = None;
        for path in owned_paths {
            if let Err(error) = self.cleanup_owned_path(&transaction, &path) {
                if cleanup_error.is_none() {
                    cleanup_error = Some(error.to_string());
                }
            }
        }

        transaction.phase = TransactionPhase::Failed;
        transaction.status = TransactionStatus::Failed;
        transaction.finished_at_ms = Some(now_ms());
        transaction.failure = cleanup_error
            .clone()
            .or_else(|| Some("recovered interrupted pre-activation transaction".to_owned()));
        self.write_transaction(&transaction)?;

        if let Some(reason) = cleanup_error {
            return Err(StateError::RecoveryCleanupFailed {
                transaction_id,
                reason,
            });
        }
        Ok(())
    }

    pub fn cleanup_owned_path(
        &self,
        transaction: &TransactionRecord,
        candidate: &Path,
    ) -> Result<(), StateError> {
        let candidate = normalize(candidate)?;
        let owned = transaction
            .owned_paths
            .iter()
            .filter_map(|path| normalize(path).ok())
            .any(|path| path == candidate);
        let Some(managed_root) = managed_root(&self.paths, &candidate) else {
            return Err(StateError::UnsafeRecoveryPath(candidate));
        };
        if !owned
            || candidate == managed_root
            || candidate.starts_with(&self.paths.user_data_root)
            || has_unsafe_ancestor(&managed_root, &candidate)?
        {
            return Err(StateError::UnsafeRecoveryPath(candidate));
        }
        if !candidate.exists() {
            return Ok(());
        }
        if candidate.is_dir() {
            fs::remove_dir_all(candidate)?;
        } else {
            fs::remove_file(candidate)?;
        }
        Ok(())
    }

    /// Explicit operator action to clear a durable review block after the caller has
    /// repaired or otherwise verified the installation. Normal recovery never calls this.
    pub fn resolve_review(&self, transaction_id: &str) -> Result<(), StateError> {
        let Some(mut transaction) = self.load_transaction()? else {
            return Err(StateError::ReviewRequiredTransaction(
                transaction_id.to_owned(),
            ));
        };
        if transaction.id != transaction_id
            || transaction.status != TransactionStatus::ReviewRequired
        {
            return Err(StateError::ReviewRequiredTransaction(
                transaction_id.to_owned(),
            ));
        }
        transaction.status = TransactionStatus::Failed;
        transaction.phase = TransactionPhase::Failed;
        transaction.finished_at_ms = Some(now_ms());
        self.write_transaction(&transaction)
    }

    fn write_transaction(&self, transaction: &TransactionRecord) -> Result<(), StateError> {
        self.initialize()?;
        atomic_write_json(&self.paths.transaction_path(), transaction)?;
        if let Some(logger) = &self.logger {
            logger.log(
                "info",
                "transaction.transition",
                [
                    (
                        "transaction_id".to_owned(),
                        serde_json::json!(transaction.id.clone()),
                    ),
                    (
                        "operation".to_owned(),
                        serde_json::json!(format!("{:?}", transaction.operation)),
                    ),
                    (
                        "phase".to_owned(),
                        serde_json::json!(format!("{:?}", transaction.phase)),
                    ),
                    (
                        "status".to_owned(),
                        serde_json::json!(format!("{:?}", transaction.status)),
                    ),
                ],
            )?;
        }
        Ok(())
    }
}

pub struct Transaction {
    store: StateStore,
    record: TransactionRecord,
}

impl Transaction {
    pub fn begin(
        store: StateStore,
        operation: OperationKind,
        current_commit: Option<String>,
        target_commit: Option<String>,
        owned_paths: Vec<PathBuf>,
    ) -> Result<Self, StateError> {
        if let Some(existing) = store.load_transaction()? {
            match existing.status {
                TransactionStatus::Running => {
                    return Err(StateError::ActiveTransaction(existing.id));
                }
                TransactionStatus::ReviewRequired => {
                    return Err(StateError::ReviewRequiredTransaction(existing.id));
                }
                _ => {}
            }
        }
        let record = TransactionRecord {
            schema_version: STATE_SCHEMA_VERSION,
            id: Uuid::new_v4().to_string(),
            operation,
            phase: TransactionPhase::Idle,
            status: TransactionStatus::Running,
            started_at_ms: now_ms(),
            finished_at_ms: None,
            current_commit,
            target_commit,
            owned_paths: owned_paths
                .into_iter()
                .map(|path| normalize(&path))
                .collect::<Result<Vec<_>, _>>()?,
            activation_started: false,
            failure: None,
            toolchain_refs: BTreeMap::new(),
            published_version: None,
            current_switched: false,
            new_process_started: false,
            health_check_passed: false,
            db_backup_path: None,
            db_backup_required: false,
            rollback_completed: false,
            database_path: None,
            workspace_path: None,
            database_pre_state: None,
            pre_activation_state: None,
        };
        store.write_transaction(&record)?;
        Ok(Self { store, record })
    }

    pub(crate) fn resume_existing(store: StateStore, record: TransactionRecord) -> Self {
        Self { store, record }
    }

    pub fn record(&self) -> &TransactionRecord {
        &self.record
    }

    pub fn set_target_commit(
        &mut self,
        target_commit: impl Into<String>,
    ) -> Result<(), StateError> {
        self.record.target_commit = Some(target_commit.into());
        self.store.write_transaction(&self.record)
    }

    pub fn own_path(&mut self, path: impl AsRef<Path>) -> Result<(), StateError> {
        let path = normalize(path.as_ref())?;
        if !self.record.owned_paths.contains(&path) {
            self.record.owned_paths.push(path);
            self.store.write_transaction(&self.record)?;
        }
        Ok(())
    }

    pub fn pin_toolchain(
        &mut self,
        kind: impl Into<String>,
        toolchain_id: impl Into<String>,
    ) -> Result<(), StateError> {
        self.record
            .toolchain_refs
            .insert(kind.into(), toolchain_id.into());
        self.store.write_transaction(&self.record)
    }

    pub fn set_pre_activation_state(&mut self, state: InstallationState) -> Result<(), StateError> {
        self.record.pre_activation_state = Some(state);
        self.store.write_transaction(&self.record)
    }

    pub fn transition(&mut self, next: TransactionPhase) -> Result<(), StateError> {
        if !allowed_transition(&self.record.phase, &next) {
            return Err(StateError::InvalidTransition {
                from: self.record.phase.clone(),
                to: next,
            });
        }
        self.record.phase = next;
        if self.record.phase == TransactionPhase::Activating {
            self.record.activation_started = true;
        }
        if self.record.phase == TransactionPhase::Failed {
            self.record.status = TransactionStatus::Failed;
            self.record.finished_at_ms = Some(now_ms());
        }
        self.store.write_transaction(&self.record)
    }

    pub fn mark_activation_started(&mut self) -> Result<(), StateError> {
        self.record.activation_started = true;
        self.store.write_transaction(&self.record)
    }

    pub fn set_published_version(&mut self, version: impl Into<String>) -> Result<(), StateError> {
        self.record.published_version = Some(version.into());
        self.store.write_transaction(&self.record)
    }

    pub fn mark_current_switched(&mut self) -> Result<(), StateError> {
        self.record.current_switched = true;
        self.store.write_transaction(&self.record)
    }

    pub fn mark_process_started(&mut self) -> Result<(), StateError> {
        self.record.new_process_started = true;
        self.store.write_transaction(&self.record)
    }

    pub fn mark_health_check_passed(&mut self) -> Result<(), StateError> {
        self.record.health_check_passed = true;
        self.store.write_transaction(&self.record)
    }

    pub fn set_db_backup_path(&mut self, path: impl Into<PathBuf>) -> Result<(), StateError> {
        self.record.db_backup_path = Some(path.into());
        self.store.write_transaction(&self.record)
    }

    pub fn mark_db_backup_required(&mut self) -> Result<(), StateError> {
        self.record.db_backup_required = true;
        self.store.write_transaction(&self.record)
    }

    pub fn mark_rollback_completed(&mut self) -> Result<(), StateError> {
        self.record.rollback_completed = true;
        self.store.write_transaction(&self.record)
    }

    pub fn set_database_path(&mut self, path: impl Into<PathBuf>) -> Result<(), StateError> {
        self.record.database_path = Some(path.into());
        self.store.write_transaction(&self.record)
    }

    pub fn set_workspace_path(&mut self, path: impl Into<PathBuf>) -> Result<(), StateError> {
        self.record.workspace_path = Some(path.into());
        self.store.write_transaction(&self.record)
    }

    pub fn set_database_pre_state(&mut self, state: DatabasePreState) -> Result<(), StateError> {
        self.record.database_pre_state = Some(state);
        self.store.write_transaction(&self.record)
    }

    pub fn mark_review_required(&mut self, reason: impl Into<String>) -> Result<(), StateError> {
        self.record.status = TransactionStatus::ReviewRequired;
        self.record.failure = Some(reason.into());
        self.record.finished_at_ms = Some(now_ms());
        self.store.write_transaction(&self.record)
    }

    /// Clear a durable review block only after an explicit operator repair/recovery action.
    pub fn resolve_review(&mut self) -> Result<(), StateError> {
        if self.record.status != TransactionStatus::ReviewRequired {
            return Err(StateError::InvalidTransition {
                from: self.record.phase.clone(),
                to: TransactionPhase::Failed,
            });
        }
        self.record.status = TransactionStatus::Failed;
        self.record.phase = TransactionPhase::Failed;
        self.record.finished_at_ms = Some(now_ms());
        self.store.write_transaction(&self.record)
    }

    pub fn complete(&mut self) -> Result<(), StateError> {
        if !allowed_transition(&self.record.phase, &TransactionPhase::Completed) {
            return Err(StateError::InvalidTransition {
                from: self.record.phase.clone(),
                to: TransactionPhase::Completed,
            });
        }
        self.record.phase = TransactionPhase::Completed;
        self.record.status = TransactionStatus::Completed;
        self.record.finished_at_ms = Some(now_ms());
        self.store.write_transaction(&self.record)
    }

    pub fn fail(&mut self, reason: impl Into<String>) -> Result<(), StateError> {
        self.record.phase = TransactionPhase::Failed;
        self.record.status = TransactionStatus::Failed;
        self.record.failure = Some(reason.into());
        self.record.finished_at_ms = Some(now_ms());
        self.store.write_transaction(&self.record)
    }

    pub fn cleanup_owned_paths(&self) -> Result<(), StateError> {
        let owned_paths = self.record.owned_paths.clone();
        for path in owned_paths {
            self.store.cleanup_owned_path(&self.record, &path)?;
        }
        Ok(())
    }
}

fn allowed_transition(from: &TransactionPhase, to: &TransactionPhase) -> bool {
    use TransactionPhase::*;
    matches!(
        (from, to),
        (
            Idle,
            ResolvingTarget | PreparingToolchain | Verifying | Staging
        ) | (
            ResolvingTarget,
            PreparingToolchain | FetchingSource | Verifying
        ) | (
            PreparingToolchain,
            FetchingSource | PreparingWorktree | BuildingFrontend | Verifying
        ) | (FetchingSource, PreparingWorktree | Verifying)
            | (PreparingWorktree, BuildingFrontend | Verifying)
            | (BuildingFrontend, BuildingBackend | Verifying)
            | (BuildingBackend, BuildingDesktop | Verifying)
            | (BuildingDesktop, Verifying)
            | (Verifying, Completed | Staging | Failed)
            | (Staging, WaitingForShutdown | Activating | Failed)
            | (WaitingForShutdown, Activating | Failed)
            | (Activating, HealthChecking | RollingBack | Failed)
            | (HealthChecking, Completed | RollingBack | Failed)
            | (RollingBack, Completed | Failed)
            | (ResolvingTarget, Failed)
            | (PreparingToolchain, Failed)
            | (FetchingSource, Failed)
            | (PreparingWorktree, Failed)
            | (BuildingFrontend, Failed)
            | (BuildingBackend, Failed)
            | (BuildingDesktop, Failed)
    )
}

fn normalize(path: &Path) -> Result<PathBuf, StateError> {
    if path
        .components()
        .any(|component| component == Component::ParentDir)
    {
        return Err(StateError::UnsafeRecoveryPath(path.to_path_buf()));
    }
    if path.is_absolute() {
        return Ok(path.to_path_buf());
    }
    Ok(std::env::current_dir()?.join(path))
}

/// Validate a path that the installer may create, read, replace, or remove.
///
/// This is deliberately shared by recovery and activation code. It rejects parent
/// traversal, paths outside the declared root, and symlink/reparse ancestors all
/// the way above that root.
pub(crate) fn validate_managed_path(root: &Path, path: &Path) -> Result<PathBuf, StateError> {
    let root = normalize(root)?;
    let path = normalize(path)?;
    if !path.starts_with(&root) || has_unsafe_ancestor(&root, &path)? {
        return Err(StateError::UnsafeRecoveryPath(path));
    }
    Ok(path)
}

fn managed_root(paths: &InstallationPaths, candidate: &Path) -> Option<PathBuf> {
    [
        paths.app_root.clone(),
        paths.state_root.clone(),
        paths.cache_root.clone(),
    ]
    .into_iter()
    .filter(|root| candidate.starts_with(root))
    .max_by_key(|root| root.components().count())
}

fn has_unsafe_ancestor(root: &Path, candidate: &Path) -> io::Result<bool> {
    let Ok(relative) = candidate.strip_prefix(root) else {
        return Ok(true);
    };
    if !root.is_absolute() || !candidate.is_absolute() {
        return Ok(true);
    }
    let ancestors = candidate.ancestors().collect::<Vec<_>>();
    for ancestor in ancestors.into_iter().rev() {
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
    #[cfg(not(windows))]
    {
        Ok(false)
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        use windows_sys::Win32::Storage::FileSystem::FILE_ATTRIBUTE_REPARSE_POINT;

        Ok(metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0)
    }
}

pub(crate) fn atomic_write_json<T: Serialize>(path: &Path, value: &T) -> Result<(), StateError> {
    let encoded = serde_json::to_vec_pretty(value)?;
    let parent = path
        .parent()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "state path has no parent"))?;
    fs::create_dir_all(parent)?;
    let temporary = parent.join(format!(
        ".{}.{}.tmp",
        path.file_name().unwrap().to_string_lossy(),
        Uuid::new_v4()
    ));
    {
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&temporary)?;
        file.write_all(&encoded)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
    }
    replace_file(&temporary, path)?;
    sync_parent(parent);
    Ok(())
}

pub(crate) fn durable_replace_file(temporary: &Path, destination: &Path) -> Result<(), StateError> {
    replace_file(temporary, destination)?;
    if let Some(parent) = destination.parent() {
        sync_parent(parent);
    }
    Ok(())
}

/// Publish an immutable directory without replacing an existing destination.
/// The caller must have validated both paths against its managed root.
pub(crate) fn durable_promote_directory(
    staging: &Path,
    destination: &Path,
) -> Result<(), StateError> {
    if destination.exists() {
        return Err(io::Error::new(
            io::ErrorKind::AlreadyExists,
            format!(
                "immutable directory already exists: {}",
                destination.display()
            ),
        )
        .into());
    }
    #[cfg(not(windows))]
    {
        fs::rename(staging, destination)?;
        if let Some(parent) = destination.parent() {
            File::open(parent)?.sync_all()?;
        }
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::{MoveFileExW, MOVEFILE_WRITE_THROUGH};
        let source: Vec<u16> = staging
            .as_os_str()
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        let target: Vec<u16> = destination
            .as_os_str()
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        let result =
            unsafe { MoveFileExW(source.as_ptr(), target.as_ptr(), MOVEFILE_WRITE_THROUGH) };
        if result == 0 {
            return Err(io::Error::last_os_error().into());
        }
    }
    Ok(())
}

#[cfg(not(windows))]
fn replace_file(temporary: &Path, destination: &Path) -> io::Result<()> {
    fs::rename(temporary, destination)
}

#[cfg(windows)]
fn replace_file(temporary: &Path, destination: &Path) -> io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };
    let source: Vec<u16> = temporary
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let target: Vec<u16> = destination
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let result = unsafe {
        MoveFileExW(
            source.as_ptr(),
            target.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    };
    if result == 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}

#[cfg(not(windows))]
fn sync_parent(parent: &Path) {
    if let Ok(file) = File::open(parent) {
        let _ = file.sync_all();
    }
}

#[cfg(windows)]
fn sync_parent(_parent: &Path) {}

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
    use std::fs;
    use tempfile::tempdir;

    fn store() -> StateStore {
        let root = tempdir().unwrap().keep();
        StateStore::new(InstallationPaths {
            platform: Platform::Linux,
            architecture: TargetArchitecture::X64,
            app_root: root.join("app"),
            user_data_root: root.join("user-data"),
            state_root: root.join("state"),
            cache_root: root.join("cache"),
        })
    }

    #[test]
    fn persists_installation_state_atomically() {
        let store = store();
        let mut state = store.load_installation().unwrap();
        state.product_version = Some("1.0.0".to_owned());
        state.current_commit = Some("abc".to_owned());
        store.save_installation(&state).unwrap();
        assert_eq!(store.load_installation().unwrap(), state);
        assert!(!store.paths().state_root.join(".install.json").exists());
    }

    #[test]
    fn validates_state_machine_and_persists_each_transition() {
        let store = store();
        let mut transaction = Transaction::begin(
            store.clone(),
            OperationKind::Install,
            None,
            Some("target".to_owned()),
            vec![],
        )
        .unwrap();
        transaction
            .transition(TransactionPhase::ResolvingTarget)
            .unwrap();
        transaction
            .transition(TransactionPhase::PreparingToolchain)
            .unwrap();
        assert!(matches!(
            transaction.transition(TransactionPhase::Completed),
            Err(StateError::InvalidTransition { .. })
        ));
        assert!(matches!(
            store.load_transaction().unwrap().unwrap().phase,
            TransactionPhase::PreparingToolchain
        ));
    }

    #[test]
    fn recovery_resumes_before_activation_but_requires_review_after_marker() {
        let store = store();
        let mut transaction = Transaction::begin(
            store.clone(),
            OperationKind::Update,
            Some("old".to_owned()),
            Some("new".to_owned()),
            vec![],
        )
        .unwrap();
        transaction
            .transition(TransactionPhase::ResolvingTarget)
            .unwrap();
        assert!(matches!(
            store.recovery_action().unwrap(),
            RecoveryAction::Resume { .. }
        ));
        transaction.mark_activation_started().unwrap();
        assert!(matches!(
            store.recovery_action().unwrap(),
            RecoveryAction::ReviewRequired { .. }
        ));
    }

    #[test]
    fn recovery_cleans_journaled_partial_without_requiring_a_marker() {
        let store = store();
        let partial = store.paths().build_dir().join("candidates").join("partial");
        fs::create_dir_all(&partial).unwrap();
        fs::write(partial.join("checkout-file"), b"partial").unwrap();
        let transaction = Transaction::begin(
            store.clone(),
            OperationKind::Update,
            None,
            Some("target".to_owned()),
            vec![partial.clone()],
        )
        .unwrap();
        drop(transaction);

        store.recover_pre_activation().unwrap();
        assert!(!partial.exists());
        assert!(matches!(
            store.load_transaction().unwrap().unwrap().status,
            TransactionStatus::Failed
        ));
        assert!(Transaction::begin(store, OperationKind::Update, None, None, Vec::new()).is_ok());
    }

    #[test]
    fn review_required_transaction_protects_all_pinned_toolchains() {
        let store = store();
        let mut transaction = Transaction::begin(
            store.clone(),
            OperationKind::Update,
            None,
            Some("target".to_owned()),
            Vec::new(),
        )
        .unwrap();
        transaction.pin_toolchain("jdk", "jdk-target").unwrap();
        transaction.pin_toolchain("node", "node-target").unwrap();
        transaction
            .mark_review_required("rollback needs operator review")
            .unwrap();

        let protected = store.protected_toolchain_ids().unwrap();
        assert!(protected.contains("jdk-target"));
        assert!(protected.contains("node-target"));
        assert!(matches!(
            Transaction::begin(store, OperationKind::Update, None, None, Vec::new()),
            Err(StateError::ReviewRequiredTransaction(_))
        ));
    }

    #[test]
    fn review_required_transaction_protects_pre_activation_toolchains_after_switch() {
        let store = store();
        let mut pre_activation = InstallationState::for_paths(store.paths());
        pre_activation.current_commit = Some("a".to_owned());
        pre_activation.previous_commit = Some("p".to_owned());
        pre_activation.current_toolchains = BTreeMap::from([
            ("jdk".to_owned(), "jdk-a".to_owned()),
            ("node".to_owned(), "node-a".to_owned()),
        ]);
        pre_activation.previous_toolchains = BTreeMap::from([
            ("jdk".to_owned(), "jdk-p".to_owned()),
            ("node".to_owned(), "node-p".to_owned()),
        ]);

        let mut after_switch = pre_activation.clone();
        after_switch.current_commit = Some("b".to_owned());
        after_switch.previous_commit = Some("a".to_owned());
        after_switch.current_toolchains = BTreeMap::from([
            ("jdk".to_owned(), "jdk-b".to_owned()),
            ("node".to_owned(), "node-b".to_owned()),
        ]);
        store.save_installation(&after_switch).unwrap();

        let mut transaction = Transaction::begin(
            store.clone(),
            OperationKind::Update,
            Some("a".to_owned()),
            Some("b".to_owned()),
            Vec::new(),
        )
        .unwrap();
        transaction
            .set_pre_activation_state(pre_activation)
            .unwrap();
        transaction.pin_toolchain("jdk", "jdk-b").unwrap();
        transaction.pin_toolchain("node", "node-b").unwrap();
        transaction
            .mark_review_required("database restore failed after pointer switch")
            .unwrap();

        let protected = store.protected_toolchain_ids().unwrap();
        for id in ["jdk-p", "node-p", "jdk-a", "node-a", "jdk-b", "node-b"] {
            assert!(protected.contains(id), "missing protected toolchain {id}");
        }
    }

    #[cfg(windows)]
    #[test]
    fn durable_directory_promotion_does_not_replace_immutable_destination() {
        let root = tempdir().unwrap();
        let staging = root.path().join("staging");
        let destination = root.path().join("versions/target");
        fs::create_dir_all(&staging).unwrap();
        fs::create_dir_all(destination.parent().unwrap()).unwrap();
        fs::write(staging.join("payload"), b"payload").unwrap();
        durable_promote_directory(&staging, &destination).unwrap();
        assert_eq!(fs::read(destination.join("payload")).unwrap(), b"payload");

        let second = root.path().join("second");
        fs::create_dir_all(&second).unwrap();
        assert!(matches!(
            durable_promote_directory(&second, &destination),
            Err(StateError::Io(error)) if error.kind() == io::ErrorKind::AlreadyExists
        ));
    }

    #[test]
    fn cleanup_requires_exact_owned_managed_path_and_never_user_data() {
        let store = store();
        let owned = store.paths().build_dir().join("transaction.partial");
        fs::create_dir_all(owned.parent().unwrap()).unwrap();
        fs::write(&owned, b"partial").unwrap();
        let user_data = store.paths().user_data_root.join("database.db");
        fs::create_dir_all(user_data.parent().unwrap()).unwrap();
        fs::write(&user_data, b"keep").unwrap();
        let transaction = Transaction::begin(
            store.clone(),
            OperationKind::Update,
            None,
            None,
            vec![owned.clone()],
        )
        .unwrap();
        store
            .cleanup_owned_path(transaction.record(), &owned)
            .unwrap();
        assert!(!owned.exists());
        assert!(store
            .cleanup_owned_path(transaction.record(), &user_data)
            .is_err());
        assert!(user_data.exists());
    }

    #[test]
    fn cleanup_rejects_parent_directory_escape() {
        let store = store();
        let outside = store.paths().app_root.parent().unwrap().join("outside");
        fs::write(&outside, b"keep").unwrap();
        let transaction = Transaction::begin(
            store.clone(),
            OperationKind::Update,
            None,
            None,
            vec![store.paths().build_dir().join("partial")],
        )
        .unwrap();
        let escaped = store.paths().build_dir().join("..").join("outside");
        assert!(matches!(
            store.cleanup_owned_path(transaction.record(), &escaped),
            Err(StateError::UnsafeRecoveryPath(_))
        ));
        assert!(outside.exists());
    }

    #[cfg(unix)]
    #[test]
    fn cleanup_rejects_symlink_ancestor_escape() {
        use std::os::unix::fs::symlink;

        let store = store();
        let outside = store.paths().app_root.parent().unwrap().join("outside");
        fs::create_dir_all(&outside).unwrap();
        let external_file = outside.join("partial");
        fs::write(&external_file, b"keep").unwrap();
        let link = store.paths().build_dir().join("external");
        fs::create_dir_all(link.parent().unwrap()).unwrap();
        symlink(&outside, &link).unwrap();
        let candidate = link.join("partial");
        let transaction = Transaction::begin(
            store.clone(),
            OperationKind::Update,
            None,
            None,
            vec![candidate.clone()],
        )
        .unwrap();

        assert!(matches!(
            store.cleanup_owned_path(transaction.record(), &candidate),
            Err(StateError::UnsafeRecoveryPath(_))
        ));
        assert!(external_file.exists());
    }

    #[test]
    fn rejects_unknown_transaction_schema_before_recovery() {
        let store = store();
        let transaction =
            Transaction::begin(store.clone(), OperationKind::Update, None, None, Vec::new())
                .unwrap();
        let mut record: serde_json::Value = serde_json::to_value(transaction.record()).unwrap();
        record["schema_version"] = serde_json::json!(999);
        fs::write(
            store.paths().transaction_path(),
            serde_json::to_vec(&record).unwrap(),
        )
        .unwrap();

        assert!(matches!(
            store.load_transaction(),
            Err(StateError::UnsupportedSchema(999))
        ));
        assert!(matches!(
            store.recovery_action(),
            Err(StateError::UnsupportedSchema(999))
        ));
    }
}
