use serde::{Deserialize, Serialize};
use std::env;
use std::path::{Path, PathBuf};

use thiserror::Error;

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub enum Platform {
    Windows,
    Linux,
}

impl Platform {
    pub fn current() -> Result<Self, PathError> {
        match env::consts::OS {
            "windows" => Ok(Self::Windows),
            "linux" => Ok(Self::Linux),
            other => Err(PathError::UnsupportedPlatform(other.to_owned())),
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Windows => "windows",
            Self::Linux => "linux",
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub enum TargetArchitecture {
    X64,
    Other(String),
}

impl TargetArchitecture {
    pub fn current() -> Self {
        match env::consts::ARCH {
            "x86_64" => Self::X64,
            other => Self::Other(other.to_owned()),
        }
    }

    pub fn as_str(&self) -> &str {
        match self {
            Self::X64 => "x64",
            Self::Other(value) => value,
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct PathEnvironment {
    pub home: Option<PathBuf>,
    pub local_app_data: Option<PathBuf>,
    pub app_data: Option<PathBuf>,
    pub xdg_data_home: Option<PathBuf>,
    pub xdg_state_home: Option<PathBuf>,
    pub xdg_cache_home: Option<PathBuf>,
}

impl PathEnvironment {
    pub fn from_process() -> Self {
        Self {
            home: env::var_os("HOME")
                .or_else(|| env::var_os("USERPROFILE"))
                .map(PathBuf::from),
            local_app_data: env::var_os("LOCALAPPDATA").map(PathBuf::from),
            app_data: env::var_os("APPDATA").map(PathBuf::from),
            xdg_data_home: env::var_os("XDG_DATA_HOME").map(PathBuf::from),
            xdg_state_home: env::var_os("XDG_STATE_HOME").map(PathBuf::from),
            xdg_cache_home: env::var_os("XDG_CACHE_HOME").map(PathBuf::from),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct InstallationPaths {
    pub platform: Platform,
    pub architecture: TargetArchitecture,
    pub app_root: PathBuf,
    pub user_data_root: PathBuf,
    pub state_root: PathBuf,
    pub cache_root: PathBuf,
}

#[derive(Debug, Error)]
pub enum PathError {
    #[error("unsupported platform: {0}")]
    UnsupportedPlatform(String),
    #[error("cannot determine an absolute per-user root for {0:?}")]
    MissingUserRoot(Platform),
}

impl InstallationPaths {
    pub fn current() -> Result<Self, PathError> {
        Self::for_environment(
            Platform::current()?,
            TargetArchitecture::current(),
            &PathEnvironment::from_process(),
        )
    }

    pub fn for_environment(
        platform: Platform,
        architecture: TargetArchitecture,
        environment: &PathEnvironment,
    ) -> Result<Self, PathError> {
        let home = absolute_root(platform, environment.home.as_deref());
        let (app_root, user_data_root, state_root, cache_root) = match platform {
            Platform::Windows => {
                let local = configured_or_home(
                    platform,
                    environment.local_app_data.as_deref(),
                    home.as_deref(),
                    "AppData\\Local",
                )?;
                let roaming = configured_or_home(
                    platform,
                    environment.app_data.as_deref(),
                    home.as_deref(),
                    "AppData\\Roaming",
                )?;
                let app = local.join("HarmoniaSuite");
                (
                    app.clone(),
                    roaming.join("HarmoniaSuite"),
                    app.join("state"),
                    app.join("cache"),
                )
            }
            Platform::Linux => {
                let data = configured_or_home(
                    platform,
                    environment.xdg_data_home.as_deref(),
                    home.as_deref(),
                    ".local/share",
                )?;
                let state = configured_or_home(
                    platform,
                    environment.xdg_state_home.as_deref(),
                    home.as_deref(),
                    ".local/state",
                )?;
                let cache = configured_or_home(
                    platform,
                    environment.xdg_cache_home.as_deref(),
                    home.as_deref(),
                    ".cache",
                )?;
                (
                    data.join("harmonia-suite"),
                    data.join("harmonia-suite-data"),
                    state.join("harmonia-suite"),
                    cache.join("harmonia-suite"),
                )
            }
        };
        Ok(Self {
            platform,
            architecture,
            app_root,
            user_data_root,
            state_root,
            cache_root,
        })
    }

    pub fn bin_dir(&self) -> PathBuf {
        self.app_root.join("bin")
    }

    pub fn versions_dir(&self) -> PathBuf {
        self.app_root.join("versions")
    }

    pub fn toolchain_dir(&self) -> PathBuf {
        self.app_root.join("toolchain")
    }

    pub fn toolchain_trash_dir(&self) -> PathBuf {
        self.toolchain_dir().join(".trash")
    }

    pub fn source_dir(&self) -> PathBuf {
        self.app_root.join("source")
    }

    pub fn build_dir(&self) -> PathBuf {
        self.app_root.join("build")
    }

    pub fn diagnostics_dir(&self) -> PathBuf {
        self.state_root.join("diagnostics")
    }

    pub fn build_results_dir(&self) -> PathBuf {
        self.state_root.join("build-results")
    }

    pub fn install_state_path(&self) -> PathBuf {
        self.state_root.join("install.json")
    }

    pub fn transaction_path(&self) -> PathBuf {
        self.state_root.join("transaction.json")
    }

    pub fn bootstrap_operation_path(&self) -> PathBuf {
        self.state_root.join("bootstrap-operation.json")
    }

    pub fn bootstrap_ack_dir(&self) -> PathBuf {
        self.state_root.join("bootstrap-acks")
    }

    pub fn bootstrap_ack_path(&self, operation_id: &str, nonce: &str) -> PathBuf {
        self.bootstrap_ack_dir()
            .join(format!("{operation_id}-{nonce}.json"))
    }

    pub fn update_operation_path(&self) -> PathBuf {
        self.state_root.join("update-operation.json")
    }

    pub fn update_manifest_cache_path(&self) -> PathBuf {
        self.cache_root.join("manifests").join("rolling-main.json")
    }

    pub fn update_manifest_signature_cache_path(&self) -> PathBuf {
        self.cache_root
            .join("manifests")
            .join("rolling-main.json.sig")
    }

    pub fn toolchain_state_path(&self) -> PathBuf {
        self.state_root.join("toolchains.json")
    }

    pub fn lock_path(&self) -> PathBuf {
        self.state_root.join("install.lock")
    }

    pub fn downloads_dir(&self) -> PathBuf {
        self.cache_root.join("downloads")
    }

    pub fn maven_cache_dir(&self) -> PathBuf {
        self.cache_root.join("maven")
    }

    pub fn npm_cache_dir(&self) -> PathBuf {
        self.cache_root.join("npm")
    }

    pub fn managed_paths(&self) -> [PathBuf; 7] {
        [
            self.bin_dir(),
            self.versions_dir(),
            self.toolchain_dir(),
            self.source_dir(),
            self.build_dir(),
            self.cache_root.clone(),
            self.state_root.clone(),
        ]
    }

    pub fn is_managed_path(&self, path: &Path) -> bool {
        path.starts_with(&self.app_root)
            || path.starts_with(&self.state_root)
            || path.starts_with(&self.cache_root)
    }
}

fn configured_or_home(
    platform: Platform,
    configured: Option<&Path>,
    home: Option<&Path>,
    suffix: &str,
) -> Result<PathBuf, PathError> {
    if let Some(path) = absolute_root(platform, configured) {
        return Ok(path);
    }
    home.map(|path| path.join(suffix))
        .ok_or(PathError::MissingUserRoot(platform))
}

fn absolute_root(platform: Platform, path: Option<&Path>) -> Option<PathBuf> {
    path.filter(|path| is_absolute_for(platform, path))
        .map(Path::to_path_buf)
}

fn is_absolute_for(platform: Platform, path: &Path) -> bool {
    if platform == Platform::Linux {
        return path.to_string_lossy().starts_with('/');
    }
    let value = path.to_string_lossy();
    value.starts_with("\\\\")
        || (value.len() >= 3
            && value.as_bytes()[1] == b':'
            && matches!(value.as_bytes()[2], b'/' | b'\\'))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn environment() -> PathEnvironment {
        PathEnvironment {
            home: Some(PathBuf::from("/home/tester")),
            local_app_data: Some(PathBuf::from("C:/Users/tester/AppData/Local")),
            app_data: Some(PathBuf::from("C:/Users/tester/AppData/Roaming")),
            xdg_data_home: None,
            xdg_state_home: None,
            xdg_cache_home: None,
        }
    }

    #[test]
    fn windows_keeps_user_data_outside_application_root() {
        let paths = InstallationPaths::for_environment(
            Platform::Windows,
            TargetArchitecture::X64,
            &environment(),
        )
        .unwrap();
        assert_eq!(
            paths.app_root,
            PathBuf::from("C:/Users/tester/AppData/Local/HarmoniaSuite")
        );
        assert_eq!(
            paths.user_data_root,
            PathBuf::from("C:/Users/tester/AppData/Roaming/HarmoniaSuite")
        );
        assert_eq!(paths.state_root, paths.app_root.join("state"));
    }

    #[test]
    fn linux_uses_consistent_lowercase_roots_and_xdg_fallbacks() {
        let paths = InstallationPaths::for_environment(
            Platform::Linux,
            TargetArchitecture::X64,
            &environment(),
        )
        .unwrap();
        assert_eq!(
            paths.app_root,
            PathBuf::from("/home/tester/.local/share/harmonia-suite")
        );
        assert_eq!(
            paths.user_data_root,
            PathBuf::from("/home/tester/.local/share/harmonia-suite-data")
        );
        assert_eq!(
            paths.state_root,
            PathBuf::from("/home/tester/.local/state/harmonia-suite")
        );
        assert_eq!(
            paths.cache_root,
            PathBuf::from("/home/tester/.cache/harmonia-suite")
        );
    }

    #[test]
    fn rejects_missing_linux_user_root_without_tmp_fallback() {
        let environment = PathEnvironment {
            xdg_data_home: None,
            xdg_state_home: None,
            xdg_cache_home: None,
            home: None,
            ..PathEnvironment::default()
        };
        assert!(matches!(
            InstallationPaths::for_environment(
                Platform::Linux,
                TargetArchitecture::X64,
                &environment,
            ),
            Err(PathError::MissingUserRoot(Platform::Linux))
        ));
    }

    #[test]
    fn ignores_relative_xdg_roots_and_uses_absolute_home_fallback() {
        let environment = PathEnvironment {
            home: Some(PathBuf::from("/home/tester")),
            xdg_data_home: Some(PathBuf::from("relative/data")),
            xdg_state_home: Some(PathBuf::from("relative/state")),
            xdg_cache_home: Some(PathBuf::from("relative/cache")),
            ..PathEnvironment::default()
        };
        let paths = InstallationPaths::for_environment(
            Platform::Linux,
            TargetArchitecture::X64,
            &environment,
        )
        .unwrap();
        assert_eq!(
            paths.app_root,
            PathBuf::from("/home/tester/.local/share/harmonia-suite")
        );
    }

    #[test]
    fn rejects_missing_windows_user_roots_without_public_fallback() {
        let environment = PathEnvironment::default();
        assert!(matches!(
            InstallationPaths::for_environment(
                Platform::Windows,
                TargetArchitecture::X64,
                &environment,
            ),
            Err(PathError::MissingUserRoot(Platform::Windows))
        ));
    }
}
