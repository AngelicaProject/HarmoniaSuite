import { afterEach, describe, expect, it, vi, type MockInstance } from "vitest";
import { api, query } from "./client";
import { ApiError, request } from "./transport";

afterEach(() => {
  vi.unstubAllGlobals();
});

function stubJsonFetch(body: unknown = {}): MockInstance {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    statusText: "OK",
    json: async () => body,
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function firstUrl(fetchMock: MockInstance): URL {
  return new URL(String(fetchMock.mock.calls[0]?.[0]), "http://localhost");
}

describe("query serialization", () => {
  it("keeps zero-valued pagination and position parameters", () => {
    const url = new URL(
      `/api/test?${query({ rowIndex: 0, afterRow: 0, afterCol: 0, offset: 0 })}`,
      "http://localhost",
    );

    expect(url.searchParams.get("rowIndex")).toBe("0");
    expect(url.searchParams.get("afterRow")).toBe("0");
    expect(url.searchParams.get("afterCol")).toBe("0");
    expect(url.searchParams.get("offset")).toBe("0");
  });

  it("keeps explicit false and omits only absent or empty values", () => {
    expect(
      query({ enabled: false, missing: undefined, nullable: null, empty: "" }),
    ).toBe("enabled=false");
  });
});

describe("API method query URLs", () => {
  it("sends rowIndex=0", async () => {
    const fetchMock = stubJsonFetch();
    await api.rowsPosition("project", { rowIndex: 0 });

    expect(firstUrl(fetchMock).searchParams.get("rowIndex")).toBe("0");
  });

  it("sends afterRow=0 and afterCol=0", async () => {
    const fetchMock = stubJsonFetch();
    await api.rowsNext("project", { afterRow: 0, afterCol: 0 });

    const url = firstUrl(fetchMock);
    expect(url.searchParams.get("afterRow")).toBe("0");
    expect(url.searchParams.get("afterCol")).toBe("0");
  });

  it("sends offset=0", async () => {
    const fetchMock = stubJsonFetch();
    await api.files("project", undefined, { offset: 0 });

    expect(firstUrl(fetchMock).searchParams.get("offset")).toBe("0");
  });
});

describe("API response mapping", () => {
  it("maps backend settings into the frontend camelCase model", async () => {
    stubJsonFetch({
      game_path: "C:/game",
      game_valid: true,
      game_version: "7.0",
      active_root: "C:/game/data",
      ready: true,
      configured: true,
    });

    await expect(api.settings()).resolves.toEqual({
      gamePath: "C:/game",
      gameValid: true,
      gameVersion: "7.0",
      activeRoot: "C:/game/data",
      ready: true,
      configured: true,
    });
  });

  it("maps a saved entry response before it reaches UI state", async () => {
    stubJsonFetch({
      ok: true,
      entry: {
        uuid: "uuid-1",
        id: "entry-1",
        source: "Hello",
        translation: "Привет",
        status: "human_reviewed",
        file: "dialogue.csv",
        column_index: 0,
        row_index: 1,
      },
      warnings: [],
      summary: { entries: 1, files: 1, translated: 1, untranslated: 0 },
    });

    await expect(
      api.patchEntry("project-1", "uuid-1", "Привет", "human_reviewed"),
    ).resolves.toMatchObject({
      ok: true,
      entry: {
        id: "entry-1",
        translation: "Привет",
        file: "dialogue.csv",
        columnIndex: 0,
        rowIndex: 1,
      },
    });
  });
});

describe("transport errors", () => {
  it("preserves structured backend errors without exposing technical details", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 422,
        statusText: "Unprocessable Entity",
        headers: new Headers({ "content-type": "application/json" }),
        json: async () => ({ error: "Перевод содержит недопустимый тег" }),
      }),
    );

    const error = await request("/api/test").catch((value: unknown) => value);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      kind: "http",
      status: 422,
      message: "Перевод содержит недопустимый тег",
    });
  });

  it("classifies network failures separately from backend failures", async () => {
    const errorLog = vi.spyOn(console, "error").mockImplementation(() => {});
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));

    const error = await request("/api/test").catch((value: unknown) => value);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      kind: "network",
      message: "Не удалось связаться с сервером",
    });
    expect(errorLog).toHaveBeenCalled();
    errorLog.mockRestore();
  });

  it("rejects a successful response with malformed JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        statusText: "OK",
        headers: new Headers({ "content-type": "application/json" }),
        json: async () => {
          throw new SyntaxError("unexpected token");
        },
      }),
    );

    const error = await request("/api/test").catch((value: unknown) => value);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      kind: "malformed",
      message: "Сервер вернул некорректный ответ",
    });
  });
});
