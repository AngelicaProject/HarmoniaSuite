import { computed, onUnmounted, ref, type Ref } from "vue";
import { api } from "../api/client";
import type { Entry } from "../api/types";
import {
  decorateRowGroups,
  groupPreviewEntries,
  mergeEntries as mergeDocumentEntries,
  pageCount,
  ROW_GROUP_PAGE_SIZE,
} from "../domain/fileRows";
import { needsWork } from "../domain/translationStatus";
import type {
  DisplayRowGroup,
  FileRowState,
  ProjectDocument,
} from "../domain/workspace";

export interface FilePreview {
  rows: Entry[];
  total: number;
}

export interface FileRowsController {
  rowGroupPageSize: number;
  fileRows: Ref<FileRowState>;
  phrasePage: Ref<number>;
  phraseSearchQ: Ref<string>;
  fileRowGroups: Readonly<Ref<DisplayRowGroup[]>>;
  rowGroupsPaged: Readonly<Ref<DisplayRowGroup[]>>;
  rowGroupPages: Readonly<Ref<number>>;
  rowContext: Readonly<
    Ref<{
      file: string;
      q: string;
      page: number;
      pageSize: number;
      totalGroups: number;
      groups: DisplayRowGroup[];
    }>
  >;
  reset: () => void;
  mergeEntries: (entries: Entry[]) => void;
  localEntry: (id: string) => Entry | null;
  loadFileRows: (
    file: string,
    page?: number,
    query?: string,
  ) => Promise<FileRowState | null>;
  loadFilePreview: (file: string) => Promise<void>;
  replaceEntryInPreview: (entry: Entry) => void;
  ensureEntry: (id: string) => Promise<Entry | null>;
  filePhrases: (file: string) => FilePreview;
  filePhraseGroups: (file: string) => DisplayRowGroup[];
  setRowGroupPage: (page: number) => Promise<void>;
  setPhraseSearch: (query: string) => void;
}

export interface FileRowsOptions {
  projectId: Ref<string>;
  document: Ref<ProjectDocument | null>;
  scopeFile: () => string;
  activeEntryId: () => string;
  onCacheChanged?: () => void;
  onError?: (error: unknown) => void;
}

