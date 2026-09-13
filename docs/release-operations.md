# Release operations

The release workflow treats the two signing systems differently:

* `HARMONIA_WINDOWS_SIGNING_CERTIFICATE_B64` and
  `HARMONIA_WINDOWS_SIGNING_PASSWORD` sign and verify `HarmoniaSetup.exe` and
  `HarmoniaSuite.exe` with Authenticode on the Windows runner when configured. If absent, the
  tagged distribution continues with explicit unsigned metadata; this is not a trust substitute.
* `HARMONIA_MANIFEST_SIGNING_KEY_PEM` is an Ed25519 PKCS#8 private key for rolling publication,
  matching the public trust root compiled into `apps/installer/src/manifest.rs`.
  `HARMONIA_MANIFEST_PUBLIC_KEY_HEX` must equal that reviewed public key. The key is written only
  to the ephemeral runner temp directory, and the signed envelope is verified before upload.

The Ed25519 private key must be stored in the repository/environment secret manager or an
equivalent release secret service before rolling publication. A comment or public key in source is
not an operational substitute. The key is removed by the workflow cleanup trap after signing.
Ordinary tagged distribution does not require this Ed25519 key because the tag workflow publishes
only installer/launcher binaries and does not publish a rolling manifest.

Tagged distribution publication is draft-first and rerun-safe: an existing draft is reused,
expected assets are uploaded with clobber semantics, and the draft is made public only after all
assets verify. A published tag is immutable: a rerun verifies existing assets and never replaces
them. Rolling publication runs only after successful `main` CI, accepts a candidate only when it is
in `main` history and is equal to or a descendant of the latest published target, and treats late
ancestor completions as no-ops. It persists SHA-to-generation mapping under
`rolling/generations`, invokes the Rust production verifier, and updates stable aliases last. A
rerun for the same SHA is idempotent; a generation collision with different bytes is fatal.

The updater uses only the compiled platform-specific HTTPS Pages endpoint and the signed
`channel: rolling` contract. It does not use GitHub `releases/latest/download` as an application
update source. The installer has no XivExdUnpacker dependency: Electron's verified runnable
payload is the desktop artifact contract.
