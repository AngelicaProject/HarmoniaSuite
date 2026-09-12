# Release operations

The release workflow fails closed unless both production signing systems are configured:

* `HARMONIA_WINDOWS_SIGNING_CERTIFICATE_B64` and
  `HARMONIA_WINDOWS_SIGNING_PASSWORD` sign and verify `HarmoniaSetup.exe` and
  `HarmoniaSuite.exe` with Authenticode on the Windows runner.
* `HARMONIA_MANIFEST_SIGNING_KEY_PEM` is an Ed25519 PKCS#8 private key matching the public trust
  root compiled into `apps/installer/src/manifest.rs`. `HARMONIA_MANIFEST_PUBLIC_KEY_HEX` must
  equal that reviewed public key. The key is written only to the ephemeral runner temp directory,
  and the signed envelope is verified before upload.

The private key must be stored in the repository/environment secret manager or an equivalent
release secret service before a production tag is pushed. A comment or public key in source is not
an operational substitute. The workflow publishes Windows/Linux x64 binaries and their hashes
first, then uploads the signed platform manifests. It never creates MSI, portable ZIP, or macOS
artifacts.