export function useFileRows(options: FileRowsOptions): FileRowsController {
  const fileRows = ref<FileRowState>({
    file: "",
    q: "",
    page: 0,
    groups: [],
    totalGroups: 0,
    loading: false,
  });
  const phrasePage = ref(0);
  const phraseSearchQ = ref("");
  const filePreviewCache = new Map<string, FilePreview>();
  let rowRequest = 0;
  let phraseSearchTimer: ReturnType<typeof setTimeout> | null = null;
  const cacheRevision = ref(0);

  function notifyCacheChanged(): void {
    cacheRevision.value += 1;
    options.onCacheChanged?.();
  }

  function reset(): void {
    fileRows.value = {
      file: "",
      q: "",
      page: 0,
      groups: [],
      totalGroups: 0,
      loading: false,
    };
    filePreviewCache.clear();
    rowRequest += 1;
    notifyCacheChanged();
  }

  function mergeEntries(entries: Entry[]): void {
    if (!entries.length) return;
    options.document.value = mergeDocumentEntries(
      options.document.value,
      entries,
    );
    notifyCacheChanged();
  }

  function localEntry(id: string): Entry | null {
    const known = (options.document.value?.entries || []).find(
      (entry) => entry.id === id,
    );
    if (known) return known;
    for (const group of fileRows.value.groups || []) {
      const entry = (group.cells || []).find((cell) => cell.id === id);
      if (entry) return entry;
    }
    for (const preview of filePreviewCache.values()) {
      const entry = (preview.rows || []).find((cell) => cell.id === id);
      if (entry) return entry;
    }
    return null;
  }

  async function loadFileRows(
    file: string,
    page = 0,
    query = "",
  ): Promise<FileRowState | null> {
    if (!file || !options.projectId.value) return null;
    const cleanPage = Math.max(0, page | 0);
    const cleanQuery = String(query || "").trim();
    const previousPageIds = new Set(
      (fileRows.value.groups || []).flatMap((group) =>
        (group.cells || []).map((entry) => entry.id),
      ),
    );
    const activeEntryId = options.activeEntryId();
    const request = ++rowRequest;
    fileRows.value = { ...fileRows.value, loading: true };
    try {
      const response = await api.rowsPage(options.projectId.value, {
        file,
        offset: cleanPage * ROW_GROUP_PAGE_SIZE,
        limit: ROW_GROUP_PAGE_SIZE,
        q: cleanQuery,
      });
      if (request !== rowRequest) return null;
      const groups = decorateRowGroups(response.groups || [], cleanPage);
      const pageEntries = groups.flatMap((group) => group.cells || []);
      const entries = new Map<string, Entry>();
      for (const entry of options.document.value?.entries || []) {
        if (
          entry?.id &&
          (!previousPageIds.has(entry.id) || entry.id === activeEntryId)
        ) {
          entries.set(entry.id, entry);
        }
      }
      for (const entry of pageEntries) {
        if (entry?.id) entries.set(entry.id, entry);
      }
      options.document.value = mergeDocumentEntries(options.document.value, [
        ...entries.values(),
      ]);
      notifyCacheChanged();
      fileRows.value = {
        file,
        q: cleanQuery,
        page: cleanPage,
        groups,
        totalGroups: Number(response.totalGroups || 0),
        loading: false,
      };
      return fileRows.value;
    } catch (error) {
      if (request === rowRequest)
        fileRows.value = { ...fileRows.value, loading: false };
      throw error;
    }
  }

  async function loadFilePreview(file: string): Promise<void> {
    if (!file || !options.projectId.value || filePreviewCache.has(file)) return;
    const response = await api.entries(
      options.projectId.value,
      { file },
      { limit: 100 },
    );
    filePreviewCache.set(file, {
      rows: response.entries || [],
      total: response.total || 0,
    });
    notifyCacheChanged();
  }

  function replaceEntryInPreview(entry: Entry): void {
    if (!entry) return;
    const preview = filePreviewCache.get(entry.file);
    if (!preview) return;
    const rows = preview.rows || [];
    if (!rows.some((cell) => cell.id === entry.id)) return;
    const nextRows = rows.map((cell) => (cell.id === entry.id ? entry : cell));
    preview.rows = nextRows;
    notifyCacheChanged();
  }

  async function ensureEntry(id: string): Promise<Entry | null> {
    if (!id || !options.projectId.value) return null;
    const known = localEntry(id);
    if (known) return known;
    const loaded = await api.entryByCell(options.projectId.value, id);
    if (loaded?.id) mergeEntries([loaded]);
    return loaded || null;
  }

  function filePhrases(file: string): FilePreview {
    cacheRevision.value;
    const preview = filePreviewCache.get(file);
    if (!preview) return { rows: [], total: 0 };
    const untranslated: Entry[] = [];
    const translated: Entry[] = [];
    for (const entry of preview.rows || [])
      (needsWork(entry) ? untranslated : translated).push(entry);
    return {
      rows: untranslated.concat(translated).slice(0, 100),
      total: preview.total || 0,
    };
  }

  function filePhraseGroups(file: string): DisplayRowGroup[] {
    return groupPreviewEntries(filePhrases(file).rows);
  }

  const fileRowGroups = computed(() =>
    fileRows.value.file === options.scopeFile() ? fileRows.value.groups : [],
  );
  const rowGroupsPaged = computed(() => fileRowGroups.value);
  const rowGroupPages = computed(() => pageCount(fileRows.value.totalGroups));
  const rowContext = computed(() => ({
    file: fileRows.value.file,
    q: fileRows.value.q,
    page: fileRows.value.page,
    pageSize: ROW_GROUP_PAGE_SIZE,
    totalGroups: fileRows.value.totalGroups,
    groups: fileRows.value.groups,
  }));

  async function setRowGroupPage(page: number): Promise<void> {
    const file = options.scopeFile();
    if (!file) return;
    phrasePage.value = Math.max(0, Math.min(rowGroupPages.value - 1, page));
    try {
      await loadFileRows(file, phrasePage.value, phraseSearchQ.value);
    } catch (error) {
      options.onError?.(error);
    }
  }

  function setPhraseSearch(query: string): void {
    phraseSearchQ.value = query || "";
    phrasePage.value = 0;
    if (phraseSearchTimer) clearTimeout(phraseSearchTimer);
    phraseSearchTimer = setTimeout(() => {
      const file = options.scopeFile();
      if (!file) return;
      loadFileRows(file, 0, phraseSearchQ.value).catch((error) =>
        options.onError?.(error),
      );
    }, 250);
  }

  onUnmounted(() => {
    if (phraseSearchTimer) clearTimeout(phraseSearchTimer);
  });

  return {
    rowGroupPageSize: ROW_GROUP_PAGE_SIZE,
    fileRows,
    phrasePage,
    phraseSearchQ,
    fileRowGroups,
    rowGroupsPaged,
    rowGroupPages,
    rowContext,
    reset,
    mergeEntries,
    localEntry,
    loadFileRows,
    loadFilePreview,
    replaceEntryInPreview,
    ensureEntry,
    filePhrases,
    filePhraseGroups,
    setRowGroupPage,
    setPhraseSearch,
  };
}
