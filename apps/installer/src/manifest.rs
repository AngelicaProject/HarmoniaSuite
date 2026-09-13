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
pub const ROLLING_MANIFEST_BASE_URL: &str =
    "https://angelicaproject.github.io/HarmoniaSuite/rolling";

pub fn production_manifest_urls(platform: Platform) -> (String, String) {
    let base = format!(
        "{}/{}/manifest.json",
        ROLLING_MANIFEST_BASE_URL,
        platform.as_str()
    );
    let signature = format!("{}.sig", base);
    (base, signature)
}

// Release infrastructure owns the corresponding private keys outside this repository. Replacing
// this trust root requires a reviewed installer release. Private keys are never accepted from
// runtime input and are provisioned only through the release secret/file contract. Keep future
// rotations as an overlap (A + B) until clients trusting A have adopted the release containing B.
const PRODUCTION_MANIFEST_KEYS: &[(&str, &str)] = &[(
    "primary-2026-09",
    "5e02dfc689bc3c447cffa720d94225b5bedb593cb4f77c5ba0461103705363d2",
)];
const MAX_MANIFEST_BYTES: usize = 1024 * 1024;

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
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
#[serde(rename_all = "camelCase")]
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
    #[error("manifest product version is malformed: {0}")]
    InvalidProductVersion(String),
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
                | Self::InvalidProductVersion(_)
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
    keys: BTreeMap<String, VerifyingKey>,
}

impl ManifestVerifier {
    pub fn production() -> Self {
        let keys: BTreeMap<String, VerifyingKey> = PRODUCTION_MANIFEST_KEYS
            .iter()
            .map(|(key_id, public_key_hex)| {
                let public_key_bytes = decode_hex_32(public_key_hex).unwrap_or_else(|| {
                    panic!("compiled production manifest key {key_id} is not 32-byte hex")
                });
                let key = VerifyingKey::from_bytes(&public_key_bytes).unwrap_or_else(|_| {
                    panic!("compiled production manifest key {key_id} is not a valid Ed25519 key")
                });
                ((*key_id).to_owned(), key)
            })
            .collect();
        Self { keys }
    }

