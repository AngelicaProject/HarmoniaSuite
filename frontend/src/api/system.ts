import type { AiStatus, UpdateStatus } from "./types";
import type { AppVersion as VersionDto } from "./requestTypes";
import { mapJob, mapUpdateStatus } from "./mappers";
import { encode, isErrorPayload, request } from "./transport";

export const systemApi = {
  status: (): Promise<AiStatus> => request<AiStatus>("/api/status"),
  version: (): Promise<VersionDto> => request<VersionDto>("/api/version"),
  updateStatus: (): Promise<UpdateStatus> =>
    request<Record<string, unknown>>("/api/update/status").then(
      mapUpdateStatus,
    ),
  runUpdate: () =>
    request<unknown>("/api/update", { method: "POST" }).then(mapJob),
  logTail: (tail = 500): Promise<string> =>
    fetch(`/api/log?tail=${encode(tail)}`).then(async (response) => {
      const text = await response.text();
      if (!response.ok) {
        let message = text;
        try {
          const data: unknown = JSON.parse(text);
          if (isErrorPayload(data) && data.error) message = data.error;
        } catch {
          /* keep response text */
        }
        throw new Error(
          message || response.statusText || "Не удалось получить журнал",
        );
      }
      return text;
    }),
};
