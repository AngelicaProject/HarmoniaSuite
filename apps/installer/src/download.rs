use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::thread;
use std::time::Duration;

use reqwest::blocking::Client;
use reqwest::redirect::Policy;
use thiserror::Error;

use crate::checksum::{sha256_file, verify_sha256, ChecksumError};

#[derive(Clone, Debug)]
pub struct DownloadRequest {
    pub url: String,
    pub destination: PathBuf,
    pub expected_sha256: Option<String>,
    pub timeout: Duration,
    pub max_retries: u32,
    pub resume: bool,
}

impl DownloadRequest {
    pub fn new(url: impl Into<String>, destination: impl Into<PathBuf>) -> Self {
        Self {
            url: url.into(),
            destination: destination.into(),
            expected_sha256: None,
            timeout: Duration::from_secs(10 * 60),
            max_retries: 2,
            resume: true,
        }
    }

    pub fn expected_sha256(mut self, value: impl Into<String>) -> Self {
        self.expected_sha256 = Some(value.into());
        self
    }

    pub fn timeout(mut self, value: Duration) -> Self {
        self.timeout = value;
        self
    }

    pub fn max_retries(mut self, value: u32) -> Self {
        self.max_retries = value;
        self
    }
}

#[derive(Debug, Eq, PartialEq)]
pub struct DownloadReceipt {
    pub path: PathBuf,
    pub bytes: u64,
    pub sha256: String,
    pub resumed: bool,
}

