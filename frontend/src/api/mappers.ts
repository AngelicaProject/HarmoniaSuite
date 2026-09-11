import type {
  AiModel,
  AiStatus,
  Backup,
  BackupList,
  DeltaExport,
  DeltaImportResult,
  Entry,
  EntriesPage,
  FileStats,
  FileTreeResponse,
  FilesResponse,
  Job,
  Overview,
  PackMeta,
  PackResponse,
  ProjectsResponse,
  PendingByFile,
  ExportList,
  RowGroupsPage,
  SourcePreview,
  SourceFiles,
  SourceSettings,
  Summary,
  UpdateStatus,
} from "./types";
import type { AppVersion, DeltaRequest } from "./requestTypes";
import { record } from "./transport";

export function mapSummary(value: unknown): Summary | undefined {
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

export function mapCreateProject(value: unknown): { id: string; name: string } {
  const data = record(value);
  return { id: String(data.id || ""), name: String(data.name || "") };
}

export function mapPendingByFile(value: unknown): PendingByFile {
  const files = record(record(value).files);
  return {
    files: Object.fromEntries(
      Object.entries(files).map(([file, count]) => [file, Number(count || 0)]),
    ),
  };
}

export function mapSourceFiles(value: unknown): SourceFiles {
  const data = record(value);
  return {
    files: Array.isArray(data.files) ? data.files.map(String) : [],
    root: String(data.root || ""),
  };
}

export function mapSourceSettings(value: unknown): SourceSettings {
  const data = record(value);
  return {
    gamePath: typeof data.game_path === "string" ? data.game_path : undefined,
    gameValid: Boolean(data.game_valid),
    gameVersion:
      typeof data.game_version === "string" ? data.game_version : undefined,
    activeRoot:
      typeof data.active_root === "string" ? data.active_root : undefined,
    ready: Boolean(data.ready),
    configured: Boolean(data.configured),
  };
}

export function mapAiStatus(value: unknown): AiStatus {
  const data = record(value);
  const stringArray = (field: string): string[] =>
    Array.isArray(data[field]) ? data[field].map(String) : [];
  return {
    provider: typeof data.provider === "string" ? data.provider : undefined,
    providers: stringArray("providers"),
    geminiKeySet: Boolean(data.gemini_key_set),
    geminiKeyHint:
      typeof data.gemini_key_hint === "string"
        ? data.gemini_key_hint
        : undefined,
    geminiKeySource:
      typeof data.gemini_key_source === "string"
        ? data.gemini_key_source
        : undefined,
    geminiConfigured: Boolean(data.gemini_configured),
    geminiModel:
      typeof data.gemini_model === "string" ? data.gemini_model : undefined,
    geminiModels: stringArray("gemini_models"),
    openrouterKeySet: Boolean(data.openrouter_key_set),
    openrouterKeyHint:
      typeof data.openrouter_key_hint === "string"
        ? data.openrouter_key_hint
        : undefined,
    openrouterKeySource:
      typeof data.openrouter_key_source === "string"
        ? data.openrouter_key_source
        : undefined,
    openrouterConfigured: Boolean(data.openrouter_configured),
    openrouterModel:
      typeof data.openrouter_model === "string"
        ? data.openrouter_model
        : undefined,
    openrouterReasoning:
      typeof data.openrouter_reasoning === "string"
        ? data.openrouter_reasoning
        : undefined,
  };
}

export function mapAiModels(value: unknown): AiModel[] {
  return (Array.isArray(value) ? value : []).map((item) => {
    const data = record(item);
    return {
      id: String(data.id || ""),
      name: typeof data.name === "string" ? data.name : undefined,
      context_length:
        typeof data.context_length === "string" ||
        typeof data.context_length === "number"
          ? data.context_length
          : undefined,
    };
  });
}

export function mapDetectedSettings(value: unknown): { gamePath?: string } {
  const data = record(value);
  return {
    gamePath: typeof data.game_path === "string" ? data.game_path : undefined,
  };
}

export function mapBackup(value: unknown): Backup {
  const data = record(value);
  return {
    name: String(data.name || ""),
    size: Number(data.size || 0),
    createdAt: String(data.created_at || ""),
  };
}

export function mapExportList(value: unknown): ExportList {
  const data = record(value);
  return {
    files: (Array.isArray(data.files) ? data.files : []).map((item) => {
      const file = record(item);
      return { name: String(file.name || ""), size: Number(file.size || 0) };
    }),
    manifest: Boolean(data.manifest),
  };
}

export function mapVersion(value: unknown): AppVersion {
  const data = record(value);
  return {
    version: String(data.version || ""),
    buildTime:
      typeof data.build_time === "string" ? data.build_time : undefined,
    commit: typeof data.commit === "string" ? data.commit : undefined,
  };
}

export function mapNumber(value: unknown): number {
  return typeof value === "number" ? value : Number(value || 0);
}

export function mapEntry(value: unknown): Entry {
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

export function mapFileStats(value: unknown): FileStats {
  const data = record(value);
  return {
    path: String(data.path || data.file_path || ""),
    total: Number(data.total || 0),
    translated: Number(data.translated || 0),
    untranslated: Number(data.untranslated || 0),
  };
}

export function mapOverview(value: unknown): Overview {
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

export function mapJob(value: unknown): Job {
  const data = record(value);
  return {
    id: String(data.id || ""),
    action: String(data.action || ""),
    status: String(data.status || ""),
    output: typeof data.output === "string" ? data.output : undefined,
    code: typeof data.code === "number" ? data.code : undefined,
  };
}

export function mapProjects(value: unknown): ProjectsResponse {
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

export function mapEntriesPage(value: unknown): EntriesPage {
  const data = record(value);
  return {
    entries: ((data.entries as unknown[]) || []).map(mapEntry),
    total: Number(data.total || 0),
    offset: Number(data.offset || 0),
    limit: Number(data.limit || 0),
  };
}

export function mapRowsPage(value: unknown): RowGroupsPage {
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

export function mapFiles(value: unknown): FilesResponse {
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

export function mapFileTree(value: unknown): FileTreeResponse {
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

export function mapDeltaResult(value: unknown): DeltaImportResult {
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

export function mapBackupList(value: unknown): BackupList {
  const data = record(value);
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

export function mapUpdateStatus(value: unknown): UpdateStatus {
  const data = record(value);
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

export function mapSourcePreview(value: unknown): SourcePreview {
  const data = record(value);
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

export function mapPackMeta(data: Record<string, unknown>): PackMeta {
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

export function mapPackResponse(value: unknown): PackResponse {
  const data = record(value);
  return {
    pack: mapPackMeta((data.pack as Record<string, unknown> | undefined) || {}),
    manifest: data.manifest as Record<string, unknown> | undefined,
    errors: (data.errors as string[] | undefined) || [],
  };
}

export function packPayload(pack: PackMeta): Record<string, unknown> {
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

export function mapDeltaExport(value: unknown): DeltaExport {
  const data = record(value);
  const header = record(data.header);
  const rows = (data.rows as Array<Record<string, unknown>> | undefined) || [];
  return {
    header: {
      sourcesFp:
        typeof header.sources_fp === "string" ? header.sources_fp : undefined,
      gameVersion:
        typeof header.game_version === "string"
          ? header.game_version
          : undefined,
      author: typeof header.author === "string" ? header.author : undefined,
      exportedAt:
        typeof header.exported_at === "string" ? header.exported_at : undefined,
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

export function deltaPayload(body: DeltaRequest): Record<string, unknown> {
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
