export interface ApiErrorPayload {
  error?: string;
}

export type QueryValue = string | number | boolean | null | undefined;

export async function request<T>(
  url: string,
  options?: RequestInit,
): Promise<T> {
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
    const data: unknown = await response.json().catch(() => null);
    if (!response.ok) {
      const message =
        isErrorPayload(data) && data.error ? data.error : response.statusText;
      throw new Error(message || "Ошибка запроса");
    }
    if (isErrorPayload(data) && data.error) throw new Error(data.error);
    return data as T;
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new Error("Превышено время ожидания ответа сервера");
    }
    throw error;
  } finally {
    if (timer) clearTimeout(timer);
  }
}

export async function download(url: string, fallback: string): Promise<Blob> {
  const response = await fetch(url);
  if (!response.ok) {
    const data: unknown = await response.json().catch(() => null);
    const message = isErrorPayload(data) && data.error ? data.error : fallback;
    throw new Error(message);
  }
  return response.blob();
}

export function isErrorPayload(value: unknown): value is ApiErrorPayload {
  return typeof value === "object" && value !== null && "error" in value;
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
