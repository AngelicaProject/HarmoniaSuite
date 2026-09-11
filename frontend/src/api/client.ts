import type {
  AiModel,
  AiStatus,
  Backup,
  BackupList,
  DeltaExport,
  DeltaImportResult,
  DeltaRow,
  EntriesPage,
  Entry,
  ExportList,
  FileTreeResponse,
  FileStats,
  FilesResponse,
  Job,
  Overview,
  PackMeta,
  PackResponse,
  PendingByFile,
  ProjectsResponse,
  RowGroupsPage,
  SourceFiles,
  SourcePreview,
  SourceSettings,
  Summary,
  UpdateStatus,
} from "./types";

export interface EntryFilters {
  file?: string;
  q?: string;
  status?: string;
  rowKey?: string;
  untranslated?: boolean;
}

export interface FileFilters {
  q?: string;
  hideReady?: boolean;
  readyOnly?: boolean;
}

export interface PageRequest {
  offset?: number;
  limit?: number;
}

export interface JobRequest {
  action: string;
  projectId?: string;
  root?: string;
  output?: string;
  files?: string[];
  force?: boolean;
  model?: string;
  reasoning?: string;
}

export interface DeltaRequest {
  author?: string;
  filesAllowlist?: string[];
  maxStatus?: string;
  sourcesFp?: string;
  gameVersion?: string;
  rows?: DeltaRow[];
}

export interface AppVersion {
  version: string;
  buildTime?: string;
  commit?: string;
}

export interface ApiErrorPayload {
  error?: string;
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const controller =
    typeof AbortController === "undefined" ? null : new AbortController();
  const timer = controller
    ? setTimeout(() => controller.abort(), 120_000)
    : undefined;
  try {
    const response = await fetch(url, {
      ...options,
      ...(controller ? { signal: controller.signal } : {}),
    });
    const data: unknown = await response.json().catch(() => null);
    if (!response.ok) {
      const message =
        isErrorPayload(data) && data.error ? data.error : response.statusText;
      throw new Error(message || "Ошибка запроса");
    }
    if (isErrorPayload(data) && data.error) {
      throw new Error(data.error);
    }
    return data as T;
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new Error("Превышено время ожидания ответа сервера");
    }
    throw error;
  } finally {
    if (timer) clearTimeout(timer);
  }
}

async function download(url: string, fallback: string): Promise<Blob> {
  const response = await fetch(url);
  if (!response.ok) {
    const data: unknown = await response.json().catch(() => null);
    const message = isErrorPayload(data) && data.error ? data.error : fallback;
    throw new Error(message);
  }
  return response.blob();
}

function isErrorPayload(value: unknown): value is ApiErrorPayload {
  return typeof value === "object" && value !== null && "error" in value;
}

function record(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null
    ? (value as Record<string, unknown>)
    : {};
}

function mapSummary(value: unknown): Summary | undefined {
  const data = record(value);
  if (!Object.keys(data).length) return undefined;
  return {
    entries: Number(data.entries || 0),
    files: Number(data.files || 0),
    translated: Number(data.translated || 0),
    untranslated: Number(data.untranslated || 0),
    byStatus: (data.by_status as Record<string, number> | undefined) || {},
    outputDir:
      typeof data.output_dir === "string" ? data.output_dir : undefined,
  };
}

function mapEntry(value: unknown): Entry {
  const data = record(value);
  return {
    uuid: String(data.uuid || ""),
    id: String(data.id || data.cell_id || ""),
    source: String(data.source || ""),
    translation:
      typeof data.translation === "string" ? data.translation : undefined,
    status: String(data.status || ""),
    file: String(data.file || data.file_path || ""),
    rowKey: typeof data.row_key === "string" ? data.row_key : undefined,
    columnIndex: Number(data.column_index || 0),
    columnName:
      typeof data.column_name === "string" ? data.column_name : undefined,
    rowIndex: Number(data.row_index || 0),
    createdAt:
      typeof data.created_at === "string" ? data.created_at : undefined,
    updatedAt:
      typeof data.updated_at === "string" ? data.updated_at : undefined,
  };
}

