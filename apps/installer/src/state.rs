use std::collections::BTreeMap;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use uuid::Uuid;

use crate::diagnostics::{DiagnosticError, DiagnosticLogger};
use crate::paths::InstallationPaths;

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
    UnsupportedSchema(u32),
    #[error("invalid transaction transition from {from:?} to {to:?}")]
    InvalidTransition {
        from: TransactionPhase,
        to: TransactionPhase,
    },
    #[error("recovery refused to remove unowned or unsafe path: {0}")]
    UnsafeRecoveryPath(PathBuf),
    #[error("transaction {0} is still running")]
    ActiveTransaction(String),
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
            return Err(StateError::UnsupportedSchema(state.schema_version));
        }
        Ok(state)
    }

    pub fn save_installation(&self, state: &InstallationState) -> Result<(), StateError> {
        if state.schema_version != STATE_SCHEMA_VERSION {
            return Err(StateError::UnsupportedSchema(state.schema_version));
        }
        self.initialize()?;
        atomic_write_json(&self.paths.install_state_path(), state)
    }

    pub fn load_transaction(&self) -> Result<Option<TransactionRecord>, StateError> {
        let path = self.paths.transaction_path();
        if !path.is_file() {
            return Ok(None);
        }
        Ok(Some(serde_json::from_slice(&fs::read(path)?)?))
    }

    pub fn recovery_action(&self) -> Result<RecoveryAction, StateError> {
        let Some(transaction) = self.load_transaction()? else {
            return Ok(RecoveryAction::None);
        };
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

    pub fn cleanup_owned_path(
        &self,
        transaction: &TransactionRecord,
        candidate: &Path,
    ) -> Result<(), StateError> {
        let candidate = normalize(candidate);
        let owned = transaction
            .owned_paths
            .iter()
            .map(|path| normalize(path))
            .any(|path| path == candidate);
        if !owned
            || !self.paths.is_managed_path(&candidate)
            || candidate.starts_with(&self.paths.user_data_root)
            || is_symlink(&candidate)?
        {
            return Err(StateError::UnsafeRecoveryPath(candidate));
        }
        if candidate.is_dir() {
            fs::remove_dir_all(candidate)?;
        } else {
            fs::remove_file(candidate)?;
        }
        Ok(())
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
            if existing.status == TransactionStatus::Running {
                return Err(StateError::ActiveTransaction(existing.id));
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
                .collect(),
            activation_started: false,
            failure: None,
        };
        store.write_transaction(&record)?;
        Ok(Self { store, record })
    }

    pub fn record(&self) -> &TransactionRecord {
        &self.record
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
            | (Verifying, Staging | Failed)
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

fn normalize(path: &Path) -> PathBuf {
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("."))
            .join(path)
    }
}

fn is_symlink(path: &Path) -> io::Result<bool> {
    Ok(path.exists() && fs::symlink_metadata(path)?.file_type().is_symlink())
}

fn atomic_write_json<T: Serialize>(path: &Path, value: &T) -> Result<(), StateError> {
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
        let root = tempdir().unwrap().keep().unwrap();
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
}
