use std::collections::BTreeMap;
use std::fs::{create_dir_all, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::Serialize;
use serde_json::Value;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum DiagnosticError {
    #[error("diagnostic log I/O failed: {0}")]
    Io(#[from] io::Error),
    #[error("diagnostic log serialization failed: {0}")]
    Serialization(#[from] serde_json::Error),
}

#[derive(Debug, Serialize)]
struct LogEvent<'a> {
    timestamp_ms: u128,
    level: &'a str,
    event: &'a str,
    #[serde(flatten)]
    fields: BTreeMap<String, Value>,
}

#[derive(Clone)]
pub struct DiagnosticLogger {
    path: PathBuf,
    file: Arc<Mutex<File>>,
}

impl DiagnosticLogger {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, DiagnosticError> {
        let path = path.as_ref().to_path_buf();
        if let Some(parent) = path.parent() {
            create_dir_all(parent)?;
        }
        let file = OpenOptions::new().create(true).append(true).open(&path)?;
        Ok(Self {
            path,
            file: Arc::new(Mutex::new(file)),
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn log(
        &self,
        level: &str,
        event: &str,
        fields: impl IntoIterator<Item = (String, Value)>,
    ) -> Result<(), DiagnosticError> {
        let record = LogEvent {
            timestamp_ms: now_ms(),
            level,
            event,
            fields: fields.into_iter().collect(),
        };
        let encoded = serde_json::to_vec(&record)?;
        let mut file = self.file.lock().map_err(|_| {
            DiagnosticError::Io(io::Error::new(
                io::ErrorKind::Other,
                "diagnostic log mutex poisoned",
            ))
        })?;
        file.write_all(&encoded)?;
        file.write_all(b"\n")?;
        file.flush()?;
        file.sync_data()?;
        Ok(())
    }

    pub fn info(&self, event: &str) -> Result<(), DiagnosticError> {
        self.log("info", event, std::iter::empty())
    }

    pub fn warn(
        &self,
        event: &str,
        fields: impl IntoIterator<Item = (String, Value)>,
    ) -> Result<(), DiagnosticError> {
        self.log("warn", event, fields)
    }
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
    use serde_json::json;
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn writes_structured_json_lines_and_flushes() {
        let directory = tempdir().unwrap();
        let logger =
            DiagnosticLogger::open(directory.path().join("diagnostics/install.jsonl")).unwrap();
        logger
            .log(
                "info",
                "transaction.transition",
                [("phase".to_owned(), json!("Staging"))],
            )
            .unwrap();
        let line = fs::read_to_string(logger.path()).unwrap();
        let value: Value = serde_json::from_str(line.lines().next().unwrap()).unwrap();
        assert_eq!(value["event"], "transaction.transition");
        assert_eq!(value["phase"], "Staging");
        assert!(value["timestamp_ms"].as_u64().is_some());
    }
}
