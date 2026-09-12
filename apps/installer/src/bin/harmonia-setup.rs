use std::env;
use std::process::ExitCode;

use harmonia_installer::{
    BootstrapInstaller, BootstrapOptions, BootstrapResult, BootstrapStatus, DiagnosticLogger,
    HttpDownloader, HttpManifestFetcher, InstallationPaths, LocalBackendHealthChecker,
    ManifestVerifier, NoopActivationHooks, SystemDetachedLauncher, SystemProcessRunner,
    UpdateEngine, UpdateResult, UpdateStatus,
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
    let cli = match parse_cli(&arguments) {
        Ok(cli) => cli,
        Err(error) => return report_error(false, EXIT_USAGE, &error),
    };
    if matches!(cli.command, Command::Repair | Command::Uninstall) {
        return report_not_implemented(cli.json, cli.command.as_str());
    }
    let paths = match InstallationPaths::current() {
        Ok(paths) => paths,
        Err(error) => return report_error(cli.json, EXIT_INTERNAL, &error.to_string()),
    };
    let logger_name = if matches!(cli.command, Command::Update | Command::Check) {
        "updater.jsonl"
    } else {
        "bootstrap.jsonl"
    };
    let logger = match DiagnosticLogger::open(paths.diagnostics_dir().join(logger_name)) {
        Ok(logger) => Some(logger),
        Err(error) => return report_error(cli.json, EXIT_INTERNAL, &error.to_string()),
    };
    if matches!(cli.command, Command::Update | Command::Check) {
        let updater = UpdateEngine::new(
            paths,
            HttpDownloader::default(),
            SystemProcessRunner::default(),
            logger,
        );
        if matches!(cli.command, Command::Check) {
            let fetcher = HttpManifestFetcher::default();
            let verifier = ManifestVerifier::production();
            return match updater.check_for_update(
                &fetcher,
                &verifier,
                harmonia_installer::manifest::DEFAULT_MANIFEST_URL,
                harmonia_installer::manifest::DEFAULT_MANIFEST_SIGNATURE_URL,
            ) {
                Ok(result) => report_update_result(cli.json, result),
                Err(error) => report_error(cli.json, EXIT_INTERNAL, &error.to_string()),
            };
        }
        let mut hooks = NoopActivationHooks;
        let checker = LocalBackendHealthChecker::new(SystemProcessRunner::default());
        return match updater.update(&mut hooks, &checker, &SystemDetachedLauncher) {
            Ok(result) => report_update_result(cli.json, result),
            Err(error) => report_error(cli.json, EXIT_INTERNAL, &error.to_string()),
        };
    }
    let installer = BootstrapInstaller::new(
        paths,
        HttpDownloader::default(),
        SystemProcessRunner::default(),
        SystemDetachedLauncher,
        logger,
    );
    match installer.install(BootstrapOptions::default()) {
        Ok(result) => report_result(cli.json, result),
        Err(error) => report_error(cli.json, EXIT_INTERNAL, &error.to_string()),
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Command {
    Install,
    Check,
    Update,
    Repair,
    Uninstall,
}

impl Command {
    fn as_str(self) -> &'static str {
        match self {
            Self::Install => "install",
            Self::Check => "check",
            Self::Update => "update",
            Self::Repair => "repair",
            Self::Uninstall => "uninstall",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct Cli {
    command: Command,
    json: bool,
}

fn parse_cli(arguments: &[String]) -> Result<Cli, String> {
    let mut command = None;
    let mut json = false;
    for argument in arguments {
        match argument.as_str() {
            "--json" => json = true,
            "install" => set_command(&mut command, Command::Install)?,
            "check" => set_command(&mut command, Command::Check)?,
            "update" => set_command(&mut command, Command::Update)?,
            "repair" => set_command(&mut command, Command::Repair)?,
            "uninstall" => set_command(&mut command, Command::Uninstall)?,
            value if value.starts_with('-') => {
                return Err(format!("unknown option {value:?}"));
            }
            value => return Err(format!("unknown command {value:?}")),
        }
    }
    Ok(Cli {
        command: command.unwrap_or(Command::Install),
        json,
    })
}

fn set_command(command: &mut Option<Command>, value: Command) -> Result<(), String> {
    if command.replace(value).is_some() {
        return Err("more than one command was provided".to_owned());
    }
    Ok(())
}

fn report_not_implemented(json: bool, operation: &str) -> ExitCode {
    if json {
        println!(
            "{}",
            serde_json::json!({
                "status": "NotImplemented",
                "operation": operation,
            })
        );
    } else {
        eprintln!("{operation} is not implemented in this installer phase");
    }
    ExitCode::from(EXIT_USAGE)
}

fn report_update_result(json: bool, result: UpdateResult) -> ExitCode {
    let code = match &result.status {
        UpdateStatus::UpToDate { .. } | UpdateStatus::Updated { .. } => EXIT_INSTALLED,
        UpdateStatus::UpdateAvailable { .. } => EXIT_ALREADY_INSTALLED,
        UpdateStatus::LaunchFailed { .. } => EXIT_LAUNCH_FAILED,
        UpdateStatus::ReviewRequired { .. } => EXIT_REVIEW_REQUIRED,
        UpdateStatus::TrustFailure { .. } => EXIT_USAGE,
        UpdateStatus::UpdateBuildFailed { .. } => EXIT_BUILD_FAILED,
        UpdateStatus::ActivationFailed { .. } | UpdateStatus::RollbackCompleted { .. } => {
            EXIT_ACTIVATION_FAILED
        }
    };
    if json {
        println!(
            "{}",
            serde_json::to_string_pretty(&result).unwrap_or_else(|_| "{}".to_owned())
        );
    } else {
        println!(
            "{}",
            serde_json::to_string(&result).unwrap_or_else(|_| "{}".to_owned())
        );
    }
    ExitCode::from(code)
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

#[cfg(test)]
mod tests {
    use super::*;

    fn args(values: &[&str]) -> Vec<String> {
        values.iter().map(|value| (*value).to_owned()).collect()
    }

    #[test]
    fn cli_rejects_typo_instead_of_defaulting_to_install() {
        assert!(parse_cli(&args(&["instal"])).is_err());
    }

    #[test]
    fn cli_rejects_production_source_and_version_overrides() {
        assert!(parse_cli(&args(&["install", "--remote-url"])).is_err());
        assert!(parse_cli(&args(&["install", "--product-version", "1.2.3"])).is_err());
        assert!(parse_cli(&args(&["--unknown"])).is_err());
    }

    #[test]
    fn cli_accepts_only_install_and_json_for_implemented_flow() {
        assert_eq!(
            parse_cli(&args(&["install", "--json"])),
            Ok(Cli {
                command: Command::Install,
                json: true,
            })
        );
        assert_eq!(
            parse_cli(&[]),
            Ok(Cli {
                command: Command::Install,
                json: false,
            })
        );
        assert_eq!(
            parse_cli(&args(&["update", "--json"])),
            Ok(Cli {
                command: Command::Update,
                json: true,
            })
        );
    }
}
