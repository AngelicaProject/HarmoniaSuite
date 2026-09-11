import { describe, expect, it } from "vitest";

import {
  GatewayConfigError,
  parseGatewayConfig,
  validateRemoteUrl,
} from "../src/gateway-config.js";

describe("gateway configuration", () => {
  it("defaults to one local gateway", () => {
    expect(parseGatewayConfig(undefined)).toMatchObject({
      activeGatewayId: "local",
      profiles: [{ id: "local", mode: "local" }],
    });
  });

  it("accepts HTTPS remote profiles and keeps only credential references", () => {
    const config = parseGatewayConfig({
      activeGatewayId: "team",
      profiles: [{ id: "team", mode: "remote", url: "https://gateway.example.test/", credentialRef: "team-token" }],
    });
    expect(config.profiles[0]).toEqual({
      id: "team",
      mode: "remote",
      url: "https://gateway.example.test",
      credentialRef: "team-token",
    });
  });

  it("rejects non-loopback HTTP without an explicit development override", () => {
    expect(() => validateRemoteUrl("http://gateway.example.test")).toThrow(GatewayConfigError);
    expect(validateRemoteUrl("http://gateway.example.test", true)).toBe("http://gateway.example.test");
    expect(validateRemoteUrl("http://127.0.0.1:8765")).toBe("http://127.0.0.1:8765");
  });

  it("rejects plaintext credential fields", () => {
    expect(() => parseGatewayConfig({ profiles: [], apiKey: "secret" })).toThrow(/OS keyring/);
    expect(() => parseGatewayConfig({ profiles: [{ id: "x", mode: "remote", url: "https://example.test", token: "secret" }] })).toThrow(/OS keyring/);
  });
});
