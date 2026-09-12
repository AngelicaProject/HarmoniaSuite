# Phase 7–8 production updater design

Phase 7 and Phase 8 share one checkpoint: a rolling updater is usable only when its target and
its inputs are trusted. The updater is an orchestration boundary over the existing Phase 4
`BuildPipeline`, Phase 5 `ActivationEngine`, managed downloader, toolchain state and detached
launcher. It does not implement a second build or activation path.

## Operation boundary

`harmonia-setup update` acquires the one `InstallationLock` before recovery, manifest handling,
build, activation and relaunch. `BuildPipeline::run_with_lock` and
`ActivationEngine::activate_with_lock` are the internal APIs used while that lock is held. An
update journal is persisted at the installation state root:

```text
Recovering → Checking → ResolvingTarget → Preparing → WaitingForShutdown → Activating → Restarting → Completed
                                      └──────────────────────────→ Failed/ReviewRequired
```

The journal records operation id, exact target SHA, signed manifest identity, toolchain IDs,
activation completion, launch attempt and launch acknowledgement. A process interruption before
activation is reconciled after low-level recovery. A durable review state is fail-closed.

## Signed manifest v1

Production fetches use product-controlled HTTPS URLs only:

```text
.../harmonia-manifest.json
.../harmonia-manifest.json.sig
```

The signature covers the exact raw manifest bytes. The detached signature document contains
`schemaVersion`, `keyId` and `signatureHex`; the installer verifies Ed25519 using the reviewed
production public-key map. The corresponding private key is release-secret material and is never
stored in the repository or accepted as runtime input. The manifest must contain
`channel: "rolling"`. Unknown keys, invalid signatures, malformed SemVer minimums, credentials
in URLs, HTTP, redirects to HTTP, oversized documents and malformed schema are rejected.
`keyId` permits a reviewed binary to carry a rotation map; an unknown key is a `TrustFailure`.

Manifest v1 can select only `target_commit`, product version, and the known JDK/Node descriptor
shape. Descriptor validation still enforces HTTPS, SHA-256, platform/architecture, safe relative
home and executable paths. It cannot introduce a command, shell fragment, install root or
arbitrary component.

The accepted generation, raw-manifest SHA-256 and key id are stored in installation state.
Lower generations and same-generation different content are rejected, and rollback never lowers
the accepted generation.

## Exact source and artifacts

The signed target SHA is passed to libgit2 as an exact-object fetch and then to `BuildConfig`.
The pipeline verifies that checkout HEAD and `BuildResult.target_commit` equal that SHA. It never
re-resolves `origin/main` during a manifest-selected build. Existing lockfile, managed JDK/Node,
persistent npm/Maven caches, immutable candidate and activation verification remain authoritative.

## Runtime handoff

Activation completes before relaunch. The desktop starts the helper and waits for its durable
startup acceptance record. The helper coordinates shutdown with the installed desktop before
database snapshot/activation; a direct CLI update fails closed when a live desktop session is
detected. The updater uses the existing detached launcher contract, with a fresh operation
id/nonce and acknowledgement file under installer-owned state. Desktop is started only from
`RuntimePaths.desktop_executable`, with managed Java, active version, exact user data/workspace
paths and launch identity in a bounded environment.

If the new desktop cannot be handed off, the updater restores the exact pre-activation
installation state and database snapshot, health-checks the previous backend, relaunches the
previous desktop and waits for its acknowledgement. A failed rollback or previous launch becomes
durable `ReviewRequired` and blocks later updates/GC. Recovery compares the active commit with the
journal target and never reports an already-rolled-back operation as `Updated`.

Fresh install durably publishes the setup helper under the immutable per-user `bin` root with
size/SHA-256 sidecar metadata and Unix executable-bit validation. Existing helpers are never
replaced while running; replacement is a later self-update boundary.

The Electron integration is intentionally narrow: future renderer code receives only update
status/check/install operations through the main-process boundary. Renderer code does not read
the manifest, public keys, filesystem or installer state.

## Explicit boundaries

This checkpoint does not add repair/uninstall, shortcuts, system-wide installation, signed
installer self-replacement, or Phase 9/10 UX. Release signing tooling must receive a private key
from an explicit external CI/file input; no private key or fake signature is stored in the
repository.
