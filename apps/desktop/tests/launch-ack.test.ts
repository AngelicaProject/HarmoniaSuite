import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import {
  isMatchingLaunchAcknowledgement,
  launchRequestFromAdditionalData,
  launchRequestFromEnvironment,
  readLaunchAcknowledgement,
  writeLaunchAcknowledgement,
  type LaunchRequest,
} from "../src/launch-ack.js";

const temporaryRoots: string[] = [];

afterEach(async () => {
  await Promise.all(temporaryRoots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("launch acknowledgement", () => {
  it("parses a complete environment-bound request", () => {
    const request = launchRequestFromEnvironment({
      HARMONIA_LAUNCH_ACK: resolve("state with spaces", "ack.json"),
      HARMONIA_INSTALL_STATE_ROOT: resolve("state with spaces"),
      HARMONIA_LAUNCH_OPERATION_ID: "operation-1",
      HARMONIA_LAUNCH_COMMIT: "a".repeat(40),
      HARMONIA_LAUNCH_NONCE: "nonce-1",
    });
    expect(request?.targetCommit).toBe("a".repeat(40));
  });

  it("does not require a handshake for an ordinary installed launch", () => {
    expect(launchRequestFromEnvironment({
      HARMONIA_INSTALL_STATE_ROOT: resolve("state with spaces"),
    })).toBeUndefined();
  });

  it("rejects an acknowledgement outside installer state", () => {
    expect(() => launchRequestFromEnvironment({
      HARMONIA_LAUNCH_ACK: resolve("outside", "ack.json"),
      HARMONIA_INSTALL_STATE_ROOT: resolve("state"),
      HARMONIA_LAUNCH_OPERATION_ID: "operation-1",
      HARMONIA_LAUNCH_COMMIT: "a".repeat(40),
      HARMONIA_LAUNCH_NONCE: "nonce-1",
    })).toThrow("inside installer state");
  });

  it("writes an atomic acknowledgement and reads the exact identity", async () => {
    const root = await mkdtemp(join(tmpdir(), "harmonia-launch-ack-"));
    temporaryRoots.push(root);
    const request = requestFor(root);

    await writeLaunchAcknowledgement(request);

    const document = JSON.parse(await readFile(request.ackPath, "utf8")) as Record<string, unknown>;
    expect(document.operationId).toBe(request.operationId);
    expect(await readLaunchAcknowledgement(request)).toMatchObject(request);
  });

  it("ignores stale acknowledgement identities", async () => {
    const root = await mkdtemp(join(tmpdir(), "harmonia-launch-ack-"));
    temporaryRoots.push(root);
    const request = requestFor(root);
    await writeLaunchAcknowledgement(request);

    const wrong = { ...request, nonce: "different-nonce" };
    expect(isMatchingLaunchAcknowledgement(
      JSON.parse(await readFile(request.ackPath, "utf8")),
      wrong,
    )).toBe(false);
    expect(await readLaunchAcknowledgement(wrong)).toBeUndefined();
  });

  it("accepts a recovery request delivered as Electron additional data", () => {
    const request: LaunchRequest = requestFor(resolve("state with spaces"));
    expect(launchRequestFromAdditionalData(
      { harmoniaLaunch: request },
      { HARMONIA_INSTALL_STATE_ROOT: request.stateRoot },
    )).toEqual(request);
  });

  it("rejects recovery data for a different installer state root", () => {
    const request: LaunchRequest = requestFor(resolve("state with spaces"));
    expect(launchRequestFromAdditionalData(
      { harmoniaLaunch: request },
      { HARMONIA_INSTALL_STATE_ROOT: resolve("another-state") },
    )).toBeUndefined();
  });
});

function requestFor(root: string): LaunchRequest {
  return {
    ackPath: join(root, "bootstrap-acks", "operation-1-nonce-1.json"),
    stateRoot: root,
    operationId: "operation-1",
    targetCommit: "a".repeat(40),
    nonce: "nonce-1",
  };
}
