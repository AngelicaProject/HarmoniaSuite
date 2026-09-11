import { readFile } from "node:fs/promises";

import { protocol } from "electron";

import type { ActiveGateway } from "./types.js";
import type { CredentialStore } from "./credentials.js";
import { CONTENT_SECURITY_POLICY } from "./csp.js";
import { contentTypeForFile, resolveFrontendFile } from "./protocol-mapping.js";
import { proxyApiRequest } from "./protocol-proxy.js";

const HARMONIA_HOST = "app";

export function registerHarmoniaProtocol(
  distRoot: string,
  gateway: ActiveGateway,
  credentials: CredentialStore,
): void {
  protocol.handle("harmonia", async (request) => {
    const url = new URL(request.url);
    if (url.hostname !== HARMONIA_HOST) {
      return new Response("Not found", { status: 404 });
    }
    if (url.pathname === "/api" || url.pathname.startsWith("/api/")) {
      return proxyApiRequest(request, gateway, credentials);
    }
    try {
      const file = resolveFrontendFile(distRoot, url.pathname);
      const body = await readFile(file);
      return new Response(body, {
        headers: {
          "content-type": contentTypeForFile(file),
          "content-security-policy": CONTENT_SECURITY_POLICY,
          "x-content-type-options": "nosniff",
          "referrer-policy": "no-referrer",
        },
      });
    } catch {
      return new Response("Not found", { status: 404 });
    }
  });
}
