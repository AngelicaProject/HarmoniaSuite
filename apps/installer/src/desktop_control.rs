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
#[serde(rename_all = "camelCase")]
struct DesktopSession {
    pid: u32,
    started_at_ms: u128,
    session_id: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct ShutdownRequest {
    request_id: String,
    session_id: String,
    pid: u32,
    started_at_ms: u128,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct ShutdownAcknowledgement {
    request_id: String,
    session_id: String,
    pid: u32,
    started_at_ms: u128,
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
            session_id: Uuid::new_v4().simple().to_string(),
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
    Ok(live_desktop_session(paths)?.map(|session| session.pid))
}

fn live_desktop_session(
    paths: &InstallationPaths,
) -> Result<Option<DesktopSession>, DesktopControlError> {
    let path = desktop_session_path(paths);
    let bytes = match fs::read(&path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let session: DesktopSession = serde_json::from_slice(&bytes)?;
    if process_alive(session.pid) {
        Ok(Some(session))
    } else {
        let _ = fs::remove_file(path);
        Ok(None)
    }
}

pub struct DesktopShutdownHooks {
    paths: InstallationPaths,
    allow_request: bool,
    request: Option<ShutdownRequest>,
}

impl DesktopShutdownHooks {
    pub fn new(paths: InstallationPaths, allow_request: bool) -> Self {
        Self {
            paths,
            allow_request,
            request: None,
        }
    }
}

impl ActivationHooks for DesktopShutdownHooks {
    fn request_shutdown(&mut self) -> Result<(), String> {
        cleanup_shutdown_coordination(&self.paths).map_err(|error| error.to_string())?;
        let Some(session) = live_desktop_session(&self.paths).map_err(|error| error.to_string())?
        else {
            return Ok(());
        };
        if !self.allow_request {
            return Err(DesktopControlError::DesktopRunning.to_string());
        }
        let request = ShutdownRequest {
            request_id: Uuid::new_v4().simple().to_string(),
            session_id: session.session_id,
            pid: session.pid,
            started_at_ms: session.started_at_ms,
        };
        write_json(&self.paths.desktop_shutdown_request_path(), &request)
            .map_err(|error| error.to_string())?;
        self.request = Some(request);
        Ok(())
    }

    fn wait_for_shutdown(&mut self, timeout: Duration) -> Result<(), String> {
        let Some(request) = self.request.clone() else {
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
                    if ack.request_id == request.request_id
                        && ack.session_id == request.session_id
                        && ack.pid == request.pid
                        && ack.started_at_ms == request.started_at_ms
                    {
                        cleanup_shutdown_coordination(&self.paths)
                            .map_err(|error| error.to_string())?;
                        return Ok(());
                    }
                }
            }
            if live_desktop_session(&self.paths)
                .map_err(|error| error.to_string())?
                .is_none()
            {
                cleanup_shutdown_coordination(&self.paths).map_err(|error| error.to_string())?;
                return Ok(());
            }
            if std::time::Instant::now() >= deadline {
                return Err(DesktopControlError::ShutdownTimeout.to_string());
            }
            std::thread::sleep(POLL_INTERVAL);
        }
    }
}

pub fn cleanup_shutdown_coordination(paths: &InstallationPaths) -> Result<(), DesktopControlError> {
    for path in [
        paths.desktop_shutdown_request_path(),
        paths.desktop_shutdown_ack_path(),
    ] {
        match fs::remove_file(path) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
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

    #[test]
    fn matching_ack_consumes_request_and_ack() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        crate::StateStore::new(paths.clone()).initialize().unwrap();
        publish_desktop_session(&paths, std::process::id()).unwrap();
        let mut hooks = DesktopShutdownHooks::new(paths.clone(), true);
        hooks.request_shutdown().unwrap();
        let request: ShutdownRequest =
            serde_json::from_slice(&fs::read(paths.desktop_shutdown_request_path()).unwrap())
                .unwrap();
        write_json(
            &paths.desktop_shutdown_ack_path(),
            &ShutdownAcknowledgement {
                request_id: request.request_id.clone(),
                session_id: request.session_id.clone(),
                pid: request.pid,
                started_at_ms: request.started_at_ms,
                acknowledged_at_ms: now_ms(),
            },
        )
        .unwrap();
        hooks.wait_for_shutdown(Duration::from_millis(100)).unwrap();
        assert!(!paths.desktop_shutdown_request_path().exists());
        assert!(!paths.desktop_shutdown_ack_path().exists());
        clear_desktop_session(&paths).unwrap();
    }

    #[test]
    fn mismatched_session_ack_is_ignored_and_stale_cleanup_is_idempotent() {
        let root = tempdir().unwrap();
        let paths = paths(root.path());
        crate::StateStore::new(paths.clone()).initialize().unwrap();
        publish_desktop_session(&paths, std::process::id()).unwrap();
        let mut hooks = DesktopShutdownHooks::new(paths.clone(), true);
        hooks.request_shutdown().unwrap();
        let request: ShutdownRequest =
            serde_json::from_slice(&fs::read(paths.desktop_shutdown_request_path()).unwrap())
                .unwrap();
        write_json(
            &paths.desktop_shutdown_ack_path(),
            &ShutdownAcknowledgement {
                request_id: request.request_id,
                session_id: "different-session".to_owned(),
                pid: request.pid,
                started_at_ms: request.started_at_ms,
                acknowledged_at_ms: now_ms(),
            },
        )
        .unwrap();
        assert!(matches!(
            hooks.wait_for_shutdown(Duration::ZERO),
            Err(message) if message.contains("timed out")
        ));
        cleanup_shutdown_coordination(&paths).unwrap();
        cleanup_shutdown_coordination(&paths).unwrap();
        clear_desktop_session(&paths).unwrap();
    }
}
