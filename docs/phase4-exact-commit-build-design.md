# Phase 4 — exact-commit rolling build design

This checkpoint adds a build engine only. It prepares and verifies a build for one immutable
full Git object id and does not stage, activate, switch `current`/`previous`, or modify user data.

## Managed layout

`InstallationPaths` remains the authority for per-user roots:

```text
<app-root>/
  source/                         libgit2 bare repository, remote `origin`
  build/<full-commit-sha>/        clean detached checkout and build outputs
<state-root>/
  transaction.json                persistent Phase 4 transaction journal
  build-results/<full-commit-sha>.json
<cache-root>/
  npm/                            managed npm cache
  maven/                          managed Maven user home/cache
```

The source mirror is created and updated by libgit2. Only `refs/heads/main` is fetched into
`refs/remotes/origin/main`; the resolved target is recorded as the full 40-character commit SHA.
No Git executable, `git pull`, merge, or mutable checkout is used.

Each build checkout is created from the managed mirror, detached at the target SHA, and verified
with libgit2 before any build command runs. Cleanup accepts only a checkout whose installer-owned
marker identifies the same SHA, or a path explicitly owned by the active transaction. Unknown
directories, symlink/reparse-point trees, and paths outside `build/` are left untouched.

## Process and dependency policy

All npm and Maven commands go through the existing hardened `ProcessRunner`. Node and npm are
resolved from the managed Node record; the frontend and desktop each run `npm ci` and then their
build script. The backend invokes the checkout's Maven Wrapper directly with the managed JDK/Node
environment. Wrapper properties must contain `distributionType=only-script` and a valid
`distributionSha256Sum`; no standalone Maven, system Java, Node, npm, or Git fallback is allowed.

`ManagedEnvironment::apply_to` keeps the fixed safe OS-variable allowlist and overrides only
`PATH`, `JAVA_HOME`, `MAVEN_USER_HOME`, and `npm_config_cache`. The transaction pins the exact
JDK and Node toolchain IDs before build work begins, so Phase 3 GC cannot remove them.

## Transaction sequence and recovery

The pipeline persists these phases:

```text
ResolvingTarget -> PreparingToolchain -> FetchingSource -> PreparingWorktree
                -> BuildingFrontend -> BuildingBackend -> BuildingDesktop
                -> Verifying -> Completed
```

The existing `InstallationState` is read but never written by Phase 4. A failed command records a
failed transaction and removes only the installer-owned checkout. It never touches versions,
current/previous pointers, user data, workspace files, or backups. An interruption before
activation is recoverable as a non-activated transaction; the next invocation may safely inspect
or clean its owned checkout. Source mirror and caches are durable shared prerequisites and are not
deleted as build failure cleanup.

## Build result

Successful verification atomically writes `<state-root>/build-results/<sha>.json`:

```json
{
  "schema_version": 1,
  "status": "Completed",
  "target_commit": "<full Git object id / commit SHA>",
  "product_version": "...",
  "checkout_dir": "build/<sha>",
  "toolchains": { "jdk": "...", "node": "..." },
  "frontend": { "path": "frontend/dist", "sha256": "..." },
  "backend": { "path": "target/harmonia-suite.jar", "sha256": "..." },
  "desktop": { "path": "apps/desktop/dist", "sha256": "..." },
  "started_at_ms": 0,
  "finished_at_ms": 0,
  "duration_ms": 0
}
```

Directory hashes are deterministic manifests of relative file paths and file SHA-256 values; the
backend hash is the SHA-256 of the packaged JAR. Diagnostics include target SHA, transaction
phase, command outcome and duration while using the existing redacted command logger.

Phase 5 will consume this result for transactional staging and activation. It is intentionally
outside this checkpoint.
