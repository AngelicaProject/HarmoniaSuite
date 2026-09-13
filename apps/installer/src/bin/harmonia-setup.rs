use std::env;
use std::process::ExitCode;

use harmonia_installer::uninstall::uninstall;
use harmonia_installer::{
    stable_runtime_launch_spec, ActivationEngine, BootstrapInstaller, BootstrapOptions,
    BootstrapResult, BootstrapStatus, DesktopShutdownHooks, DetachedLauncher, DiagnosticLogger,
    HttpDownloader, HttpManifestFetcher, InstallationPaths, LocalBackendHealthChecker,
    ManifestVerifier, RepairEngine, RepairResult, RepairStatus, SystemDetachedLauncher,
    SystemProcessRunner, UninstallResult, UninstallStatus, UpdateEngine, UpdateResult,
    UpdateStatus,
};

const EXIT_INSTALLED: u8 = 0;
const EXIT_ALREADY_INSTALLED: u8 = 10;
const EXIT_REPAIR_REQUIRED: u8 = 20;
const EXIT_REVIEW_REQUIRED: u8 = 21;
const EXIT_BUILD_FAILED: u8 = 30;
const EXIT_ACTIVATION_FAILED: u8 = 40;
const EXIT_LAUNCH_FAILED: u8 = 41;
const EXIT_UPGRADE_REQUIRED: u8 = 42;
const EXIT_USAGE: u8 = 64;
const EXIT_INTERNAL: u8 = 70;

fn is_stable_launcher_process() -> bool {
    let Some(name) = env::current_exe().ok().and_then(|path| {
        path.file_stem()
            .map(|value| value.to_string_lossy().to_ascii_lowercase())
    }) else {
        return false;
    };
    name == "harmoniasuite" || name == "harmonia-suite"
}

fn run_stable_launcher() -> ExitCode {
    let paths = match InstallationPaths::current() {
        Ok(paths) => paths,
        Err(error) => return report_error(false, EXIT_INTERNAL, &error.to_string()),
    };
    let activation = ActivationEngine::new(paths.clone());
    let runtime = match activation.resolve_current() {
        Ok(Some(runtime)) => runtime,
        Ok(None) => {
            return report_error(
                false,
                EXIT_REPAIR_REQUIRED,
                "HarmoniaSuite is not installed",
            )
        }
        Err(error) => return report_error(false, EXIT_REPAIR_REQUIRED, &error.to_string()),
    };
    match SystemDetachedLauncher.launch(&stable_runtime_launch_spec(&paths, &runtime)) {
        Ok(_) => ExitCode::from(EXIT_INSTALLED),
        Err(error) => report_error(false, EXIT_LAUNCH_FAILED, &error.to_string()),
    }
}

