use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io;
use std::path::{Component, Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use reqwest::Url;
use serde::{Deserialize, Serialize};
use thiserror::Error;
use uuid::Uuid;

use crate::checksum::{verify_sha256, ChecksumError};
use crate::download::{DownloadClient, DownloadError, DownloadRequest};
use crate::extract::{extract_archive, ExtractionError};
use crate::paths::{InstallationPaths, Platform, TargetArchitecture};
use crate::process::CommandSpec;
use crate::state::{StateError, StateStore};

const TOOLCHAIN_STATE_SCHEMA_VERSION: u32 = 1;

#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ToolchainKind {
    Jdk,
    Node,
}

impl ToolchainKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Jdk => "jdk",
            Self::Node => "node",
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ArchiveFormat {
    Zip,
    #[serde(rename = "tar.gz")]
    TarGz,
}

impl ArchiveFormat {
    fn extension(self) -> &'static str {
        match self {
            Self::Zip => "zip",
            Self::TarGz => "tar.gz",
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct ToolchainDescriptor {
    pub kind: ToolchainKind,
    pub version: String,
    pub platform: Platform,
    pub architecture: TargetArchitecture,
    pub url: String,
    pub sha256: String,
    pub archive: ArchiveFormat,
    #[serde(default = "default_home_dir")]
    pub home_dir: PathBuf,
    pub executables: BTreeMap<String, PathBuf>,
}

impl ToolchainDescriptor {
    pub fn new(
        kind: ToolchainKind,
        version: impl Into<String>,
        platform: Platform,
        architecture: TargetArchitecture,
        url: impl Into<String>,
        sha256: impl Into<String>,
        archive: ArchiveFormat,
    ) -> Self {
        Self {
            kind,
            version: version.into(),
            platform,
            architecture,
            url: url.into(),
            sha256: sha256.into(),
            archive,
            home_dir: default_home_dir(),
            executables: BTreeMap::new(),
        }
    }

    pub fn home_dir(mut self, relative_path: impl Into<PathBuf>) -> Self {
        self.home_dir = relative_path.into();
        self
    }

    pub fn executable(
        mut self,
        name: impl Into<String>,
        relative_path: impl Into<PathBuf>,
    ) -> Self {
        self.executables.insert(name.into(), relative_path.into());
        self
    }

    pub fn validate_for(
        &self,
        platform: Platform,
        architecture: &TargetArchitecture,
    ) -> Result<(), ToolchainError> {
        if self.platform != platform || self.architecture != *architecture {
            return Err(ToolchainError::UnsupportedTarget {
                expected_platform: platform,
                expected_architecture: architecture.clone(),
                actual_platform: self.platform,
                actual_architecture: self.architecture.clone(),
            });
        }
        if self.version.trim().is_empty()
            || self.version.contains('/')
            || self.version.contains('\\')
            || self.version == "."
            || self.version == ".."
        {
            return Err(ToolchainError::InvalidDescriptor(
                "version must be a non-empty path-safe identifier".to_owned(),
            ));
        }
        let url = Url::parse(&self.url)
            .map_err(|_| ToolchainError::InvalidDescriptor("URL is invalid".to_owned()))?;
        if url.scheme() != "https" || url.host_str().is_none() {
            return Err(ToolchainError::InvalidDescriptor(
                "toolchain URL must be an HTTPS URL with a host".to_owned(),
            ));
        }
        if !is_sha256(&self.sha256) {
            return Err(ToolchainError::InvalidDescriptor(
                "toolchain SHA-256 must be 64 hexadecimal characters".to_owned(),
            ));
        }
        if !is_safe_relative_path(&self.home_dir) {
            return Err(ToolchainError::InvalidDescriptor(
                "home_dir must be a safe relative path".to_owned(),
            ));
        }
        if self.executables.is_empty() {
            return Err(ToolchainError::InvalidDescriptor(
                "at least one executable is required".to_owned(),
            ));
        }
        for (name, path) in &self.executables {
            if name.trim().is_empty() || !is_safe_relative_path(path) {
                return Err(ToolchainError::InvalidDescriptor(format!(
                    "executable path for {name:?} is not safely relative"
                )));
            }
        }
        Ok(())
    }

    pub fn id(&self) -> String {
        format!(
            "{}-{}-{}-{}-{}",
            self.kind.as_str(),
            safe_id_part(&self.version),
            self.platform.as_str(),
            safe_id_part(self.architecture.as_str()),
            self.sha256.to_ascii_lowercase()
        )
    }

    fn archive_name(&self) -> String {
        format!("{}.{}", self.id(), self.archive.extension())
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct OfficialToolchainCatalog {
    pub schema_version: u32,
    pub descriptors: Vec<ToolchainDescriptor>,
}

impl OfficialToolchainCatalog {
    pub fn from_json(bytes: &[u8]) -> Result<Self, ToolchainError> {
        let catalog: Self = serde_json::from_slice(bytes)?;
        if catalog.schema_version != TOOLCHAIN_STATE_SCHEMA_VERSION {
            return Err(ToolchainError::UnsupportedCatalogSchema(
                catalog.schema_version,
            ));
        }
        let mut ids = BTreeSet::new();
        for descriptor in &catalog.descriptors {
            descriptor.validate_for(descriptor.platform, &descriptor.architecture)?;
            if !ids.insert(descriptor.id()) {
                return Err(ToolchainError::InvalidDescriptor(
                    "catalog contains duplicate descriptor IDs".to_owned(),
                ));
            }
        }
        Ok(catalog)
    }

    pub fn descriptor(
        &self,
        kind: ToolchainKind,
        version: &str,
        platform: Platform,
        architecture: &TargetArchitecture,
    ) -> Result<&ToolchainDescriptor, ToolchainError> {
        self.descriptors
            .iter()
            .find(|descriptor| {
                descriptor.kind == kind
                    && descriptor.version == version
                    && descriptor.platform == platform
                    && descriptor.architecture == *architecture
            })
            .ok_or_else(|| ToolchainError::NotFound {
                kind,
                version: version.to_owned(),
            })
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct ToolchainRecord {
    pub id: String,
    pub kind: ToolchainKind,
    pub version: String,
    pub platform: Platform,
    pub architecture: TargetArchitecture,
    pub url: String,
    pub sha256: String,
    pub archive: ArchiveFormat,
    #[serde(default = "default_home_dir")]
    pub home_dir: PathBuf,
    pub install_dir: PathBuf,
    pub executables: BTreeMap<String, PathBuf>,
    pub installed_at_ms: u128,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct ToolchainState {
    pub schema_version: u32,
    pub records: BTreeMap<String, ToolchainRecord>,
    #[serde(default)]
    pub pending_gc: Vec<PendingGarbageCollection>,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct PendingGarbageCollection {
    pub id: String,
    pub original_dir: PathBuf,
    pub trash_dir: PathBuf,
}

impl Default for ToolchainState {
    fn default() -> Self {
        Self {
            schema_version: TOOLCHAIN_STATE_SCHEMA_VERSION,
            records: BTreeMap::new(),
            pending_gc: Vec::new(),
        }
    }
}

#[derive(Clone, Debug)]
pub struct ToolchainStateStore {
    paths: InstallationPaths,
}

impl ToolchainStateStore {
    pub fn new(paths: InstallationPaths) -> Self {
        Self { paths }
    }

    pub fn load(&self) -> Result<ToolchainState, ToolchainError> {
        let path = self.paths.toolchain_state_path();
        validate_managed_path(&path)?;
        if !path.is_file() {
            return Ok(ToolchainState::default());
        }
        let state: ToolchainState = serde_json::from_slice(&fs::read(path)?)?;
        if state.schema_version != TOOLCHAIN_STATE_SCHEMA_VERSION {
            return Err(ToolchainError::UnsupportedStateSchema(state.schema_version));
        }
        for (key, record) in &state.records {
            if key != &record.id {
                return Err(ToolchainError::InvalidState(format!(
                    "toolchain state key {key:?} does not match record ID {:?}",
                    record.id
                )));
            }
            if !is_safe_relative_path(&record.home_dir)
                || !is_safe_relative_path(&record.install_dir)
            {
                return Err(ToolchainError::InvalidState(format!(
                    "toolchain record {key:?} contains an unsafe relative path"
                )));
            }
        }
        Ok(state)
    }

    pub fn save(&self, state: &ToolchainState) -> Result<(), ToolchainError> {
        if state.schema_version != TOOLCHAIN_STATE_SCHEMA_VERSION {
            return Err(ToolchainError::UnsupportedStateSchema(state.schema_version));
        }
        validate_managed_path(&self.paths.state_root)?;
        fs::create_dir_all(&self.paths.state_root)?;
        validate_managed_path(&self.paths.toolchain_state_path())?;
        Ok(crate::state::atomic_write_json(
            &self.paths.toolchain_state_path(),
            state,
        )?)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ResolvedToolchain {
    pub id: String,
    pub kind: ToolchainKind,
    pub version: String,
    pub root: PathBuf,
    pub home_dir: PathBuf,
    pub executables: BTreeMap<String, PathBuf>,
}

impl ResolvedToolchain {
    pub fn executable(&self, name: &str) -> Option<&Path> {
        self.executables.get(name).map(PathBuf::as_path)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ManagedEnvironment {
    pub variables: BTreeMap<String, String>,
    pub path: Vec<PathBuf>,
}

impl ManagedEnvironment {
    pub fn apply_to(&self, command: CommandSpec) -> CommandSpec {
        command.envs(self.variables.clone())
    }
}

#[derive(Debug, Error)]
pub enum ToolchainError {
    #[error("toolchain descriptor is invalid: {0}")]
    InvalidDescriptor(String),
    #[error("toolchain target {actual_platform:?}/{actual_architecture:?} does not match {expected_platform:?}/{expected_architecture:?}")]
    UnsupportedTarget {
        expected_platform: Platform,
        expected_architecture: TargetArchitecture,
        actual_platform: Platform,
        actual_architecture: TargetArchitecture,
    },
    #[error("toolchain {kind:?} version {version} was not found")]
    NotFound {
        kind: ToolchainKind,
        version: String,
    },
    #[error("toolchain state schema {0} is not supported")]
    UnsupportedStateSchema(u32),
    #[error("toolchain catalog schema {0} is not supported")]
    UnsupportedCatalogSchema(u32),
    #[error("toolchain state is invalid: {0}")]
    InvalidState(String),
    #[error("more than one {0:?} toolchain was requested")]
    DuplicateKind(ToolchainKind),
    #[error("toolchain download failed: {0}")]
    Download(#[from] DownloadError),
    #[error("Maven Wrapper checksum failed: {0}")]
    Checksum(#[from] ChecksumError),
    #[error("toolchain extraction failed: {0}")]
    Extraction(#[from] ExtractionError),
    #[error("toolchain I/O failed: {0}")]
    Io(#[from] io::Error),
    #[error("toolchain JSON failed: {0}")]
    Json(#[from] serde_json::Error),
    #[error("installer state failed: {0}")]
    State(#[from] StateError),
    #[error("toolchain installation directory is already occupied: {0}")]
    Occupied(PathBuf),
    #[error("installed toolchain {id:?} metadata is incompatible: {reason}")]
    IncompatibleMetadata { id: String, reason: String },
    #[error("toolchain installation is incomplete: {0}")]
    Incomplete(PathBuf),
    #[error("unsafe managed toolchain path: {0}")]
    UnsafePath(PathBuf),
    #[error("toolchain executable {name:?} is not installed in {root}")]
    MissingExecutable { name: String, root: PathBuf },
}

pub struct ToolchainManager<D> {
    paths: InstallationPaths,
    downloader: D,
    state: ToolchainStateStore,
}

impl<D: DownloadClient> ToolchainManager<D> {
    pub fn new(paths: InstallationPaths, downloader: D) -> Self {
        let state = ToolchainStateStore::new(paths.clone());
        Self {
            paths,
            downloader,
            state,
        }
    }

    pub fn paths(&self) -> &InstallationPaths {
        &self.paths
    }

    pub fn state_store(&self) -> &ToolchainStateStore {
        &self.state
    }

    pub fn ensure(
        &self,
        descriptor: &ToolchainDescriptor,
    ) -> Result<ResolvedToolchain, ToolchainError> {
        descriptor.validate_for(self.paths.platform, &self.paths.architecture)?;
        let id = descriptor.id();
        let mut state = self.load_recovered_state()?;
        if let Some(existing) = state.records.get(&id).cloned() {
            self.validate_existing_record(&existing, descriptor, &id)?;
            if existing.home_dir == descriptor.home_dir
                && existing.executables == descriptor.executables
            {
                return self.resolve_record(&existing);
            }

            let mut updated = existing;
            updated.home_dir = descriptor.home_dir.clone();
            updated.executables = descriptor.executables.clone();
            let resolved = self.resolve_record(&updated)?;
            state.records.insert(id.clone(), updated);
            self.state.save(&state)?;
            return Ok(resolved);
        }

        let final_root = self.install_root(descriptor, &id)?;
        if final_root.exists() {
            return Err(ToolchainError::Occupied(final_root));
        }
        validate_managed_path(&self.paths.cache_root)?;
        fs::create_dir_all(self.paths.downloads_dir())?;
        validate_managed_path(&self.paths.downloads_dir())?;
        let archive_path = self.paths.downloads_dir().join(descriptor.archive_name());
        validate_managed_path(&archive_path)?;
        let receipt = self.downloader.download(&DownloadRequest::new(
            &descriptor.url,
            &archive_path,
            &descriptor.sha256,
        ))?;
        verify_sha256(&receipt.path, &descriptor.sha256)?;

        let kind_root = self.paths.toolchain_dir().join(descriptor.kind.as_str());
        validate_managed_path(&kind_root)?;
        fs::create_dir_all(&kind_root)?;
        validate_managed_path(&kind_root)?;
        let staging = kind_root.join(format!(".{}.{}", id, Uuid::new_v4()));
        let mut staging_guard = StagingGuard::new(staging.clone());
        extract_archive(&receipt.path, descriptor.archive, &staging)?;
        resolve_home_dir(&staging, &descriptor.home_dir)?;
        resolve_executable_paths(&staging, &descriptor.executables)?;
        let install_dir = final_root
            .strip_prefix(&self.paths.app_root)
            .map_err(|_| ToolchainError::UnsafePath(final_root.clone()))?
            .to_path_buf();
        if final_root.exists() {
            return Err(ToolchainError::Occupied(final_root));
        }
        fs::rename(&staging, &final_root)?;
        staging_guard.disarm();
        let record = ToolchainRecord {
            id: id.clone(),
            kind: descriptor.kind,
            version: descriptor.version.clone(),
            platform: descriptor.platform,
            architecture: descriptor.architecture.clone(),
            url: descriptor.url.clone(),
            sha256: descriptor.sha256.to_ascii_lowercase(),
            archive: descriptor.archive,
            home_dir: descriptor.home_dir.clone(),
            install_dir,
            executables: descriptor.executables.clone(),
            installed_at_ms: now_ms(),
        };
        state.records.insert(id.clone(), record);
        if let Err(error) = self.state.save(&state) {
            let _ = fs::remove_dir_all(&final_root);
            return Err(error);
        }
        self.resolve_record(state.records.get(&id).expect("inserted toolchain record"))
    }

    pub fn resolve(&self, id: &str) -> Result<ResolvedToolchain, ToolchainError> {
        let state = self.load_recovered_state()?;
        let record = state
            .records
            .get(id)
            .ok_or_else(|| ToolchainError::Incomplete(PathBuf::from(id)))?;
        self.resolve_record(record)
    }

    pub fn resolve_executable(&self, id: &str, name: &str) -> Result<PathBuf, ToolchainError> {
        let resolved = self.resolve(id)?;
        resolved
            .executable(name)
            .map(Path::to_path_buf)
            .ok_or_else(|| ToolchainError::MissingExecutable {
                name: name.to_owned(),
                root: resolved.root,
            })
    }

    pub fn environment(&self, ids: &[String]) -> Result<ManagedEnvironment, ToolchainError> {
        let mut resolved = ids
            .iter()
            .map(|id| self.resolve(id))
            .collect::<Result<Vec<_>, _>>()?;
        resolved.sort_by(|left, right| left.id.cmp(&right.id));
        let mut path = Vec::new();
        let mut seen = BTreeSet::new();
        let mut seen_kinds = BTreeSet::new();
        let mut variables = BTreeMap::new();
        for toolchain in &resolved {
            if !seen_kinds.insert(toolchain.kind) {
                return Err(ToolchainError::DuplicateKind(toolchain.kind));
            }
            if toolchain.kind == ToolchainKind::Jdk {
                variables.insert(
                    "JAVA_HOME".to_owned(),
                    toolchain
                        .root
                        .join(&toolchain.home_dir)
                        .display()
                        .to_string(),
                );
            }
            for executable in toolchain.executables.values() {
                if let Some(parent) = executable.parent() {
                    if seen.insert(parent.to_path_buf()) {
                        path.push(parent.to_path_buf());
                    }
                }
            }
        }
        for utility_path in safe_os_utility_paths(self.paths.platform) {
            if utility_path.is_dir() && seen.insert(utility_path.clone()) {
                path.push(utility_path);
            }
        }
        fs::create_dir_all(self.paths.maven_cache_dir())?;
        fs::create_dir_all(self.paths.npm_cache_dir())?;
        variables.insert(
            "MAVEN_USER_HOME".to_owned(),
            self.paths.maven_cache_dir().display().to_string(),
        );
        variables.insert(
            "npm_config_cache".to_owned(),
            self.paths.npm_cache_dir().display().to_string(),
        );
        variables.insert("PATH".to_owned(), join_paths(&path));
        Ok(ManagedEnvironment { variables, path })
    }

    pub fn maven_wrapper_path(&self, checkout: &Path) -> Result<PathBuf, ToolchainError> {
        let name = if self.paths.platform == Platform::Windows {
            "mvnw.cmd"
        } else {
            "mvnw"
        };
        let path = checkout.join(name);
        if !path.is_file() {
            return Err(ToolchainError::MissingExecutable {
                name: name.to_owned(),
                root: checkout.to_path_buf(),
            });
        }
        validate_managed_path(&path)?;
        validate_maven_wrapper_properties(checkout)?;
        Ok(path)
    }

    pub fn verify_maven_wrapper(
        &self,
        checkout: &Path,
        expected_sha256: &str,
    ) -> Result<PathBuf, ToolchainError> {
        let wrapper = self.maven_wrapper_path(checkout)?;
        verify_sha256(&wrapper, expected_sha256)?;
        Ok(wrapper)
    }

    pub fn collect_garbage(&self, state_store: &StateStore) -> Result<Vec<String>, ToolchainError> {
        let protected = state_store.protected_toolchain_ids()?;
        let mut state = self.load_recovered_state()?;
        let mut candidates = Vec::new();
        for (id, record) in &state.records {
            if protected.contains(id) {
                continue;
            }
            let root = self.record_root(record)?;
            if root.exists() {
                validate_managed_path(&root)?;
            }
            candidates.push((id.clone(), root));
        }
        if candidates.is_empty() {
            return Ok(Vec::new());
        }

        let transaction_id = Uuid::new_v4().to_string();
        let mut pending = Vec::new();
        for (id, root) in &candidates {
            let original_dir = root
                .strip_prefix(&self.paths.app_root)
                .map_err(|_| ToolchainError::UnsafePath(root.clone()))?
                .to_path_buf();
            let trash_dir = self
                .paths
                .toolchain_trash_dir()
                .join(&transaction_id)
                .join(id);
            validate_managed_path(&trash_dir)?;
            pending.push(PendingGarbageCollection {
                id: id.clone(),
                original_dir,
                trash_dir: trash_dir
                    .strip_prefix(&self.paths.app_root)
                    .map_err(|_| ToolchainError::UnsafePath(trash_dir.clone()))?
                    .to_path_buf(),
            });
        }

        state.pending_gc = pending.clone();
        self.state.save(&state)?;

        let mut moved = Vec::new();
        let move_result = (|| -> Result<(), ToolchainError> {
            for item in &pending {
                let original = self.app_relative_path(&item.original_dir)?;
                let trash = self.app_relative_path(&item.trash_dir)?;
                if !original.exists() {
                    continue;
                }
                validate_managed_path(&original)?;
                if trash.exists() {
                    return Err(ToolchainError::InvalidState(format!(
                        "garbage-collection trash path already exists: {}",
                        trash.display()
                    )));
                }
                fs::create_dir_all(
                    trash
                        .parent()
                        .ok_or_else(|| ToolchainError::UnsafePath(trash.clone()))?,
                )?;
                validate_managed_path(&trash)?;
                fs::rename(&original, &trash)?;
                moved.push((original, trash));
            }
            Ok(())
        })();
        if let Err(error) = move_result {
            self.rollback_gc_moves(&moved)?;
            state.pending_gc.clear();
            self.state.save(&state)?;
            return Err(error);
        }

        let removed = candidates
            .iter()
            .map(|(id, _)| id.clone())
            .collect::<Vec<_>>();
        for id in &removed {
            state.records.remove(id);
        }
        if let Err(error) = self.state.save(&state) {
            self.rollback_gc_moves(&moved)?;
            return Err(error);
        }

        for (_, trash) in &moved {
            if trash.exists() {
                fs::remove_dir_all(trash)?;
            }
        }
        state.pending_gc.clear();
        self.state.save(&state)?;
        Ok(removed)
    }

    fn load_recovered_state(&self) -> Result<ToolchainState, ToolchainError> {
        let mut state = self.state.load()?;
        self.recover_pending_gc(&mut state)?;
        self.reconcile_orphans(&state)?;
        Ok(state)
    }

    fn recover_pending_gc(&self, state: &mut ToolchainState) -> Result<(), ToolchainError> {
        if state.pending_gc.is_empty() {
            return Ok(());
        }
        for item in &state.pending_gc {
            let original = self.app_relative_path(&item.original_dir)?;
            let trash = self.app_relative_path(&item.trash_dir)?;
            if !original.starts_with(self.paths.toolchain_dir())
                || original.starts_with(self.paths.toolchain_trash_dir())
                || !trash.starts_with(self.paths.toolchain_trash_dir())
            {
                return Err(ToolchainError::UnsafePath(original));
            }
            validate_managed_path(&original)?;
            validate_managed_path(&trash)?;
            if state.records.contains_key(&item.id) {
                if original.exists() && trash.exists() {
                    return Err(ToolchainError::InvalidState(format!(
                        "pending garbage collection has both original and trash for {}",
                        item.id
                    )));
                }
                if !original.exists() && trash.exists() {
                    fs::create_dir_all(
                        original
                            .parent()
                            .ok_or_else(|| ToolchainError::UnsafePath(original.clone()))?,
                    )?;
                    validate_managed_path(&original)?;
                    fs::rename(&trash, &original)?;
                }
            } else if trash.exists() {
                fs::remove_dir_all(&trash)?;
            } else if original.exists() {
                return Err(ToolchainError::InvalidState(format!(
                    "completed garbage collection left an original directory for {}",
                    item.id
                )));
            }
        }
        state.pending_gc.clear();
        self.state.save(state)
    }

    fn reconcile_orphans(&self, state: &ToolchainState) -> Result<(), ToolchainError> {
        let known = state
            .records
            .values()
            .map(|record| record.install_dir.clone())
            .collect::<BTreeSet<_>>();
        for kind in [ToolchainKind::Jdk, ToolchainKind::Node] {
            let kind_root = self.paths.toolchain_dir().join(kind.as_str());
            if !kind_root.exists() {
                continue;
            }
            validate_managed_path(&kind_root)?;
            for entry in fs::read_dir(&kind_root)? {
                let entry = entry?;
                let path = entry.path();
                let metadata = fs::symlink_metadata(&path)?;
                if metadata.file_type().is_symlink() || is_reparse_point(&path)? {
                    return Err(ToolchainError::UnsafePath(path));
                }
                let name = entry.file_name().to_string_lossy().into_owned();
                if name.starts_with('.') {
                    if metadata.is_dir() {
                        validate_managed_path(&path)?;
                        fs::remove_dir_all(path)?;
                    }
                    continue;
                }
                let relative = path
                    .strip_prefix(&self.paths.app_root)
                    .map_err(|_| ToolchainError::UnsafePath(path.clone()))?
                    .to_path_buf();
                if metadata.is_dir()
                    && looks_like_toolchain_id(kind, &name)
                    && !known.contains(&relative)
                {
                    validate_managed_path(&path)?;
                    fs::remove_dir_all(path)?;
                }
            }
        }
        Ok(())
    }

    fn app_relative_path(&self, relative: &Path) -> Result<PathBuf, ToolchainError> {
        if !is_safe_relative_path(relative) {
            return Err(ToolchainError::UnsafePath(relative.to_path_buf()));
        }
        let path = self.paths.app_root.join(relative);
        if !path.starts_with(&self.paths.app_root) {
            return Err(ToolchainError::UnsafePath(path));
        }
        validate_managed_path(&path)?;
        Ok(path)
    }

    fn rollback_gc_moves(&self, moved: &[(PathBuf, PathBuf)]) -> Result<(), ToolchainError> {
        for (original, trash) in moved.iter().rev() {
            if !trash.exists() {
                continue;
            }
            if original.exists() {
                return Err(ToolchainError::InvalidState(format!(
                    "cannot roll back garbage collection because {} exists",
                    original.display()
                )));
            }
            validate_managed_path(original)?;
            validate_managed_path(trash)?;
            fs::rename(trash, original)?;
        }
        Ok(())
    }

    fn install_root(
        &self,
        descriptor: &ToolchainDescriptor,
        id: &str,
    ) -> Result<PathBuf, ToolchainError> {
        let root = self
            .paths
            .toolchain_dir()
            .join(descriptor.kind.as_str())
            .join(id);
        if !root.starts_with(self.paths.app_root.join("toolchain")) {
            return Err(ToolchainError::UnsafePath(root));
        }
        Ok(root)
    }

    fn validate_existing_record(
        &self,
        record: &ToolchainRecord,
        descriptor: &ToolchainDescriptor,
        id: &str,
    ) -> Result<(), ToolchainError> {
        let expected_install_dir = self
            .install_root(descriptor, id)?
            .strip_prefix(&self.paths.app_root)
            .map_err(|_| ToolchainError::UnsafePath(record.install_dir.clone()))?
            .to_path_buf();
        let artifact_matches = record.id == id
            && record.kind == descriptor.kind
            && record.version == descriptor.version
            && record.platform == descriptor.platform
            && record.architecture == descriptor.architecture
            && record.url == descriptor.url
            && record.sha256 == descriptor.sha256.to_ascii_lowercase()
            && record.archive == descriptor.archive
            && record.install_dir == expected_install_dir;
        if !artifact_matches {
            return Err(ToolchainError::IncompatibleMetadata {
                id: id.to_owned(),
                reason: "stored artifact identity does not match descriptor".to_owned(),
            });
        }
        Ok(())
    }

    fn resolve_record(
        &self,
        record: &ToolchainRecord,
    ) -> Result<ResolvedToolchain, ToolchainError> {
        let root = self.record_root(record)?;
        if !root.is_dir() {
            return Err(ToolchainError::Incomplete(root));
        }
        let home_dir = resolve_home_dir(&root, &record.home_dir)?;
        let executables = resolve_executable_paths(&root, &record.executables)?;
        Ok(ResolvedToolchain {
            id: record.id.clone(),
            kind: record.kind,
            version: record.version.clone(),
            root,
            home_dir,
            executables,
        })
    }

    fn record_root(&self, record: &ToolchainRecord) -> Result<PathBuf, ToolchainError> {
        if !is_safe_relative_path(&record.install_dir) {
            return Err(ToolchainError::UnsafePath(record.install_dir.clone()));
        }
        let root = self.paths.app_root.join(&record.install_dir);
        if !root.starts_with(self.paths.toolchain_dir())
            || root.starts_with(self.paths.toolchain_trash_dir())
        {
            return Err(ToolchainError::UnsafePath(root));
        }
        validate_managed_path(&root)?;
        Ok(root)
    }
}

fn resolve_executable_paths(
    root: &Path,
    paths: &BTreeMap<String, PathBuf>,
) -> Result<BTreeMap<String, PathBuf>, ToolchainError> {
    let mut resolved = BTreeMap::new();
    for (name, relative) in paths {
        if !is_safe_relative_path(relative) {
            return Err(ToolchainError::UnsafePath(relative.clone()));
        }
        let path = root.join(relative);
        if !path.starts_with(root) {
            return Err(ToolchainError::MissingExecutable {
                name: name.clone(),
                root: root.to_path_buf(),
            });
        }
        validate_toolchain_file(root, &path).map_err(|error| match error {
            ToolchainError::UnsafePath(_) => error,
            _ => ToolchainError::MissingExecutable {
                name: name.clone(),
                root: root.to_path_buf(),
            },
        })?;
        resolved.insert(name.clone(), path);
    }
    Ok(resolved)
}

fn validate_toolchain_file(root: &Path, path: &Path) -> Result<(), ToolchainError> {
    let parent = path
        .parent()
        .ok_or_else(|| ToolchainError::UnsafePath(path.to_path_buf()))?;
    validate_managed_path(parent)?;
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() {
        let canonical_root = fs::canonicalize(root)?;
        let canonical_target = fs::canonicalize(path)?;
        if !canonical_target.starts_with(&canonical_root) || !canonical_target.is_file() {
            return Err(ToolchainError::UnsafePath(path.to_path_buf()));
        }
    } else if !metadata.is_file() {
        return Err(ToolchainError::MissingExecutable {
            name: path.display().to_string(),
            root: root.to_path_buf(),
        });
    }
    Ok(())
}

fn resolve_home_dir(root: &Path, relative: &Path) -> Result<PathBuf, ToolchainError> {
    if !is_safe_relative_path(relative) {
        return Err(ToolchainError::UnsafePath(relative.to_path_buf()));
    }
    let home = root.join(relative);
    if !home.starts_with(root) || !home.is_dir() {
        return Err(ToolchainError::Incomplete(home));
    }
    validate_managed_path(&home)?;
    Ok(relative.to_path_buf())
}

fn is_safe_relative_path(path: &Path) -> bool {
    if path.as_os_str().is_empty() || path.is_absolute() {
        return false;
    }
    let mut has_component = false;
    for component in path.components() {
        match component {
            Component::Normal(value) => {
                has_component = true;
                if value.to_string_lossy().contains(':') {
                    return false;
                }
            }
            Component::CurDir => has_component = true,
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => return false,
        }
    }
    has_component
}

fn validate_managed_path(path: &Path) -> Result<(), ToolchainError> {
    for ancestor in path.ancestors().collect::<Vec<_>>().into_iter().rev() {
        match fs::symlink_metadata(ancestor) {
            Ok(metadata) => {
                if metadata.file_type().is_symlink() || is_reparse_point(ancestor)? {
                    return Err(ToolchainError::UnsafePath(ancestor.to_path_buf()));
                }
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
}

fn is_sha256(value: &str) -> bool {
    let value = value.trim();
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn safe_id_part(value: &str) -> String {
    value
        .chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() || matches!(character, '.' | '-' | '_') {
                character
            } else {
                '-'
            }
        })
        .collect()
}

fn join_paths(paths: &[PathBuf]) -> String {
    let separator = if cfg!(windows) { ';' } else { ':' };
    paths
        .iter()
        .map(|path| path.display().to_string())
        .collect::<Vec<_>>()
        .join(&separator.to_string())
}

pub(crate) fn safe_os_utility_paths(platform: Platform) -> Vec<PathBuf> {
    match platform {
        Platform::Linux => [PathBuf::from("/usr/bin"), PathBuf::from("/bin")].to_vec(),
        Platform::Windows => {
            let Some(root) = std::env::var_os("SystemRoot").map(PathBuf::from) else {
                return Vec::new();
            };
            vec![
                root.join("System32"),
                root.join("System32").join("WindowsPowerShell").join("v1.0"),
                root,
            ]
        }
    }
}

struct StagingGuard {
    path: PathBuf,
    armed: bool,
}

impl StagingGuard {
    fn new(path: PathBuf) -> Self {
        Self { path, armed: true }
    }

    fn disarm(&mut self) {
        self.armed = false;
    }
}

impl Drop for StagingGuard {
    fn drop(&mut self) {
        if self.armed && self.path.exists() {
            let _ = fs::remove_dir_all(&self.path);
        }
    }
}

fn looks_like_toolchain_id(kind: ToolchainKind, name: &str) -> bool {
    let prefix = format!("{}-", kind.as_str());
    name.starts_with(&prefix) && name.rsplit('-').next().map(is_sha256).unwrap_or(false)
}

fn default_home_dir() -> PathBuf {
    PathBuf::from(".")
}

fn validate_maven_wrapper_properties(checkout: &Path) -> Result<(), ToolchainError> {
    let properties = checkout
        .join(".mvn")
        .join("wrapper")
        .join("maven-wrapper.properties");
    if !properties.is_file() {
        return Err(ToolchainError::InvalidDescriptor(
            "Maven Wrapper properties are missing".to_owned(),
        ));
    }
    validate_managed_path(&properties)?;
    let mut distribution_type = None;
    let mut distribution_sha256 = None;
    let contents = fs::read_to_string(&properties)?;
    for line in contents.lines() {
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        match key.trim() {
            "distributionType" => distribution_type = Some(value.trim()),
            "distributionSha256Sum" => distribution_sha256 = Some(value.trim()),
            _ => {}
        }
    }
    if distribution_type != Some("only-script") {
        return Err(ToolchainError::InvalidDescriptor(
            "Maven Wrapper must use distributionType=only-script".to_owned(),
        ));
    }
    if !distribution_sha256.map(is_sha256).unwrap_or(false) {
        return Err(ToolchainError::InvalidDescriptor(
            "Maven Wrapper distributionSha256Sum is required".to_owned(),
        ));
    }
    Ok(())
}

fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::download::{DownloadResponse, DownloadTransport, ResumableDownloader};
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::io::Cursor;
    use std::sync::{Arc, Mutex};
    use tar::{Builder, Header};
    use tempfile::tempdir;

    struct FakeTransport {
        bytes: Vec<u8>,
        calls: Arc<Mutex<usize>>,
    }

    impl DownloadTransport for FakeTransport {
        fn get(
            &self,
            _url: &str,
            _range_from: Option<u64>,
            _timeout: std::time::Duration,
        ) -> Result<DownloadResponse, DownloadError> {
            *self.calls.lock().unwrap() += 1;
            Ok(DownloadResponse {
                status: 200,
                content_range_start: None,
                body: Box::new(Cursor::new(self.bytes.clone())),
            })
        }
    }

    fn paths(root: &Path) -> InstallationPaths {
        InstallationPaths {
            platform: Platform::Linux,
            architecture: TargetArchitecture::X64,
            app_root: root.join("app"),
            user_data_root: root.join("data"),
            state_root: root.join("state"),
            cache_root: root.join("cache"),
        }
    }

    #[test]
    fn descriptor_requires_https_and_sha256() {
        let descriptor = ToolchainDescriptor::new(
            ToolchainKind::Node,
            "24.0.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "http://example.test/node.tar.gz",
            "bad",
            ArchiveFormat::TarGz,
        )
        .executable("node", "bin/node");
        assert!(matches!(
            descriptor.validate_for(Platform::Linux, &TargetArchitecture::X64),
            Err(ToolchainError::InvalidDescriptor(_))
        ));
        let unsafe_home = ToolchainDescriptor::new(
            ToolchainKind::Jdk,
            "21.0.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://example.test/jdk.tar.gz",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ArchiveFormat::TarGz,
        )
        .home_dir("../outside")
        .executable("java", "bin/java");
        assert!(matches!(
            unsafe_home.validate_for(Platform::Linux, &TargetArchitecture::X64),
            Err(ToolchainError::InvalidDescriptor(_))
        ));
    }

    #[test]
    fn managed_environment_preserves_safe_inherited_environment() {
        let environment = ManagedEnvironment {
            variables: BTreeMap::from([
                ("PATH".to_owned(), "/managed/bin".to_owned()),
                ("JAVA_HOME".to_owned(), "/managed/jdk".to_owned()),
            ]),
            path: Vec::new(),
        };
        let applied = environment.apply_to(CommandSpec::new("tool").inherit_env("CUSTOM_SAFE"));

        assert!(applied.environment_allowlist.contains(&"PATH".to_owned()));
        assert!(applied
            .environment_allowlist
            .contains(&"CUSTOM_SAFE".to_owned()));
        assert!(!applied
            .environment_allowlist
            .contains(&"HARMONIA_SECRET".to_owned()));
        assert_eq!(
            applied.environment.get("PATH"),
            Some(&"/managed/bin".to_owned())
        );
        assert_eq!(
            applied.environment.get("JAVA_HOME"),
            Some(&"/managed/jdk".to_owned())
        );
    }

    #[test]
    fn official_catalog_keeps_exact_descriptor_identity() {
        let catalog = OfficialToolchainCatalog::from_json(
            br#"{
                "schema_version": 1,
                "descriptors": [{
                    "kind": "node",
                    "version": "24.21.0",
                    "platform": "Linux",
                    "architecture": "X64",
                    "url": "https://nodejs.org/dist/v24.21.0/node-v24.21.0-linux-x64.tar.gz",
                    "sha256": "6e1db87ef58b8819e5d5402eff1536491b18edd8eb7bee5ef7897876e88dc5ff",
                    "archive": "tar.gz",
                    "executables": {"node": "node-v24.21.0-linux-x64/bin/node"}
                }]
            }"#,
        )
        .unwrap();
        let descriptor = catalog
            .descriptor(
                ToolchainKind::Node,
                "24.21.0",
                Platform::Linux,
                &TargetArchitecture::X64,
            )
            .unwrap();
        assert_eq!(descriptor.sha256.len(), 64);
        assert_eq!(descriptor.id(), catalog.descriptors[0].id());
    }

    #[test]
    fn manager_reuses_verified_archive_and_keeps_versions_side_by_side() {
        let root = tempdir().unwrap();
        let payload = archive_with_file("node", b"#!/bin/sh\n");
        let digest = crate::checksum::sha256_file({
            let path = root.path().join("payload");
            fs::write(&path, &payload).unwrap();
            path
        })
        .unwrap();
        let calls = Arc::new(Mutex::new(0));
        let downloader = ResumableDownloader::new(FakeTransport {
            bytes: payload.clone(),
            calls: calls.clone(),
        });
        let manager = ToolchainManager::new(paths(root.path()), downloader);
        let descriptor = ToolchainDescriptor::new(
            ToolchainKind::Node,
            "24.0.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://nodejs.org/node.tar.gz",
            digest.clone(),
            ArchiveFormat::TarGz,
        )
        .executable("node", "node");
        let first = manager.ensure(&descriptor).unwrap();
        assert!(first.executable("node").unwrap().is_file());
        assert_eq!(*calls.lock().unwrap(), 1);
        manager.ensure(&descriptor).unwrap();
        assert_eq!(*calls.lock().unwrap(), 1);

        let second_descriptor = ToolchainDescriptor::new(
            ToolchainKind::Node,
            "24.1.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://nodejs.org/node.tar.gz",
            digest,
            ArchiveFormat::TarGz,
        )
        .executable("node", "node");
        manager.ensure(&second_descriptor).unwrap();
        assert_ne!(first.id, second_descriptor.id());
        assert!(manager
            .paths()
            .toolchain_dir()
            .join("node")
            .join(first.id)
            .is_dir());
        assert!(manager
            .paths()
            .toolchain_dir()
            .join("node")
            .join(second_descriptor.id())
            .is_dir());
        assert_eq!(descriptor.id().split('-').count(), 5);
    }

    #[test]
    fn reuse_updates_valid_descriptor_metadata_for_same_artifact_id() {
        let root = tempdir().unwrap();
        let payload = archive_with_files(&[("node", b"node"), ("node-home/bin/node", b"node")]);
        let digest = crate::checksum::sha256_file({
            let path = root.path().join("payload");
            fs::write(&path, &payload).unwrap();
            path
        })
        .unwrap();
        let calls = Arc::new(Mutex::new(0));
        let manager = ToolchainManager::new(
            paths(root.path()),
            ResumableDownloader::new(FakeTransport {
                bytes: payload,
                calls: calls.clone(),
            }),
        );
        let initial = ToolchainDescriptor::new(
            ToolchainKind::Node,
            "24.0.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://nodejs.org/node.tar.gz",
            digest.clone(),
            ArchiveFormat::TarGz,
        )
        .executable("node", "node");
        let installed = manager.ensure(&initial).unwrap();

        let corrected = ToolchainDescriptor::new(
            ToolchainKind::Node,
            "24.0.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://nodejs.org/node.tar.gz",
            digest,
            ArchiveFormat::TarGz,
        )
        .home_dir("node-home")
        .executable("node", "node-home/bin/node");
        let reused = manager.ensure(&corrected).unwrap();

        assert_eq!(reused.id, installed.id);
        assert_eq!(reused.home_dir, PathBuf::from("node-home"));
        assert_eq!(
            reused.executable("node").unwrap(),
            &installed.root.join("node-home/bin/node")
        );
        assert_eq!(*calls.lock().unwrap(), 1);
        let record = manager
            .state_store()
            .load()
            .unwrap()
            .records
            .remove(&corrected.id())
            .unwrap();
        assert_eq!(record.home_dir, PathBuf::from("node-home"));
        assert_eq!(
            record.executables.get("node"),
            Some(&PathBuf::from("node-home/bin/node"))
        );

        let changed_source = ToolchainDescriptor::new(
            ToolchainKind::Node,
            "24.0.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://mirror.example/node.tar.gz",
            record.sha256,
            ArchiveFormat::TarGz,
        )
        .home_dir("node-home")
        .executable("node", "node-home/bin/node");
        assert!(matches!(
            manager.ensure(&changed_source),
            Err(ToolchainError::IncompatibleMetadata { .. })
        ));
    }

    #[test]
    fn jdk_home_dir_points_at_nested_archive_directory() {
        let root = tempdir().unwrap();
        let payload = archive_with_files(&[("jdk-21.0.8/bin/java", b"java")]);
        let digest = crate::checksum::sha256_file({
            let path = root.path().join("payload");
            fs::write(&path, &payload).unwrap();
            path
        })
        .unwrap();
        let manager = ToolchainManager::new(
            paths(root.path()),
            ResumableDownloader::new(FakeTransport {
                bytes: payload,
                calls: Arc::new(Mutex::new(0)),
            }),
        );
        let descriptor = ToolchainDescriptor::new(
            ToolchainKind::Jdk,
            "21.0.8",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://adoptium.net/jdk.tar.gz",
            digest,
            ArchiveFormat::TarGz,
        )
        .home_dir("jdk-21.0.8")
        .executable("java", "jdk-21.0.8/bin/java");
        let installed = manager.ensure(&descriptor).unwrap();
        let environment = manager.environment(&[installed.id]).unwrap();
        assert_eq!(
            environment.variables.get("JAVA_HOME").unwrap(),
            &installed.root.join("jdk-21.0.8").display().to_string()
        );
    }

    #[test]
    fn environment_rejects_multiple_toolchains_of_one_kind() {
        let root = tempdir().unwrap();
        let payload = archive_with_file("node", b"node");
        let digest = crate::checksum::sha256_file({
            let path = root.path().join("payload");
            fs::write(&path, &payload).unwrap();
            path
        })
        .unwrap();
        let manager = ToolchainManager::new(
            paths(root.path()),
            ResumableDownloader::new(FakeTransport {
                bytes: payload,
                calls: Arc::new(Mutex::new(0)),
            }),
        );
        let first = ToolchainDescriptor::new(
            ToolchainKind::Node,
            "24.0.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://nodejs.org/node.tar.gz",
            digest.clone(),
            ArchiveFormat::TarGz,
        )
        .executable("node", "node");
        let second = ToolchainDescriptor::new(
            ToolchainKind::Node,
            "24.1.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://nodejs.org/node.tar.gz",
            digest,
            ArchiveFormat::TarGz,
        )
        .executable("node", "node");
        let first = manager.ensure(&first).unwrap();
        let second = manager.ensure(&second).unwrap();
        assert!(matches!(
            manager.environment(&[first.id, second.id]),
            Err(ToolchainError::DuplicateKind(ToolchainKind::Node))
        ));
    }

    #[test]
    fn maven_wrapper_requires_distribution_checksum() {
        let root = tempdir().unwrap();
        let manager = ToolchainManager::new(
            paths(root.path()),
            ResumableDownloader::new(FakeTransport {
                bytes: Vec::new(),
                calls: Arc::new(Mutex::new(0)),
            }),
        );
        let checkout = root.path().join("checkout");
        fs::create_dir_all(checkout.join(".mvn/wrapper")).unwrap();
        fs::write(checkout.join("mvnw"), b"#!/bin/sh\n").unwrap();
        fs::write(
            checkout.join(".mvn/wrapper/maven-wrapper.properties"),
            "distributionType=only-script\ndistributionSha256Sum=83aaf914c785c9faed661f223000a92d1de9553f5c82d3b4362e66d9c031625f\n",
        )
        .unwrap();
        assert_eq!(
            manager.maven_wrapper_path(&checkout).unwrap(),
            checkout.join("mvnw")
        );
        fs::write(
            checkout.join(".mvn/wrapper/maven-wrapper.properties"),
            "distributionType=only-script\n",
        )
        .unwrap();
        assert!(matches!(
            manager.maven_wrapper_path(&checkout),
            Err(ToolchainError::InvalidDescriptor(_))
        ));
    }

    #[test]
    fn recovery_removes_orphan_final_and_staging_installations() {
        let root = tempdir().unwrap();
        let payload = archive_with_file("node", b"node");
        let digest = crate::checksum::sha256_file({
            let path = root.path().join("payload");
            fs::write(&path, &payload).unwrap();
            path
        })
        .unwrap();
        let manager = ToolchainManager::new(
            paths(root.path()),
            ResumableDownloader::new(FakeTransport {
                bytes: payload,
                calls: Arc::new(Mutex::new(0)),
            }),
        );
        let descriptor = ToolchainDescriptor::new(
            ToolchainKind::Node,
            "24.0.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://nodejs.org/node.tar.gz",
            digest,
            ArchiveFormat::TarGz,
        )
        .executable("node", "node");
        let installed = manager.ensure(&descriptor).unwrap();
        let mut state = manager.state_store().load().unwrap();
        state.records.remove(&installed.id);
        manager.state_store().save(&state).unwrap();
        let staging = manager.paths().toolchain_dir().join("node/.orphan-staging");
        fs::create_dir_all(&staging).unwrap();
        fs::write(staging.join("partial"), b"partial").unwrap();
        assert!(manager.resolve(&installed.id).is_err());
        assert!(!installed.root.exists());
        assert!(!staging.exists());
    }

    #[test]
    fn recovery_restores_precommit_gc_and_finishes_postcommit_gc() {
        let root = tempdir().unwrap();
        let payload = archive_with_file("node", b"node");
        let digest = crate::checksum::sha256_file({
            let path = root.path().join("payload");
            fs::write(&path, &payload).unwrap();
            path
        })
        .unwrap();
        let manager = ToolchainManager::new(
            paths(root.path()),
            ResumableDownloader::new(FakeTransport {
                bytes: payload,
                calls: Arc::new(Mutex::new(0)),
            }),
        );
        let descriptor = ToolchainDescriptor::new(
            ToolchainKind::Node,
            "24.0.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://nodejs.org/node.tar.gz",
            digest,
            ArchiveFormat::TarGz,
        )
        .executable("node", "node");
        let installed = manager.ensure(&descriptor).unwrap();
        let original = installed.root.clone();
        let original_dir = manager
            .state_store()
            .load()
            .unwrap()
            .records
            .get(&installed.id)
            .unwrap()
            .install_dir
            .clone();
        let trash_dir = PathBuf::from(format!("toolchain/.trash/recovery/{}", installed.id));
        let trash = manager.paths().app_root.join(&trash_dir);
        fs::create_dir_all(trash.parent().unwrap()).unwrap();
        fs::rename(&original, &trash).unwrap();
        let mut state = manager.state_store().load().unwrap();
        state.pending_gc = vec![PendingGarbageCollection {
            id: installed.id.clone(),
            original_dir: original_dir.clone(),
            trash_dir: trash_dir.clone(),
        }];
        manager.state_store().save(&state).unwrap();
        assert!(manager.resolve(&installed.id).is_ok());
        assert!(original.exists());
        assert!(!trash.exists());
        assert!(manager.state_store().load().unwrap().pending_gc.is_empty());

        fs::rename(&original, &trash).unwrap();
        let mut state = manager.state_store().load().unwrap();
        state.records.remove(&installed.id);
        state.pending_gc = vec![PendingGarbageCollection {
            id: installed.id.clone(),
            original_dir,
            trash_dir,
        }];
        manager.state_store().save(&state).unwrap();
        assert!(manager.resolve(&installed.id).is_err());
        assert!(!trash.exists());
        assert!(manager.state_store().load().unwrap().pending_gc.is_empty());
    }

    #[test]
    fn garbage_collection_preserves_current_and_active_transaction_toolchains() {
        let root = tempdir().unwrap();
        let payload = archive_with_file("node", b"node");
        let digest = crate::checksum::sha256_file({
            let path = root.path().join("payload");
            fs::write(&path, &payload).unwrap();
            path
        })
        .unwrap();
        let manager = ToolchainManager::new(
            paths(root.path()),
            ResumableDownloader::new(FakeTransport {
                bytes: payload,
                calls: Arc::new(Mutex::new(0)),
            }),
        );
        let current = ToolchainDescriptor::new(
            ToolchainKind::Node,
            "24.0.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://nodejs.org/node.tar.gz",
            digest.clone(),
            ArchiveFormat::TarGz,
        )
        .executable("node", "node");
        let active = ToolchainDescriptor::new(
            ToolchainKind::Node,
            "2.0.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://nodejs.org/node.tar.gz",
            digest.clone(),
            ArchiveFormat::TarGz,
        )
        .executable("node", "node");
        let unreferenced = ToolchainDescriptor::new(
            ToolchainKind::Jdk,
            "21.0.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://adoptium.net/jdk.tar.gz",
            digest,
            ArchiveFormat::TarGz,
        )
        .executable("java", "node");
        manager.ensure(&current).unwrap();
        manager.ensure(&active).unwrap();
        manager.ensure(&unreferenced).unwrap();

        let store = StateStore::new(manager.paths().clone());
        let mut installation = store.load_installation().unwrap();
        installation
            .current_toolchains
            .insert("node".to_owned(), current.id());
        store.save_installation(&installation).unwrap();
        let mut transaction = crate::state::Transaction::begin(
            store.clone(),
            crate::state::OperationKind::Update,
            None,
            Some("target".to_owned()),
            Vec::new(),
        )
        .unwrap();
        transaction
            .pin_toolchain("node-previous", active.id())
            .unwrap();

        let removed = manager.collect_garbage(&store).unwrap();
        assert_eq!(removed, vec![unreferenced.id()]);
        assert!(manager.resolve(&current.id()).is_ok());
        assert!(manager.resolve(&active.id()).is_ok());
        assert!(manager.resolve(&unreferenced.id()).is_err());
    }

    fn archive_with_file(name: &str, content: &[u8]) -> Vec<u8> {
        archive_with_files(&[(name, content)])
    }

    fn archive_with_files(files: &[(&str, &[u8])]) -> Vec<u8> {
        let encoder = GzEncoder::new(Vec::new(), Compression::fast());
        let mut builder = Builder::new(encoder);
        for (name, content) in files {
            let mut header = Header::new_gnu();
            header.set_size(content.len() as u64);
            header.set_mode(0o755);
            header.set_cksum();
            builder.append_data(&mut header, *name, *content).unwrap();
        }
        let encoder = builder.into_inner().unwrap();
        encoder.finish().unwrap()
    }
}
