import type { PackMeta, PackResponse } from "./types";
import { mapPackResponse, packPayload } from "./mappers";
import { encode, request } from "./transport";

export const packApi = {
  getPack: (projectId: string): Promise<PackResponse> =>
    request<Record<string, unknown>>(
      `/api/projects/${encode(projectId)}/pack`,
    ).then(mapPackResponse),
  savePack: (projectId: string, pack: PackMeta): Promise<PackResponse> =>
    request<Record<string, unknown>>(
      `/api/projects/${encode(projectId)}/pack`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(packPayload(pack)),
      },
    ).then(mapPackResponse),
};
