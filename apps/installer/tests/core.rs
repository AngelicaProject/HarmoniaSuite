use std::fs;
use std::path::PathBuf;

use harmonia_installer::{
    InstallationLock, InstallationPaths, InstallationState, OperationKind, Platform,
    RecoveryAction, StateStore, TargetArchitecture, Transaction, TransactionPhase,
};
use tempfile::tempdir;

fn paths() -> InstallationPaths {
    let root = tempdir().unwrap().keep();
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
fn install_transaction_has_recovery_record_and_does_not_create_user_data() {
    let paths = paths();
    let store = StateStore::new(paths.clone());
    store.initialize().unwrap();
    assert!(!paths.user_data_root.exists());

    let owned = paths.build_dir().join("install.partial");
    fs::create_dir_all(owned.parent().unwrap()).unwrap();
    fs::write(&owned, b"staging").unwrap();
    let mut transaction = Transaction::begin(
        store.clone(),
        OperationKind::Install,
        None,
        Some("target-sha".to_owned()),
        vec![owned.clone()],
    )
    .unwrap();
    transaction
        .transition(TransactionPhase::ResolvingTarget)
        .unwrap();
    transaction.transition(TransactionPhase::Verifying).unwrap();
    assert!(matches!(
        store.recovery_action().unwrap(),
        RecoveryAction::Resume { .. }
    ));
    store
        .cleanup_owned_path(transaction.record(), &owned)
        .unwrap();
    assert!(!owned.exists());
}

#[test]
fn complete_transaction_persists_installation_state_and_lock_is_exclusive() {
    let paths = paths();
    let store = StateStore::new(paths.clone());
    let lock = InstallationLock::acquire(paths.lock_path(), "install").unwrap();
    assert!(InstallationLock::acquire(paths.lock_path(), "repair").is_err());

    let mut state: InstallationState = store.load_installation().unwrap();
    state.current_commit = Some("target-sha".to_owned());
    store.save_installation(&state).unwrap();
    assert_eq!(
        store.load_installation().unwrap().current_commit.as_deref(),
        Some("target-sha")
    );

    let mut transaction = Transaction::begin(
        store,
        OperationKind::Install,
        None,
        Some("target-sha".to_owned()),
        Vec::<PathBuf>::new(),
    )
    .unwrap();
    transaction
        .transition(TransactionPhase::ResolvingTarget)
        .unwrap();
    transaction.transition(TransactionPhase::Verifying).unwrap();
    transaction.transition(TransactionPhase::Staging).unwrap();
    transaction
        .transition(TransactionPhase::Activating)
        .unwrap();
    transaction
        .transition(TransactionPhase::HealthChecking)
        .unwrap();
    transaction.complete().unwrap();
    assert_eq!(
        transaction.record().status,
        harmonia_installer::TransactionStatus::Completed
    );
    drop(lock);
}
