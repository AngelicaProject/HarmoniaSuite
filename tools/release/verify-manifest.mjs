#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { createPublicKey, verify } from "node:crypto";

const [, , manifestPath, signaturePath] = process.argv;
const publicHex = process.env.HARMONIA_MANIFEST_PUBLIC_KEY_HEX;
if (!manifestPath || !signaturePath || !publicHex) throw new Error("manifest, signature and public key are required");
if (!/^[0-9a-f]{64}$/i.test(publicHex)) throw new Error("public key must be 32-byte Ed25519 hex");
const manifest = await readFile(manifestPath);
const envelope = JSON.parse(await readFile(signaturePath, "utf8"));
if (envelope.schemaVersion !== 1 || envelope.keyId !== "primary-2026" || !/^[0-9a-f]{128}$/i.test(envelope.signatureHex)) {
  throw new Error("invalid production signature envelope");
}
const der = Buffer.concat([
  Buffer.from("302a300506032b6570032100", "hex"),
  Buffer.from(publicHex, "hex"),
]);
const key = createPublicKey({ key: der, format: "der", type: "spki" });
if (!verify(null, manifest, key, Buffer.from(envelope.signatureHex, "hex"))) throw new Error("manifest signature verification failed");
