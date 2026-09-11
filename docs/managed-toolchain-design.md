# Managed toolchain design (Phase 3)

Phase 3 adds a small, transactional toolchain layer to the Rust installer core. It
does not fetch Git history, create worktrees, or orchestrate a build; those are
Phase 4 responsibilities.

## Layout and ownership

The installer resolves the existing per-user `InstallationPaths` and stores
immutable, side-by-side toolchain installations under:

```text
<app-root>/toolchain/
  jdk/<toolchain-id>/
  node/<toolchain-id>/
  git/<toolchain-id>/
<cache-root>/downloads/<archive-name>
<cache-root>/maven/
<cache-root>/npm/
<state-root>/toolchains.json
```

`toolchain-id` contains the kind, pinned version, target platform/architecture,
and artifact digest. An existing directory is never replaced in place. Archives
are downloaded through the Phase 2 verified downloader and are not extracted
until their expected SHA-256 matches. Installation first extracts into a unique
managed staging directory and then renames it into the side-by-side destination.

Only Windows x64 and Linux x64 are supported in this phase. Platform resolution
is explicit and never falls back to the host's system Java, Node, or Git.

## Pinned descriptors

`ToolchainDescriptor` is the only input accepted by the manager. It contains the
tool kind, exact version, target, HTTPS URL, archive format, SHA-256, and the
relative executable paths. Descriptor validation rejects missing or malformed
digests, non-HTTPS URLs, path traversal, and a target that differs from the
requested platform/architecture. The release/update layer supplies a
versioned catalog containing these values; its URL and digest must be copied
from official upstream release metadata. The installer accepts the catalog only
after validating every descriptor. Test descriptors use a local fake transport
and are never treated as production metadata.

## Metadata and protection

`toolchains.json` is an atomically written, versioned state file. Each record
contains the descriptor identity, archive digest, installation directory, and
resolved executable paths. `InstallationState` keeps current and previous
toolchain references, while a running transaction may pin additional references
in its transaction record. Garbage collection fails closed if state cannot be
read and refuses to remove every ID referenced by current, previous, or running
transaction state. Unreferenced records may be removed only through the explicit
GC API.

## Execution and caches

The resolver returns executable paths only from managed installations. It builds
an explicit environment for future build orchestration: managed tool directories
are prepended to `PATH`, `JAVA_HOME` points at the managed JDK, and Maven and npm
use persistent `<cache-root>/maven` and `<cache-root>/npm` directories. Maven is
invoked through the repository's Maven Wrapper; no standalone Maven runtime is
installed in Phase 3. The resolver does not inherit or search arbitrary system
tool locations.

## Extraction safety and recovery

ZIP and tar.gz entries are checked lexically before creation. Absolute paths,
`..` components, duplicate/colliding entries, symlinks, hard links, and Windows
reparse points are rejected. Extraction remains inside a managed staging root;
metadata is committed only after all files and required executables exist. A
failed extraction removes only the installer-owned staging directory. The core
uses the existing installation lock and diagnostic logger for callers that run
toolchain preparation inside a transaction.

## Rust reproducibility

The installer pins Rust 1.89.0 in `apps/installer/rust-toolchain.toml` and CI
uses the same channel. This pin applies to the installer build only; it does not
install or select the product JDK, Node, or Git.