function mapFileStats(value: unknown): FileStats {
  const data = record(value);
  return {
    path: String(data.path || data.file_path || ""),
    total: Number(data.total || 0),
    translated: Number(data.translated || 0),
    untranslated: Number(data.untranslated || 0),
  };
}

function mapOverview(value: unknown): Overview {
  const data = record(value);
  return {
    id: String(data.id || ""),
    name: String(data.name || ""),
    inputRoot:
      typeof data.input_root === "string" ? data.input_root : undefined,
    outputDir:
      typeof data.output_dir === "string" ? data.output_dir : undefined,
    sourceLocale:
      typeof data.source_locale === "string" ? data.source_locale : undefined,
    targetLocale:
      typeof data.target_locale === "string" ? data.target_locale : undefined,
    createdAt:
      typeof data.created_at === "string" ? data.created_at : undefined,
    updatedAt:
      typeof data.updated_at === "string" ? data.updated_at : undefined,
    summary: mapSummary(data.summary),
  };
}

function mapJob(value: unknown): Job {
  const data = record(value);
  return {
    id: String(data.id || ""),
    action: String(data.action || ""),
    status: String(data.status || ""),
    output: typeof data.output === "string" ? data.output : undefined,
    code: typeof data.code === "number" ? data.code : undefined,
  };
}

function mapProjects(value: unknown): ProjectsResponse {
  const data = record(value);
  const projects =
    (data.projects as Array<Record<string, unknown>> | undefined) || [];
  return {
    projects: projects.map((project) => ({
      id: String(project.id || ""),
      name: String(project.name || ""),
      exists: Boolean(project.exists),
      files: Number(project.files || 0),
      entries: Number(project.entries || 0),
      translated: Number(project.translated || 0),
      updatedAt:
        typeof project.updated_at === "string" ? project.updated_at : undefined,
      error: typeof project.error === "string" ? project.error : undefined,
    })),
  };
}

function mapEntriesPage(value: unknown): EntriesPage {
  const data = record(value);
  const entries = (data.entries as unknown[] | undefined) || [];
  return {
    entries: entries.map(mapEntry),
    total: Number(data.total || 0),
    offset: Number(data.offset || 0),
    limit: Number(data.limit || 0),
  };
}

function mapRowsPage(value: unknown): RowGroupsPage {
  const data = record(value);
  const groups =
    (data.groups as Array<Record<string, unknown>> | undefined) || [];
  return {
    groups: groups.map((group) => ({
      row: Number(group.row || 0),
      rowKey: typeof group.row_key === "string" ? group.row_key : undefined,
      cells: ((group.cells as unknown[]) || []).map(mapEntry),
      un: Number(group.un || 0),
    })),
    totalGroups: Number(data.total_groups || 0),
    offset: Number(data.offset || 0),
    limit: Number(data.limit || 0),
  };
}

function mapFiles(value: unknown): FilesResponse {
  const data = record(value);
  return {
    files: ((data.files as unknown[]) || []).map(mapFileStats),
    total: Number(data.total || 0),
    offset: Number(data.offset || 0),
    limit: Number(data.limit || 0),
    needFiles: Number(data.need_files || 0),
    readyFiles: Number(data.ready_files || 0),
    summary: mapSummary(data.summary),
  };
}

function mapFileTree(value: unknown): FileTreeResponse {
  const data = record(value);
  return {
    files: ((data.files as unknown[]) || []).map(mapFileStats),
    total: Number(data.total || 0),
  };
}

function mapDeltaSide(value: unknown): {
  translation?: string;
  status: string;
} {
  const data = record(value);
  return {
    translation:
      typeof data.translation === "string" ? data.translation : undefined,
    status: String(data.status || ""),
  };
}

