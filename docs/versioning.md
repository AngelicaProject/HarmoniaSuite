# HarmoniaSuite versioning contract

`versions.json` at the repository root is the authoritative version policy. Every checkout must
pass `node tools/version/check-versions.mjs` before it is built or published.

## Product Version

`productVersion` is the version of the HarmoniaSuite product as a whole. It is copied exactly to
the root Maven project, `frontend/package.json`, and `apps/desktop/package.json`, including the
root package metadata in their lockfiles when present. Frontend and desktop remain private
components; their package version identifies the product release they belong to, not a separate
release line.

The product version may be a stable SemVer release or a development SemVer such as
`1.0.12-SNAPSHOT`. Rolling builds do not bump this value for every commit. Their identity is the
exact commit OID plus the durable rolling generation.

At a product release, the current stable product version must be greater than the product version
of the previous valid product release. This prevents re-publishing an older product under a new
or repeated tag.

## Installer Engine Version

`installerVersion` is the independent SemVer version of the Rust installer/updater engine. It must
equal `apps/installer/Cargo.toml` and the corresponding package entry in
`Cargo.lock` when that entry is tracked. Changing installer behavior requires a higher engine
version before a new installer binary is published. An installer-only compatible fix can bump
`0.1.0` to `0.1.1`; a substantial engine capability or protocol change can bump the minor line to
`0.2.0` while the product version remains independent.

## Minimum Installer Version

`minimumInstallerVersion` is the compatibility floor required by the rolling update contract. It
is the signed manifest's `minInstallerVersion`; it is not automatically copied from
`installerVersion`. It must be less than or equal to `installerVersion` and is raised only when a
new rolling contract cannot safely run on older engines.

The two values are separate by definition: they may be equal in the initial release, but a new
installer engine does not automatically raise the compatibility floor. The floor changes only
after the required installer engine is published and the new rolling contract has been reviewed.

The compatible installer must be published and available to users before a later main commit
raises this floor. Otherwise an old client could create an update deadlock. If the running engine
is below the signed floor, the updater fails closed with `UpdaterUpgradeRequired`.

## Rolling Identity

Rolling publication uses the exact green commit and a durable monotonic generation. Product
version equality does not identify a rolling update:

```text
1.0.12-SNAPSHOT / commit A / generation 10
1.0.12-SNAPSHOT / commit B / generation 11
1.0.12-SNAPSHOT / commit C / generation 12
```

Each signed manifest takes `productVersion` and `minimumInstallerVersion` from the exact
candidate checkout's version contract. The target commit and generation remain the update
identity.

## GitHub Product Release

An annotated or lightweight tag `vX.Y.Z` means a stable HarmoniaSuite product release, not an
installer release. Release CI requires the tag without `v` to equal the checkout's stable
`productVersion`; `-SNAPSHOT` product versions cannot be tagged. Release titles use
`HarmoniaSuite vX.Y.Z`.

Every tagged release also publishes `release-metadata.json`:

```json
{
  "schemaVersion": 1,
  "productVersion": "1.0.11",
  "installerVersion": "0.1.0",
  "commit": "<exact tag commit>"
}
```

This records which installer engine was shipped with the product release without exposing any
secrets.

The release workflow also embeds the exact tag commit and stable product version into the
installer at compile time. A fresh install from that tagged installer builds only that immutable
seed commit and fails closed if its `versions.json` product version differs. These values are not
runtime CLI or environment overrides; after the seed is installed, normal signed rolling updates
continue to select later exact commits.

The first-release flow is therefore:

```text
tag v1.0.11
  -> embedded exact seed commit
  -> exact Product Version 1.0.11
  -> successful first launch
  -> subsequent updates from signed rolling manifests
```

If installer source or behavior changed since the previous valid product release, the next
product release must use a greater `installerVersion`. If the installer did not change, reusing
the engine version is allowed.

## Lifecycle examples

Product release lifecycle:

```text
main:             1.0.11-SNAPSHOT
prepare release:  1.0.11
tag:              v1.0.11
GitHub Release:   HarmoniaSuite v1.0.11
next main:        1.0.12-SNAPSHOT
```

Installer lifecycle:

```text
Product v1.0.11 -> installer 0.1.0
installer gets compatible fixes -> installer 0.1.1
minimum remains 0.1.0
Product v1.0.12 publishes installer 0.1.1
later rolling functionality needs 0.1.1
minimumInstallerVersion becomes 0.1.1 in a subsequent main commit
```

It is valid for product releases to reuse an unchanged engine:

```text
Product v1.0.12 -> installer 0.1.1
Product v1.0.13 -> installer 0.1.1
```

The 0.1.0/0.1.1 to 0.1.2 migration is staged separately from rolling application publication:
the user runs a trusted, prebuilt 0.1.2 setup binary's `repair` command, which publishes the
direct-launch `current` surface and removes the legacy proxy only after integration succeeds.
Until that step, existing clients remain on their legacy proxy contract. The rolling manifest's
`minimumInstallerVersion` stays at `0.1.0` until this upgrade path is available and adopted; this
change does not raise the floor.

## SemVer policy and tooling

Use the version tooling for all coordinated changes:

```bash
node tools/version/set-version.mjs product 1.0.12-SNAPSHOT
node tools/version/set-version.mjs installer 0.1.1
node tools/version/set-version.mjs minimum-installer 0.1.1
node tools/version/check-versions.mjs
```

Product PATCH releases are bug fixes, hardening, and internal improvements without a new
significant product surface (`1.0.11` -> `1.0.12`). MINOR releases add backward-compatible
functionality (`1.0.x` -> `1.1.0`). MAJOR releases change a significant product/data/config/user
contract (`1.x` -> `2.0.0`). Before a release, move `1.0.12-SNAPSHOT` to `1.0.12`; after its
publication, move the next development line to `1.0.13-SNAPSHOT`.
