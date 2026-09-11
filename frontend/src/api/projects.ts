import type {
  PendingByFile,
  Entry,
  Overview,
  ProjectsResponse,
  FileTreeResponse,
  FilesResponse,
  EntriesPage,
  RowGroupsPage,
  Summary,
} from "./types";
import type { EntryFilters, FileFilters, PageRequest } from "./requestTypes";
import {
  mapEntriesPage,
  mapEntry,
  mapFileTree,
  mapFiles,
  mapOverview,
  mapProjects,
  mapRowsPage,
  mapSummary,
} from "./mappers";
import { encode, query, request, record } from "./transport";

export const projectsApi = {
  projects: (): Promise<ProjectsResponse> =>
    request<unknown>("/api/projects").then(mapProjects),
  createProject: (name: string, root: string) =>
    request<{ id: string; name: string }>("/api/projects", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ id: name, root }),
    }),
  overview: (projectId: string): Promise<Overview> =>
    request<unknown>(`/api/projects/${encode(projectId)}/overview`).then(
      mapOverview,
    ),
  pendingByFile: (projectId: string): Promise<PendingByFile> =>
    request<PendingByFile>(
      `/api/projects/${encode(projectId)}/translate/pending-by-file`,
    ),
  files: (
    projectId: string,
    filters?: FileFilters,
    page?: PageRequest,
  ): Promise<FilesResponse> =>
    request<unknown>(
      `/api/projects/${encode(projectId)}/files?${query({ q: filters?.q, hideReady: filters?.hideReady, readyOnly: filters?.readyOnly, offset: page?.offset, limit: page?.limit })}`,
    ).then(mapFiles),
  entries: (
    projectId: string,
    filters?: EntryFilters,
    page?: PageRequest,
  ): Promise<EntriesPage> =>
    request<unknown>(
      `/api/projects/${encode(projectId)}/entries?${query({ file: filters?.file, q: filters?.q, status: filters?.status, rowKey: filters?.rowKey, untranslated: filters?.untranslated, offset: page?.offset, limit: page?.limit })}`,
    ).then(mapEntriesPage),
  rowsPage: (
    projectId: string,
    filters?: { file?: string; offset?: number; limit?: number; q?: string },
  ): Promise<RowGroupsPage> =>
    request<unknown>(
      `/api/projects/${encode(projectId)}/rows?${query(filters || {})}`,
    ).then(mapRowsPage),
  rowsPosition: (
    projectId: string,
    filters?: { file?: string; rowIndex?: number; q?: string },
  ): Promise<number> =>
    request<number>(
      `/api/projects/${encode(projectId)}/rows/position?${query(filters || {})}`,
    ),
  rowsNext: (
    projectId: string,
    filters?: {
      file?: string;
      afterRow?: number;
      afterCol?: number;
      q?: string;
    },
  ): Promise<Entry> =>
    request<unknown>(
      `/api/projects/${encode(projectId)}/rows/next?${query(filters || {})}`,
    ).then(mapEntry),
  entry: (projectId: string, entryId: string): Promise<Entry> =>
    request<unknown>(
      `/api/projects/${encode(projectId)}/entries/${encode(entryId)}`,
    ).then(mapEntry),
  entryByCell: (projectId: string, cellId: string): Promise<Entry> =>
    request<unknown>(
      `/api/projects/${encode(projectId)}/entries/by-cell/${encode(cellId)}`,
    ).then(mapEntry),
  fileTree: (projectId: string): Promise<FileTreeResponse> =>
    request<unknown>(`/api/projects/${encode(projectId)}/files/tree`).then(
      mapFileTree,
    ),
  patchEntry: (
    projectId: string,
    key: string,
    translation: string,
    status: string,
  ) =>
    request<unknown>(
      `/api/projects/${encode(projectId)}/entries/${encode(key)}`,
      {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ translation, status }),
      },
    ).then((value) => {
      const data = record(value);
      return {
        ok: Boolean(data.ok),
        entry: mapEntry(data.entry),
        warnings: (data.warnings as string[] | undefined) || [],
        summary: mapSummary(data.summary),
      } as { ok: boolean; entry: Entry; warnings: string[]; summary?: Summary };
    }),
};
