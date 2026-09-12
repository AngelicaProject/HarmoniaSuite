use std::env;
use std::process::ExitCode;

use harmonia_installer::{
    BootstrapInstaller, BootstrapOptions, BootstrapResult, BootstrapStatus, DiagnosticLogger,
    HttpDownloader, InstallationPaths, SystemDetachedLauncher, SystemProcessRunner,
};

const EXIT_INSTALLED: u8 = 0;
const EXIT_ALREADY_INSTALLED: u8 = 10;
const EXIT_REPAIR_REQUIRED: u8 = 20;
const EXIT_REVIEW_REQUIRED: u8 = 21;
const EXIT_BUILD_FAILED: u8 = 30;
const EXIT_ACTIVATION_FAILED: u8 = 40;
const EXIT_LAUNCH_FAILED: u8 = 41;
const EXIT_USAGE: u8 = 64;
const EXIT_INTERNAL: u8 = 70;

fn main() -> ExitCode {
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    let json = arguments.iter().any(|argument| argument == "--json");
    let command = arguments
        .iter()
        .find(|argument| {
            argument.as_str() == "install"
                || argument.as_str() == "repair"
                || argument.as_str() == "uninstall"
        })
        .map(String::as_str)
        .unwrap_or("install");
    if command != "install" {
        return report_error(
            json,
            EXIT_USAGE,
            &format!("unsupported command {command:?}; only install is implemented"),
        );
    }

    let remote_url = option_value(&arguments, "--remote-url");
    let product_version = option_value(&arguments, "--product-version");
    let paths = match InstallationPaths::current() {
        Ok(paths) => paths,
        Err(error) => return report_error(json, EXIT_INTERNAL, &error.to_string()),
    };
    let logger = match DiagnosticLogger::open(paths.diagnostics_dir().join("bootstrap.jsonl")) {
        Ok(logger) => Some(logger),
        Err(error) => return report_error(json, EXIT_INTERNAL, &error.to_string()),
    };
    let options = BootstrapOptions {
        remote_url: remote_url.unwrap_or_else(|| BootstrapOptions::default().remote_url),
        product_version: product_version
            .unwrap_or_else(|| BootstrapOptions::default().product_version),
    };
    let installer = BootstrapInstaller::new(
        paths,
        HttpDownloader::default(),
        SystemProcessRunner::default(),
        SystemDetachedLauncher,
        logger,
    );
    match installer.install(options) {
        Ok(result) => report_result(json, result),
        Err(error) => report_error(json, EXIT_INTERNAL, &error.to_string()),
    }
}

fn option_value(arguments: &[String], name: &str) -> Option<String> {
    arguments
        .windows(2)
        .find(|pair| pair[0] == name)
        .map(|pair| pair[1].clone())
}

fn report_result(json: bool, result: BootstrapResult) -> ExitCode {
    let code = match &result.status {
        BootstrapStatus::Installed { .. } => EXIT_INSTALLED,
        BootstrapStatus::AlreadyInstalled { .. } => EXIT_ALREADY_INSTALLED,
        BootstrapStatus::RepairRequired { .. } => EXIT_REPAIR_REQUIRED,
        BootstrapStatus::ReviewRequired { .. } => EXIT_REVIEW_REQUIRED,
        BootstrapStatus::BuildFailed { .. } => EXIT_BUILD_FAILED,
        BootstrapStatus::ActivationFailed { .. } => EXIT_ACTIVATION_FAILED,
        BootstrapStatus::LaunchFailed { .. } => EXIT_LAUNCH_FAILED,
    };
    if json {
        println!(
            "{}",
            serde_json::to_string_pretty(&result).unwrap_or_else(|_| "{}".to_owned())
        );
    } else {
        match &result.status {
            BootstrapStatus::Installed {
                commit,
                version_dir,
                ..
            } => {
                println!(
                    "HarmoniaSuite installed at {} (commit {commit})",
                    version_dir.display()
                );
            }
            BootstrapStatus::AlreadyInstalled {
                commit,
                version_dir,
            } => {
                eprintln!(
                    "HarmoniaSuite is already installed at {} (commit {commit})",
                    version_dir.display()
                );
            }
            BootstrapStatus::RepairRequired { reason, .. }
            | BootstrapStatus::ReviewRequired { reason }
            | BootstrapStatus::BuildFailed { reason }
            | BootstrapStatus::ActivationFailed { reason }
            | BootstrapStatus::LaunchFailed { reason, .. } => eprintln!("{reason}"),
        }
    }
    ExitCode::from(code)
}

fn report_error(json: bool, code: u8, message: &str) -> ExitCode {
    if json {
        println!(
            "{}",
            serde_json::json!({"status": "InternalError", "error": message})
        );
    } else {
        eprintln!("{message}");
    }
    ExitCode::from(code)
}