#[derive(Debug, Error)]
pub enum DownloadError {
    #[error("download URL must use HTTPS")]
    InvalidUrl,
    #[error("download request is invalid: {0}")]
    InvalidRequest(String),
    #[error("download transport failed: {0}")]
    Transport(String),
    #[error("download returned HTTP status {0}")]
    HttpStatus(u16),
    #[error("download I/O failed: {0}")]
    Io(#[from] io::Error),
    #[error("download checksum failed: {0}")]
    Checksum(#[from] ChecksumError),
}

pub struct DownloadResponse {
    pub status: u16,
    pub body: Box<dyn Read + Send>,
}

pub trait DownloadTransport: Send + Sync {
    fn get(
        &self,
        url: &str,
        range_from: Option<u64>,
        timeout: Duration,
    ) -> Result<DownloadResponse, DownloadError>;
}

pub trait DownloadClient {
    fn download(&self, request: &DownloadRequest) -> Result<DownloadReceipt, DownloadError>;
}

pub struct ResumableDownloader<T> {
    transport: T,
    retry_delay: Duration,
}

impl<T> ResumableDownloader<T> {
    pub fn new(transport: T) -> Self {
        Self {
            transport,
            retry_delay: Duration::from_millis(250),
        }
    }

    #[cfg(test)]
    fn with_retry_delay(transport: T, retry_delay: Duration) -> Self {
        Self {
            transport,
            retry_delay,
        }
    }
}

impl<T: DownloadTransport> DownloadClient for ResumableDownloader<T> {
    fn download(&self, request: &DownloadRequest) -> Result<DownloadReceipt, DownloadError> {
        validate_request(request)?;
        if let Some(expected) = &request.expected_sha256 {
            if request.destination.is_file()
                && verify_sha256(&request.destination, expected).is_ok()
            {
                return Ok(receipt_for(&request.destination, false)?);
            }
            if request.destination.is_file() {
                fs::remove_file(&request.destination)?;
            }
        }
        if let Some(parent) = request.destination.parent() {
            fs::create_dir_all(parent)?;
        }
        let partial = partial_path(&request.destination)?;
        let mut resumed = false;
        for attempt in 0..=request.max_retries {
            let existing = if request.resume && partial.is_file() {
                fs::metadata(&partial)?.len()
            } else {
                0
            };
            let response = match self.transport.get(
                &request.url,
                (existing > 0).then_some(existing),
                request.timeout,
            ) {
                Ok(response) => response,
                Err(_error) if attempt < request.max_retries => {
                    thread::sleep(self.retry_delay);
                    continue;
                }
                Err(error) => return Err(error),
            };
            if response.status == 416 {
                if let Some(expected) = &request.expected_sha256 {
                    match verify_sha256(&partial, expected) {
                        Ok(_) => {
                            promote(&partial, &request.destination)?;
                            return receipt_for(&request.destination, true);
                        }
                        Err(error) => {
                            let _ = fs::remove_file(&partial);
                            return Err(error.into());
                        }
                    }
                }
            }
            if !(200..300).contains(&response.status) {
                if response.status >= 400 && response.status < 500 {
                    let _ = fs::remove_file(&partial);
                }
                return Err(DownloadError::HttpStatus(response.status));
            }

            let append = existing > 0 && response.status == 206;
            let mut file = if append {
                resumed = true;
                OpenOptions::new().append(true).open(&partial)?
            } else {
                File::create(&partial)?
            };
            if let Err(error) = copy_body(response.body, &mut file).and_then(|_| file.sync_all()) {
                if attempt < request.max_retries {
                    thread::sleep(self.retry_delay);
                    continue;
                }
                return Err(error.into());
            }
            let digest = sha256_file(&partial)?;
            if let Some(expected) = &request.expected_sha256 {
                if let Err(error) = verify_sha256(&partial, expected) {
                    let _ = fs::remove_file(&partial);
                    return Err(error.into());
                }
            }
            promote(&partial, &request.destination)?;
            return Ok(DownloadReceipt {
                bytes: fs::metadata(&request.destination)?.len(),
                path: request.destination.clone(),
                sha256: digest,
                resumed,
            });
        }
        unreachable!("retry loop always returns")
    }
}

#[derive(Clone)]
pub struct HttpDownloader {
    user_agent: String,
}

impl Default for HttpDownloader {
    fn default() -> Self {
        Self {
            user_agent: "harmonia-suite-installer/0.1".to_owned(),
        }
    }
}

impl HttpDownloader {
    pub fn new(user_agent: impl Into<String>) -> Self {
        Self {
            user_agent: user_agent.into(),
        }
    }
}

impl DownloadTransport for HttpDownloader {
    fn get(
        &self,
        url: &str,
        range_from: Option<u64>,
        timeout: Duration,
    ) -> Result<DownloadResponse, DownloadError> {
        let parsed = reqwest::Url::parse(url).map_err(|_| DownloadError::InvalidUrl)?;
        if parsed.scheme() != "https" {
            return Err(DownloadError::InvalidUrl);
        }
        let client = Client::builder()
            .timeout(timeout)
            .redirect(Policy::limited(5))
            .user_agent(&self.user_agent)
            .build()
            .map_err(|error| DownloadError::Transport(error.to_string()))?;
        let mut request = client.get(parsed);
        if let Some(start) = range_from {
            request = request.header(reqwest::header::RANGE, format!("bytes={start}-"));
        }
        let response = request
            .send()
            .map_err(|error| DownloadError::Transport(error.to_string()))?;
        if response.url().scheme() != "https" {
            return Err(DownloadError::InvalidUrl);
        }
        Ok(DownloadResponse {
            status: response.status().as_u16(),
            body: Box::new(response),
        })
    }
}

impl DownloadClient for HttpDownloader {
    fn download(&self, request: &DownloadRequest) -> Result<DownloadReceipt, DownloadError> {
        ResumableDownloader::new(self.clone()).download(request)
    }
}

fn validate_request(request: &DownloadRequest) -> Result<(), DownloadError> {
    if request.url.is_empty() {
        return Err(DownloadError::InvalidRequest("URL is empty".to_owned()));
    }
    if request.destination.file_name().is_none() {
        return Err(DownloadError::InvalidRequest(
            "destination must name a file".to_owned(),
        ));
    }
    if let Some(expected) = &request.expected_sha256 {
        let value = expected.trim();
        if value.len() != 64 || !value.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err(DownloadError::InvalidRequest(
                "invalid expected SHA-256".to_owned(),
            ));
        }
    }
    Ok(())
}

fn partial_path(destination: &Path) -> Result<PathBuf, DownloadError> {
    let file_name = destination
        .file_name()
        .ok_or_else(|| DownloadError::InvalidRequest("destination must name a file".to_owned()))?;
    Ok(destination.with_file_name(format!("{}.partial", file_name.to_string_lossy())))
}

fn copy_body(mut body: Box<dyn Read + Send>, file: &mut File) -> io::Result<u64> {
    io::copy(&mut body, file)
}

fn promote(partial: &Path, destination: &Path) -> Result<(), DownloadError> {
    if destination.exists() {
        fs::remove_file(destination)?;
    }
    fs::rename(partial, destination)?;
    Ok(())
}

fn receipt_for(path: &Path, resumed: bool) -> Result<DownloadReceipt, DownloadError> {
    Ok(DownloadReceipt {
        path: path.to_path_buf(),
        bytes: fs::metadata(path)?.len(),
        sha256: sha256_file(path)?,
        resumed,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;
    use std::fs;
    use std::io::Cursor;
    use std::sync::Mutex;
    use tempfile::tempdir;

    struct FakeTransport {
        responses: Mutex<VecDeque<DownloadResponse>>,
    }

    impl FakeTransport {
        fn new(responses: Vec<DownloadResponse>) -> Self {
            Self {
                responses: Mutex::new(responses.into()),
            }
        }
    }

    impl DownloadTransport for FakeTransport {
        fn get(
            &self,
            _url: &str,
            _range_from: Option<u64>,
            _timeout: Duration,
        ) -> Result<DownloadResponse, DownloadError> {
            self.responses
                .lock()
                .unwrap()
                .pop_front()
                .ok_or_else(|| DownloadError::Transport("no fake response".to_owned()))
        }
    }

    fn response(status: u16, body: &[u8]) -> DownloadResponse {
        DownloadResponse {
            status,
            body: Box::new(Cursor::new(body.to_vec())),
        }
    }

    #[test]
    fn verifies_checksum_before_promoting_download() {
        let directory = tempdir().unwrap();
        let destination = directory.path().join("tool.zip");
        let request = DownloadRequest::new("https://example.test/tool.zip", &destination)
            .expected_sha256("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        let downloader = ResumableDownloader::with_retry_delay(
            FakeTransport::new(vec![response(200, b"hello")]),
            Duration::ZERO,
        );
        let receipt = downloader.download(&request).unwrap();
        assert_eq!(receipt.bytes, 5);
        assert!(destination.is_file());
        assert!(!destination.with_file_name("tool.zip.partial").exists());
    }

    #[test]
    fn resumes_partial_and_removes_corrupt_checksum() {
        let directory = tempdir().unwrap();
        let destination = directory.path().join("tool.zip");
        let partial = directory.path().join("tool.zip.partial");
        fs::write(&partial, b"hello ").unwrap();
        let expected_file = directory.path().join("expected");
        fs::write(&expected_file, b"hello world").unwrap();
        let expected = sha256_file(&expected_file).unwrap();
        let request = DownloadRequest::new("https://example.test/tool.zip", &destination)
            .expected_sha256(expected);
        let downloader = ResumableDownloader::with_retry_delay(
            FakeTransport::new(vec![response(206, b"world")]),
            Duration::ZERO,
        );
        let receipt = downloader.download(&request).unwrap();
        assert!(receipt.resumed);
        assert_eq!(fs::read(destination).unwrap(), b"hello world");

        let bad_destination = directory.path().join("bad.zip");
        let bad_partial = directory.path().join("bad.zip.partial");
        fs::write(&bad_partial, b"stale").unwrap();
        let bad_request = DownloadRequest::new("https://example.test/bad.zip", &bad_destination)
            .expected_sha256("0000000000000000000000000000000000000000000000000000000000000000");
        let bad_downloader = ResumableDownloader::with_retry_delay(
            FakeTransport::new(vec![response(200, b"bad")]),
            Duration::ZERO,
        );
        assert!(matches!(
            bad_downloader.download(&bad_request),
            Err(DownloadError::Checksum(_))
        ));
        assert!(!bad_partial.exists());
        assert!(!bad_destination.exists());
    }

    #[test]
    fn rejects_non_https_http_transport_urls() {
        let downloader = HttpDownloader::default();
        let directory = tempdir().unwrap();
        let request = DownloadRequest::new(
            "http://example.test/tool.zip",
            directory.path().join("tool.zip"),
        );
        assert!(matches!(
            downloader.download(&request),
            Err(DownloadError::InvalidUrl)
        ));
    }
}
