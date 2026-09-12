use std::collections::BTreeMap;
use std::env;
use std::io;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::Duration;

use thiserror::Error;

/// A direct executable launch for the installed desktop. This intentionally
/// does not use `ProcessRunner`: the desktop must survive setup.exe exiting.
#[derive(Clone, Debug)]
pub struct DetachedLaunchSpec {
    pub program: PathBuf,
    pub args: Vec<String>,
    pub current_dir: Option<PathBuf>,
    pub environment: BTreeMap<String, String>,
}

impl DetachedLaunchSpec {
    pub fn new(program: impl Into<PathBuf>) -> Self {
        Self {
            program: program.into(),
            args: Vec::new(),
            current_dir: None,
            environment: BTreeMap::new(),
        }
    }

    pub fn args<I, S>(mut self, values: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.args.extend(values.into_iter().map(Into::into));
        self
    }

    pub fn current_dir(mut self, path: impl Into<PathBuf>) -> Self {
        self.current_dir = Some(path.into());
        self
    }

    pub fn env(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.environment.insert(name.into(), value.into());
        self
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DetachedLaunch {
    pub process_id: u32,
}

#[derive(Debug, Error)]
pub enum DetachedLaunchError {
    #[error("detached launch failed for {program}: {source}")]
    Spawn { program: String, source: io::Error },
    #[error("detached launch path is invalid: {0}")]
    InvalidPath(PathBuf),
    #[error("detached child exited during startup for {program} (status={status:?})")]
    ExitedEarly {
        program: String,
        status: Option<i32>,
    },
}

pub trait DetachedLauncher {
    fn launch(&self, spec: &DetachedLaunchSpec) -> Result<DetachedLaunch, DetachedLaunchError>;
}

#[derive(Clone, Copy, Debug, Default)]
pub struct SystemDetachedLauncher;

const STARTUP_GRACE: Duration = Duration::from_millis(150);

impl DetachedLauncher for SystemDetachedLauncher {
    fn launch(&self, spec: &DetachedLaunchSpec) -> Result<DetachedLaunch, DetachedLaunchError> {
        if !spec.program.is_absolute() || !spec.program.is_file() {
            return Err(DetachedLaunchError::InvalidPath(spec.program.clone()));
        }

        let mut command = Command::new(&spec.program);
        command
            .args(&spec.args)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        if let Some(current_dir) = &spec.current_dir {
            command.current_dir(current_dir);
        }
        command.env_clear();
        for name in safe_detached_environment_allowlist() {
            if let Some(value) = env::var_os(name) {
                command.env(name, value);
            }
        }
        command.envs(&spec.environment);

        #[cfg(unix)]
        configure_detached_unix(&mut command);

        let mut child = command
            .spawn()
            .map_err(|source| DetachedLaunchError::Spawn {
                program: spec.program.display().to_string(),
                source,
            })?;
        std::thread::sleep(STARTUP_GRACE);
        if let Some(status) = child
            .try_wait()
            .map_err(|source| DetachedLaunchError::Spawn {
                program: spec.program.display().to_string(),
                source,
            })?
        {
            return Err(DetachedLaunchError::ExitedEarly {
                program: spec.program.display().to_string(),
                status: status.code(),
            });
        }
        let process_id = child.id();
        // Dropping Child does not terminate a process. The child has no owner
        // containment object and all standard handles are detached/null.
        drop(child);
        Ok(DetachedLaunch { process_id })
    }
}

#[cfg(unix)]
fn configure_detached_unix(command: &mut Command) {
    use std::os::unix::process::CommandExt;

    unsafe {
        command.pre_exec(|| {
            // Do not inherit the owned-process PDEATHSIG contract from any
            // future caller. A new session also prevents setup's process group
            // from being used as the desktop's lifetime boundary.
            if libc::prctl(libc::PR_SET_PDEATHSIG, 0) != 0 {
                return Err(io::Error::last_os_error());
            }
            if libc::setsid() < 0 {
                return Err(io::Error::last_os_error());
            }
            Ok(())
        });
    }
}

fn safe_detached_environment_allowlist() -> &'static [&'static str] {
    #[cfg(windows)]
    {
        &[
            "SystemRoot",
            "SystemDrive",
            "TEMP",
            "TMP",
            "USERPROFILE",
            "APPDATA",
            "LOCALAPPDATA",
            "PATH",
            "PATHEXT",
            "COMSPEC",
            "LANG",
        ]
    }
    #[cfg(not(windows))]
    {
        &[
            "HOME",
            "USER",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "DISPLAY",
            "WAYLAND_DISPLAY",
            "XDG_RUNTIME_DIR",
            "DBUS_SESSION_BUS_ADDRESS",
            "XAUTHORITY",
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;
    use std::time::Duration;
    use tempfile::tempdir;

    #[test]
    fn detached_launcher_rejects_immediate_nonzero_exit() {
        #[cfg(unix)]
        let command = DetachedLaunchSpec::new("/bin/sh").args(["-c", "exit 17"]);
        #[cfg(windows)]
        let command = {
            let powershell = PathBuf::from(env::var_os("SystemRoot").unwrap())
                .join("System32")
                .join("WindowsPowerShell")
                .join("v1.0")
                .join("powershell.exe");
            DetachedLaunchSpec::new(powershell).args(["-NoProfile", "-Command", "exit 17"])
        };

        match SystemDetachedLauncher.launch(&command) {
            Err(DetachedLaunchError::ExitedEarly {
                status: Some(_), ..
            }) => {}
            other => panic!("expected early exit with status, got {other:?}"),
        }
    }

    #[cfg(unix)]
    #[test]
    fn detached_child_survives_owner_process_exit() {
        if env::var_os("HARMONIA_DETACHED_HELPER").is_some() {
            let marker = env::var_os("HARMONIA_DETACHED_MARKER").unwrap();
            let command = DetachedLaunchSpec::new("/bin/sh").args([
                "-c",
                &format!(
                    "sleep 1; echo detached > '{}'",
                    PathBuf::from(marker).display()
                ),
            ]);
            SystemDetachedLauncher.launch(&command).unwrap();
            return;
        }

        let directory = tempdir().unwrap();
        let marker = directory.path().join("detached.txt");
        let output = Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "detached::tests::detached_child_survives_owner_process_exit",
                "--nocapture",
            ])
            .env("HARMONIA_DETACHED_HELPER", "1")
            .env("HARMONIA_DETACHED_MARKER", &marker)
            .output()
            .unwrap();
        assert!(output.status.success(), "helper failed: {output:?}");
        for _ in 0..60 {
            if marker.is_file() {
                return;
            }
            thread::sleep(Duration::from_millis(50));
        }
        panic!("detached child did not survive owner exit");
    }

    #[cfg(windows)]
    #[test]
    fn detached_windows_child_survives_owner_process_exit() {
        if env::var_os("HARMONIA_DETACHED_HELPER").is_some() {
            let marker = env::var_os("HARMONIA_DETACHED_MARKER").unwrap();
            let powershell = PathBuf::from(env::var_os("SystemRoot").unwrap())
                .join("System32")
                .join("WindowsPowerShell")
                .join("v1.0")
                .join("powershell.exe");
            let marker = PathBuf::from(marker);
            let marker_literal = marker.display().to_string().replace('\'', "''");
            let command = DetachedLaunchSpec::new(powershell).args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                &format!(
                    "Start-Sleep -Seconds 1; Set-Content -LiteralPath '{}' -Value detached",
                    marker_literal
                ),
            ]);
            SystemDetachedLauncher.launch(&command).unwrap();
            return;
        }

        let directory = tempdir().unwrap();
        let marker = directory.path().join("detached.txt");
        let output = Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "detached::tests::detached_windows_child_survives_owner_process_exit",
                "--nocapture",
            ])
            .env("HARMONIA_DETACHED_HELPER", "1")
            .env("HARMONIA_DETACHED_MARKER", &marker)
            .output()
            .unwrap();
        assert!(output.status.success(), "helper failed: {output:?}");
        for _ in 0..60 {
            if marker.is_file() {
                return;
            }
            thread::sleep(Duration::from_millis(50));
        }
        panic!("detached child did not survive owner exit");
    }
}
