import type { SourceFiles, SourcePreview } from "./types";
import { mapSourceFiles, mapSourcePreview } from "./mappers";
import { query, request } from "./transport";

export const sourceApi = {
  preview: (root: string, file: string): Promise<SourcePreview> =>
    request(`/api/source/preview?${query({ root, file })}`).then(
      mapSourcePreview,
    ),
  previewFull: (root: string, file: string): Promise<SourcePreview> =>
    request(`/api/source/preview?${query({ root, file, full: true })}`).then(
      mapSourcePreview,
    ),
  sourceFiles: (root: string): Promise<SourceFiles> =>
    request(`/api/source/files?${query({ root })}`).then(mapSourceFiles),
};
