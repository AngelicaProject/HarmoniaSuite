export interface ApiErrorPayload {
  error?: string;
}

export type ApiErrorKind = "timeout" | "network" | "http" | "malformed";

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status?: number;
  readonly cause?: unknown;

  constructor(
    kind: ApiErrorKind,
    message: string,
    status?: number,
    cause?: unknown,
  ) {
    super(message);
    this.name = "ApiError";
    this.kind = kind;
    this.status = status;
    this.cause = cause;
  }
}

export type QueryValue = string | number | boolean | null | undefined;

export async function request(
  url: string,
  options?: RequestInit,
): Promise<unknown> {
  const controller =
    typeof AbortController === "undefined" ? null : new AbortController();
  const timer = controller
    ? setTimeout(() => controller.abort(), 120_000)
    : undefined;
  try {
    const response = await fetch(url, {
      ...options,
      ...(controller ? { signal: controller.signal } : {}),
    });
    let data: unknown = null;
    try {
      data = await response.json();
    } catch (error) {
      const contentType = response.headers?.get("content-type") || "";
      if (response.ok && contentType.includes("json")) {
        throw new ApiError(
          "malformed",
          "Сервер вернул некорректный ответ",
          response.status,
          error,
        );
      }
    }
    if (!response.ok) {
      const message =
        isErrorPayload(data) && data.error ? data.error : response.statusText;
      throw new ApiError("http", message || "Ошибка запроса", response.status);
    }
    if (isErrorPayload(data) && data.error)
      throw new ApiError("http", data.error, response.status);
    return data;
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") {
      throw new ApiError(
        "timeout",
        "Превышено время ожидания ответа сервера",
        undefined,
        error,
      );
    }
    if (error instanceof ApiError) throw error;
    const cause = error instanceof Error ? error : undefined;
    console.error("API request failed", { url, error });
    throw new ApiError(
      "network",
      "Не удалось связаться с сервером",
      undefined,
      cause,
    );
  } finally {
    if (timer) clearTimeout(timer);
  }
}

export async function download(url: string, fallback: string): Promise<Blob> {
  try {
    const response = await fetch(url);
    if (!response.ok) {
      const data: unknown = await response.json().catch(() => null);
      const message =
        isErrorPayload(data) && data.error ? data.error : fallback;
      throw new ApiError("http", message, response.status);
    }
    return response.blob();
  } catch (error) {
    if (error instanceof ApiError) throw error;
    console.error("API download failed", { url, error });
    throw new ApiError("network", "Не удалось скачать файл", undefined, error);
  }
}

export async function requestText(
  url: string,
  fallback = "Не удалось получить данные от сервера",
): Promise<string> {
  try {
    const response = await fetch(url);
    const text = await response.text();
    if (!response.ok) {
      let message = response.statusText || "Ошибка запроса";
      try {
        const data: unknown = JSON.parse(text);
        if (isErrorPayload(data) && data.error) message = data.error;
      } catch {
        // Keep the safe HTTP fallback for non-JSON error bodies.
      }
      throw new ApiError("http", message, response.status);
    }
    return text;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    console.error("API text request failed", { url, error });
    throw new ApiError("network", fallback, undefined, error);
  }
}

export function isErrorPayload(value: unknown): value is ApiErrorPayload {
  return (
    typeof value === "object" &&
    value !== null &&
    "error" in value &&
    (value.error === undefined || typeof value.error === "string")
  );
}

export function record(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null
    ? (value as Record<string, unknown>)
    : {};
}

export function query(params: Record<string, QueryValue>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      search.set(key, String(value));
    }
  }
  return search.toString();
}

export const encode = encodeURIComponent;

export const esc = (value: unknown): string =>
  String(value).replace(
    /[&<>"']/g,
    (character) =>
      ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#039;",
      })[character] || character,
  );
