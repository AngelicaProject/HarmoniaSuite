# Phase 5 — transactional staging, activation and rollback

This checkpoint consumes only a verified Phase 4 `BuildResult`. It turns the build candidate
into an immutable runtime version, atomically selects that version through installation state,
starts its backend on a Spring-selected loopback port, and rolls back on failed health checks.
Installer UI, shortcuts, uninstall registration, signed publication and updater UX remain out
of scope.

## Runtime and user-data layout

`InstallationPaths` remains the authority for per-user roots. User data is never copied into an
application version:

```text
<app-root>/
  versions/<full-commit-sha>/
    desktop/                       complete Electron payload
    backend/harmonia-suite.jar     runnable Spring artifact
    metadata.json                  immutable VersionMetadata
  versions/<full-commit-sha>.staging.<txid>/
<state-root>/
  install.json                      current/previous pointer state
  transaction.json                  activation journal
  build-results/<txid>.json         Phase 4 input
<user-data-root>/
  data/harmonia.db                  existing SQLite database
  backups/installer/<txid>/         pre-activation DB snapshot
```

The final version directory is published with a same-filesystem rename and is never modified in
place. `current_commit` and `previous_commit` in `install.json` are the stable pointer model;
`staged_commit` and `pending_commit` expose durable pre-completion activation intent. Directory
symlinks are not required. Current and previous versions are protected from future GC.

## Activation state machine

```text
verified BuildResult
  -> Staging
  -> WaitingForShutdown
  -> Activating (journal activation boundary)
  -> HealthChecking
  -> Completed

HealthChecking failure -> RollingBack -> Failed
```

Before `Activating`, only transaction-owned staging paths may be removed during recovery. After
the boundary, recovery inspects the journal, current pointer, published version and DB backup;
it either completes a safe rollback to the recorded previous version or returns an explicit
review-required error. A state transition never leaves `current_commit` pointing to a missing or
incomplete version.

## BuildResult and metadata trust boundary

The result schema, completed status, full target SHA, candidate location and every component hash
are validated before any copy. Relative paths must remain below the managed build candidate for
the same target SHA; absolute paths, `..`, symlink/reparse ancestors and missing/tampered files
are rejected. Staging copies only the desktop payload and backend JAR, never the source checkout.

`VersionMetadata` records schema version, target SHA, product version, creation time, component
relative paths and hashes, toolchain IDs, and backend runtime paths. It is written and flushed in
staging before the directory is atomically promoted.

## Health check and database rollback

The backend is launched from `versions/<sha>/backend/harmonia-suite.jar` with managed Java, the
existing user-data workspace, `--server.port=0`, and an instance handshake. The actual port is
accepted only from the owned Spring readiness line and `/api/status` is queried on loopback.
The desktop shell receives the active version/backend paths through runtime environment metadata;
repository-relative `target/` remains a development-only fallback.

Before first launch, the activation engine creates a unique snapshot outside `versions/`. The
current backend must be stopped through the host lifecycle hook before the snapshot. SQLite DB,
WAL and SHM files are copied as one stopped-backend snapshot, and the journal records its path.
Rollback restores that snapshot before returning to the previous runtime. A missing or failed
snapshot blocks activation when a DB exists; a failed restore produces `ReviewRequired` and never
guesses by deleting user data.

## Recovery and locking

The existing global installation lock covers recovery, staging, pointer switching and health
check. Journal fields record old/current/target commits, published version, current-switch status,
new-process start, health result, DB backup and rollback completion. Crash recovery is idempotent:
pre-switch staging is cleaned, while post-switch transactions are resumed as rollback decisions
from durable state. Existing user data, workspaces and backups are never cleanup targets.
