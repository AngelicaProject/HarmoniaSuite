import type { DeltaRow } from "./types";

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
