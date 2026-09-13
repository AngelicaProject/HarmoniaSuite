use std::env;
use std::fs;
use std::process::ExitCode;

use harmonia_installer::manifest::ManifestVerifier;
use harmonia_installer::{Platform, TargetArchitecture};

fn main() -> ExitCode {
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    if arguments.len() != 3 {
        eprintln!(
            "usage: harmonia-manifest-verify <linux|windows> <manifest.json> <manifest.json.sig>"
        );
        return ExitCode::from(64);
    }
    let platform = match arguments[0].as_str() {
        "linux" => Platform::Linux,
        "windows" => Platform::Windows,
        _ => {
            eprintln!("platform must be linux or windows");
            return ExitCode::from(64);
        }
    };
    let manifest = match fs::read(&arguments[1]) {
        Ok(bytes) => bytes,
        Err(error) => {
            eprintln!("manifest read failed: {error}");
            return ExitCode::from(1);
        }
    };
    let signature = match fs::read(&arguments[2]) {
        Ok(bytes) => bytes,
        Err(error) => {
            eprintln!("signature read failed: {error}");
            return ExitCode::from(1);
        }
    };
    match ManifestVerifier::production().verify(
        &manifest,
        &signature,
        platform,
        &TargetArchitecture::X64,
    ) {
        Ok(signed) => {
            println!(
                "{}",
                serde_json::to_string(&signed.manifest).unwrap_or_default()
            );
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("manifest verification failed: {error}");
            ExitCode::from(1)
        }
    }
}
