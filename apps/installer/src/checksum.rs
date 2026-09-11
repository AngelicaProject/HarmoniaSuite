use std::fs::File;
use std::io::{self, Read};
use std::path::Path;

use sha2::{Digest, Sha256};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum ChecksumError {
    #[error("cannot read file for SHA-256: {0}")]
    Io(#[from] io::Error),
    #[error("SHA-256 must be exactly 64 hexadecimal characters")]
    InvalidExpected,
    #[error("SHA-256 mismatch: expected {expected}, got {actual}")]
    Mismatch { expected: String, actual: String },
}

pub fn sha256_file(path: impl AsRef<Path>) -> Result<String, ChecksumError> {
    let mut file = File::open(path)?;
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 1024 * 64];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        digest.update(&buffer[..read]);
    }
    Ok(hex_digest(&digest.finalize()))
}

pub fn verify_sha256(path: impl AsRef<Path>, expected: &str) -> Result<String, ChecksumError> {
    let normalized = expected.trim().to_ascii_lowercase();
    if normalized.len() != 64 || !normalized.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(ChecksumError::InvalidExpected);
    }
    let actual = sha256_file(path)?;
    if actual != normalized {
        return Err(ChecksumError::Mismatch {
            expected: normalized,
            actual,
        });
    }
    Ok(actual)
}

fn hex_digest(bytes: &[u8]) -> String {
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        output.push_str(&format!("{byte:02x}"));
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn verifies_sha256_and_rejects_tampering() {
        let directory = tempdir().unwrap();
        let file = directory.path().join("artifact.bin");
        fs::write(&file, b"harmonia").unwrap();
        let expected = "88e98e6a7c3c6a6c6fd4f50d4f87f97a3e4eb4aa6d7c2a89e2b4f7c2e0a9df2b";
        let actual = sha256_file(&file).unwrap();
        assert_eq!(actual.len(), 64);
        assert!(verify_sha256(&file, &actual).is_ok());
        assert!(matches!(
            verify_sha256(&file, expected),
            Err(ChecksumError::Mismatch { .. })
        ));
    }

    #[test]
    fn rejects_invalid_digest_shape() {
        let directory = tempdir().unwrap();
        let file = directory.path().join("artifact.bin");
        fs::write(&file, b"data").unwrap();
        assert!(matches!(
            verify_sha256(&file, "nope"),
            Err(ChecksumError::InvalidExpected)
        ));
    }
}
