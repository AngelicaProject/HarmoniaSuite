import type { DeltaExport, DeltaImportResult } from "./types";
import type { DeltaRequest } from "./requestTypes";
import { deltaPayload, mapDeltaExport, mapDeltaResult } from "./mappers";
import { encode, query, request } from "./transport";

export const deltaApi = {
  deltaExport: (
    projectId: string,
    filters?: {
      sinceUpdatedAt?: string;
      sinceCellId?: string;
      files?: string;
      limit?: number;
      author?: string;
    },
  ): Promise<DeltaExport> =>
    request<Record<string, unknown>>(
      `/api/projects/${encode(projectId)}/delta?${query({ sinceUpdatedAt: filters?.sinceUpdatedAt, sinceCellId: filters?.sinceCellId, files: filters?.files, limit: filters?.limit, author: filters?.author })}`,
    ).then(mapDeltaExport),
  deltaPreview: (
    projectId: string,
    body: DeltaRequest,
  ): Promise<DeltaImportResult> =>
    request<unknown>(`/api/projects/${encode(projectId)}/delta/preview`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(deltaPayload(body)),
    }).then(mapDeltaResult),
  deltaImport: (
    projectId: string,
    body: DeltaRequest,
  ): Promise<DeltaImportResult> =>
    request<unknown>(`/api/projects/${encode(projectId)}/delta/import`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(deltaPayload(body)),
    }).then(mapDeltaResult),
};
