import type { AiStatus, UpdateStatus } from "./types";
import type { AppVersion as VersionDto } from "./requestTypes";
import { mapAiStatus, mapJob, mapUpdateStatus, mapVersion } from "./mappers";
import { encode, request, requestText } from "./transport";

export const systemApi = {
  status: (): Promise<AiStatus> => request("/api/status").then(mapAiStatus),
  version: (): Promise<VersionDto> => request("/api/version").then(mapVersion),
  updateStatus: (): Promise<UpdateStatus> =>
    request("/api/update/status").then(mapUpdateStatus),
  runUpdate: () => request("/api/update", { method: "POST" }).then(mapJob),
  logTail: (tail = 500): Promise<string> =>
    requestText(`/api/log?tail=${encode(tail)}`, "Не удалось получить журнал"),
};
