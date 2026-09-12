//! Signed rolling-update manifest verification.
//!
//! The manifest is an input to the updater, never an instruction stream.  It can select only
//! the immutable commit and the two toolchain descriptors understood by this installer.  URLs,
//! paths and executable mappings are still validated by the normal toolchain and Git policies.

use std::collections::BTreeMap;
use std::io::{self, Read};
use std::time::Duration;

use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use reqwest::blocking::Client;
use reqwest::redirect::Policy;
use reqwest::Url;
use semver::Version;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::paths::{Platform, TargetArchitecture};
use crate::toolchain::{ToolchainDescriptor, ToolchainError, ToolchainKind};

pub const MANIFEST_SCHEMA_VERSION: u32 = 1;
pub const DEFAULT_MANIFEST_URL: &str =
    "https://github.com/AngelicaProject/HarmoniaSuite/releases/latest/download/harmonia-manifest.json";
pub const DEFAULT_MANIFEST_SIGNATURE_URL: &str =
    "https://github.com/AngelicaProject/HarmoniaSuite/releases/latest/download/harmonia-manifest.json.sig";

// Release infrastructure owns the corresponding private key outside this repository. Replacing
// this trust root requires a reviewed installer release. The private key is never accepted from
// runtime input and is provisioned only through the release secret/file contract.
const PRIMARY_PUBLIC_KEY_HEX: &str =
    "ee7809268d92d5a832ae8fb03f7d090123073a4833cb7f1f725babfe74ff1925";
