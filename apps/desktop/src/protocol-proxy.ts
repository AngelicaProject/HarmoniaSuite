import { apiGatewayUrl } from "./protocol-mapping.js";
import type { CredentialStore } from "./credentials.js";
import type { ActiveGateway } from "./types.js";

const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "content-length",
  "host",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
]);

export async function proxyApiRequest(
  request: Request,
  gateway: ActiveGateway,
  credentials: CredentialStore,
  fetchImpl: typeof fetch = fetch,
): Promise<Response> {
  const requestUrl = new URL(request.url);
  const target = apiGatewayUrl(gateway.baseUrl, requestUrl.pathname + requestUrl.search);
  const headers = new Headers();
  request.headers.forEach((value, key) => {
    if (!HOP_BY_HOP_HEADERS.has(key.toLowerCase())) {
      headers.set(key, value);
    }
  });
  const authorization = await credentials.authorizationHeader(
    gateway.profile.mode === "remote" ? gateway.profile.credentialRef : undefined,
  );
  if (authorization) {
    headers.set("authorization", authorization);
  }
  const init: RequestInit = {
    method: request.method,
    headers,
    redirect: "manual",
  };
  if (request.method !== "GET" && request.method !== "HEAD") {
    init.body = await request.arrayBuffer();
  }
  return fetchImpl(target, init);
}
