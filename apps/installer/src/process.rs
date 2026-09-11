use std::collections::BTreeMap;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use serde_json::json;
use thiserror::Error;
use wait_timeout::ChildExt;

use crate::diagnostics::DiagnosticLogger;

#[derive(Clone, Debug)]
pub struct CommandSpec {
    pub program: PathBuf,
    pub args: Vec<String>,
    pub current_dir: Option<PathBuf>,
    pub environment: BTreeMap<String, String>,
    pub timeout: Option<Duration>,
}

impl CommandSpec {
    pub fn new(program: impl Into<PathBuf>) -> Self {
        Self {
            program: program.into(),
            args: Vec::new(),
            current_dir: None,
            environment: BTreeMap::new(),
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
        if let Some(current_dir) = &command.current_dir {
            process.current_dir(current_dir);
        }
        process.envs(&command.environment);
        let mut child = process.spawn().map_err(|source| ProcessError::Spawn {
            program: command.program.display().to_string(),
            source,
        })?;
        let stdout = child.stdout.take().ok_or(ProcessError::Reader)?;
        let stderr = child.stderr.take().ok_or(ProcessError::Reader)?;
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
            let _ = child.kill();
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
    reader.read_to_end(&mut output)?;
    Ok(output)
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
}
