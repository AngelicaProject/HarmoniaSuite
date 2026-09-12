# Phase 6 fresh bootstrap design

This checkpoint adds the first per-user installer operation. It orchestrates the
existing Phase 4 build pipeline and Phase 5 activation engine; it does not add
an updater, repair implementation, installer UI, shortcuts, or uninstall.

## Roots and process model

`InstallationPaths` remains the single path contract: Windows uses
`%LOCALAPPDATA%\\HarmoniaSuite` for immutable application/toolchain/build state
and `%APPDATA%\\HarmoniaSuite` for user data; Linux uses the existing XDG data,
state, and cache roots. No repository checkout is installed as runtime and no
system-wide elevation is required.

The CLI (`harmonia-setup`, `HarmoniaSetup.exe`) acquires the installation lock
once for recovery, build, activation, and launch handoff. Locked entry points
in the existing engines prevent nested non-reentrant lock acquisition. Build
and activation retain their own durable transaction journals; the bootstrap
operation log records the high-level operation and machine-readable result.

The health checker uses the existing owned-process containment. The final
desktop handoff uses a separate detached launcher: Windows does not attach the
child to the installer Job Object, and Linux creates a new session and clears
parent-death containment. The launcher passes only the desktop runtime
allowlist plus explicit installed-runtime variables.

## Fresh-install state machine

`Recovering -> Detecting -> Initializing -> ResolvingTarget -> PreparingToolchain
-> Building -> Activating -> Launching -> Completed`.

`AlreadyInstalled`, `RepairRequired`, `ReviewRequired`, build failure,
activation failure, and launch failure are terminal outcomes for the operation.
Activation failure on a clean install is rolled back by Phase 5 to `NoInstall`;
launch failure leaves a healthy activated version in place and is reported as
`LaunchFailed`.

## Production toolchain catalog

The checked-in catalog has its own schema version, separate from the persisted
toolchain state schema. It contains only Windows x64 and Linux x64 Temurin JDK
21 and Node 24.15.0 archives, each with an official HTTPS URL, exact SHA-256,
archive format, nested `home_dir`, and executable mappings. Git remains the
Rust/libgit2 implementation from Phase 4. Maven remains the checksum-pinned
wrapper from the checkout.

## Transaction and recovery contract

The high-level bootstrap never writes current state directly. Build failures
only leave the existing build transaction's owned staging paths for its
recovery logic. Activation is the only owner of immutable version publication
and current/previous pointer changes. Before the first operation, durable
`ReviewRequired` state blocks the install. Pre-activation running transactions
are recovered through the existing journaled `owned_paths`; user-data paths
are never cleanup targets.

Successful activation yields `RuntimePaths` from `resolve_current()`. The
desktop is launched from that immutable directory with `HARMONIA_RUNTIME_MODE=installed`,
`HARMONIA_ACTIVE_VERSION_DIR`, `HARMONIA_BACKEND_JAR`,
`HARMONIA_JAVA_BINARY`, and the exact workspace contract. A successful spawn is
the launch handoff; the setup process does not wait for the desktop.

## Result contract

The CLI emits a human-readable stderr message and deterministic exit code, or
JSON with operation id, status, target SHA, product version, version directory,
toolchain IDs, phase, duration, activation result, and launch result. Logs are
JSONL and contain no complete environment or credentials.

The Rust package exposes `harmonia-setup` for Linux and a `HarmoniaSetup` bin
alias for Windows packaging; both targets use the same source entrypoint.
`install` is the implemented operation and `--json` emits the serialized
result. Stable exit codes are: `0` installed, `10` already installed, `20`
repair required, `21` review required, `30` build failed, `40` activation
failed, and `41` launch failed.
