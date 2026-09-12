# Phase 4 — exact-commit rolling build design

This checkpoint adds a build engine only. It prepares and verifies a build for one immutable
full Git object id and does not stage, activate, switch `current`/`previous`, or modify user data.

## Managed layout

`InstallationPaths` remains the authority for per-user roots:

```text
<app-root>/
  source/                                  libgit2 bare repository, remote `origin`
  build/staging/<txid>/<sha>/              attempt-specific checkout and outputs
  build/candidates/<sha>/<txid>/<sha>/     verified published candidate
<state-root>/
  transaction.json                         persistent Phase 4 transaction journal
  build-results/<txid>.json                 result published only after verification
<cache-root>/
  npm/                                     managed npm cache
  maven/                                   managed Maven user home/cache
```

The source mirror is created and updated by libgit2. Only `+refs/heads/main` is fetched into
`refs/remotes/origin/main`, so a force-updated upstream ref is followed. The first successful
fetch resolves and records the full 40-character commit SHA. That SHA is immutable for the rest
of the transaction: no second fetch or moving-ref comparison is performed. No Git executable,
`git pull`, merge, or mutable checkout is used.

Each checkout is created from the managed mirror, detached at the target SHA, and verified with
libgit2 before any build command runs. Cleanup accepts only a checkout whose installer-owned marker
identifies the same SHA, or an exact path explicitly owned by the active transaction. Unknown
directories, symlink/reparse-point trees, and paths outside `build/` are left untouched.

## Process and dependency policy

All npm and Maven commands go through the existing hardened `ProcessRunner`. Node and npm are
resolved from the managed Node record; the frontend runs `npm ci` and its build script, while the
desktop runs `npm ci`, TypeScript compilation, and its payload packaging script. The backend invokes
the checkout's Maven Wrapper directly with the managed JDK/Node environment. Wrapper properties
must contain `distributionType=only-script` and a valid `distributionSha256Sum`; no standalone
Maven, system Java, Node, npm, or Git fallback is allowed. Maven no longer invokes the legacy
frontend npm/resource packaging; the Electron payload owns the renderer assets.

`ManagedEnvironment::apply_to` keeps the fixed safe OS-variable allowlist and overrides only
`PATH`, `JAVA_HOME`, `MAVEN_USER_HOME`, and `npm_config_cache`. Managed directories are first in
`PATH`, followed only by platform-safe OS utility directories needed by wrapper scripts. The
transaction pins the exact toolchain IDs before build work begins, so Phase 3 GC cannot remove them.

## Transaction sequence and recovery

The pipeline persists these phases:

```text
ResolvingTarget -> PreparingToolchain -> FetchingSource -> PreparingWorktree
                -> BuildingFrontend -> BuildingBackend -> BuildingDesktop
                -> Verifying -> Completed
```

The global installation lock covers recovery and the complete build transaction and is acquired
before `Transaction::begin`. The existing `InstallationState` is read but never written by Phase 4.
A failed command records a failed transaction and removes only exact installer-owned journal paths.
An interrupted pre-activation transaction is marked failed during the next recovery pass and its
owned paths are cleaned even if a checkout marker was never written. Activation transactions remain
blocked for explicit review. Versions, current/previous pointers, user data, workspace files,
backups, source mirror and caches are never build-failure cleanup targets.

Every attempt gets a unique transaction-specific staging directory. After all outputs are verified,
the staging directory is atomically renamed into a candidate directory and a unique result record
is written. A previous verified result is retained; a failed rebuild cannot destroy a usable
candidate or leave a stale Completed result under the same commit.

## Build result

Successful verification atomically writes `<state-root>/build-results/<transaction-id>.json`:

```json
{
  "schema_version": 1,
  "status": "Completed",
  "target_commit": "<full Git object id / commit SHA>",
  "product_version": "...",
  "checkout_dir": "build/candidates/<sha>/<transaction-id>/<sha>",
  "toolchains": { "jdk": "...", "node": "..." },
  "frontend": { "path": "frontend/dist", "sha256": "..." },
  "backend": { "path": "target/harmonia-suite.jar", "sha256": "..." },
  "desktop": { "path": "apps/desktop/artifacts/linux-x64", "sha256": "..." },
  "started_at_ms": 0,
  "finished_at_ms": 0,
  "duration_ms": 0
}
```

The desktop artifact is a complete unpacked Electron payload: Electron runtime files, compiled
main/preload JavaScript under `resources/app`, and the built frontend under `frontend/dist`.
Phase 5 can stage this directory without another build step. Directory hashes are deterministic
manifests of relative file paths and file SHA-256 values; the backend hash is the SHA-256 of the
packaged JAR. Diagnostics include target SHA, transaction phase, command outcome and duration while
using the existing redacted command logger.

Phase 5 will consume this result for transactional staging and activation. It is intentionally
outside this checkpoint.