function mapDeltaResult(value: unknown): DeltaImportResult {
  const data = record(value);
  return {
    applied: Number(data.applied || 0),
    noop: Number(data.noop || 0),
    skipped: (
      (data.skipped as Array<Record<string, unknown>> | undefined) || []
    ).map((item) => ({
      cellId: typeof item.cell_id === "string" ? item.cell_id : undefined,
      filePath: typeof item.file_path === "string" ? item.file_path : undefined,
      reason: String(item.reason || ""),
      detail: typeof item.detail === "string" ? item.detail : undefined,
    })),
    conflicts: (
      (data.conflicts as Array<Record<string, unknown>> | undefined) || []
    ).map((item) => ({
      cellId: String(item.cell_id || ""),
      filePath: String(item.file_path || ""),
      ours: mapDeltaSide(item.ours),
      theirs: mapDeltaSide(item.theirs),
    })),
    warnings: (
      (data.warnings as Array<Record<string, unknown>> | undefined) || []
    ).map((item) => ({
      cellId: String(item.cell_id || ""),
      filePath: String(item.file_path || ""),
      warnings: ((item.warnings as unknown[]) || []).map(String),
    })),
    summary: mapSummary(data.summary),
  };
}

function query(
  params: Record<string, string | number | boolean | undefined>,
): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "" && value !== false && value !== 0) {
      search.set(key, String(value));
    }
  }
  return search.toString();
}

const encode = encodeURIComponent;

function mapBackupList(data: Record<string, unknown>): BackupList {
  return {
    backups: (
      (data.backups as Array<Record<string, unknown>> | undefined) || []
    ).map((backup) => ({
      name: String(backup.name || ""),
      size: Number(backup.size || 0),
      createdAt: String(backup.created_at || ""),
    })),
    retention: Number(data.retention || 0),
    autoIntervalMinutes: Number(data.auto_interval_minutes || 0),
    usedBytes: Number(data.used_bytes || 0),
    estimatedBytes: Number(data.estimated_bytes || 0),
  };
}

function mapUpdateStatus(data: Record<string, unknown>): UpdateStatus {
  return {
    version: String(data.version || ""),
    supported: Boolean(data.supported),
    mode: typeof data.mode === "string" ? data.mode : undefined,
    needsToolchain: Boolean(data.needs_toolchain),
    currentSha:
      typeof data.current_sha === "string" ? data.current_sha : undefined,
    latestSha:
      typeof data.latest_sha === "string" ? data.latest_sha : undefined,
    behindBy: Number(data.behind_by || 0),
    subjects: Array.isArray(data.subjects) ? data.subjects.map(String) : [],
    updateAvailable: Boolean(data.update_available),
    state: String(data.state || "unavailable"),
    reason: typeof data.reason === "string" ? data.reason : undefined,
  };
}

function mapSourcePreview(data: Record<string, unknown>): SourcePreview {
  return {
    file: String(data.file || ""),
    rows: Number(data.rows || 0),
    stringColumns: (data.string_columns as number[] | undefined) || [],
    translatable: Number(data.translatable || 0),
    preview: (data.preview as string[][] | undefined) || [],
    head: (data.head as string[][] | undefined) || [],
    data: (data.data as string[][] | undefined) || [],
    truncated: Boolean(data.truncated),
  };
}

function mapPackMeta(data: Record<string, unknown>): PackMeta {
  return {
    packId: typeof data.pack_id === "string" ? data.pack_id : undefined,
    translationVersion:
      typeof data.translation_version === "string"
        ? data.translation_version
        : undefined,
    gameVersion:
      typeof data.game_version === "string" ? data.game_version : undefined,
    compatibleGameVersions:
      (data.compatible_game_versions as string[] | undefined) || [],
    vendorId: typeof data.vendor_id === "string" ? data.vendor_id : undefined,
    vendorName:
      typeof data.vendor_name === "string" ? data.vendor_name : undefined,
    vendorUrl:
      typeof data.vendor_url === "string" ? data.vendor_url : undefined,
    vendorContact:
      typeof data.vendor_contact === "string" ? data.vendor_contact : undefined,
    authors: (data.authors as PackMeta["authors"]) || [],
    languages: (data.languages as string[] | undefined) || [],
    title: typeof data.title === "string" ? data.title : undefined,
    description:
      typeof data.description === "string" ? data.description : undefined,
    changelog: typeof data.changelog === "string" ? data.changelog : undefined,
    homepage: typeof data.homepage === "string" ? data.homepage : undefined,
    license: typeof data.license === "string" ? data.license : undefined,
    minPluginVersion:
      typeof data.min_plugin_version === "string"
        ? data.min_plugin_version
        : undefined,
  };
}

