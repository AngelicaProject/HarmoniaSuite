import { existsSync } from "node:fs";
import { extname, resolve, sep } from "node:path";

export const CONTENT_TYPES: Record<string, string> = {
  ".css": "text/css; charset=utf-8",
  ".gif": "image/gif",
  ".html": "text/html; charset=utf-8",
  ".ico": "image/x-icon",
  ".jpg": "image/jpeg",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".map": "application/json; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
};

export function apiGatewayUrl(baseUrl: string, requestPath: string): string {
  if (!requestPath.startsWith("/api/") && requestPath !== "/api") {
    throw new Error("only /api requests can be proxied");
  }
  const base = new URL(baseUrl);
  const basePath = base.pathname.replace(/\/+$/, "");
  const target = new URL(`${basePath}${requestPath}`, base.origin);
  return target.toString();
}

export function resolveFrontendFile(distRoot: string, pathname: string): string {
  let decoded: string;
  try {
    decoded = decodeURIComponent(pathname);
  } catch {
    throw new Error("invalid encoded UI path");
  }
  if (decoded.includes("\0") || !decoded.startsWith("/")) {
    throw new Error("invalid UI path");
  }
  const root = resolve(distRoot);
  const relative = decoded === "/" ? "index.html" : decoded.slice(1);
  const candidate = resolve(root, relative);
  if (candidate !== root && !candidate.startsWith(`${root}${sep}`)) {
    throw new Error("UI path escapes frontend dist");
  }
  return existsSync(candidate) ? candidate : resolve(root, "index.html");
}

export function contentTypeForFile(file: string): string {
  return CONTENT_TYPES[extname(file).toLowerCase()] || "application/octet-stream";
}
