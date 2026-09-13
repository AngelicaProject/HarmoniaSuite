# Harmonia installer core

Phase 2 through Phase 5 provide the shared Rust foundation. Phase 6 adds the first CLI bootstrap
operation; it is still not an installer UI, OS registration, shortcut manager, repair implementation,
uninstaller, or updater.

The core owns:

- Windows/Linux per-user application and user-data path resolution;
- atomic installation state and transaction journal writes;
- exclusive installation locking;
- persistent structured diagnostics;
- HTTPS download abstraction with retries, `.partial` resume, mandatory SHA-256 checks and
  `Content-Range` validation before append;
- shell-free process execution with process-tree timeout termination, bounded output capture,
  explicit environment policy and redacted command diagnostics;
- validated transaction phases and conservative crash-recovery inspection.

Phase 3 additionally provides:

- pinned HTTPS descriptors for side-by-side JDK and Node installations;
- verified archive download/reuse and ZIP/tar.gz extraction with traversal, safe internal symlink and reparse-point
  protections;
- versioned toolchain metadata, nested JDK homes and crash-safe garbage collection protected by
  current, previous and active transaction references;
- managed-only executable resolution and explicit build environments with persistent Maven and npm
  caches;
- Rust/libgit2 as the single Git implementation planned for Phase 4 on Windows and Linux; Git CLI
  archives are not a mandatory managed toolchain;
- Rust 1.89.0 pinning for installer development and CI.

The Phase 6 production descriptor catalog is checked in under `src/catalog.rs`. Every descriptor carries
an exact upstream version, HTTPS URL and SHA-256; the manager does not resolve “latest” or fall back to
system Java or Node. Git operations use the Rust/libgit2 implementation directly.

Phase 4 additionally provides:

- a libgit2-only bare mirror for `origin/main`, exact full-SHA resolution and installer-owned
  attempt-specific staging and verified candidates under `build/staging/<transaction-id>/<sha>` and
  `build/candidates/<sha>/<transaction-id>/<sha>`;
- reproducible frontend, backend and desktop build orchestration through managed Node/JDK paths,
  `npm ci`, the Maven Wrapper checksum policy and persistent npm/Maven caches;
- persistent build diagnostics and machine-readable results bound to one exact commit, with global
  installation locking and crash recovery of journaled pre-activation paths without changing
  installation state or user data;
- complete unpacked Electron payloads, including runtime, compiled shell and frontend assets, ready
  for later Phase 5 staging without another build step.

Phase 4 deliberately does not stage or activate an artifact, switch current/previous versions, or
implement signed manifests, installer UI, shortcuts or uninstall. Those are later phases.

Phase 5 adds immutable version staging, managed-Java runtime metadata, health checks, database-safe
rollback, current/previous state and crash recovery. Phase 6 composes those engines into one
per-user `install` operation:

- `harmonia-setup install` resolves one exact `origin/main` commit, downloads only the checked-in
  production JDK/Node descriptors, builds through the existing pipeline, activates through the
  existing engine, and launches the immutable Electron payload;
- the global installation lock is held across recovery, build, activation and detached launch
  handoff, using locked engine APIs to avoid nested lock deadlocks;
- `--json` emits a machine-readable outcome and the stable exit codes are documented in
  `docs/phase6-fresh-bootstrap-design.md`;
- the final desktop handoff uses a separate detached launcher, so the setup process's health-process
  containment does not kill the installed desktop when setup exits. It passes the exact
  `HARMONIA_USER_DATA_ROOT` contract to Electron and waits for an operation-bound, durable
  acknowledgement after Electron readiness. A bounded early-exit check remains a fast-failure signal;
  an already-running primary can acknowledge a recovery request received through `second-instance`;
- production setup accepts no arbitrary source URL or product-version override. Custom repositories
  and descriptors remain restricted to internal fixture seams.

Phase 9–10 adds the production lifecycle around those engines:

- `install`, `update`, `check-update`, `repair`, and `uninstall` are strict per-user operations with
  documented stable exit codes and optional JSON output;
- repair rebuilds the trusted exact current commit and atomically republishes a corrupted immutable
  version; uninstall stops the bound desktop session and preserves user data unless
  `--remove-user-data` is explicit;
- `bin/HarmoniaSetup.exe`/`harmonia-setup` and `bin/HarmoniaSuite.exe`/`harmonia-suite` are the
  setup and stable launcher contracts. The stable launcher resolves current and passes managed Java,
  workspace, and user-data paths explicitly;
- Windows per-user Start Menu/Desktop shortcuts and HKCU uninstall registration, plus Linux XDG
  desktop entry/icon integration, target only the stable launcher;
- release CI is Windows/Linux x64 only and publishes signed setup/launcher artifacts and signed
  platform manifests. MSI, portable ZIP, and macOS artifacts are deliberately excluded.

Run the checks from the repository root with:

```text
cargo fmt --check --manifest-path apps/installer/Cargo.toml
cargo clippy --manifest-path apps/installer/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path apps/installer/Cargo.toml
cargo build --release --manifest-path apps/installer/Cargo.toml
```
