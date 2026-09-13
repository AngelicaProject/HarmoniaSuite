# Release operations

The release workflow treats the two signing systems differently:

* `HARMONIA_WINDOWS_SIGNING_CERTIFICATE_B64` and
  `HARMONIA_WINDOWS_SIGNING_PASSWORD` sign and verify `HarmoniaSetup.exe` and
  `HarmoniaSuite.exe` with Authenticode on the Windows runner when configured. If absent, the
  product release continues with explicit unsigned metadata; this is not a trust substitute.
* `HARMONIA_MANIFEST_SIGNING_KEY_PEM` is an Ed25519 PKCS#8 private key for rolling publication,
  matching the public trust root compiled into `apps/installer/src/manifest.rs`.
  The current production key is `keyId=primary-2026-09` with public key
  `5e02dfc689bc3c447cffa720d94225b5bedb593cb4f77c5ba0461103705363d2`.
  `HARMONIA_MANIFEST_PUBLIC_KEY_HEX` must equal that reviewed public key. The private key is
  stored only in the external secret manager/GitHub Actions secret, never committed, materialized
  only in runner temp storage for signing, and removed after signing. The signed envelope is
  verified before upload.

The Ed25519 private key must be stored in the external secret manager/GitHub Actions secret before
rolling publication. A comment or public key in source is not an operational substitute. The key is
removed by the workflow cleanup trap after signing. The secret names remain
`HARMONIA_MANIFEST_SIGNING_KEY_PEM` and `HARMONIA_MANIFEST_PUBLIC_KEY_HEX`; CI does not create,
print, or publish private keys.

Future production key rotation must use overlap: A -> A+B -> B.

1. Current clients trust A.
2. Publish an installer signed by A whose compiled keyring trusts A+B.
3. Wait until sufficient client adoption.
4. Switch manifest signing to B; clients with A+B accept it.
5. Remove A only in a later installer release.

After production clients exist, never replace A directly with B: old updater versions that trust
only A will be unable to verify the new manifest.

The product release workflow does not require this Ed25519 key because it publishes product
release assets and does not publish a rolling manifest.

Product release publication is draft-first and rerun-safe: an existing draft is reused,
expected assets are uploaded with clobber semantics, and the draft is made public only after all
assets verify. A published tag is immutable: a rerun verifies existing assets and never replaces
them. Rolling publication runs only after successful `main` CI, accepts a candidate only when it is
in `main` history and is equal to or a descendant of the latest published target, and treats late
ancestor completions as no-ops. It persists SHA-to-generation mapping under
`rolling/generations`, invokes the Rust production verifier, and updates stable aliases last. A
rerun for the same SHA is idempotent; a generation collision with different bytes is fatal.

The rolling manifest keeps four identities separate: `productVersion` comes from
`versions.json` in the exact candidate checkout and must match Maven, frontend, and desktop;
`installerVersion` is the Rust engine version; `minInstallerVersion` is the separately reviewed
compatibility floor from `versions.json`; and `targetCommit` plus `generation` is the rolling
publication identity. Generation and commit values are never embedded into the product version.

Every exact production build reads the product version from `versions.json` and fail-closes if
Maven `project.version`, frontend package metadata, or desktop package metadata differs. Fresh
install and repair use that verified value directly; a rolling manifest must match it exactly or
the update fails closed. The installer Cargo version is separate and is used for engine and
compatibility metadata.

The updater uses only the compiled platform-specific HTTPS Pages endpoint and the signed
`channel: rolling` contract. It does not use GitHub `releases/latest/download` as an application
update source. The installer has no XivExdUnpacker dependency: Electron's verified runnable
payload is the desktop artifact contract.
