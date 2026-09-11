export type EntryStatus =
  | "untranslated"
  | "machine_translated"
  | "no_translation_required"
  | "stale"
  | "human_reviewed"
  | "approved";

export interface ProjectListItem {
  id: string;
  name: string;
  exists: boolean;
  files: number;
  entries: number;
  translated: number;
  updatedAt?: string;
  error?: string;
}

export interface ProjectsResponse {
  projects: ProjectListItem[];
}

export interface FileStats {
  path: string;
  total: number;
  translated: number;
  untranslated: number;
}

export interface Summary {
  entries: number;
  files: number;
  translated: number;
  untranslated: number;
  byStatus: Record<string, number>;
  outputDir?: string;
}

export interface Overview {
  id: string;
  name: string;
  inputRoot?: string;
  outputDir?: string;
  sourceLocale?: string;
  targetLocale?: string;
  createdAt?: string;
  updatedAt?: string;
  summary?: Summary;
}

export interface FileTreeResponse {
  files: FileStats[];
  total: number;
}

export interface FilesResponse {
  files: FileStats[];
  total: number;
  offset: number;
  limit: number;
  needFiles: number;
  readyFiles: number;
  summary?: Summary;
}

export interface Entry {
  uuid: string;
  id: string;
  source: string;
  translation?: string;
  status: EntryStatus | string;
  file: string;
  rowKey?: string;
  columnIndex: number;
  columnName?: string;
  rowIndex: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface EntriesPage {
  entries: Entry[];
  total: number;
  offset: number;
  limit: number;
}

export interface RowGroup {
  row: number;
  rowKey?: string;
  cells: Entry[];
  un: number;
}

export interface RowGroupsPage {
  groups: RowGroup[];
  totalGroups: number;
  offset: number;
  limit: number;
}

export interface Job {
  id: string;
  action: string;
  status: string;
  output?: string;
  code?: number;
}

export interface UpdateStatus {
  version: string;
  supported: boolean;
  mode?: string;
  needsToolchain: boolean;
  currentSha?: string;
  latestSha?: string;
  behindBy: number;
  subjects: string[];
  updateAvailable: boolean;
  state: string;
  reason?: string;
}

export interface SourceSettings {
  gamePath?: string;
  gameValid: boolean;
  gameVersion?: string;
  activeRoot?: string;
  ready: boolean;
  configured: boolean;
}

export interface SourceFiles {
  files: string[];
  root: string;
}

export interface SourcePreview {
  file: string;
  rows: number;
  stringColumns: number[];
  translatable: number;
  preview: string[][];
  head: string[][];
  data: string[][];
  truncated: boolean;
}

export interface AiStatus {
  provider?: string;
  providers?: string[];
  geminiKeySet?: boolean;
  geminiKeyHint?: string;
  geminiKeySource?: string;
  geminiConfigured?: boolean;
  geminiModel?: string;
  geminiModels?: string[];
  openrouterKeySet?: boolean;
  openrouterKeyHint?: string;
  openrouterKeySource?: string;
  openrouterConfigured?: boolean;
  openrouterModel?: string;
  openrouterReasoning?: string;
}

export interface AiModel {
  id: string;
  name?: string;
  context_length?: string | number;
  contextLength?: string | number;
}

export interface Backup {
  name: string;
  size: number;
  createdAt: string;
}

export interface BackupList {
  backups: Backup[];
  retention: number;
  autoIntervalMinutes: number;
  usedBytes: number;
  estimatedBytes: number;
}

export interface PackAuthor {
  name?: string;
  role?: string;
  contact?: string;
}

export interface PackMeta {
  packId?: string;
  translationVersion?: string;
  gameVersion?: string;
  compatibleGameVersions?: string[];
  vendorId?: string;
  vendorName?: string;
  vendorUrl?: string;
  vendorContact?: string;
  authors?: PackAuthor[];
  languages?: string[];
  title?: string;
  description?: string;
  changelog?: string;
  homepage?: string;
  license?: string;
  minPluginVersion?: string;
}

export interface PackResponse {
  pack: PackMeta;
  manifest?: Record<string, unknown>;
  errors: string[];
}

export interface ExportFile {
  name: string;
  size: number;
}

export interface ExportList {
  files: ExportFile[];
  manifest: boolean;
}

export interface PendingByFile {
  files: Record<string, number>;
}

export interface DeltaRow {
  cellId: string;
  filePath: string;
  source: string;
  translation?: string;
  status: string;
}

export interface DeltaHeader {
  sourcesFp?: string;
  gameVersion?: string;
  author?: string;
  exportedAt?: string;
}

export interface DeltaExport {
  header: DeltaHeader;
  rows: DeltaRow[];
  nextSinceUpdatedAt?: string;
  nextSinceCellId?: string;
  complete: boolean;
}

export interface DeltaSide {
  translation?: string;
  status: string;
}

export interface DeltaConflict {
  cellId: string;
  filePath: string;
  ours: DeltaSide;
  theirs: DeltaSide;
}

export interface DeltaSkipped {
  cellId?: string;
  filePath?: string;
  reason: string;
  detail?: string;
}

export interface DeltaWarning {
  cellId: string;
  filePath: string;
  warnings: string[];
}

export interface DeltaImportResult {
  applied: number;
  noop: number;
  skipped: DeltaSkipped[];
  conflicts: DeltaConflict[];
  warnings: DeltaWarning[];
  summary?: Summary;
}
