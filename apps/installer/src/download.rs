use std::fs::{self, File, OpenOptions};
use std::io::{self, Read};
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
    expected_sha256: String,
    pub timeout: Duration,
    pub max_retries: u32,
    pub resume: bool,
}

impl DownloadRequest {
    pub fn new(
        url: impl Into<String>,
        destination: impl Into<PathBuf>,
        expected_sha256: impl Into<String>,
    ) -> Self {
        Self {
            url: url.into(),
            destination: destination.into(),
            expected_sha256: expected_sha256.into(),
            timeout: Duration::from_secs(10 * 60),
            max_retries: 2,
            resume: true,
        }
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
    #[error("download destination is a symlink or reparse point: {0}")]
    UnsafeDestination(PathBuf),
    #[error("download resume range mismatch: expected start {expected}, got {actual:?}")]
    ResumeRangeMismatch { expected: u64, actual: Option<u64> },
}

pub struct DownloadResponse {
    pub status: u16,
    pub content_range_start: Option<u64>,
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
        let expected = &request.expected_sha256;
        let partial = partial_path(&request.destination)?;
        reject_unsafe_path(&request.destination)?;
        reject_unsafe_path(&partial)?;
        if request.destination.is_file() && verify_sha256(&request.destination, expected).is_ok() {
            return receipt_for(&request.destination, false);
        }
        if request.destination.is_file() {
            fs::remove_file(&request.destination)?;
        }
        if let Some(parent) = request.destination.parent() {
            fs::create_dir_all(parent)?;
        }
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
            if !(200..300).contains(&response.status) {
                if response.status >= 400 && response.status < 500 {
                    let _ = fs::remove_file(&partial);
                }
                return Err(DownloadError::HttpStatus(response.status));
            }

            let append = existing > 0 && response.status == 206;
            if append && response.content_range_start != Some(existing) {
                return Err(DownloadError::ResumeRangeMismatch {
                    expected: existing,
                    actual: response.content_range_start,
                });
            }
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
            if let Err(error) = verify_sha256(&partial, expected) {
                let _ = fs::remove_file(&partial);
                return Err(error.into());
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
            .redirect(Policy::custom(|attempt| {
                let all_https = attempt.previous().iter().all(|url| url.scheme() == "https");
                if attempt.url().scheme() == "https" && all_https && attempt.previous().len() < 5 {
                    attempt.follow()
                } else {
                    attempt.stop()
                }
            }))
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
            content_range_start: response
                .headers()
                .get(reqwest::header::CONTENT_RANGE)
                .and_then(|value| value.to_str().ok())
                .and_then(parse_content_range_start),
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
    let expected = request.expected_sha256.trim();
    if expected.len() != 64 || !expected.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(DownloadError::InvalidRequest(
            "a valid expected SHA-256 is required".to_owned(),
        ));
    }
    Ok(())
}

fn parse_content_range_start(value: &str) -> Option<u64> {
    let range = value.strip_prefix("bytes ")?;
    let (start, _) = range.split_once('-')?;
    start.parse().ok()
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

fn reject_unsafe_path(path: &Path) -> Result<(), DownloadError> {
    for ancestor in path.ancestors().collect::<Vec<_>>().into_iter().rev() {
        match fs::symlink_metadata(ancestor) {
            Ok(metadata) => {
                if metadata.file_type().is_symlink() || is_reparse_point(ancestor)? {
                    return Err(DownloadError::UnsafeDestination(ancestor.to_path_buf()));
                }
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
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
            content_range_start: None,
            body: Box::new(Cursor::new(body.to_vec())),
        }
    }

    fn partial_response(start: u64, body: &[u8]) -> DownloadResponse {
        DownloadResponse {
            status: 206,
            content_range_start: Some(start),
            body: Box::new(Cursor::new(body.to_vec())),
        }
    }

    #[test]
    fn verifies_checksum_before_promoting_download() {
        let directory = tempdir().unwrap();
        let destination = directory.path().join("tool.zip");
        let request = DownloadRequest::new(
            "https://example.test/tool.zip",
            &destination,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        );
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
        let request = DownloadRequest::new("https://example.test/tool.zip", &destination, expected);
        let downloader = ResumableDownloader::with_retry_delay(
            FakeTransport::new(vec![partial_response(6, b"world")]),
            Duration::ZERO,
        );
        let receipt = downloader.download(&request).unwrap();
        assert!(receipt.resumed);
        assert_eq!(fs::read(destination).unwrap(), b"hello world");

        let bad_destination = directory.path().join("bad.zip");
        let bad_partial = directory.path().join("bad.zip.partial");
        fs::write(&bad_partial, b"stale").unwrap();
        let bad_request = DownloadRequest::new(
            "https://example.test/bad.zip",
            &bad_destination,
            "0000000000000000000000000000000000000000000000000000000000000000",
        );
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
            "0000000000000000000000000000000000000000000000000000000000000000",
        );
        assert!(matches!(
            downloader.download(&request),
            Err(DownloadError::InvalidUrl)
        ));
    }

    #[test]
    fn rejects_missing_checksum_before_transport() {
        let directory = tempdir().unwrap();
        let request = DownloadRequest::new(
            "https://example.test/tool.zip",
            directory.path().join("tool.zip"),
            "",
        );
        let downloader = ResumableDownloader::with_retry_delay(
            FakeTransport::new(vec![response(200, b"unused")]),
            Duration::ZERO,
        );
        assert!(matches!(
            downloader.download(&request),
            Err(DownloadError::InvalidRequest(_))
        ));
    }

    #[test]
    fn rejects_resume_response_with_wrong_content_range_start() {
        let directory = tempdir().unwrap();
        let destination = directory.path().join("tool.zip");
        let partial = directory.path().join("tool.zip.partial");
        fs::write(&partial, b"hello ").unwrap();
        let expected_file = directory.path().join("expected");
        fs::write(&expected_file, b"hello world").unwrap();
        let expected = sha256_file(&expected_file).unwrap();
        let request = DownloadRequest::new("https://example.test/tool.zip", &destination, expected);
        let downloader = ResumableDownloader::with_retry_delay(
            FakeTransport::new(vec![partial_response(0, b"world")]),
            Duration::ZERO,
        );
        assert!(matches!(
            downloader.download(&request),
            Err(DownloadError::ResumeRangeMismatch {
                expected: 6,
                actual: Some(0)
            })
        ));
        assert_eq!(fs::read(partial).unwrap(), b"hello ");
        assert!(!destination.exists());
    }

    #[cfg(unix)]
    #[test]
    fn rejects_symlink_partial_before_writing() {
        use std::os::unix::fs::symlink;

        let directory = tempdir().unwrap();
        let destination = directory.path().join("tool.zip");
        let external = directory.path().join("external");
        fs::write(&external, b"must stay unchanged").unwrap();
        symlink(&external, destination.with_file_name("tool.zip.partial")).unwrap();
        let request = DownloadRequest::new(
            "https://example.test/tool.zip",
            &destination,
            "0000000000000000000000000000000000000000000000000000000000000000",
        );
        let downloader = ResumableDownloader::with_retry_delay(
            FakeTransport::new(vec![response(200, b"unused")]),
            Duration::ZERO,
        );
        assert!(matches!(
            downloader.download(&request),
            Err(DownloadError::UnsafeDestination(_))
        ));
        assert_eq!(fs::read(external).unwrap(), b"must stay unchanged");
    }
}
