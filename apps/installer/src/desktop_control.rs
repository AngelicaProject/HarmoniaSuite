//! Coordination between the installed desktop and the updater.
//!
//! The updater must never snapshot or restore SQLite while the owned desktop backend may still
//! be alive. The desktop publishes a short-lived PID marker and watches a request file. The
//! updater only requests shutdown when it was launched by the desktop; a direct CLI invocation
//! fails closed if a live desktop marker is present.

use std::fs;
use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use uuid::Uuid;

use crate::activation::ActivationHooks;
use crate::paths::InstallationPaths;

const POLL_INTERVAL: Duration = Duration::from_millis(50);

#[derive(Clone, Debug, Deserialize, Serialize)]
struct DesktopSession {
    pid: u32,
    started_at_ms: u128,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct ShutdownRequest {
    request_id: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct ShutdownAcknowledgement {
    request_id: String,
    acknowledged_at_ms: u128,
}

#[derive(Debug, Error)]
pub enum DesktopControlError {
    #[error("desktop control I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("desktop control JSON failed: {0}")]
    Json(#[from] serde_json::Error),
    #[error("desktop is running; shutdown coordination is required")]
    DesktopRunning,
    #[error("desktop shutdown acknowledgement timed out")]
    ShutdownTimeout,
}

pub fn desktop_session_path(paths: &InstallationPaths) -> PathBuf {
    paths.desktop_session_path()
}

pub fn publish_desktop_session(
    paths: &InstallationPaths,
    pid: u32,
) -> Result<(), DesktopControlError> {
    write_json(
        &desktop_session_path(paths),
        &DesktopSession {
            pid,
            started_at_ms: now_ms(),
        },
    )
}

pub fn clear_desktop_session(paths: &InstallationPaths) -> Result<(), DesktopControlError> {
    match fs::remove_file(desktop_session_path(paths)) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

pub fn live_desktop_pid(paths: &InstallationPaths) -> Result<Option<u32>, DesktopControlError> {
    let path = desktop_session_path(paths);
    let bytes = match fs::read(&path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let session: DesktopSession = serde_json::from_slice(&bytes)?;
    if process_alive(session.pid) {
        Ok(Some(session.pid))
    } else {
        let _ = fs::remove_file(path);
        Ok(None)
    }
}

pub struct DesktopShutdownHooks {
    paths: InstallationPaths,
    allow_request: bool,
    request_id: Option<String>,
}

impl DesktopShutdownHooks {
    pub fn new(paths: InstallationPaths, allow_request: bool) -> Self {
        Self {
            paths,
            allow_request,
            request_id: None,
        }
    }
}

impl ActivationHooks for DesktopShutdownHooks {
    fn request_shutdown(&mut self) -> Result<(), String> {
        let Some(_pid) = live_desktop_pid(&self.paths).map_err(|error| error.to_string())? else {
            return Ok(());
        };
        if !self.allow_request {
            return Err(DesktopControlError::DesktopRunning.to_string());
        }
        let request_id = Uuid::new_v4().simple().to_string();
        write_json(
            &self.paths.desktop_shutdown_request_path(),
            &ShutdownRequest {
                request_id: request_id.clone(),
            },
        )
        .map_err(|error| error.to_string())?;
        self.request_id = Some(request_id);
        Ok(())
    }

    fn wait_for_shutdown(&mut self, timeout: Duration) -> Result<(), String> {
        let Some(request_id) = self.request_id.clone() else {
            if live_desktop_pid(&self.paths)
                .map_err(|error| error.to_string())?
                .is_none()
            {
                return Ok(());
            }
            return Err(DesktopControlError::DesktopRunning.to_string());
        };
        let deadline = std::time::Instant::now() + timeout;
        loop {
            if let Ok(bytes) = fs::read(self.paths.desktop_shutdown_ack_path()) {
                if let Ok(ack) = serde_json::from_slice::<ShutdownAcknowledgement>(&bytes) {
                    if ack.request_id == request_id {
                        return Ok(());
                    }
                }
            }
            if live_desktop_pid(&self.paths)
                .map_err(|error| error.to_string())?
                .is_none()
            {
                return Ok(());
            }
            if std::time::Instant::now() >= deadline {
                return Err(DesktopControlError::ShutdownTimeout.to_string());
            }
            std::thread::sleep(POLL_INTERVAL);
        }
    }
}

fn write_json<T: Serialize>(path: &std::path::Path, value: &T) -> Result<(), DesktopControlError> {
    crate::state::atomic_write_json(path, value).map_err(|error| match error {
        crate::state::StateError::Io(error) => DesktopControlError::Io(error),
        crate::state::StateError::Json(error) => DesktopControlError::Json(error),
        other => DesktopControlError::Io(std::io::Error::other(other.to_string())),
    })
}

fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}

#[cfg(unix)]
fn process_alive(pid: u32) -> bool {
    unsafe {
        libc::kill(pid as libc::pid_t, 0) == 0
            || std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::paths::{Platform, TargetArchitecture};
    use tempfile::tempdir;

    fn paths(root: &std::path::Path) -> InstallationPaths {
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
    fn direct_update_fails_closed_when_desktop_pid_is_live() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        crate::StateStore::new(paths.clone()).initialize().unwrap();
        publish_desktop_session(&paths, std::process::id()).unwrap();
        let mut hooks = DesktopShutdownHooks::new(paths.clone(), false);
        assert!(hooks.request_shutdown().unwrap_err().contains("shutdown"));
        clear_desktop_session(&paths).unwrap();
    }
}

#[cfg(windows)]
fn process_alive(pid: u32) -> bool {
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::Threading::{OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION};

    let handle = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
    if handle.is_null() {
        false
    } else {
        unsafe { CloseHandle(handle) };
        true
    }
}
