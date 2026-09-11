import { describe, expect, it } from "vitest";
import { resolve } from "node:path";

import { CONTENT_SECURITY_POLICY } from "../src/csp.js";
import { apiGatewayUrl, resolveFrontendFile } from "../src/protocol-mapping.js";
import { proxyApiRequest } from "../src/protocol-proxy.js";

describe("harmonia protocol mapping", () => {
  it("uses a restrictive renderer CSP without general inline execution", () => {
    expect(CONTENT_SECURITY_POLICY).toContain("script-src 'self'");
    expect(CONTENT_SECURITY_POLICY).toContain("style-src 'self'");
    expect(CONTENT_SECURITY_POLICY).toContain("style-src-attr 'unsafe-inline'");
    expect(CONTENT_SECURITY_POLICY).not.toContain("script-src 'self' 'unsafe-inline'");
    expect(CONTENT_SECURITY_POLICY).not.toContain("style-src 'self' 'unsafe-inline'");
  });

  it("maps API requests to the active gateway while preserving query", () => {
    expect(apiGatewayUrl("https://gateway.example.test/base", "/api/status?x=1")).toBe(
      "https://gateway.example.test/base/api/status?x=1",
    );
  });

  it("serves SPA routes from index and prevents traversal", () => {
    const root = resolve("frontend-dist-fixture");
    expect(resolveFrontendFile(root, "/editor")).toBe(resolve(root, "index.html"));
    expect(() => resolveFrontendFile(root, "/%2e%2e/%2e%2e/secret")).toThrow(/escapes/);
  });

  it("proxies request bodies and injects only a keyring-backed authorization header", async () => {
    const request = new Request("harmonia://app/api/projects?limit=1", {
      method: "POST",
      headers: { "content-type": "application/json", host: "app" },
      body: JSON.stringify({ name: "demo" }),
    });
    const fetchImpl = async (url: string, init?: RequestInit) => {
      expect(url).toBe("https://gateway.example.test/base/api/projects?limit=1");
      expect(init?.method).toBe("POST");
      expect(new Headers(init?.headers).get("host")).toBeNull();
      expect(new Headers(init?.headers).get("authorization")).toBe("Bearer from-keyring");
      expect(await new Response(init?.body).json()).toEqual({ name: "demo" });
      return new Response("{}", { status: 200 });
    };
    const response = await proxyApiRequest(
      request,
      { profile: { id: "team", mode: "remote", url: "https://gateway.example.test/base", credentialRef: "team" }, baseUrl: "https://gateway.example.test/base" },
      { authorizationHeader: async (ref) => ref === "team" ? "Bearer from-keyring" : undefined },
      fetchImpl,
    );
    expect(response.status).toBe(200);
  });
});