const MAX_MANIFEST_BYTES: usize = 1024 * 1024;

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct RollingManifest {
    pub schema_version: u32,
    pub channel: String,
    pub generation: u64,
    pub product_version: String,
    pub target_commit: String,
    #[serde(default)]
    pub min_installer_version: Option<String>,
    pub jdk: ToolchainDescriptor,
    pub node: ToolchainDescriptor,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
pub struct ManifestSignature {
    pub schema_version: u32,
    #[serde(rename = "keyId")]
    pub key_id: String,
    #[serde(rename = "signatureHex")]
    pub signature_hex: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SignedManifest {
    pub manifest: RollingManifest,
    pub key_id: String,
    pub manifest_sha256: String,
}

#[derive(Debug, Error)]
pub enum ManifestError {
    #[error("manifest URL is not an HTTPS URL without credentials: {0}")]
    InvalidUrl(String),
    #[error("manifest transport failed: {0}")]
    Transport(String),
    #[error("manifest HTTP status {0}")]
    HttpStatus(u16),
    #[error("manifest exceeds the bounded size limit")]
    TooLarge,
    #[error("manifest I/O failed: {0}")]
    Io(#[from] io::Error),
    #[error("manifest JSON failed: {0}")]
    Json(#[from] serde_json::Error),
    #[error("manifest signature schema is unsupported")]
    UnsupportedSignatureSchema,
    #[error("manifest signature encoding is invalid")]
    InvalidSignature,
    #[error("manifest key is unknown: {0}")]
    UnknownKey(String),
    #[error("manifest signature verification failed")]
    SignatureVerification,
    #[error("manifest schema is unsupported: {0}")]
    UnsupportedSchema(u32),
    #[error("manifest requires a newer installer: {0}")]
    UpdaterUpgradeRequired(String),
    #[error("manifest minimum installer version is malformed: {0}")]
    InvalidMinimumInstallerVersion(String),
    #[error("manifest channel is unsupported")]
    UnsupportedChannel,
    #[error("manifest target commit is invalid")]
    InvalidCommit,
    #[error("manifest product version is empty")]
    EmptyProductVersion,
    #[error("manifest toolchain is invalid: {0}")]
    Toolchain(#[from] ToolchainError),
    #[error("manifest toolchain kind is invalid")]
    InvalidToolchainKind,
    #[error("manifest toolchain platform or architecture does not match the installer")]
    WrongTarget,
}

impl ManifestError {
    pub fn is_trust_failure(&self) -> bool {
        matches!(
            self,
            Self::InvalidUrl(_)
                | Self::Json(_)
                | Self::UnsupportedSignatureSchema
                | Self::InvalidSignature
                | Self::UnknownKey(_)
                | Self::SignatureVerification
                | Self::UnsupportedSchema(_)
                | Self::InvalidMinimumInstallerVersion(_)
                | Self::UnsupportedChannel
                | Self::InvalidCommit
                | Self::EmptyProductVersion
                | Self::Toolchain(_)
                | Self::InvalidToolchainKind
                | Self::WrongTarget
        )
    }
}

pub trait ManifestFetcher: Send + Sync {
    fn fetch(&self, url: &str) -> Result<Vec<u8>, ManifestError>;
}

#[derive(Clone, Debug)]
pub struct HttpManifestFetcher {
    pub timeout: Duration,
}

impl Default for HttpManifestFetcher {
    fn default() -> Self {
        Self {
            timeout: Duration::from_secs(30),
        }
    }
}

impl ManifestFetcher for HttpManifestFetcher {
    fn fetch(&self, value: &str) -> Result<Vec<u8>, ManifestError> {
        let url = validate_https_url(value)?;
        let client = Client::builder()
            .timeout(self.timeout)
            .redirect(Policy::custom(|attempt| {
                let previous_https = attempt
                    .previous()
                    .iter()
                    .all(|item| item.scheme() == "https");
                if attempt.url().scheme() == "https"
                    && previous_https
                    && attempt.previous().len() < 5
                {
                    attempt.follow()
                } else {
                    attempt.stop()
                }
            }))
            .user_agent("harmonia-suite-updater/1")
            .build()
            .map_err(|error| ManifestError::Transport(error.to_string()))?;
        let response = client
            .get(url)
            .send()
            .map_err(|error| ManifestError::Transport(error.to_string()))?;
        if response.url().scheme() != "https" {
            return Err(ManifestError::InvalidUrl(response.url().to_string()));
        }
        if !response.status().is_success() {
            return Err(ManifestError::HttpStatus(response.status().as_u16()));
        }
        if response
            .content_length()
            .is_some_and(|length| length > MAX_MANIFEST_BYTES as u64)
        {
            return Err(ManifestError::TooLarge);
        }
        let mut bytes = Vec::new();
        response
            .take((MAX_MANIFEST_BYTES + 1) as u64)
            .read_to_end(&mut bytes)?;
        if bytes.len() > MAX_MANIFEST_BYTES {
            return Err(ManifestError::TooLarge);
        }
        Ok(bytes)
    }
}

#[derive(Clone, Debug)]
pub struct ManifestVerifier {
    keys: BTreeMap<String, [u8; 32]>,
}

impl ManifestVerifier {
    pub fn production() -> Self {
        let mut keys = BTreeMap::new();
        keys.insert(
            "primary-2026".to_owned(),
            decode_hex_32(PRIMARY_PUBLIC_KEY_HEX).unwrap(),
        );
        Self { keys }
    }

    pub fn with_keys(keys: BTreeMap<String, [u8; 32]>) -> Self {
        Self { keys }
    }

    pub fn verify(
        &self,
        manifest_bytes: &[u8],
        signature_bytes: &[u8],
        platform: Platform,
        architecture: &TargetArchitecture,
    ) -> Result<SignedManifest, ManifestError> {
        let envelope: ManifestSignature = serde_json::from_slice(signature_bytes)?;
        if envelope.schema_version != MANIFEST_SCHEMA_VERSION {
            return Err(ManifestError::UnsupportedSignatureSchema);
        }
        let key_bytes = self
            .keys
            .get(&envelope.key_id)
            .ok_or_else(|| ManifestError::UnknownKey(envelope.key_id.clone()))?;
        let key =
            VerifyingKey::from_bytes(key_bytes).map_err(|_| ManifestError::InvalidSignature)?;
        let signature_bytes =
            decode_hex::<64>(&envelope.signature_hex).ok_or(ManifestError::InvalidSignature)?;
        let signature = Signature::from_bytes(&signature_bytes);
        key.verify(manifest_bytes, &signature)
            .map_err(|_| ManifestError::SignatureVerification)?;

        let manifest: RollingManifest = serde_json::from_slice(manifest_bytes)?;
        validate_manifest(&manifest, platform, architecture)?;
        let mut digest = Sha256::new();
        digest.update(manifest_bytes);
        Ok(SignedManifest {
            manifest,
            key_id: envelope.key_id,
            manifest_sha256: hex_digest(&digest.finalize()),
        })
    }
}

fn validate_manifest(
    manifest: &RollingManifest,
    platform: Platform,
    architecture: &TargetArchitecture,
) -> Result<(), ManifestError> {
    if manifest.schema_version != MANIFEST_SCHEMA_VERSION {
        return Err(ManifestError::UnsupportedSchema(manifest.schema_version));
    }
    if manifest.channel != "rolling" {
        return Err(ManifestError::UnsupportedChannel);
    }
    if manifest.product_version.trim().is_empty() {
        return Err(ManifestError::EmptyProductVersion);
    }
    if let Some(minimum) = manifest.min_installer_version.as_deref() {
        Version::parse(minimum)
            .map_err(|_| ManifestError::InvalidMinimumInstallerVersion(minimum.to_owned()))?;
    }
    if manifest.target_commit.len() != 40
        || !manifest
            .target_commit
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
    {
        return Err(ManifestError::InvalidCommit);
    }
    if manifest.jdk.kind != ToolchainKind::Jdk || manifest.node.kind != ToolchainKind::Node {
        return Err(ManifestError::InvalidToolchainKind);
    }
    for descriptor in [&manifest.jdk, &manifest.node] {
        descriptor.validate_for(platform, architecture)?;
    }
    Ok(())
}

fn validate_https_url(value: &str) -> Result<Url, ManifestError> {
    let url = Url::parse(value).map_err(|_| ManifestError::InvalidUrl(value.to_owned()))?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err(ManifestError::InvalidUrl(value.to_owned()));
    }
    Ok(url)
}

fn decode_hex<const N: usize>(value: &str) -> Option<[u8; N]> {
    if value.len() != N * 2 {
        return None;
    }
    let mut bytes = [0u8; N];
    for (index, pair) in value.as_bytes().chunks_exact(2).enumerate() {
        bytes[index] = (hex_nibble(pair[0])? << 4) | hex_nibble(pair[1])?;
    }
    Some(bytes)
}

fn decode_hex_32(value: &str) -> Option<[u8; 32]> {
    decode_hex(value)
}

fn hex_nibble(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

fn hex_digest(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    use std::collections::BTreeMap;

    fn manifest() -> RollingManifest {
        let descriptor = |kind| {
            ToolchainDescriptor::new(
                kind,
                "test",
                Platform::Linux,
                TargetArchitecture::X64,
                "https://example.invalid/tool.tar.gz",
                "0000000000000000000000000000000000000000000000000000000000000000",
                crate::toolchain::ArchiveFormat::TarGz,
            )
            .home_dir("root")
            .executable(
                if kind == ToolchainKind::Jdk {
                    "java"
                } else {
                    "node"
                },
                "root/bin/tool",
            )
        };
        RollingManifest {
            schema_version: 1,
            channel: "rolling".to_owned(),
            generation: 4,
            product_version: "1.2.3".to_owned(),
            target_commit: "0123456789abcdef0123456789abcdef01234567".to_owned(),
            min_installer_version: None,
            jdk: descriptor(ToolchainKind::Jdk),
            node: descriptor(ToolchainKind::Node),
        }
    }

    #[test]
    fn verifies_exact_raw_manifest_bytes_and_rejects_tampering() {
        let manifest_bytes = serde_json::to_vec(&manifest()).unwrap();
        let signing = SigningKey::from_bytes(&[7u8; 32]);
        let signature = signing.sign(&manifest_bytes);
        let signature_bytes = serde_json::to_vec(&ManifestSignature {
            schema_version: 1,
            key_id: "test".to_owned(),
            signature_hex: signature
                .to_bytes()
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect(),
        })
        .unwrap();
        let verifier = ManifestVerifier::with_keys(BTreeMap::from([(
            "test".to_owned(),
            signing.verifying_key().to_bytes(),
        )]));
        verifier
            .verify(
                &manifest_bytes,
                &signature_bytes,
                Platform::Linux,
                &TargetArchitecture::X64,
            )
            .unwrap();
        let mut tampered = manifest_bytes.clone();
        tampered.push(b' ');
        assert!(matches!(
            verifier.verify(
                &tampered,
                &signature_bytes,
                Platform::Linux,
                &TargetArchitecture::X64
            ),
            Err(ManifestError::SignatureVerification)
        ));
    }

    #[test]
    fn rejects_unknown_key_and_credential_bearing_urls() {
        let verifier = ManifestVerifier::production();
        assert!(matches!(
            validate_https_url("https://user:secret@example.invalid/manifest"),
            Err(ManifestError::InvalidUrl(_))
        ));
        let signature = serde_json::to_vec(&ManifestSignature {
            schema_version: 1,
            key_id: "unknown".to_owned(),
            signature_hex: "00".repeat(64),
        })
        .unwrap();
        assert!(matches!(
            verifier.verify(b"{}", &signature, Platform::Linux, &TargetArchitecture::X64),
            Err(ManifestError::UnknownKey(_))
        ));
    }

    #[test]
    fn rejects_wrong_channel_and_malformed_minimum_version() {
        let signing = SigningKey::from_bytes(&[8u8; 32]);
        let verifier = ManifestVerifier::with_keys(BTreeMap::from([(
            "test".to_owned(),
            signing.verifying_key().to_bytes(),
        )]));
        for (channel, minimum, expected) in [
            ("stable", None, ManifestError::UnsupportedChannel),
            (
                "rolling",
                Some("1.bad.0"),
                ManifestError::InvalidMinimumInstallerVersion("1.bad.0".to_owned()),
            ),
        ] {
            let mut value = manifest();
            value.channel = channel.to_owned();
            value.min_installer_version = minimum.map(str::to_owned);
            let bytes = serde_json::to_vec(&value).unwrap();
            let signature = signing.sign(&bytes);
            let envelope = serde_json::to_vec(&ManifestSignature {
                schema_version: 1,
                key_id: "test".to_owned(),
                signature_hex: signature
                    .to_bytes()
                    .iter()
                    .map(|byte| format!("{byte:02x}"))
                    .collect(),
            })
            .unwrap();
            let error = verifier
                .verify(&bytes, &envelope, Platform::Linux, &TargetArchitecture::X64)
                .unwrap_err();
            match (error, expected) {
                (ManifestError::UnsupportedChannel, ManifestError::UnsupportedChannel) => {}
                (
                    ManifestError::InvalidMinimumInstallerVersion(actual),
                    ManifestError::InvalidMinimumInstallerVersion(expected),
                ) => assert_eq!(actual, expected),
                (actual, expected) => panic!("unexpected errors: {actual:?} vs {expected:?}"),
            }
        }
    }

    #[test]
    fn accepts_valid_minimum_version_and_serializes_required_channel() {
        let mut value = manifest();
        value.min_installer_version = Some("0.1.0".to_owned());
        let document = serde_json::to_value(value).unwrap();
        assert_eq!(document["channel"], "rolling");
    }

    #[test]
    fn production_trust_root_is_not_the_public_rfc_test_vector() {
        assert_ne!(
            PRIMARY_PUBLIC_KEY_HEX,
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        );
    }
}