fn main() -> ExitCode {
    if env::args().nth(1).as_deref() == Some("__cleanup") {
        return match harmonia_installer::run_cleanup_helper() {
            Ok(()) => ExitCode::from(EXIT_INSTALLED),
            Err(error) => report_error(false, EXIT_INTERNAL, &error.to_string()),
        };
    }
    if is_stable_launcher_process() {
        return run_stable_launcher();
    }
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    let cli = match parse_cli(&arguments) {
        Ok(cli) => cli,
        Err(error) => return report_error(false, EXIT_USAGE, &error),
    };
    let paths = match InstallationPaths::current() {
        Ok(paths) => paths,
        Err(error) => return report_error(cli.json, EXIT_INTERNAL, &error.to_string()),
    };
    let logger_name = if matches!(cli.command, Command::Update | Command::CheckUpdate) {
        "updater.jsonl"
    } else {
        "bootstrap.jsonl"
    };
    let logger = match DiagnosticLogger::open(paths.diagnostics_dir().join(logger_name)) {
        Ok(logger) => Some(logger),
        Err(error) => return report_error(cli.json, EXIT_INTERNAL, &error.to_string()),
    };
    if matches!(cli.command, Command::Update | Command::CheckUpdate) {
        let updater = UpdateEngine::new(
            paths.clone(),
            HttpDownloader::default(),
            SystemProcessRunner::default(),
            logger,
        );
        if matches!(cli.command, Command::CheckUpdate) {
            let fetcher = HttpManifestFetcher::default();
            let verifier = ManifestVerifier::production();
            let (manifest_url, signature_url) =
                harmonia_installer::manifest::production_manifest_urls(paths.platform);
            return match updater.check_for_update(
                &fetcher,
                &verifier,
                &manifest_url,
                &signature_url,
            ) {
                Ok(result) => report_update_result(cli.json, result),
                Err(error) => report_error(cli.json, EXIT_INTERNAL, &error.to_string()),
            };
        }
        let mut hooks = DesktopShutdownHooks::new(
            paths,
            env::var("HARMONIA_UPDATE_FROM_DESKTOP").as_deref() == Ok("1"),
        );
        let checker = LocalBackendHealthChecker::new(SystemProcessRunner::default());
        return match updater.update(&mut hooks, &checker, &SystemDetachedLauncher) {
            Ok(result) => report_update_result(cli.json, result),
            Err(error) => report_error(cli.json, EXIT_INTERNAL, &error.to_string()),
        };
    }
    if cli.command == Command::Repair {
        let engine = RepairEngine::new(
            paths,
            HttpDownloader::default(),
            SystemProcessRunner::default(),
            logger,
        );
        return match engine.repair() {
            Ok(result) => report_repair_result(cli.json, result),
            Err(error) => report_error(cli.json, EXIT_INTERNAL, &error.to_string()),
        };
    }
    if cli.command == Command::Uninstall {
        return match uninstall(paths, cli.remove_user_data) {
            Ok(result) => report_uninstall_result(cli.json, result),
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
    CheckUpdate,
    Update,
    Repair,
    Uninstall,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct Cli {
    command: Command,
    json: bool,
    remove_user_data: bool,
}

fn parse_cli(arguments: &[String]) -> Result<Cli, String> {
    let mut command = None;
    let mut json = false;
    let mut remove_user_data = false;
    for argument in arguments {
        match argument.as_str() {
            "--json" => json = true,
            "--remove-user-data" => remove_user_data = true,
            "install" => set_command(&mut command, Command::Install)?,
            "check-update" => set_command(&mut command, Command::CheckUpdate)?,
            "update" => set_command(&mut command, Command::Update)?,
            "repair" => set_command(&mut command, Command::Repair)?,
            "uninstall" => set_command(&mut command, Command::Uninstall)?,
            value if value.starts_with('-') => {
                return Err(format!("unknown option {value:?}"));
            }
            value => return Err(format!("unknown command {value:?}")),
        }
    }
    let command = command.unwrap_or(Command::Install);
    if remove_user_data && command != Command::Uninstall {
        return Err("--remove-user-data is valid only for uninstall".to_owned());
    }
    Ok(Cli {
        command,
        json,
        remove_user_data,
    })
}

fn set_command(command: &mut Option<Command>, value: Command) -> Result<(), String> {
    if command.replace(value).is_some() {
        return Err("more than one command was provided".to_owned());
    }
    Ok(())
}

fn report_update_result(json: bool, result: UpdateResult) -> ExitCode {
    let code = match &result.status {
        UpdateStatus::UpToDate { .. } | UpdateStatus::Updated { .. } => EXIT_INSTALLED,
        UpdateStatus::UpdateAvailable { .. } => EXIT_ALREADY_INSTALLED,
        UpdateStatus::LaunchFailed { .. } => EXIT_LAUNCH_FAILED,
        UpdateStatus::ReviewRequired { .. } => EXIT_REVIEW_REQUIRED,
        UpdateStatus::UpdaterUpgradeRequired { .. } => EXIT_UPGRADE_REQUIRED,
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

fn report_repair_result(json: bool, result: RepairResult) -> ExitCode {
    let code = match &result.status {
        RepairStatus::Healthy { .. } | RepairStatus::Repaired { .. } => EXIT_INSTALLED,
        RepairStatus::RepairRequired { .. } => EXIT_REPAIR_REQUIRED,
        RepairStatus::ReviewRequired { .. } => EXIT_REVIEW_REQUIRED,
        RepairStatus::BuildFailed { .. } => EXIT_BUILD_FAILED,
        RepairStatus::ActivationFailed { .. } => EXIT_ACTIVATION_FAILED,
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

fn report_uninstall_result(json: bool, result: UninstallResult) -> ExitCode {
    let code = match result.status {
        UninstallStatus::Uninstalled { .. } | UninstallStatus::AlreadyUninstalled { .. } => {
            EXIT_INSTALLED
        }
        UninstallStatus::ReviewRequired { .. } => EXIT_REVIEW_REQUIRED,
        UninstallStatus::Failed { .. } => EXIT_INTERNAL,
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
    fn cli_accepts_install_update_and_json() {
        assert_eq!(
            parse_cli(&args(&["install", "--json"])),
            Ok(Cli {
                command: Command::Install,
                json: true,
                remove_user_data: false,
            })
        );
        assert_eq!(
            parse_cli(&[]),
            Ok(Cli {
                command: Command::Install,
                json: false,
                remove_user_data: false,
            })
        );
        assert_eq!(
            parse_cli(&args(&["update", "--json"])),
            Ok(Cli {
                command: Command::Update,
                json: true,
                remove_user_data: false,
            })
        );
        assert_eq!(
            parse_cli(&args(&["check-update"])),
            Ok(Cli {
                command: Command::CheckUpdate,
                json: false,
                remove_user_data: false,
            })
        );
    }

    #[test]
    fn cli_rejects_legacy_check_command() {
        assert!(parse_cli(&args(&["check"])).is_err());
    }

    #[test]
    fn cli_scopes_user_data_removal_to_uninstall() {
        assert!(parse_cli(&args(&["install", "--remove-user-data"])).is_err());
        assert_eq!(
            parse_cli(&args(&["uninstall", "--remove-user-data", "--json"])),
            Ok(Cli {
                command: Command::Uninstall,
                json: true,
                remove_user_data: true,
            })
        );
    }
}
