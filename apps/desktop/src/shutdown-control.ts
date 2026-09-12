export type DesktopSession = {
  sessionId: string;
  pid: number;
  startedAtMs: number;
};

export type ShutdownRequest = DesktopSession & {
  requestId: string;
};

export type ShutdownAcknowledgement = ShutdownRequest & {
  acknowledgedAtMs: number;
};

export function isMatchingShutdownRequest(
  value: unknown,
  session: DesktopSession,
): value is ShutdownRequest {
  if (!isRecord(value)) return false;
  return typeof value.requestId === "string"
    && value.requestId.length > 0
    && value.sessionId === session.sessionId
    && value.pid === session.pid
    && value.startedAtMs === session.startedAtMs;
}

export function shutdownAcknowledgement(
  request: ShutdownRequest,
  acknowledgedAtMs = Date.now(),
): ShutdownAcknowledgement {
  return { ...request, acknowledgedAtMs };
}

export function isMatchingShutdownAcknowledgement(
  value: unknown,
  request: ShutdownRequest,
): value is ShutdownAcknowledgement {
  return isMatchingShutdownRequest(value, request)
    && (value as Record<string, unknown>).requestId === request.requestId
    && typeof (value as Record<string, unknown>).acknowledgedAtMs === "number";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