function mapPackResponse(data: Record<string, unknown>): PackResponse {
  return {
    pack: mapPackMeta((data.pack as Record<string, unknown> | undefined) || {}),
    manifest: data.manifest as Record<string, unknown> | undefined,
    errors: (data.errors as string[] | undefined) || [],
  };
}

function packPayload(pack: PackMeta): Record<string, unknown> {
  return {
    pack_id: pack.packId,
    translation_version: pack.translationVersion,
    game_version: pack.gameVersion,
    compatible_game_versions: pack.compatibleGameVersions,
    vendor_id: pack.vendorId,
    vendor_name: pack.vendorName,
    vendor_url: pack.vendorUrl,
    vendor_contact: pack.vendorContact,
    authors: pack.authors,
    languages: pack.languages,
    title: pack.title,
    description: pack.description,
    changelog: pack.changelog,
    homepage: pack.homepage,
    license: pack.license,
    min_plugin_version: pack.minPluginVersion,
  };
}

function mapDeltaExport(data: Record<string, unknown>): DeltaExport {
  const rows = (data.rows as Array<Record<string, unknown>> | undefined) || [];
  return {
    header: {
      sourcesFp:
        typeof record(data.header).sources_fp === "string"
          ? (record(data.header).sources_fp as string)
          : undefined,
      gameVersion:
        typeof record(data.header).game_version === "string"
          ? (record(data.header).game_version as string)
          : undefined,
      author:
        typeof record(data.header).author === "string"
          ? (record(data.header).author as string)
          : undefined,
      exportedAt:
        typeof record(data.header).exported_at === "string"
          ? (record(data.header).exported_at as string)
          : undefined,
    },
    rows: rows.map((row) => ({
      cellId: String(row.cell_id || ""),
      filePath: String(row.file_path || ""),
      source: String(row.source || ""),
      translation:
        typeof row.translation === "string" ? row.translation : undefined,
      status: String(row.status || ""),
    })),
    nextSinceUpdatedAt:
      typeof data.next_since_updated_at === "string"
        ? data.next_since_updated_at
        : undefined,
    nextSinceCellId:
      typeof data.next_since_cell_id === "string"
        ? data.next_since_cell_id
        : undefined,
    complete: Boolean(data.complete),
  };
}

function deltaPayload(body: DeltaRequest): Record<string, unknown> {
  return {
    author: body.author,
    files_allowlist: body.filesAllowlist,
    max_status: body.maxStatus,
    sources_fp: body.sourcesFp,
    game_version: body.gameVersion,
    rows: (body.rows || []).map((row) => ({
      cell_id: row.cellId,
      file_path: row.filePath,
      source: row.source,
      translation: row.translation,
      status: row.status,
    })),
  };
}