    pub fn with_keys(keys: BTreeMap<String, [u8; 32]>) -> Self {
        let keys = keys
            .into_iter()
            .map(|(key_id, public_key_bytes)| {
                let key = VerifyingKey::from_bytes(&public_key_bytes)
                    .expect("injected manifest verifier key must be a valid Ed25519 key");
                (key_id, key)
            })
            .collect();
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
        let key = self
            .keys
            .get(&envelope.key_id)
            .ok_or_else(|| ManifestError::UnknownKey(envelope.key_id.clone()))?;
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
    Version::parse(&manifest.product_version)
        .map_err(|_| ManifestError::InvalidProductVersion(manifest.product_version.clone()))?;
    if let Some(minimum) = manifest.min_installer_version.as_deref() {
        let parsed = Version::parse(minimum)
            .map_err(|_| ManifestError::InvalidMinimumInstallerVersion(minimum.to_owned()))?;
        if !parsed.pre.is_empty() {
            return Err(ManifestError::InvalidMinimumInstallerVersion(
                minimum.to_owned(),
            ));
        }
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
    for (index, byte) in bytes.iter_mut().enumerate() {
        let offset = index * 2;
        *byte = (hex_nibble(value.as_bytes()[offset])? << 4)
            | hex_nibble(value.as_bytes()[offset + 1])?;
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

        let old_production_signature = serde_json::to_vec(&ManifestSignature {
            schema_version: 1,
            key_id: "primary-2026".to_owned(),
            signature_hex: "00".repeat(64),
        })
        .unwrap();
        assert!(matches!(
            verifier.verify(
                b"{}",
                &old_production_signature,
                Platform::Linux,
                &TargetArchitecture::X64
            ),
            Err(ManifestError::UnknownKey(key_id)) if key_id == "primary-2026"
        ));
    }

    #[test]
    fn production_keyring_contains_only_the_current_key_and_rejects_wrong_signatures() {
        assert_eq!(PRODUCTION_MANIFEST_KEYS.len(), 1);
        assert_eq!(PRODUCTION_MANIFEST_KEYS[0].0, "primary-2026-09");
        assert!(decode_hex_32(PRODUCTION_MANIFEST_KEYS[0].1).is_some());

        let verifier = ManifestVerifier::production();
        let signature = serde_json::to_vec(&ManifestSignature {
            schema_version: 1,
            key_id: "primary-2026-09".to_owned(),
            signature_hex: "00".repeat(64),
        })
        .unwrap();
        assert!(matches!(
            verifier.verify(b"{}", &signature, Platform::Linux, &TargetArchitecture::X64),
            Err(ManifestError::SignatureVerification)
        ));
    }

    #[test]
    fn verifies_manifests_from_both_keys_during_rotation_overlap() {
        let manifest_bytes = serde_json::to_vec(&manifest()).unwrap();
        let signing_a = SigningKey::from_bytes(&[21u8; 32]);
        let signing_b = SigningKey::from_bytes(&[22u8; 32]);
        let verifier = ManifestVerifier::with_keys(BTreeMap::from([
            ("primary-a".to_owned(), signing_a.verifying_key().to_bytes()),
            ("primary-b".to_owned(), signing_b.verifying_key().to_bytes()),
        ]));

        for (key_id, signing_key) in [("primary-a", signing_a), ("primary-b", signing_b)] {
            let signature = signing_key.sign(&manifest_bytes);
            let signature_bytes = serde_json::to_vec(&ManifestSignature {
                schema_version: 1,
                key_id: key_id.to_owned(),
                signature_hex: signature
                    .to_bytes()
                    .iter()
                    .map(|byte| format!("{byte:02x}"))
                    .collect(),
            })
            .unwrap();
            verifier
                .verify(
                    &manifest_bytes,
                    &signature_bytes,
                    Platform::Linux,
                    &TargetArchitecture::X64,
                )
                .unwrap();
        }
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
    fn production_urls_are_platform_specific_pages_endpoints() {
        let (windows, windows_sig) = production_manifest_urls(Platform::Windows);
        let (linux, linux_sig) = production_manifest_urls(Platform::Linux);
        assert_eq!(
            windows,
            "https://angelicaproject.github.io/HarmoniaSuite/rolling/windows/manifest.json"
        );
        assert_eq!(
            linux,
            "https://angelicaproject.github.io/HarmoniaSuite/rolling/linux/manifest.json"
        );
        assert!(windows_sig.ends_with("/manifest.json.sig"));
        assert!(linux_sig.ends_with("/manifest.json.sig"));
    }

    #[test]
    fn verifies_actual_node_generated_and_signed_manifests_on_both_platforms() {
        use std::fs;
        use std::path::PathBuf;
        use std::process::Command;
        use tempfile::tempdir;

        let root = tempdir().unwrap();
        let key_path = root.path().join("fixture-ed25519-key.pem");
        fs::write(
            &key_path,
            "-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEIPTbj1QOnOfs/NEu9Bbd/aQxEBWtyXdzibqMIxJmyY7j\n-----END PRIVATE KEY-----\n",
        )
        .unwrap();

        let verifier = ManifestVerifier::with_keys(BTreeMap::from([(
            "cross-language".to_owned(),
            decode_hex_32("69b02488d9b687a23cb0918614519c97f1618f5d8c005437e82de9b997835192")
                .unwrap(),
        )]));
        let repo_root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
        let generator = repo_root.join("tools/release/create-manifest.mjs");
        let signer = repo_root.join("tools/release/sign-manifest.mjs");
        let target = "0123456789abcdef0123456789abcdef01234567";

        for (name, platform) in [("linux", Platform::Linux), ("windows", Platform::Windows)] {
            let manifest_path = root.path().join(format!("manifest-{name}.json"));
            let signature_path = root.path().join(format!("manifest-{name}.json.sig"));
            let output = Command::new("node")
                .current_dir(&repo_root)
                .arg(&generator)
                .args([
                    "--platform",
                    name,
                    "--target-commit",
                    target,
                    "--product-version",
                    "1.0.11-SNAPSHOT",
                    "--min-installer-version",
                    "0.1.0",
                    "--generation",
                    "1",
                    "--out",
                ])
                .arg(&manifest_path)
                .output()
                .unwrap();
            assert!(
                output.status.success(),
                "manifest generator failed: {}",
                String::from_utf8_lossy(&output.stderr)
            );

            let sign = |input: &PathBuf, output: &PathBuf| {
                let result = Command::new("node")
                    .current_dir(&repo_root)
                    .env("HARMONIA_MANIFEST_SIGNING_KEY_FILE", &key_path)
                    .arg(&signer)
                    .arg(input)
                    .arg(output)
                    .arg("cross-language")
                    .output()
                    .unwrap();
                assert!(
                    result.status.success(),
                    "release signer failed: {}",
                    String::from_utf8_lossy(&result.stderr)
                );
            };
            sign(&manifest_path, &signature_path);

            let manifest_bytes = fs::read(&manifest_path).unwrap();
            let envelope = fs::read(&signature_path).unwrap();
            let document: serde_json::Value = serde_json::from_slice(&envelope).unwrap();
            assert_eq!(document["schemaVersion"], 1);
            assert_eq!(document["keyId"], "cross-language");
            assert!(document.get("schema_version").is_none());
            verifier
                .verify(
                    &manifest_bytes,
                    &envelope,
                    platform,
                    &TargetArchitecture::X64,
                )
                .unwrap();

            let wrong_platform = if platform == Platform::Linux {
                Platform::Windows
            } else {
                Platform::Linux
            };
            assert!(matches!(
                verifier.verify(
                    &manifest_bytes,
                    &envelope,
                    wrong_platform,
                    &TargetArchitecture::X64
                ),
                Err(ManifestError::Toolchain(
                    crate::toolchain::ToolchainError::UnsupportedTarget { .. }
                ))
            ));

            let mut tampered = manifest_bytes.clone();
            tampered[0] = b'[';
            assert!(matches!(
                verifier.verify(&tampered, &envelope, platform, &TargetArchitecture::X64),
                Err(ManifestError::SignatureVerification)
            ));

            let mut wrong_signature =
                serde_json::from_slice::<serde_json::Value>(&envelope).unwrap();
            let mut signature_hex = wrong_signature["signatureHex"].as_str().unwrap().to_owned();
            signature_hex.replace_range(..2, "00");
            wrong_signature["signatureHex"] = serde_json::Value::String(signature_hex);
            let wrong_signature_bytes = serde_json::to_vec(&wrong_signature).unwrap();
            assert!(matches!(
                verifier.verify(
                    &manifest_bytes,
                    &wrong_signature_bytes,
                    platform,
                    &TargetArchitecture::X64
                ),
                Err(ManifestError::SignatureVerification)
            ));

            let wrong_channel_path = root
                .path()
                .join(format!("manifest-{name}-wrong-channel.json"));
            let wrong_channel_signature = root
                .path()
                .join(format!("manifest-{name}-wrong-channel.json.sig"));
            let mut wrong_channel =
                serde_json::from_slice::<serde_json::Value>(&manifest_bytes).unwrap();
            wrong_channel["channel"] = serde_json::Value::String("tagged".to_owned());
            fs::write(
                &wrong_channel_path,
                serde_json::to_vec(&wrong_channel).unwrap(),
            )
            .unwrap();
            sign(&wrong_channel_path, &wrong_channel_signature);
            assert!(matches!(
                verifier.verify(
                    &fs::read(&wrong_channel_path).unwrap(),
                    &fs::read(&wrong_channel_signature).unwrap(),
                    platform,
                    &TargetArchitecture::X64
                ),
                Err(ManifestError::UnsupportedChannel)
            ));
        }
    }

    #[test]
    fn production_trust_root_is_not_the_public_rfc_test_vector() {
        assert_ne!(
            PRODUCTION_MANIFEST_KEYS[0].1,
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        );
    }
}
