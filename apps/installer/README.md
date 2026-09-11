# Harmonia installer core

Phase 2 through Phase 4 provide the shared Rust foundation for future install, repair and update
commands. It is currently a library crate; no installer UI, OS registration, activation or self-update
helper is included yet.

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

The production descriptor catalog is data supplied by the release/update layer. Every descriptor must
carry an exact upstream version, HTTPS URL and SHA-256; the manager does not resolve “latest” or fall
back to system Java or Node. Git operations are reserved for the Rust/libgit2 updater phase.

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

Run the checks from the repository root with:

```text
cargo fmt --check --manifest-path apps/installer/Cargo.toml
cargo clippy --manifest-path apps/installer/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path apps/installer/Cargo.toml
cargo build --release --manifest-path apps/installer/Cargo.toml
```
