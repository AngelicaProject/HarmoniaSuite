import type { Entry, FileStats } from "../api/types";

export interface ProjectDocument {
  files: FileStats[];
  entries: Entry[];
  _loadMs?: number;
}

export interface DisplayRowGroup {
  row: number;
  rowKey?: string;
  key?: string;
  section?: number;
  cells: Entry[];
  un: number;
  pos?: number;
}

export interface FileRowState {
  file: string;
  q: string;
  page: number;
  groups: DisplayRowGroup[];
  totalGroups: number;
  loading: boolean;
}

export interface SavedEntry {
  previous: Entry | null;
  entry: Entry;
  fileStats: FileStats | null;
}

export function emptyFileRowState(): FileRowState {
  return {
    file: "",
    q: "",
    page: 0,
    groups: [],
    totalGroups: 0,
    loading: false,
  };
}
