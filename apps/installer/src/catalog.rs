//! Checked-in production toolchain catalog for the first bootstrap flow.
//!
//! The URLs and digests are deliberately explicit. A future signed catalog may
//! replace this module, but bootstrap must not silently fall back to system
//! Java, Node, npm, or Git.

use crate::paths::{Platform, TargetArchitecture};
use crate::toolchain::{
    OfficialToolchainCatalog, ToolchainDescriptor, ToolchainError, ToolchainKind,
    TOOLCHAIN_CATALOG_SCHEMA_VERSION,
};
use serde::Deserialize;

pub const PRODUCTION_CATALOG_SCHEMA_VERSION: u32 = TOOLCHAIN_CATALOG_SCHEMA_VERSION;
pub const PRODUCTION_NODE_VERSION: &str = "24.15.0";
pub const PRODUCTION_JDK_VERSION: &str = "21.0.12+8";

#[derive(Debug, Deserialize)]
struct ProductionCatalogFile {
    #[serde(rename = "schemaVersion")]
    schema_version: u32,
    descriptors:
        std::collections::BTreeMap<String, std::collections::BTreeMap<String, ToolchainDescriptor>>,
}

pub fn production_catalog(
    platform: Platform,
    architecture: &TargetArchitecture,
) -> Result<OfficialToolchainCatalog, ToolchainError> {
    if architecture != &TargetArchitecture::X64 {
        return Err(ToolchainError::UnsupportedTarget {
            expected_platform: platform,
            expected_architecture: TargetArchitecture::X64,
            actual_platform: platform,
            actual_architecture: architecture.clone(),
        });
    }

    let file: ProductionCatalogFile = serde_json::from_str(include_str!(
        "../../../tools/release/production-toolchain-catalog.json"
    ))
    .map_err(|error| {
        ToolchainError::InvalidDescriptor(format!("production catalog JSON: {error}"))
    })?;
    if file.schema_version != PRODUCTION_CATALOG_SCHEMA_VERSION {
        return Err(ToolchainError::UnsupportedCatalogSchema(
            file.schema_version,
        ));
    }
    let platform_key = platform.as_str();
    let descriptors =
        file.descriptors
            .get(platform_key)
            .ok_or_else(|| ToolchainError::NotFound {
                kind: ToolchainKind::Jdk,
                version: platform_key.to_owned(),
            })?;
    let jdk = descriptors
        .get("jdk")
        .ok_or_else(|| ToolchainError::NotFound {
            kind: ToolchainKind::Jdk,
            version: PRODUCTION_JDK_VERSION.to_owned(),
        })?
        .clone();
    let node = descriptors
        .get("node")
        .ok_or_else(|| ToolchainError::NotFound {
            kind: ToolchainKind::Node,
            version: PRODUCTION_NODE_VERSION.to_owned(),
        })?
        .clone();

    let catalog = OfficialToolchainCatalog {
        schema_version: PRODUCTION_CATALOG_SCHEMA_VERSION,
        descriptors: vec![jdk, node],
    };
    for descriptor in &catalog.descriptors {
        descriptor.validate_for(platform, architecture)?;
    }
    Ok(catalog)
}

pub fn production_descriptors(
    platform: Platform,
    architecture: &TargetArchitecture,
) -> Result<(ToolchainDescriptor, ToolchainDescriptor), ToolchainError> {
    let catalog = production_catalog(platform, architecture)?;
    let jdk = catalog
        .descriptor(
            ToolchainKind::Jdk,
            PRODUCTION_JDK_VERSION,
            platform,
            architecture,
        )?
        .clone();
    let node = catalog
        .descriptor(
            ToolchainKind::Node,
            PRODUCTION_NODE_VERSION,
            platform,
            architecture,
        )?
        .clone();
    Ok((jdk, node))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn production_catalog_has_real_x64_https_descriptors() {
        for platform in [Platform::Linux, Platform::Windows] {
            let catalog = production_catalog(platform, &TargetArchitecture::X64).unwrap();
            assert_eq!(catalog.schema_version, PRODUCTION_CATALOG_SCHEMA_VERSION);
            assert_eq!(catalog.descriptors.len(), 2);
            for descriptor in &catalog.descriptors {
                descriptor
                    .validate_for(platform, &TargetArchitecture::X64)
                    .unwrap();
                assert!(descriptor.url.starts_with("https://"));
                assert_eq!(descriptor.sha256.len(), 64);
                assert!(!descriptor.executables.is_empty());
            }
        }
    }

    #[test]
    fn production_catalog_rejects_non_x64() {
        let result =
            production_catalog(Platform::Linux, &TargetArchitecture::Other("arm64".into()));
        assert!(matches!(
            result,
            Err(ToolchainError::UnsupportedTarget { .. })
        ));
    }
}
