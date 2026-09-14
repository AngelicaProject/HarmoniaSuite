# Release operations

This document is the operational contract for product releases and signed rolling updates.
Version identity and release progression rules live in [versioning.md](versioning.md).

## Supported release scope

Production release artifacts target Windows x64 and Linux x64. macOS, portable ZIP distributions,
and MSI packages are out of scope for this release contract. The application payload and installer
artifacts must not be confused with user-created translation ZIP exports.

## GitHub product releases

A tag `vX.Y.Z` means a HarmoniaSuite product release, not an installer-engine release. The
published release title is `HarmoniaSuite vX.Y.Z`, and the tag must equal the stable
`versions.json.productVersion`. The release workflow also requires the checked-out commit to be
the exact tag commit.

Release assets contain the platform setup/bootstrap binaries and their checksums:

```text
HarmoniaSetup.exe
harmonia-setup
release-metadata.json
checksums
```

The standalone Rust `HarmoniaSuite.exe`/`harmonia-suite` proxy is not published. The installed
application is launched from the real Electron executable under the managed `current` pointer
after a fresh install or completed migration.
Installer 0.1.2 uses a transition payload containing the branded Electron filename plus a temporary
`desktop/electron.exe`/`desktop/electron` hardlink so existing 0.1.0/0.1.1 updaters can still stage
it. The compatibility alias is removed only in a later rollout after the minimum installer floor
has been raised deliberately.

### Existing-installation rollout

The rolling BuildPipeline builds application components; it does not replace the installer engine
already installed on a client. Consequently, a 0.1.0/0.1.1 installation that rolls forward keeps
its legacy `bin/HarmoniaSuite.exe`/`bin/harmonia-suite` proxy and proxy shortcuts. This PR leaves
`minimumInstallerVersion` unchanged and does not claim that those installations are already on the
direct launch path.

Migration is staged through a trusted, prebuilt 0.1.2 setup asset. After verifying the release
asset and checksum, run `HarmoniaSetup.exe repair` (or `harmonia-setup repair`) for the existing
per-user installation. The new engine rebuilds the trusted current payload if necessary, publishes
`current`, rewrites shortcuts/`DisplayIcon` and Linux desktop integration, verifies that surface,
and only then removes the legacy proxy. The order is crash-safe and idempotent: failures before
successful integration preserve the proxy, and retrying the repair repeats the safe checks. Fresh
0.1.2 installs use direct launch immediately; automatic engine replacement for existing installs
is deferred to a separately staged rollout.

The tagged installer is compiled with the exact tag commit and stable product version. A fresh
install uses that embedded release bootstrap seed, builds only the exact seed commit, and fails
closed when the checkout's product version differs. After the first launch, normal updates follow
signed rolling manifests and later exact commit identities.

`release-metadata.json` is a machine-readable product-release asset:

```json
{
  "schemaVersion": 1,
  "productVersion": "1.0.11",
  "installerVersion": "0.1.0",
  "commit": "<exact tag commit>"
}
```

Its `commit` must be the same exact target as the embedded release bootstrap seed. Product release
publication is draft-first and rerun-safe; a published tag is immutable and reruns verify existing
assets instead of replacing them.

## Authenticode and rolling trust

Windows Authenticode signing is optional. An unsigned production release is allowed and is marked
in its Windows release metadata. Authenticode status is separate from rolling-update trust:
rolling manifests are trusted through Ed25519 verification, not through the Windows signature.

The rolling signing key is external secret material. The current reviewed keyring contains the
compiled public key selected by `keyId=primary-2026-09`. Private keys are never committed,
accepted as runtime input, or printed by CI. Future key rotation is an overlap sequence:

```text
A -> A+B -> B
```

Old clients trust A, a reviewed installer release adds B while retaining A, and A is removed only
after clients have had time to adopt the A+B release.

## Rolling manifest security contract

Production rolling manifests are fetched only from the compiled platform-specific HTTPS Pages
endpoint. The signed raw manifest must use `channel: "rolling"`. `keyId` selects a compiled,
reviewed public key; an unknown key produces `TrustFailure`.

Manifest and signature documents fail closed on unknown keys, invalid Ed25519 signatures,
malformed or unsupported schemas, malformed JSON or other malformed documents, malformed
`minInstallerVersion`, credentials in artifact or manifest URLs, plain HTTP, HTTPS-to-HTTP
downgrade/redirects, and oversized manifest or signature documents. The manifest's
`productVersion` must match the exact checkout's `versions.json`; `minInstallerVersion` comes from
that contract and remains independent from the current installer engine version.

Rolling publication runs only after successful main CI. It accepts a candidate only when it
remains in `main` history and is equal to or a descendant of the latest published target. The
exact target commit plus durable monotonic generation is the rolling identity; late ancestor
completions are no-ops and stable aliases are updated last.

The `gh-pages` branch is the durable rolling publication ledger: it retains the latest generation,
commit-to-generation mappings, latest published commit, stable aliases, and immutable manifest
history. GitHub Pages Actions deploys the committed ledger state as the public HTTPS rolling
endpoint. Repository Settings → Pages → Source must be set to GitHub Actions. Generation is
monotonic for the lifetime of the rolling channel and does not reset at product releases.

## Bootstrap launch acknowledgement

Electron launch acknowledgement is durable and binds all of:

```text
operation id
exact target commit OID
nonce
```

Electron writes the acknowledgement only after the local gateway is ready, the secure application
protocol is registered, and the initial UI has loaded successfully. Recovery validates the same
operation id, target commit, and nonce; stale or mismatched acknowledgements are ignored. The
`second-instance` recovery path may acknowledge on behalf of an already-running primary, while a
short-lived secondary is not treated as a successful launch by itself.

## Operational boundaries

Fresh install, rolling update, repair, and launch use the same per-user transactional state and
rollback contract. The installer does not use GitHub `releases/latest/download` as an application
update source, does not accept arbitrary source/version runtime overrides, and does not weaken the
existing updater trust model. The product release workflow publishes product assets; rolling
manifest publication is a separate signed operation.
