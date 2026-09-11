import type { ExportList } from "./types";
import { mapExportList } from "./mappers";
import { download, encode, request } from "./transport";

export const exportsApi = {
  exportCsvUrl: (projectId: string, file: string): string =>
    `/api/projects/${encode(projectId)}/exports/csv?file=${encode(file)}`,
  exportZipUrl: (projectId: string): string =>
    `/api/projects/${encode(projectId)}/exports/zip`,
  exportManifestUrl: (projectId: string): string =>
    `/api/projects/${encode(projectId)}/exports/manifest`,
  exportList: (projectId: string): Promise<ExportList> =>
    request(`/api/projects/${encode(projectId)}/exports`).then(mapExportList),
  downloadExportCsv: (projectId: string, file: string): Promise<Blob> =>
    download(
      `/api/projects/${encode(projectId)}/exports/csv?file=${encode(file)}`,
      "Файл не собран — нажмите «Собрать пак и скачать»",
    ),
  downloadExportZip: (projectId: string): Promise<Blob> =>
    download(
      `/api/projects/${encode(projectId)}/exports/zip`,
      "Архив не готов",
    ),
  downloadManifest: (projectId: string): Promise<Blob> =>
    download(
      `/api/projects/${encode(projectId)}/exports/manifest`,
      "Манифест не готов — заполните вкладку «Пак»",
    ),
};
