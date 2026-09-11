use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
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
    Git,
}

impl ToolchainKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Jdk => "jdk",
            Self::Node => "node",
            Self::Git => "git",
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
            executables: BTreeMap::new(),
        }
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
    pub install_dir: PathBuf,
    pub executables: BTreeMap<String, PathBuf>,
    pub installed_at_ms: u128,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct ToolchainState {
    pub schema_version: u32,
    pub records: BTreeMap<String, ToolchainRecord>,
}

impl Default for ToolchainState {
    fn default() -> Self {
        Self {
            schema_version: TOOLCHAIN_STATE_SCHEMA_VERSION,
            records: BTreeMap::new(),
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
        Ok(state)
    }

    pub fn save(&self, state: &ToolchainState) -> Result<(), ToolchainError> {
        if state.schema_version != TOOLCHAIN_STATE_SCHEMA_VERSION {
            return Err(ToolchainError::UnsupportedStateSchema(state.schema_version));
        }
        validate_managed_path(&self.paths.state_root)?;
        fs::create_dir_all(&self.paths.state_root)?;
        validate_managed_path(&self.paths.toolchain_state_path())?;
        atomic_write_json(&self.paths.toolchain_state_path(), state)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ResolvedToolchain {
    pub id: String,
    pub kind: ToolchainKind,
    pub version: String,
    pub root: PathBuf,
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
        command
            .without_inherited_environment()
            .envs(self.variables.clone())
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
        let mut state = self.state.load()?;
        if let Some(record) = state.records.get(&id) {
            return self.resolve_record(record);
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

        let kind_root = self.paths.toolchain_dir().join(descriptor.kind.as_str());
        validate_managed_path(&kind_root)?;
        fs::create_dir_all(&kind_root)?;
        validate_managed_path(&kind_root)?;
        let staging = kind_root.join(format!(".{}.{}", id, Uuid::new_v4()));
        extract_archive(&receipt.path, descriptor.archive, &staging)?;
        resolve_executable_paths(&staging, &descriptor.executables)?;
        let install_dir = final_root
            .strip_prefix(&self.paths.app_root)
            .map_err(|_| ToolchainError::UnsafePath(final_root.clone()))?
            .to_path_buf();
        if final_root.exists() {
            let _ = fs::remove_dir_all(&staging);
            return Err(ToolchainError::Occupied(final_root));
        }
        fs::rename(&staging, &final_root)?;
        let record = ToolchainRecord {
            id: id.clone(),
            kind: descriptor.kind,
            version: descriptor.version.clone(),
            platform: descriptor.platform,
            architecture: descriptor.architecture.clone(),
            url: descriptor.url.clone(),
            sha256: descriptor.sha256.to_ascii_lowercase(),
            archive: descriptor.archive,
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
        let state = self.state.load()?;
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
        let mut variables = BTreeMap::new();
        for toolchain in &resolved {
            if toolchain.kind == ToolchainKind::Jdk {
                variables.insert("JAVA_HOME".to_owned(), toolchain.root.display().to_string());
            }
            for executable in toolchain.executables.values() {
                if let Some(parent) = executable.parent() {
                    if seen.insert(parent.to_path_buf()) {
                        path.push(parent.to_path_buf());
                    }
                }
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
        let mut state = self.state.load()?;
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
        let mut removed = Vec::new();
        for (id, root) in candidates {
            if root.exists() {
                fs::remove_dir_all(root)?;
            }
            state.records.remove(&id);
            removed.push(id);
        }
        if !removed.is_empty() {
            self.state.save(&state)?;
        }
        Ok(removed)
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

    fn resolve_record(
        &self,
        record: &ToolchainRecord,
    ) -> Result<ResolvedToolchain, ToolchainError> {
        let root = self.record_root(record)?;
        if !root.is_dir() {
            return Err(ToolchainError::Incomplete(root));
        }
        let executables = resolve_executable_paths(&root, &record.executables)?;
        Ok(ResolvedToolchain {
            id: record.id.clone(),
            kind: record.kind,
            version: record.version.clone(),
            root,
            executables,
        })
    }

    fn record_root(&self, record: &ToolchainRecord) -> Result<PathBuf, ToolchainError> {
        if !is_safe_relative_path(&record.install_dir) {
            return Err(ToolchainError::UnsafePath(record.install_dir.clone()));
        }
        let root = self.paths.app_root.join(&record.install_dir);
        if !root.starts_with(self.paths.toolchain_dir()) {
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
        if !path.starts_with(root) || !path.is_file() {
            return Err(ToolchainError::MissingExecutable {
                name: name.clone(),
                root: root.to_path_buf(),
            });
        }
        validate_managed_path(&path)?;
        resolved.insert(name.clone(), path);
    }
    Ok(resolved)
}

fn is_safe_relative_path(path: &Path) -> bool {
    if path.as_os_str().is_empty() || path.is_absolute() {
        return false;
    }
    let mut has_normal = false;
    for component in path.components() {
        match component {
            Component::Normal(value) => {
                has_normal = true;
                if value.to_string_lossy().contains(':') {
                    return false;
                }
            }
            Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => return false,
        }
    }
    has_normal
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

fn atomic_write_json<T: Serialize>(path: &Path, value: &T) -> Result<(), ToolchainError> {
    let encoded = serde_json::to_vec_pretty(value)?;
    let parent = path
        .parent()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "state path has no parent"))?;
    fs::create_dir_all(parent)?;
    let file_name = path.file_name().ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, "state path has no file name")
    })?;
    let temporary = parent.join(format!(
        ".{}.{}.tmp",
        file_name.to_string_lossy(),
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
            ToolchainKind::Git,
            "2.0.0",
            Platform::Linux,
            TargetArchitecture::X64,
            "https://git-scm.com/git.tar.gz",
            digest.clone(),
            ArchiveFormat::TarGz,
        )
        .executable("git", "node");
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
        transaction.pin_toolchain("git", active.id()).unwrap();

        let removed = manager.collect_garbage(&store).unwrap();
        assert_eq!(removed, vec![unreferenced.id()]);
        assert!(manager.resolve(&current.id()).is_ok());
        assert!(manager.resolve(&active.id()).is_ok());
        assert!(manager.resolve(&unreferenced.id()).is_err());
    }

    fn archive_with_file(name: &str, content: &[u8]) -> Vec<u8> {
        let encoder = GzEncoder::new(Vec::new(), Compression::fast());
        let mut builder = Builder::new(encoder);
        let mut header = Header::new_gnu();
        header.set_size(content.len() as u64);
        header.set_mode(0o755);
        header.set_cksum();
        builder.append_data(&mut header, name, content).unwrap();
        let encoder = builder.into_inner().unwrap();
        encoder.finish().unwrap()
    }
}
