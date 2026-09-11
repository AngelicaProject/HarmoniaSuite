use std::collections::BTreeMap;
use std::env;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

#[cfg(unix)]
use std::os::unix::process::CommandExt;

use serde_json::json;
use thiserror::Error;
use wait_timeout::ChildExt;

use crate::diagnostics::DiagnosticLogger;

const MAX_CAPTURE_BYTES: usize = 1024 * 1024;

#[derive(Clone, Debug)]
pub struct CommandSpec {
    pub program: PathBuf,
    pub args: Vec<String>,
    pub current_dir: Option<PathBuf>,
    pub environment: BTreeMap<String, String>,
    pub environment_allowlist: Vec<String>,
    pub timeout: Option<Duration>,
}

impl CommandSpec {
    pub fn new(program: impl Into<PathBuf>) -> Self {
        Self {
            program: program.into(),
            args: Vec::new(),
            current_dir: None,
            environment: BTreeMap::new(),
            environment_allowlist: default_environment_allowlist(),
            timeout: Some(Duration::from_secs(10 * 60)),
        }
    }

    pub fn arg(mut self, value: impl Into<String>) -> Self {
        self.args.push(value.into());
        self
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

    pub fn inherit_env(mut self, name: impl Into<String>) -> Self {
        self.environment_allowlist.push(name.into());
        self
    }

    pub fn without_inherited_environment(mut self) -> Self {
        self.environment_allowlist.clear();
        self
    }

    pub fn timeout(mut self, timeout: Option<Duration>) -> Self {
        self.timeout = timeout;
        self
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ProcessOutput {
    pub status: Option<i32>,
    pub stdout: String,
    pub stderr: String,
    pub duration_ms: u128,
    pub timed_out: bool,
}

impl ProcessOutput {
    pub fn success(&self) -> bool {
        self.status == Some(0) && !self.timed_out
    }
}

#[derive(Debug, Error)]
pub enum ProcessError {
    #[error("failed to start process {program}: {source}")]
    Spawn {
        program: String,
        source: std::io::Error,
    },
    #[error("process I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("process output reader failed")]
    Reader,
}

pub trait ProcessRunner {
    fn run(&self, command: &CommandSpec) -> Result<ProcessOutput, ProcessError>;
}

#[derive(Clone, Default)]
pub struct SystemProcessRunner {
    logger: Option<Arc<DiagnosticLogger>>,
}

impl SystemProcessRunner {
    pub fn new(logger: Option<Arc<DiagnosticLogger>>) -> Self {
        Self { logger }
    }
}

impl ProcessRunner for SystemProcessRunner {
    fn run(&self, command: &CommandSpec) -> Result<ProcessOutput, ProcessError> {
        let started = Instant::now();
        if let Some(logger) = &self.logger {
            let _ = logger.log(
                "info",
                "process.start",
                [("command".to_owned(), json!(redacted_command(command)))],
            );
        }

        let mut process = Command::new(&command.program);
        process
            .args(&command.args)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        process.env_clear();
        for name in &command.environment_allowlist {
            if let Some(value) = env::var_os(name) {
                process.env(name, value);
            }
        }
        process.envs(&command.environment);
        if let Some(current_dir) = &command.current_dir {
            process.current_dir(current_dir);
        }
        #[cfg(unix)]
        process.process_group(0);
        let mut containment = ProcessContainment::new().map_err(ProcessError::Io)?;
        let mut child = process.spawn().map_err(|source| ProcessError::Spawn {
            program: command.program.display().to_string(),
            source,
        })?;
        if let Err(error) = containment.attach(&child) {
            let _ = child.kill();
            let _ = child.wait();
            return Err(ProcessError::Io(error));
        }
        let stdout = match child.stdout.take() {
            Some(stdout) => stdout,
            None => {
                let _ = containment.terminate(&mut child);
                let _ = child.wait();
                return Err(ProcessError::Reader);
            }
        };
        let stderr = match child.stderr.take() {
            Some(stderr) => stderr,
            None => {
                let _ = containment.terminate(&mut child);
                let _ = child.wait();
                return Err(ProcessError::Reader);
            }
        };
        let stdout_thread = thread::spawn(move || read_output(stdout));
        let stderr_thread = thread::spawn(move || read_output(stderr));

        let timed_out = match command.timeout {
            Some(timeout) => child.wait_timeout(timeout)?.is_none(),
            None => {
                child.wait()?;
                false
            }
        };
        if timed_out {
            containment.terminate(&mut child)?;
        }
        let status = child.wait()?.code();
        let stdout = stdout_thread.join().map_err(|_| ProcessError::Reader)??;
        let stderr = stderr_thread.join().map_err(|_| ProcessError::Reader)??;
        let result = ProcessOutput {
            status,
            stdout: String::from_utf8_lossy(&stdout).into_owned(),
            stderr: String::from_utf8_lossy(&stderr).into_owned(),
            duration_ms: started.elapsed().as_millis(),
            timed_out,
        };
        if let Some(logger) = &self.logger {
            let _ = logger.log(
                if result.success() { "info" } else { "error" },
                "process.finish",
                [
                    ("command".to_owned(), json!(redacted_command(command))),
                    ("exit_code".to_owned(), json!(result.status)),
                    ("timed_out".to_owned(), json!(result.timed_out)),
                    ("duration_ms".to_owned(), json!(result.duration_ms)),
                ],
            );
        }
        Ok(result)
    }
}

fn read_output<R: Read>(mut reader: R) -> Result<Vec<u8>, std::io::Error> {
    let mut output = Vec::new();
    let mut buffer = [0_u8; 8192];
    loop {
        let read = reader.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        if read >= MAX_CAPTURE_BYTES {
            output.clear();
            output.extend_from_slice(&buffer[read - MAX_CAPTURE_BYTES..read]);
            continue;
        }
        let overflow = output
            .len()
            .saturating_add(read)
            .saturating_sub(MAX_CAPTURE_BYTES);
        if overflow > 0 {
            output.drain(..overflow);
        }
        output.extend_from_slice(&buffer[..read]);
    }
    Ok(output)
}

fn default_environment_allowlist() -> Vec<String> {
    #[cfg(windows)]
    let names = [
        "PATH",
        "SystemRoot",
        "SystemDrive",
        "TEMP",
        "TMP",
        "USERPROFILE",
        "PATHEXT",
        "COMSPEC",
    ];
    #[cfg(not(windows))]
    let names = ["PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "LC_CTYPE"];
    names.into_iter().map(str::to_owned).collect()
}

struct ProcessContainment {
    #[cfg(unix)]
    process_group: Option<i32>,
    #[cfg(windows)]
    job: windows_job::Job,
}

impl ProcessContainment {
    fn new() -> std::io::Result<Self> {
        #[cfg(unix)]
        {
            Ok(Self {
                process_group: None,
            })
        }
        #[cfg(windows)]
        {
            Ok(Self {
                job: windows_job::Job::new()?,
            })
        }
    }

    fn attach(&mut self, child: &Child) -> std::io::Result<()> {
        #[cfg(unix)]
        {
            self.process_group = Some(child.id() as i32);
            Ok(())
        }
        #[cfg(windows)]
        {
            self.job.assign(child)
        }
    }

    fn terminate(&self, _child: &mut Child) -> std::io::Result<()> {
        #[cfg(unix)]
        {
            if let Some(process_group) = self.process_group {
                let result = unsafe { libc::kill(-process_group, libc::SIGKILL) };
                if result != 0 {
                    let error = std::io::Error::last_os_error();
                    if error.raw_os_error() != Some(libc::ESRCH) {
                        return Err(error);
                    }
                }
                Ok(())
            } else {
                _child.kill()
            }
        }
        #[cfg(windows)]
        {
            self.job.terminate()
        }
    }
}

#[cfg(windows)]
mod windows_job {
    use std::io;
    use std::mem::size_of;
    use std::os::windows::io::AsRawHandle;

    use windows_sys::Win32::Foundation::{CloseHandle, HANDLE};
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
        SetInformationJobObject, TerminateJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };

    pub struct Job {
        handle: HANDLE,
    }

    impl Job {
        pub fn new() -> io::Result<Self> {
            let handle = unsafe { CreateJobObjectW(std::ptr::null(), std::ptr::null()) };
            if handle.is_null() {
                return Err(io::Error::last_os_error());
            }
            let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = unsafe { std::mem::zeroed() };
            limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            let configured = unsafe {
                SetInformationJobObject(
                    handle,
                    JobObjectExtendedLimitInformation,
                    (&limits as *const JOBOBJECT_EXTENDED_LIMIT_INFORMATION).cast(),
                    size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
                )
            };
            if configured == 0 {
                unsafe { CloseHandle(handle) };
                return Err(io::Error::last_os_error());
            }
            Ok(Self { handle })
        }

        pub fn assign(&self, child: &std::process::Child) -> io::Result<()> {
            let assigned =
                unsafe { AssignProcessToJobObject(self.handle, child.as_raw_handle() as HANDLE) };
            if assigned == 0 {
                return Err(io::Error::last_os_error());
            }
            Ok(())
        }

        pub fn terminate(&self) -> io::Result<()> {
            let terminated = unsafe { TerminateJobObject(self.handle, 1) };
            if terminated == 0 {
                return Err(io::Error::last_os_error());
            }
            Ok(())
        }
    }

    impl Drop for Job {
        fn drop(&mut self) {
            unsafe { CloseHandle(self.handle) };
        }
    }
}

pub fn redacted_command(command: &CommandSpec) -> String {
    let mut values = Vec::with_capacity(command.args.len() + 1);
    values.push(command.program.display().to_string());
    let mut redact_next = false;
    for argument in &command.args {
        let lower = argument.to_ascii_lowercase();
        let key_value_secret = [
            "token=",
            "password=",
            "secret=",
            "authorization=",
            "api_key=",
            "apikey=",
        ]
        .iter()
        .any(|needle| lower.contains(needle));
        if redact_next || key_value_secret {
            values.push("<redacted>".to_owned());
            redact_next = false;
        } else {
            values.push(argument.clone());
            redact_next = [
                "--token",
                "--password",
                "--secret",
                "--authorization",
                "--api-key",
            ]
            .contains(&lower.as_str());
        }
    }
    values.join(" ")
}

#[allow(dead_code)]
fn _path_display(path: &Path) -> String {
    path.display().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn redacts_secret_arguments() {
        let command =
            CommandSpec::new("tool").args(["--channel", "main", "--token", "secret-value", "url"]);
        let rendered = redacted_command(&command);
        assert_eq!(rendered, "tool --channel main --token <redacted> url");
        assert!(!rendered.contains("secret-value"));
    }

    #[test]
    fn runs_a_process_without_a_shell() {
        let command = if cfg!(windows) {
            CommandSpec::new("cmd").args(["/C", "exit", "0"])
        } else {
            CommandSpec::new("sh").args(["-c", "exit 0"])
        };
        let result = SystemProcessRunner::default().run(&command).unwrap();
        assert!(result.success(), "process result: {result:?}");
    }

    #[test]
    fn reports_timeout_and_terminates_child() {
        let command = if cfg!(windows) {
            CommandSpec::new("cmd")
                .args(["/C", "ping", "127.0.0.1", "-n", "4"])
                .timeout(Some(Duration::from_millis(50)))
        } else {
            CommandSpec::new("sh")
                .args(["-c", "sleep 2"])
                .timeout(Some(Duration::from_millis(50)))
        };
        let result = SystemProcessRunner::default().run(&command).unwrap();
        assert!(result.timed_out);
        assert!(!result.success());
    }

    #[cfg(unix)]
    #[test]
    fn timeout_terminates_grandchild_process() {
        let directory = tempfile::tempdir().unwrap();
        let marker = directory.path().join("grandchild-alive");
        let script = format!(
            "(sleep 1; echo grandchild > \"{}\") & wait",
            marker.display()
        );
        let command = CommandSpec::new("sh")
            .args(["-c", &script])
            .timeout(Some(Duration::from_millis(100)));
        let result = SystemProcessRunner::default().run(&command).unwrap();
        assert!(result.timed_out);
        thread::sleep(Duration::from_millis(1200));
        assert!(!marker.exists(), "grandchild survived timeout");
    }

    #[cfg(windows)]
    #[test]
    fn timeout_terminates_grandchild_process_on_windows() {
        let directory = tempfile::tempdir().unwrap();
        let marker = directory.path().join("grandchild-alive.txt");
        let script = r#"start "" /B cmd /C "timeout /T 2 /NOBREAK >NUL & echo grandchild > grandchild-alive.txt" & timeout /T 10 /NOBREAK >NUL"#;
        let command = CommandSpec::new("cmd")
            .args(["/C", script])
            .current_dir(directory.path())
            .timeout(Some(Duration::from_millis(100)));
        let result = SystemProcessRunner::default().run(&command).unwrap();
        assert!(result.timed_out);
        thread::sleep(Duration::from_millis(2500));
        assert!(!marker.exists(), "grandchild survived timeout");
    }

    #[cfg(unix)]
    #[test]
    fn bounds_captured_output() {
        let command = CommandSpec::new("sh")
            .args(["-c", "head -c 2000000 /dev/zero"])
            .timeout(Some(Duration::from_secs(5)));
        let result = SystemProcessRunner::default().run(&command).unwrap();
        assert!(result.success());
        assert_eq!(result.stdout.len(), MAX_CAPTURE_BYTES);
    }
}
