# Harmonia installer core

Phase 2 and Phase 3 provide the shared Rust foundation for future install, repair and update commands.
It is currently a library crate; no installer UI, OS registration, exact-commit Git/build pipeline or
self-update helper is included yet.

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

- pinned HTTPS descriptors for side-by-side JDK, Node and Git installations;
- verified archive download/reuse and ZIP/tar.gz extraction with traversal, link and reparse-point
  protections;
- versioned toolchain metadata and garbage collection protected by current, previous and active
  transaction references;
- managed-only executable resolution and explicit build environments with persistent Maven and npm
  caches;
- Rust 1.89.0 pinning for installer development and CI.

The production descriptor catalog is data supplied by the release/update layer. Every descriptor must
carry an exact upstream version, HTTPS URL and SHA-256; the manager does not resolve “latest” or fall
back to system Java, Node or Git.

Run the checks from the repository root with:

```text
cargo fmt --check --manifest-path apps/installer/Cargo.toml
cargo clippy --manifest-path apps/installer/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path apps/installer/Cargo.toml
cargo build --release --manifest-path apps/installer/Cargo.toml
```
