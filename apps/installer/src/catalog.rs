//! Checked-in production toolchain catalog for the first bootstrap flow.
//!
//! The URLs and digests are deliberately explicit. A future signed catalog may
//! replace this module, but bootstrap must not silently fall back to system
//! Java, Node, npm, or Git.

use crate::paths::{Platform, TargetArchitecture};
use crate::toolchain::{
    ArchiveFormat, OfficialToolchainCatalog, ToolchainDescriptor, ToolchainError, ToolchainKind,
    TOOLCHAIN_CATALOG_SCHEMA_VERSION,
};

pub const PRODUCTION_CATALOG_SCHEMA_VERSION: u32 = TOOLCHAIN_CATALOG_SCHEMA_VERSION;
pub const PRODUCTION_NODE_VERSION: &str = "24.15.0";
pub const PRODUCTION_JDK_VERSION: &str = "21.0.12+8";

const NODE_LINUX_SHA256: &str = "44836872d9aec49f1e6b52a9a922872db9a2b02d235a616a5681b6a85fec8d89";
const NODE_WINDOWS_SHA256: &str =
    "cc5149eabd53779ce1e7bdc5401643622d0c7e6800ade18928a767e940bb0e62";
const JDK_LINUX_SHA256: &str = "e4446ff06a276155697597cc0f1b15da004ff083f4964a35271ecee567177370";
const JDK_WINDOWS_SHA256: &str = "9ba963ee2371874a74185d18bc7bb2ab9407df7683300855ed7606e0662321d0";

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

    let (jdk, node) = match platform {
        Platform::Linux => (
            ToolchainDescriptor::new(
                ToolchainKind::Jdk,
                PRODUCTION_JDK_VERSION,
                platform,
                TargetArchitecture::X64,
                "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12_8.tar.gz",
                JDK_LINUX_SHA256,
                ArchiveFormat::TarGz,
            )
            .home_dir("jdk-21.0.12+8")
            .executable("java", "jdk-21.0.12+8/bin/java"),
            ToolchainDescriptor::new(
                ToolchainKind::Node,
                PRODUCTION_NODE_VERSION,
                platform,
                TargetArchitecture::X64,
                "https://nodejs.org/download/release/v24.15.0/node-v24.15.0-linux-x64.tar.gz",
                NODE_LINUX_SHA256,
                ArchiveFormat::TarGz,
            )
            .home_dir("node-v24.15.0-linux-x64")
            .executable("node", "node-v24.15.0-linux-x64/bin/node")
            .executable("npm", "node-v24.15.0-linux-x64/bin/npm")
            .executable("npx", "node-v24.15.0-linux-x64/bin/npx"),
        ),
        Platform::Windows => (
            ToolchainDescriptor::new(
                ToolchainKind::Jdk,
                PRODUCTION_JDK_VERSION,
                platform,
                TargetArchitecture::X64,
                "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12_8.zip",
                JDK_WINDOWS_SHA256,
                ArchiveFormat::Zip,
            )
            .home_dir("jdk-21.0.12+8")
            .executable("java", "jdk-21.0.12+8/bin/java.exe"),
            ToolchainDescriptor::new(
                ToolchainKind::Node,
                PRODUCTION_NODE_VERSION,
                platform,
                TargetArchitecture::X64,
                "https://nodejs.org/download/release/v24.15.0/node-v24.15.0-win-x64.zip",
                NODE_WINDOWS_SHA256,
                ArchiveFormat::Zip,
            )
            .home_dir("node-v24.15.0-win-x64")
            .executable("node", "node-v24.15.0-win-x64/node.exe")
            .executable("npm", "node-v24.15.0-win-x64/npm.cmd")
            .executable("npx", "node-v24.15.0-win-x64/npx.cmd"),
        ),
    };

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
