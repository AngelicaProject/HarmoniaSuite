use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::json;
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::checksum::{sha256_file, ChecksumError};
use crate::diagnostics::{DiagnosticError, DiagnosticLogger};
use crate::git::{GitError, ManagedCheckout, ManagedGitRepository};
use crate::lock::{InstallationLock, LockError};
use crate::paths::InstallationPaths;
use crate::process::{redacted_command, CommandSpec, ProcessError, ProcessRunner};
use crate::state::{OperationKind, StateError, StateStore, Transaction, TransactionPhase};
use crate::toolchain::{
    ManagedEnvironment, ResolvedToolchain, ToolchainDescriptor, ToolchainError, ToolchainManager,
};
use crate::DownloadClient;

const BUILD_RESULT_SCHEMA_VERSION: u32 = 1;

#[derive(Clone, Debug)]
pub struct BuildConfig {
    pub remote_url: String,
    pub product_version: String,
    pub jdk: ToolchainDescriptor,
    pub node: ToolchainDescriptor,
}

impl BuildConfig {
    pub fn new(
        remote_url: impl Into<String>,
        product_version: impl Into<String>,
        jdk: ToolchainDescriptor,
        node: ToolchainDescriptor,
    ) -> Self {
        Self {
            remote_url: remote_url.into(),
            product_version: product_version.into(),
            jdk,
            node,
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum BuildStatus {
    Completed,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct BuildArtifact {
    pub path: PathBuf,
    pub sha256: String,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct BuildResult {
    pub schema_version: u32,
    pub status: BuildStatus,
    pub target_commit: String,
    pub product_version: String,
    pub checkout_dir: PathBuf,
    pub toolchains: BTreeMap<String, String>,
    pub frontend: BuildArtifact,
    pub backend: BuildArtifact,
    pub desktop: BuildArtifact,
    pub started_at_ms: u128,
    pub finished_at_ms: u128,
    pub duration_ms: u128,
}

#[derive(Debug, Error)]
pub enum BuildError {
    #[error("build configuration is invalid: {0}")]
    InvalidConfig(String),
    #[error("required lockfile is missing: {0}")]
    MissingLockfile(PathBuf),
    #[error("required build input is missing: {0}")]
    MissingInput(PathBuf),
    #[error("managed toolchain executable {kind:?}/{name:?} is missing")]
    MissingToolExecutable { kind: String, name: String },
    #[error("build output is missing or invalid: {0}")]
    InvalidOutput(PathBuf),
    #[error("build command failed in phase {phase:?}: {command} (status={status:?}, timed_out={timed_out})")]
    CommandFailed {
        phase: TransactionPhase,
        command: String,
        status: Option<i32>,
        timed_out: bool,
        duration_ms: u128,
    },
    #[error("build I/O failed: {0}")]
    Io(#[from] io::Error),
    #[error("build checksum failed: {0}")]
    Checksum(#[from] ChecksumError),
    #[error("build diagnostics failed: {0}")]
    Diagnostics(#[from] DiagnosticError),
    #[error("build Git operation failed: {0}")]
    Git(#[from] GitError),
    #[error("build installation lock failed: {0}")]
    Lock(#[from] LockError),
    #[error("build process failed: {0}")]
    Process(#[from] ProcessError),
    #[error("build toolchain operation failed: {0}")]
    Toolchain(#[from] ToolchainError),
    #[error("build state operation failed: {0}")]
    State(#[from] StateError),
    #[error("build result JSON failed: {0}")]
    Json(#[from] serde_json::Error),
}

pub struct BuildPipeline<D, P> {
    paths: InstallationPaths,
    toolchains: ToolchainManager<D>,
    runner: P,
    logger: Option<DiagnosticLogger>,
}

impl<D: DownloadClient, P: ProcessRunner> BuildPipeline<D, P> {
    pub fn new(
        paths: InstallationPaths,
        downloader: D,
        runner: P,
        logger: Option<DiagnosticLogger>,
    ) -> Self {
        Self {
            toolchains: ToolchainManager::new(paths.clone(), downloader),
            paths,
            runner,
            logger,
        }
    }

    pub fn toolchains(&self) -> &ToolchainManager<D> {
        &self.toolchains
    }

    /// Locate the durable result emitted by the successful build for this
    /// exact target. Activation consumes this path and validates its candidate.
    pub fn result_path_for(&self, result: &BuildResult) -> Result<PathBuf, BuildError> {
        let directory = self.paths.build_results_dir();
        let mut matches = Vec::new();
        if directory.is_dir() {
            for entry in fs::read_dir(directory)? {
                let path = entry?.path();
                if path.extension().and_then(|value| value.to_str()) != Some("json") {
                    continue;
                }
                let candidate: BuildResult = match fs::read(&path)
                    .ok()
                    .and_then(|bytes| serde_json::from_slice(&bytes).ok())
                {
                    Some(value) => value,
                    None => continue,
                };
                if candidate.target_commit == result.target_commit
                    && candidate.checkout_dir == result.checkout_dir
                    && candidate.finished_at_ms == result.finished_at_ms
                {
                    matches.push(path);
                }
            }
        }
        matches.sort();
        matches
            .pop()
            .ok_or_else(|| BuildError::InvalidOutput(result.checkout_dir.clone()))
    }

    pub fn run(&self, config: &BuildConfig) -> Result<BuildResult, BuildError> {
        validate_config(config)?;
        let git = ManagedGitRepository::new(self.paths.source_dir(), config.remote_url.clone())?;
        self.run_with_git(config, &git)
    }

    /// Run while the caller owns the single installation lock for a larger
    /// operation. This is the bootstrap/update composition boundary; it does
    /// not acquire a second non-reentrant lock.
    pub fn run_with_lock(
        &self,
        config: &BuildConfig,
        lock: &InstallationLock,
    ) -> Result<BuildResult, BuildError> {
        if lock.path() != self.paths.lock_path() {
            return Err(BuildError::InvalidConfig(
                "caller lock does not belong to this installation".to_owned(),
            ));
        }
        validate_config(config)?;
        let git = ManagedGitRepository::new(self.paths.source_dir(), config.remote_url.clone())?;
        self.run_with_git_locked(config, &git)
    }

    fn run_with_git(
        &self,
        config: &BuildConfig,
        git: &ManagedGitRepository,
    ) -> Result<BuildResult, BuildError> {
        let _installation_lock = InstallationLock::acquire(self.paths.lock_path(), "phase4-build")?;
        self.run_with_git_locked(config, git)
    }

    fn run_with_git_locked(
        &self,
        config: &BuildConfig,
        git: &ManagedGitRepository,
    ) -> Result<BuildResult, BuildError> {
        let state_store = match &self.logger {
            Some(logger) => StateStore::new(self.paths.clone()).with_logger(logger.clone()),
            None => StateStore::new(self.paths.clone()),
        };
        state_store.recover_pre_activation()?;
        let installation = state_store.load_installation()?;
        let started_at_ms = now_ms();
        let mut transaction = Transaction::begin(
            state_store,
            OperationKind::Update,
            installation.current_commit,
            None,
            Vec::new(),
        )?;
        let result = self.run_transaction(config, git, &mut transaction, started_at_ms);
        match result {
            Ok(result) => {
                transaction.complete()?;
                Ok(result)
            }
            Err(error) => {
                let _ = transaction.fail(error.to_string());
                Err(error)
            }
        }
    }

    fn run_transaction(
        &self,
        config: &BuildConfig,
        git: &ManagedGitRepository,
        transaction: &mut Transaction,
        started_at_ms: u128,
    ) -> Result<BuildResult, BuildError> {
        let result = (|| -> Result<BuildResult, BuildError> {
            transaction.transition(TransactionPhase::ResolvingTarget)?;
            let target = git.fetch_origin_main()?.sha;
            transaction.set_target_commit(target.clone())?;
            self.log_phase(
                &target,
                &TransactionPhase::ResolvingTarget,
                "target.resolved",
                [("target_commit".to_owned(), json!(target.clone()))],
            )?;

            transaction.transition(TransactionPhase::PreparingToolchain)?;
            let jdk = self.toolchains.ensure(&config.jdk)?;
            let node = self.toolchains.ensure(&config.node)?;
            transaction.pin_toolchain("jdk", jdk.id.clone())?;
            transaction.pin_toolchain("node", node.id.clone())?;
            let managed_environment = self
                .toolchains
                .environment(&[jdk.id.clone(), node.id.clone()])?;
            self.log_phase(
                &target,
                &TransactionPhase::PreparingToolchain,
                "toolchains.pinned",
                [
                    ("jdk_id".to_owned(), json!(jdk.id.clone())),
                    ("node_id".to_owned(), json!(node.id.clone())),
                ],
            )?;

            transaction.transition(TransactionPhase::FetchingSource)?;
            // The fetch above selected the immutable build target. From this point on only
            // inspect the already-fetched object; never fetch or re-read a moving remote ref.
            git.verify_commit(&target)?;

            transaction.transition(TransactionPhase::PreparingWorktree)?;
            let staging_root = self
                .paths
                .build_dir()
                .join("staging")
                .join(transaction.record().id.clone());
            transaction.own_path(&staging_root)?;
            let prepared = git.prepare_checkout(&target, &staging_root)?;
            self.log_phase(
                &target,
                &TransactionPhase::PreparingWorktree,
                "worktree.ready",
                [("checkout_dir".to_owned(), json!(prepared.path.clone()))],
            )?;

            transaction.transition(TransactionPhase::BuildingFrontend)?;
            self.build_frontend(&prepared.path, &node, &managed_environment, &target)?;

            transaction.transition(TransactionPhase::BuildingBackend)?;
            self.build_backend(&prepared.path, &managed_environment, &target)?;

            transaction.transition(TransactionPhase::BuildingDesktop)?;
            self.build_desktop(&prepared.path, &node, &managed_environment, &target)?;

            transaction.transition(TransactionPhase::Verifying)?;
            let result = self.verify_and_write_result(
                config,
                &target,
                &prepared,
                &jdk,
                &node,
                started_at_ms,
                transaction,
            )?;
            self.log_phase(
                &target,
                &TransactionPhase::Verifying,
                "build.completed",
                [("duration_ms".to_owned(), json!(result.duration_ms))],
            )?;
            Ok(result)
        })();

        match result {
            Ok(result) => Ok(result),
            Err(error) => {
                if let Err(cleanup_error) = transaction.cleanup_owned_paths() {
                    return Err(BuildError::State(cleanup_error));
                }
                Err(error)
            }
        }
    }

    fn build_frontend(
        &self,
        checkout: &Path,
        node: &ResolvedToolchain,
        environment: &ManagedEnvironment,
        target: &str,
    ) -> Result<(), BuildError> {
        let directory = checkout.join("frontend");
        self.run_npm_ci_and_build(
            &directory,
            node,
            environment,
            target,
            &TransactionPhase::BuildingFrontend,
        )
    }

    fn build_backend(
        &self,
        checkout: &Path,
        environment: &ManagedEnvironment,
        target: &str,
    ) -> Result<(), BuildError> {
        let wrapper = self.toolchains.maven_wrapper_path(checkout)?;
        let wrapper_args = ["-B", "-q", "-DskipTests", "package"];
        #[cfg(windows)]
        let output = {
            let system_root = std::env::var_os("SystemRoot").ok_or_else(|| {
                BuildError::InvalidConfig("SystemRoot is required to run mvnw.cmd".to_owned())
            })?;
            let mut command_args = vec![
                "/D".to_owned(),
                "/S".to_owned(),
                "/C".to_owned(),
                "call".to_owned(),
                wrapper.display().to_string(),
            ];
            command_args.extend(wrapper_args.iter().map(|argument| (*argument).to_owned()));
            self.run_command_strings(
                &TransactionPhase::BuildingBackend,
                target,
                environment,
                &PathBuf::from(system_root).join("System32").join("cmd.exe"),
                &command_args,
                checkout,
            )?
        };
        #[cfg(not(windows))]
        let output = self.run_command(
            &TransactionPhase::BuildingBackend,
            target,
            environment,
            &wrapper,
            &wrapper_args,
            checkout,
        )?;
        if !output.success() {
            return Err(command_failed(
                TransactionPhase::BuildingBackend,
                &wrapper,
                output,
            ));
        }
        Ok(())
    }

    fn build_desktop(
        &self,
        checkout: &Path,
        node: &ResolvedToolchain,
        environment: &ManagedEnvironment,
        target: &str,
    ) -> Result<(), BuildError> {
        let directory = checkout.join("apps").join("desktop");
        self.run_npm_ci_and_build(
            &directory,
            node,
            environment,
            target,
            &TransactionPhase::BuildingDesktop,
        )
    }

    fn run_npm_ci_and_build(
        &self,
        directory: &Path,
        node: &ResolvedToolchain,
        environment: &ManagedEnvironment,
        target: &str,
        phase: &TransactionPhase,
    ) -> Result<(), BuildError> {
        require_npm_lockfile(directory)?;
        let npm = node
            .executable("npm")
            .ok_or_else(|| BuildError::MissingToolExecutable {
                kind: "node".to_owned(),
                name: "npm".to_owned(),
            })?;
        let commands = if phase == &TransactionPhase::BuildingDesktop {
            vec![&["ci"][..], &["run", "build"][..], &["run", "package"][..]]
        } else {
            vec![&["ci"][..], &["run", "build"][..]]
        };
        for args in commands {
            let output = self.run_npm_command(phase, target, environment, npm, args, directory)?;
            if !output.success() {
                return Err(command_failed(phase.clone(), npm, output));
            }
        }
        Ok(())
    }

    fn run_npm_command(
        &self,
        phase: &TransactionPhase,
        target: &str,
        environment: &ManagedEnvironment,
        npm: &Path,
        args: &[&str],
        current_dir: &Path,
    ) -> Result<crate::process::ProcessOutput, BuildError> {
        #[cfg(windows)]
        {
            let system_root = std::env::var_os("SystemRoot").ok_or_else(|| {
                BuildError::InvalidConfig("SystemRoot is required to run managed npm".to_owned())
            })?;
            let mut command_args = vec![
                "/D".to_owned(),
                "/S".to_owned(),
                "/C".to_owned(),
                "call".to_owned(),
                npm.display().to_string(),
            ];
            command_args.extend(args.iter().map(|argument| (*argument).to_owned()));
            self.run_command_strings(
                phase,
                target,
                environment,
                &PathBuf::from(system_root).join("System32").join("cmd.exe"),
                &command_args,
                current_dir,
            )
        }
        #[cfg(not(windows))]
        {
            self.run_command(phase, target, environment, npm, args, current_dir)
        }
    }

    #[cfg(not(windows))]
    fn run_command(
        &self,
        phase: &TransactionPhase,
        target: &str,
        environment: &ManagedEnvironment,
        program: &Path,
        args: &[&str],
        current_dir: &Path,
    ) -> Result<crate::process::ProcessOutput, BuildError> {
        let owned_args = args.iter().map(|arg| (*arg).to_owned()).collect::<Vec<_>>();
        self.run_command_strings(
            phase,
            target,
            environment,
            program,
            &owned_args,
            current_dir,
        )
    }

    fn run_command_strings(
        &self,
        phase: &TransactionPhase,
        target: &str,
        environment: &ManagedEnvironment,
        program: &Path,
        args: &[String],
        current_dir: &Path,
    ) -> Result<crate::process::ProcessOutput, BuildError> {
        let command = environment.apply_to(
            CommandSpec::new(program.to_path_buf())
                .args(args.iter().cloned())
                .current_dir(current_dir.to_path_buf()),
        );
        let output = self.runner.run(&command)?;
        self.log_phase(
            target,
            phase,
            "process.completed",
            [
                ("command".to_owned(), json!(redacted_command(&command))),
                ("success".to_owned(), json!(output.success())),
                ("status".to_owned(), json!(output.status)),
                ("timed_out".to_owned(), json!(output.timed_out)),
                ("duration_ms".to_owned(), json!(output.duration_ms)),
            ],
        )?;
        Ok(output)
    }

    #[allow(clippy::too_many_arguments)]
    fn verify_and_write_result(
        &self,
        config: &BuildConfig,
        target: &str,
        checkout: &ManagedCheckout,
        jdk: &ResolvedToolchain,
        node: &ResolvedToolchain,
        started_at_ms: u128,
        transaction: &mut Transaction,
    ) -> Result<BuildResult, BuildError> {
        let frontend_path = checkout.path.join("frontend").join("dist");
        let backend_path = checkout.path.join("target").join("harmonia-suite.jar");
        let desktop_relative = PathBuf::from("apps/desktop/artifacts").join(format!(
            "{}-{}",
            self.paths.platform.as_str(),
            self.paths.architecture.as_str()
        ));
        let desktop_path = checkout.path.join(&desktop_relative);
        let frontend = BuildArtifact {
            path: PathBuf::from("frontend/dist"),
            sha256: hash_directory(&frontend_path)?,
        };
        let backend = BuildArtifact {
            path: PathBuf::from("target/harmonia-suite.jar"),
            sha256: sha256_file(&backend_path)?,
        };
        let desktop = BuildArtifact {
            path: desktop_relative,
            sha256: hash_directory(&desktop_path)?,
        };

        let staging_root = checkout
            .path
            .parent()
            .ok_or_else(|| BuildError::InvalidOutput(checkout.path.clone()))?;
        let candidate_root = self
            .paths
            .build_dir()
            .join("candidates")
            .join(target)
            .join(transaction.record().id.clone());
        if candidate_root.exists() {
            return Err(BuildError::InvalidOutput(candidate_root));
        }
        transaction.own_path(&candidate_root)?;
        if let Some(parent) = candidate_root.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::rename(staging_root, &candidate_root)?;
        let published_checkout = candidate_root.join(target);
        if !published_checkout.is_dir() {
            return Err(BuildError::InvalidOutput(published_checkout));
        }
        let finished_at_ms = now_ms();
        let checkout_dir = published_checkout
            .strip_prefix(&self.paths.app_root)
            .map_err(|_| BuildError::InvalidOutput(published_checkout.clone()))?
            .to_path_buf();
        let result = BuildResult {
            schema_version: BUILD_RESULT_SCHEMA_VERSION,
            status: BuildStatus::Completed,
            target_commit: target.to_owned(),
            product_version: config.product_version.clone(),
            checkout_dir,
            toolchains: BTreeMap::from([
                ("jdk".to_owned(), jdk.id.clone()),
                ("node".to_owned(), node.id.clone()),
            ]),
            frontend,
            backend,
            desktop,
            started_at_ms,
            finished_at_ms,
            duration_ms: finished_at_ms.saturating_sub(started_at_ms),
        };
        let result_path = self
            .paths
            .build_results_dir()
            .join(format!("{}.json", transaction.record().id));
        transaction.own_path(&result_path)?;
        crate::state::atomic_write_json(&result_path, &result)?;
        Ok(result)
    }

    fn log_phase(
        &self,
        target: &str,
        phase: &TransactionPhase,
        event: &str,
        fields: impl IntoIterator<Item = (String, serde_json::Value)>,
    ) -> Result<(), BuildError> {
        let Some(logger) = &self.logger else {
            return Ok(());
        };
        let mut values = vec![
            ("target_commit".to_owned(), json!(target)),
            ("phase".to_owned(), json!(format!("{phase:?}"))),
        ];
        values.extend(fields);
        logger.log("info", event, values)?;
        Ok(())
    }
}

fn validate_config(config: &BuildConfig) -> Result<(), BuildError> {
    if config.product_version.trim().is_empty() {
        return Err(BuildError::InvalidConfig(
            "product_version must not be empty".to_owned(),
        ));
    }
    Ok(())
}

fn require_npm_lockfile(directory: &Path) -> Result<(), BuildError> {
    let package = directory.join("package.json");
    let lockfile = directory.join("package-lock.json");
    if !package.is_file() {
        return Err(BuildError::MissingInput(package));
    }
    if !lockfile.is_file() {
        return Err(BuildError::MissingLockfile(lockfile));
    }
    Ok(())
}

fn command_failed(
    phase: TransactionPhase,
    program: &Path,
    output: crate::process::ProcessOutput,
) -> BuildError {
    BuildError::CommandFailed {
        phase,
        command: program.display().to_string(),
        status: output.status,
        timed_out: output.timed_out,
        duration_ms: output.duration_ms,
    }
}

pub(crate) fn hash_directory(path: &Path) -> Result<String, BuildError> {
    if !path.is_dir() {
        return Err(BuildError::InvalidOutput(path.to_path_buf()));
    }
    let mut files = Vec::new();
    collect_files(path, &mut files)?;
    if files.is_empty() {
        return Err(BuildError::InvalidOutput(path.to_path_buf()));
    }
    files.sort();
    let mut digest = Sha256::new();
    for file in files {
        let relative = file
            .strip_prefix(path)
            .map_err(|_| BuildError::InvalidOutput(file.clone()))?;
        digest.update(relative.to_string_lossy().replace('\\', "/").as_bytes());
        digest.update([0]);
        digest.update(sha256_file(&file)?.as_bytes());
        digest.update([0]);
    }
    Ok(hex_digest(&digest.finalize()))
}

fn collect_files(path: &Path, files: &mut Vec<PathBuf>) -> Result<(), BuildError> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() {
        return Err(BuildError::InvalidOutput(path.to_path_buf()));
    }
    if metadata.is_dir() {
        for entry in fs::read_dir(path)? {
            collect_files(&entry?.path(), files)?;
        }
    } else if metadata.is_file() {
        files.push(path.to_path_buf());
    }
    Ok(())
}

fn hex_digest(bytes: &[u8]) -> String {
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        output.push_str(&format!("{byte:02x}"));
    }
    output
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
    use crate::download::{DownloadError, DownloadReceipt, DownloadRequest};
    use crate::paths::{Platform, TargetArchitecture};
    use crate::process::{CommandSpec, ProcessOutput, SystemProcessRunner};
    #[cfg(windows)]
    use crate::toolchain::safe_os_utility_paths;
    use crate::toolchain::{ArchiveFormat, ToolchainKind};
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use git2::{Oid, Repository, Signature};
    use std::collections::BTreeMap;
    use std::io;
    use std::sync::{Arc, Mutex};
    use tar::Builder;
    use tempfile::{tempdir, TempDir};

    #[derive(Clone, Default)]
    struct FixtureDownloader {
        archives: BTreeMap<String, Vec<u8>>,
    }

    impl DownloadClient for FixtureDownloader {
        fn download(&self, request: &DownloadRequest) -> Result<DownloadReceipt, DownloadError> {
            let bytes = self.archives.get(&request.url).ok_or_else(|| {
                DownloadError::Transport(format!("fixture archive is missing: {}", request.url))
            })?;
            if let Some(parent) = request.destination.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::write(&request.destination, bytes)?;
            Ok(DownloadReceipt {
                path: request.destination.clone(),
                bytes: bytes.len() as u64,
                sha256: bytes_sha256(bytes),
                resumed: false,
            })
        }
    }

    #[derive(Clone, Copy)]
    enum FailurePoint {
        Frontend,
        Backend,
        Desktop,
    }

    #[derive(Clone, Default)]
    struct FixtureRunner {
        calls: Arc<Mutex<Vec<CommandSpec>>>,
        failure: Option<FailurePoint>,
    }

    impl ProcessRunner for FixtureRunner {
        fn run(&self, command: &CommandSpec) -> Result<ProcessOutput, ProcessError> {
            self.calls.lock().unwrap().push(command.clone());
            let current_dir = command
                .current_dir
                .as_ref()
                .ok_or_else(|| ProcessError::Spawn {
                    program: command.program.display().to_string(),
                    source: io::Error::new(io::ErrorKind::InvalidInput, "missing fixture cwd"),
                })?;
            let failure = match self.failure {
                Some(FailurePoint::Frontend)
                    if current_dir.ends_with(Path::new("frontend"))
                        && is_build_command(command) =>
                {
                    true
                }
                Some(FailurePoint::Backend) if is_maven_command(command) => true,
                Some(FailurePoint::Desktop)
                    if current_dir.ends_with(Path::new("desktop")) && is_build_command(command) =>
                {
                    true
                }
                _ => false,
            };
            if failure {
                return Ok(ProcessOutput {
                    status: Some(17),
                    stdout: String::new(),
                    stderr: "fixture failure".to_owned(),
                    duration_ms: 1,
                    timed_out: false,
                });
            }

            if is_build_command(command) {
                let output = current_dir.join("dist");
                fs::create_dir_all(&output)?;
                fs::write(output.join("index.js"), b"fixture build")?;
            } else if is_desktop_package_command(command) {
                let output = current_dir.join("artifacts/linux-x64");
                fs::create_dir_all(&output)?;
                fs::write(
                    output.join("harmonia-electron"),
                    b"fixture electron payload",
                )?;
            } else if is_maven_command(command) {
                let output = current_dir.join("target");
                fs::create_dir_all(&output)?;
                fs::write(output.join("harmonia-suite.jar"), b"fixture jar")?;
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

    struct Fixture {
        _root: TempDir,
        paths: InstallationPaths,
        config: BuildConfig,
        git: ManagedGitRepository,
        target: String,
        downloader: FixtureDownloader,
    }

    fn fixture() -> Fixture {
        let root = tempdir().unwrap();
        let paths = InstallationPaths {
            platform: Platform::Linux,
            architecture: TargetArchitecture::X64,
            app_root: root.path().join("app"),
            user_data_root: root.path().join("user-data"),
            state_root: root.path().join("state"),
            cache_root: root.path().join("cache"),
        };
        let remote_path = root.path().join("remote.git");
        let remote = Repository::init_bare(&remote_path).unwrap();
        let target = commit_fixture(&remote);
        let remote_url = reqwest::Url::from_file_path(&remote_path)
            .unwrap()
            .to_string();

        let jdk_archive = tar_gz(&[("jdk-21/bin/java", b"managed java", 0o100755)]);
        let node_archive = tar_gz(&[
            ("node-24/bin/node", b"managed node", 0o100755),
            ("node-24/bin/npm", b"managed npm", 0o100755),
        ]);
        let jdk_url = "https://example.invalid/jdk-21.tar.gz".to_owned();
        let node_url = "https://example.invalid/node-24.tar.gz".to_owned();
        let jdk = ToolchainDescriptor::new(
            ToolchainKind::Jdk,
            "21.0.1",
            Platform::Linux,
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
            Platform::Linux,
            TargetArchitecture::X64,
            node_url.clone(),
            bytes_sha256(&node_archive),
            ArchiveFormat::TarGz,
        )
        .home_dir("node-24")
        .executable("node", "node-24/bin/node")
        .executable("npm", "node-24/bin/npm");
        let downloader = FixtureDownloader {
            archives: BTreeMap::from([(jdk_url, jdk_archive), (node_url, node_archive)]),
        };
        let git =
            ManagedGitRepository::new_for_test(paths.source_dir(), remote_url.clone()).unwrap();
        Fixture {
            _root: root,
            paths,
            config: BuildConfig::new(remote_url, "1.0.11-test", jdk, node),
            git,
            target,
            downloader,
        }
    }

    fn commit_fixture(repository: &Repository) -> String {
        let frontend = tree_with_files(
            repository,
            &[
                ("package.json", b"{\"scripts\":{\"build\":\"vite build\"}}"),
                ("package-lock.json", b"{\"lockfileVersion\":3}"),
            ],
        );
        let desktop = tree_with_files(
            repository,
            &[
                ("package.json", b"{\"scripts\":{\"build\":\"tsc\"}}"),
                ("package-lock.json", b"{\"lockfileVersion\":3}"),
            ],
        );
        let apps = tree_with_children(repository, &[("desktop", desktop)]);
        let wrapper = tree_with_files(
            repository,
            &[(
                "maven-wrapper.properties",
                b"distributionType=only-script\ndistributionSha256Sum=0000000000000000000000000000000000000000000000000000000000000000\n",
            )],
        );
        let dot_mvn = tree_with_children(repository, &[("wrapper", wrapper)]);
        let root = tree_with_children(
            repository,
            &[("frontend", frontend), ("apps", apps), (".mvn", dot_mvn)],
        );
        let base_tree = repository.find_tree(root).unwrap();
        let mut root_builder = repository.treebuilder(Some(&base_tree)).unwrap();
        let wrapper_blob = repository.blob(b"#!/bin/sh\nexit 0\n").unwrap();
        root_builder.insert("mvnw", wrapper_blob, 0o100755).unwrap();
        let pom_blob = repository.blob(b"<project/>\n").unwrap();
        root_builder.insert("pom.xml", pom_blob, 0o100644).unwrap();
        let root = repository.find_tree(root_builder.write().unwrap()).unwrap();
        let signature = Signature::now("Harmonia Test", "test@example.invalid").unwrap();
        repository
            .commit(
                Some("refs/heads/main"),
                &signature,
                &signature,
                "phase 4 fixture",
                &root,
                &[],
            )
            .unwrap()
            .to_string()
    }

    fn tree_with_files(repository: &Repository, files: &[(&str, &[u8])]) -> Oid {
        let mut builder = repository.treebuilder(None).unwrap();
        for (name, contents) in files {
            let blob = repository.blob(contents).unwrap();
            builder.insert(name, blob, 0o100644).unwrap();
        }
        builder.write().unwrap()
    }

    fn tree_with_children(repository: &Repository, children: &[(&str, Oid)]) -> Oid {
        let mut builder = repository.treebuilder(None).unwrap();
        for (name, tree) in children {
            builder.insert(name, *tree, 0o040000).unwrap();
        }
        builder.write().unwrap()
    }

    fn tar_gz(entries: &[(&str, &[u8], u32)]) -> Vec<u8> {
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
        let mut digest = Sha256::new();
        digest.update(bytes);
        hex_digest(&digest.finalize())
    }

    fn is_build_command(command: &CommandSpec) -> bool {
        (command.args.len() == 2 && command.args[0] == "run" && command.args[1] == "build")
            || command
                .args
                .iter()
                .any(|argument| argument.contains("\"run\" \"build\""))
            || command
                .args
                .windows(2)
                .any(|args| args[0] == "run" && args[1] == "build")
    }

    fn is_desktop_package_command(command: &CommandSpec) -> bool {
        (command.args.len() == 2 && command.args[0] == "run" && command.args[1] == "package"
            || command
                .args
                .iter()
                .any(|argument| argument.contains("\"run\" \"package\""))
            || command
                .args
                .windows(2)
                .any(|args| args[0] == "run" && args[1] == "package"))
            && command
                .current_dir
                .as_ref()
                .is_some_and(|path| path.ends_with(Path::new("desktop")))
    }

    fn is_npm_ci_command(command: &CommandSpec) -> bool {
        command.args.first().map(String::as_str) == Some("ci")
            || command
                .args
                .iter()
                .any(|argument| argument.contains("\"ci\""))
    }

    fn uses_managed_toolchain(command: &CommandSpec, toolchain_dir: &Path) -> bool {
        command.program.starts_with(toolchain_dir)
            || command
                .environment
                .get("PATH")
                .is_some_and(|path| path.contains(toolchain_dir.to_string_lossy().as_ref()))
    }

    fn is_maven_command(command: &CommandSpec) -> bool {
        command
            .program
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.starts_with("mvnw"))
            || command
                .args
                .iter()
                .any(|argument| argument.contains("mvnw"))
    }

    #[test]
    fn pipeline_uses_exact_sha_managed_tools_and_ci_only() {
        let fixture = fixture();
        let runner = FixtureRunner::default();
        let calls = runner.calls.clone();
        let pipeline = BuildPipeline::new(fixture.paths.clone(), fixture.downloader, runner, None);
        let result = pipeline
            .run_with_git(&fixture.config, &fixture.git)
            .unwrap();

        assert_eq!(result.target_commit, fixture.target);
        assert_eq!(result.status, BuildStatus::Completed);
        let first_checkout = fixture.paths.app_root.join(&result.checkout_dir);
        assert!(first_checkout.is_dir());
        assert_eq!(
            result.desktop.path,
            PathBuf::from("apps/desktop/artifacts/linux-x64")
        );
        assert_eq!(
            fs::read_dir(fixture.paths.state_root.join("build-results"))
                .unwrap()
                .count(),
            1
        );
        let calls = calls.lock().unwrap();
        let npm_calls = calls
            .iter()
            .filter(|call| is_npm_ci_command(call))
            .collect::<Vec<_>>();
        assert_eq!(npm_calls.len(), 2);
        assert!(calls.iter().all(|call| {
            call.program
                .file_name()
                .and_then(|name| name.to_str())
                .map(|name| !name.eq_ignore_ascii_case("git"))
                .unwrap_or(true)
        }));
        assert!(calls.iter().any(|call| {
            uses_managed_toolchain(call, &fixture.paths.toolchain_dir()) && is_npm_ci_command(call)
        }));
        assert!(calls.iter().any(|call| {
            call.environment
                .get("JAVA_HOME")
                .map(|value| value.contains("jdk-21"))
                .unwrap_or(false)
        }));
        drop(calls);

        let contamination = first_checkout.join("contamination.txt");
        fs::write(&contamination, b"must not survive a rebuild").unwrap();
        let second = pipeline
            .run_with_git(&fixture.config, &fixture.git)
            .unwrap();
        assert_eq!(second.target_commit, fixture.target);
        assert!(
            contamination.exists(),
            "previous verified candidate was destroyed"
        );
        assert_ne!(result.checkout_dir, second.checkout_dir);
        assert_eq!(
            fs::read_dir(fixture.paths.state_root.join("build-results"))
                .unwrap()
                .count(),
            2
        );
    }

    #[test]
    fn failed_build_preserves_installation_and_user_data_and_cleans_checkout() {
        for failure in [
            FailurePoint::Frontend,
            FailurePoint::Backend,
            FailurePoint::Desktop,
        ] {
            let fixture = fixture();
            let state_store = StateStore::new(fixture.paths.clone());
            let mut installation = state_store.load_installation().unwrap();
            installation.current_commit = Some("current-commit".to_owned());
            state_store.save_installation(&installation).unwrap();
            let installation_before = fs::read(fixture.paths.install_state_path()).unwrap();
            let user_file = fixture
                .paths
                .user_data_root
                .join("workspace")
                .join("keep.txt");
            fs::create_dir_all(user_file.parent().unwrap()).unwrap();
            fs::write(&user_file, b"keep").unwrap();

            let pipeline = BuildPipeline::new(
                fixture.paths.clone(),
                fixture.downloader,
                FixtureRunner {
                    calls: Arc::new(Mutex::new(Vec::new())),
                    failure: Some(failure),
                },
                None,
            );
            let error = pipeline
                .run_with_git(&fixture.config, &fixture.git)
                .unwrap_err();
            let expected_phase = match failure {
                FailurePoint::Frontend => TransactionPhase::BuildingFrontend,
                FailurePoint::Backend => TransactionPhase::BuildingBackend,
                FailurePoint::Desktop => TransactionPhase::BuildingDesktop,
            };
            assert!(matches!(
                error,
                BuildError::CommandFailed { phase, .. } if phase == expected_phase
            ));
            assert_eq!(
                fs::read(fixture.paths.install_state_path()).unwrap(),
                installation_before
            );
            assert_eq!(
                state_store
                    .load_installation()
                    .unwrap()
                    .current_commit
                    .as_deref(),
                Some("current-commit")
            );
            assert!(user_file.is_file());
            let candidate_root = fixture
                .paths
                .build_dir()
                .join("candidates")
                .join(&fixture.target);
            assert!(
                !candidate_root.exists() || fs::read_dir(candidate_root).unwrap().next().is_none()
            );
            assert!(matches!(
                state_store.load_transaction().unwrap().unwrap().status,
                crate::state::TransactionStatus::Failed
            ));
        }
    }

    #[test]
    fn missing_or_invalid_lockfile_fails_closed() {
        let root = tempdir().unwrap();
        let directory = root.path().join("frontend");
        fs::create_dir_all(&directory).unwrap();
        fs::write(directory.join("package.json"), b"{}").unwrap();
        assert!(matches!(
            require_npm_lockfile(&directory),
            Err(BuildError::MissingLockfile(_))
        ));
        fs::create_dir(directory.join("package-lock.json")).unwrap();
        assert!(matches!(
            require_npm_lockfile(&directory),
            Err(BuildError::MissingLockfile(_))
        ));
    }

    #[test]
    fn maven_wrapper_checksum_is_required_by_build_boundary() {
        let root = tempdir().unwrap();
        let paths = InstallationPaths {
            platform: Platform::Linux,
            architecture: TargetArchitecture::X64,
            app_root: root.path().join("app"),
            user_data_root: root.path().join("user-data"),
            state_root: root.path().join("state"),
            cache_root: root.path().join("cache"),
        };
        let checkout = root.path().join("checkout");
        fs::create_dir_all(checkout.join(".mvn/wrapper")).unwrap();
        fs::write(checkout.join("mvnw"), b"wrapper").unwrap();
        fs::write(
            checkout.join(".mvn/wrapper/maven-wrapper.properties"),
            b"distributionType=only-script\n",
        )
        .unwrap();
        let manager = ToolchainManager::new(paths, FixtureDownloader::default());
        assert!(matches!(
            manager.maven_wrapper_path(&checkout),
            Err(ToolchainError::InvalidDescriptor(message))
                if message.contains("distributionSha256Sum")
        ));
    }

    #[cfg(unix)]
    #[test]
    fn managed_maven_wrapper_environment_runs_real_subprocess() {
        let root = tempdir().unwrap();
        let java_home = root.path().join("managed-jdk");
        fs::create_dir_all(java_home.join("bin")).unwrap();
        use std::os::unix::fs::PermissionsExt;
        fs::write(java_home.join("bin/java"), b"#!/bin/sh\n").unwrap();
        let mut permissions = fs::metadata(java_home.join("bin/java"))
            .unwrap()
            .permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(java_home.join("bin/java"), permissions).unwrap();
        let wrapper = root.path().join("mvnw");
        fs::write(
            &wrapper,
            "#!/bin/sh\nset -eu\ncommand -v dirname >/dev/null\ntest -x \"$JAVA_HOME/bin/java\"\n",
        )
        .unwrap();
        let mut permissions = fs::metadata(&wrapper).unwrap().permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(&wrapper, permissions).unwrap();
        let environment = ManagedEnvironment {
            variables: BTreeMap::from([
                (
                    "PATH".to_owned(),
                    format!("{}:/usr/bin:/bin", java_home.join("bin").display()),
                ),
                ("JAVA_HOME".to_owned(), java_home.display().to_string()),
            ]),
            path: vec![java_home.join("bin")],
        };
        let command = environment.apply_to(CommandSpec::new(wrapper));
        let output = SystemProcessRunner::default().run(&command).unwrap();
        assert!(output.success(), "wrapper smoke test failed: {output:?}");
    }

    #[cfg(windows)]
    #[test]
    fn managed_maven_wrapper_environment_runs_real_subprocess() {
        let root = tempdir().unwrap();
        let java_home = root.path().join("managed-jdk");
        fs::create_dir_all(java_home.join("bin")).unwrap();
        fs::write(java_home.join("bin/java.exe"), b"managed java").unwrap();
        let wrapper = root.path().join("mvnw.cmd");
        fs::write(
            &wrapper,
            "@echo off\r\nif not exist \"%JAVA_HOME%\\bin\\java.exe\" exit /b 1\r\npowershell -NoProfile -Command \"if ($env:JAVA_HOME) { exit 0 } else { exit 1 }\"\r\nexit /b %ERRORLEVEL%\r\n",
        )
        .unwrap();
        let mut path = vec![java_home.join("bin")];
        path.extend(safe_os_utility_paths(Platform::Windows));
        let path_value = path
            .iter()
            .map(|entry| entry.display().to_string())
            .collect::<Vec<_>>()
            .join(";");
        let environment = ManagedEnvironment {
            variables: BTreeMap::from([
                ("PATH".to_owned(), path_value),
                ("JAVA_HOME".to_owned(), java_home.display().to_string()),
            ]),
            path,
        };
        let command = environment.apply_to(CommandSpec::new("cmd.exe").args([
            "/D",
            "/C",
            &wrapper.display().to_string(),
        ]));
        let output = SystemProcessRunner::default().run(&command).unwrap();
        assert!(output.success(), "wrapper smoke test failed: {output:?}");
    }
}
