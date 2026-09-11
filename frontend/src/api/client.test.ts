import { afterEach, describe, expect, it, vi, type MockInstance } from "vitest";
import { api, query } from "./client";

afterEach(() => {
  vi.unstubAllGlobals();
});

function stubJsonFetch(): MockInstance {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    statusText: "OK",
    json: async () => ({}),
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
