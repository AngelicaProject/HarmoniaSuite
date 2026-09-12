#!/usr/bin/env node

// Release-only helper. It requires an explicit Ed25519 PKCS#8 private-key file supplied by the
// release environment; no key is checked into the repository and there is no unsigned fallback.
import { readFile, writeFile } from "node:fs/promises";
import { createPrivateKey, sign } from "node:crypto";

const [, , manifestPath, signaturePath, keyId = "primary-2026"] = process.argv;
const keyPath = process.env.HARMONIA_MANIFEST_SIGNING_KEY_FILE;
if (!manifestPath || !signaturePath || !keyPath) {
  throw new Error(
    "usage: HARMONIA_MANIFEST_SIGNING_KEY_FILE=/secure/key.pem node sign-manifest.mjs manifest.json manifest.json.sig [keyId]",
  );
}

const manifest = await readFile(manifestPath);
const privateKey = createPrivateKey(await readFile(keyPath));
if (privateKey.asymmetricKeyType !== "ed25519") {
  throw new Error("manifest signing key must be Ed25519");
}
const signature = sign(null, manifest, privateKey);
await writeFile(
  signaturePath,
  `${JSON.stringify({ schemaVersion: 1, keyId, signatureHex: signature.toString("hex") }, null, 2)}\n`,
  { flag: "wx" },
);
