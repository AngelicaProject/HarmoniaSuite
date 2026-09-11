# Installer core — Phase 2 design

Этот документ фиксирует границу Phase 2 для `apps/installer`. Это native Rust library/CLI core,
а не пользовательский installer UI и не rolling build pipeline.

## Scope и граница фаз

Phase 2 предоставляет общие primitives для fresh install, repair и будущих update operations:
platform paths, persistent state, process lock, structured diagnostics, download/checksum,
process execution, transaction journal и crash inspection. В этой фазе нет Git mirror, resolve
`origin/main`, detached worktrees, managed JDK/Node/Git, frontend/backend builds, signed manifest,
activation helper, health-check orchestration или OS installer registration. Эти обязанности
начинаются в следующих phases и используют тот же core.

## Paths

Windows application root — `%LOCALAPPDATA%\\HarmoniaSuite`, user data — `%APPDATA%\\HarmoniaSuite`.
If those variables are absent, an absolute per-user `HOME`/`USERPROFILE` is used to derive the
corresponding `AppData` roots; there is no public-profile fallback. Linux application root —
`$XDG_DATA_HOME/harmonia-suite` с fallback `~/.local/share/harmonia-suite`; user data —
`$XDG_DATA_HOME/harmonia-suite-data` с fallback `~/.local/share/harmonia-suite-data`; state —
`$XDG_STATE_HOME/harmonia-suite` с fallback `~/.local/state/harmonia-suite`; cache —
`$XDG_CACHE_HOME/harmonia-suite` с fallback `~/.cache/harmonia-suite`. Relative XDG values are
ignored in favor of these absolute fallbacks. If no absolute per-user root can be determined, path
resolution fails rather than using a shared or temporary directory.

Managed application files are grouped below `app_root`: `bin`, `versions`, `toolchain`, `source`,
`build`, `cache` and `state`. User data is never a child of a version or staging directory and is
not created or removed by core initialization.

## Persistent state и transaction journal

`state/install.json` is the machine-readable installation state. Writes use a sibling temporary file,
flush/sync, and platform replacement semantics. `state/transaction.json` is the last active or
completed transaction record. Every state-machine transition is persisted before the next operation
may rely on it. The record contains operation, phase, target/current commit metadata when known,
owned temporary paths, activation marker and failure diagnostics.

Recovery is conservative. A pre-activation interrupted transaction can be resumed or have only its
explicitly owned temporary paths cleaned. A journal that reached activation/health-check is reported
for explicit recovery/rollback handling; core does not guess that a partially switched installation
is safe. Unknown paths and user-data paths are never deleted from a recovery scan. Cleanup rejects
parent-directory components, refuses to remove a managed root itself, and fails closed if any
existing ancestor is a symlink or Windows reparse point.

## Locking and diagnostics

`state/install.lock` is an OS-backed exclusive lock held for the complete installer operation. The
file contains non-secret owner metadata for diagnostics, with a small owner sidecar used when the
platform does not permit reading the locked file; the OS lock, not a stale timestamp, is the
authority. Persistent diagnostics are JSON Lines under `state/diagnostics/`, with event,
level, timestamp and structured fields. Commands must be passed as argv and logged after redaction;
environment values, credentials and authorization headers are not logged.

## Downloads and process execution

The download layer is a transport abstraction. The production request requires a valid SHA-256 at
construction time. The HTTP implementation supports timeout, bounded retry, HTTP range resume into
`<name>.partial`, validates the server's `Content-Range` start before appending, and promotes only
after a successful digest check. Network failures may leave a resumable partial; checksum failures
delete the corrupted partial. Process execution accepts argv/cwd plus explicit environment values
and a fixed allowlist for selected inherited variables, without a shell. It runs children in a
Linux process group or Windows Job Object, terminates the group/tree on timeout, and captures only
a bounded tail of stdout/stderr.

## Phase 2 state machine

The core models the complete future state vocabulary (`Idle`, target/toolchain/source/build phases,
`Staging`, `WaitingForShutdown`, `Activating`, `HealthChecking`, `RollingBack`, `Completed`,
`Failed`) and validates allowed transitions. Phase 2 only persists and tests transitions; it does
not implement the future build or activation actions. `Install`, `Repair`, `Update`, `Reinstall`,
`Rollback` and `Uninstall` share this transaction model.
