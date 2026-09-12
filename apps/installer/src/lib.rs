pub mod activation;
pub mod bootstrap;
pub mod build;
pub mod catalog;
pub mod checksum;
pub mod desktop_control;
pub mod detached;
pub mod diagnostics;
pub mod download;
pub mod extract;
pub mod git;
pub mod helper;
pub mod lock;
pub mod manifest;
pub mod paths;
pub mod process;
pub mod state;
pub mod toolchain;
pub mod updater;

pub use activation::{
    ActivationConfig, ActivationEngine, ActivationError, ActivationHooks, HealthChecker,
    LocalBackendHealthChecker, NoopActivationHooks, RuntimeMetadata, RuntimePaths,
    VersionComponent, VersionMetadata,
};
pub use bootstrap::{
    BootstrapError, BootstrapInstaller, BootstrapOptions, BootstrapResult, BootstrapStatus,
    DEFAULT_PRODUCT_VERSION, DEFAULT_REMOTE_URL,
};
pub use build::{BuildArtifact, BuildConfig, BuildError, BuildPipeline, BuildResult, BuildStatus};
pub use catalog::{production_catalog, production_descriptors, PRODUCTION_CATALOG_SCHEMA_VERSION};
pub use desktop_control::{
    clear_desktop_session, desktop_session_path, live_desktop_pid, publish_desktop_session,
    DesktopControlError, DesktopShutdownHooks,
};
pub use detached::{
    DetachedLaunch, DetachedLaunchError, DetachedLaunchSpec, DetachedLauncher,
    SystemDetachedLauncher,
};
pub use diagnostics::DiagnosticLogger;
pub use download::{
    DownloadClient, DownloadError, DownloadReceipt, DownloadRequest, DownloadResponse,
    DownloadTransport, HttpDownloader, ResumableDownloader,
};
pub use extract::ExtractionError;
pub use git::{GitError, ManagedCheckout, ManagedGitRepository, ResolvedCommit};
pub use helper::{publish_installer_helper, HelperError, InstallerHelperMetadata};
pub use lock::{InstallationLock, LockError};
pub use manifest::{
    HttpManifestFetcher, ManifestError, ManifestFetcher, ManifestSignature, ManifestVerifier,
    RollingManifest, SignedManifest,
};
pub use paths::{InstallationPaths, PathEnvironment, PathError, Platform, TargetArchitecture};
pub use process::{
    CommandSpec, ManagedProcess, ProcessError, ProcessOutput, ProcessRunner, SystemProcessRunner,
};
pub use state::{
    InstallationState, OperationKind, RecoveryAction, StateError, StateStore, Transaction,
    TransactionPhase, TransactionRecord, TransactionStatus,
};
pub use toolchain::{
    ArchiveFormat, ManagedEnvironment, OfficialToolchainCatalog, PendingGarbageCollection,
    ResolvedToolchain, ToolchainDescriptor, ToolchainError, ToolchainKind, ToolchainManager,
    ToolchainRecord, ToolchainState, ToolchainStateStore,
};
pub use updater::{UpdateEngine, UpdateError, UpdateResult, UpdateStatus};
