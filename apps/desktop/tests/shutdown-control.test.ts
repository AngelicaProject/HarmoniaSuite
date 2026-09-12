import { describe, expect, it } from "vitest";

import {
  isMatchingShutdownRequest,
  isMatchingShutdownAcknowledgement,
  shutdownAcknowledgement,
  type DesktopSession,
} from "../src/shutdown-control.js";

const sessionA: DesktopSession = {
  sessionId: "session-a",
  pid: 101,
  startedAtMs: 1000,
};

describe("desktop shutdown control", () => {
  it("accepts a request only for the current session and creates a matching ack", () => {
    const request = { ...sessionA, requestId: "request-a" };
    expect(isMatchingShutdownRequest(request, sessionA)).toBe(true);
    expect(shutdownAcknowledgement(request, 2000)).toEqual({
      ...request,
      acknowledgedAtMs: 2000,
    });
  });

  it("ignores stale requests and mismatched request/session identities", () => {
    const request = { ...sessionA, requestId: "request-a" };
    const sessionB = { ...sessionA, sessionId: "session-b", startedAtMs: 3000 };
    expect(isMatchingShutdownRequest(request, sessionB)).toBe(false);
    expect(isMatchingShutdownRequest({ ...request, requestId: "" }, sessionA)).toBe(false);
    expect(isMatchingShutdownRequest({ ...request, pid: 202 }, sessionA)).toBe(false);
  });

  it("does not let an old ack confirm a new request", () => {
    const oldRequest = { ...sessionA, requestId: "request-a" };
    const newRequest = { ...sessionA, requestId: "request-b" };
    const oldAck = shutdownAcknowledgement(oldRequest, 2000);
    expect(isMatchingShutdownAcknowledgement(oldAck, oldRequest)).toBe(true);
    expect(isMatchingShutdownAcknowledgement(oldAck, newRequest)).toBe(false);
  });
});