export const api = {
  projects: () => request<unknown>("/api/projects").then(mapProjects),
  createProject: (name: string, root: string) =>
    request<{ id: string; name: string }>("/api/projects", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ id: name, root }),
    }),
  overview: (projectId: string) =>
    request<unknown>(`/api/projects/${encode(projectId)}/overview`).then(
      mapOverview,
    ),
  pendingByFile: (projectId: string) =>
    request<PendingByFile>(
      `/api/projects/${encode(projectId)}/translate/pending-by-file`,
    ),
  files: (projectId: string, filters?: FileFilters, page?: PageRequest) =>
    request<unknown>(
      `/api/projects/${encode(projectId)}/files?${query({ q: filters?.q, hideReady: filters?.hideReady, readyOnly: filters?.readyOnly, offset: page?.offset, limit: page?.limit })}`,
    ).then(mapFiles),
  entries: (projectId: string, filters?: EntryFilters, page?: PageRequest) =>
    request<unknown>(
      `/api/projects/${encode(projectId)}/entries?${query({ file: filters?.file, q: filters?.q, status: filters?.status, rowKey: filters?.rowKey, untranslated: filters?.untranslated, offset: page?.offset, limit: page?.limit })}`,
    ).then(mapEntriesPage),
  rowsPage: (
    projectId: string,
    filters?: { file?: string; offset?: number; limit?: number; q?: string },
  ) =>
    request<unknown>(
      `/api/projects/${encode(projectId)}/rows?${query(filters || {})}`,
    ).then(mapRowsPage),
  rowsPosition: (
    projectId: string,
    filters?: { file?: string; rowIndex?: number; q?: string },
  ) =>
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
  ) =>
    request<unknown>(
      `/api/projects/${encode(projectId)}/rows/next?${query(filters || {})}`,
    ).then(mapEntry),
  entry: (projectId: string, entryId: string) =>
    request<unknown>(
      `/api/projects/${encode(projectId)}/entries/${encode(entryId)}`,
    ).then(mapEntry),
  entryByCell: (projectId: string, cellId: string) =>
    request<unknown>(
      `/api/projects/${encode(projectId)}/entries/by-cell/${encode(cellId)}`,
    ).then(mapEntry),
  fileTree: (projectId: string) =>
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
      };
    }),
  startJob: (body: JobRequest) =>
    request<unknown>("/api/jobs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        action: body.action,
        project_id: body.projectId,
        root: body.root,
        output: body.output,
        files: body.files,
        force: body.force,
        model: body.model,
        reasoning: body.reasoning,
      }),
    }).then(mapJob),
  jobGet: (jobId: string) =>
    request<unknown>(`/api/jobs/${encode(jobId)}`).then(mapJob),
  jobCancel: (jobId: string) =>
    request<unknown>(`/api/jobs/${encode(jobId)}`, { method: "DELETE" }).then(
      mapJob,
    ),
  status: () => request<AiStatus>("/api/status"),
  version: () => request<AppVersion>("/api/version"),
  updateStatus: () =>
    request<Record<string, unknown>>("/api/update/status").then(
      mapUpdateStatus,
    ),
  runUpdate: () =>
    request<unknown>("/api/update", { method: "POST" }).then(mapJob),
  logTail: (tail = 500) =>
    fetch(`/api/log?tail=${encode(tail)}`).then(async (response) => {
      const text = await response.text();
      if (!response.ok) {
        let message = text;
        try {
          const data: unknown = JSON.parse(text);
          if (isErrorPayload(data) && data.error) message = data.error;
        } catch (error) {
          void error;
        }
        throw new Error(
          message || response.statusText || "Не удалось получить журнал",
        );
      }
      return text;
    }),
  aiSettings: () => request<AiStatus>("/api/settings/ai"),
  saveAiSettings: (body: {
    provider?: string;
    geminiKey?: string;
    openrouterKey?: string;
    openrouterModel?: string;
    openrouterReasoning?: string;
  }) =>
    request<AiStatus>("/api/settings/ai", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        provider: body.provider,
        gemini_key: body.geminiKey,
        openrouter_key: body.openrouterKey,
        openrouter_model: body.openrouterModel,
        openrouter_reasoning: body.openrouterReasoning,
      }),
    }),
  settings: () => request<SourceSettings>("/api/settings"),
  saveSettings: (body: { gamePath?: string }) =>
    request<SourceSettings>("/api/settings", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ game_path: body.gamePath }),
    }),
  checkAiKey: (body: { provider?: string; key?: string }) =>
    request<Record<string, unknown>>("/api/settings/ai/check", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ provider: body.provider, key: body.key }),
    }),
  aiModels: () => request<AiModel[]>("/api/settings/ai/models"),
  detectSettings: () => request<{ gamePath?: string }>("/api/settings/detect"),
  backups: () =>
    request<Record<string, unknown>>("/api/backup").then(mapBackupList),
  createBackup: () => request<Backup>("/api/backup", { method: "POST" }),
  saveBackupSettings: (retention?: number, autoIntervalMinutes?: number) =>
    request<Record<string, unknown>>("/api/backup/settings", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        retention,
        auto_interval_minutes: autoIntervalMinutes,
      }),
    }).then(mapBackupList),
  deleteBackup: (name: string) =>
    request<void>(`/api/backup/${encode(name)}`, { method: "DELETE" }),
  openBackupFolder: () =>
    request<void>("/api/backup/open-folder", { method: "POST" }),
  backupDownloadUrl: (name: string) => `/api/backup/${encode(name)}`,
  preview: (root: string, file: string) =>
    request<unknown>(`/api/source/preview?${query({ root, file })}`).then(
      mapSourcePreview,
    ),
  previewFull: (root: string, file: string) =>
    request<unknown>(
      `/api/source/preview?${query({ root, file, full: true })}`,
    ).then(mapSourcePreview),
  sourceFiles: (root: string) =>
    request<SourceFiles>(`/api/source/files?${query({ root })}`),
  getPack: (projectId: string) =>
    request<Record<string, unknown>>(
      `/api/projects/${encode(projectId)}/pack`,
    ).then(mapPackResponse),
  savePack: (projectId: string, pack: PackMeta) =>
    request<Record<string, unknown>>(
      `/api/projects/${encode(projectId)}/pack`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(packPayload(pack)),
      },
    ).then(mapPackResponse),
  exportCsvUrl: (projectId: string, file: string) =>
    `/api/projects/${encode(projectId)}/exports/csv?file=${encode(file)}`,
  exportZipUrl: (projectId: string) =>
    `/api/projects/${encode(projectId)}/exports/zip`,
  exportManifestUrl: (projectId: string) =>
    `/api/projects/${encode(projectId)}/exports/manifest`,
  exportList: (projectId: string) =>
    request<ExportList>(`/api/projects/${encode(projectId)}/exports`),
  downloadExportCsv: (projectId: string, file: string) =>
    download(
      `/api/projects/${encode(projectId)}/exports/csv?file=${encode(file)}`,
      "Файл не собран — нажмите «Собрать пак и скачать»",
    ),
  downloadExportZip: (projectId: string) =>
    download(
      `/api/projects/${encode(projectId)}/exports/zip`,
      "Архив не готов",
    ),
  downloadManifest: (projectId: string) =>
    download(
      `/api/projects/${encode(projectId)}/exports/manifest`,
      "Манифест не готов — заполните вкладку «Пак»",
    ),
  deltaExport: (
    projectId: string,
    filters?: {
      sinceUpdatedAt?: string;
      sinceCellId?: string;
      files?: string;
      limit?: number;
      author?: string;
    },
  ) =>
    request<Record<string, unknown>>(
      `/api/projects/${encode(projectId)}/delta?${query({ sinceUpdatedAt: filters?.sinceUpdatedAt, sinceCellId: filters?.sinceCellId, files: filters?.files, limit: filters?.limit, author: filters?.author })}`,
    ).then(mapDeltaExport),
  deltaPreview: (projectId: string, body: DeltaRequest) =>
    request<unknown>(`/api/projects/${encode(projectId)}/delta/preview`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(deltaPayload(body)),
    }).then(mapDeltaResult),
  deltaImport: (projectId: string, body: DeltaRequest) =>
    request<unknown>(`/api/projects/${encode(projectId)}/delta/import`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(deltaPayload(body)),
    }).then(mapDeltaResult),
};

export const esc = (value: unknown): string =>
  String(value).replace(
    /[&<>"']/g,
    (character) =>
      ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#039;",
      })[character] || character,
  );
