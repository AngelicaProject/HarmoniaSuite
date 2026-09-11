import { computed, ref, type Ref } from "vue";
import { api } from "../api/client";
import type { FileStats } from "../api/types";
import {
  buildFileTree,
  type FileTreeRow,
  type FileTreeSort,
} from "../domain/fileTree";

export function useFileTree(
  projectId: Ref<string>,
  onLoaded?: () => void,
): {
  fileTree: Ref<FileStats[]>;
  fileTreeLoading: Ref<boolean>;
  fileSearchQ: Ref<string>;
  fileHideReady: Ref<boolean>;
  hideEmpty: Ref<boolean>;
  fileSort: Ref<FileTreeSort>;
  expandedDirs: Ref<Record<string, boolean>>;
  treeRows: Readonly<Ref<FileTreeRow[]>>;
  treeFileCount: Readonly<Ref<number>>;
  treeReadyCount: Readonly<Ref<number>>;
  treeEmptyCount: Readonly<Ref<number>>;
  loadFileTree: () => Promise<void>;
  toggleDir: (path: string) => void;
  toggleHideReady: () => void;
  toggleHideEmpty: () => void;
  setSort: (sort: FileTreeSort) => void;
} {
  const fileTree = ref<FileStats[]>([]);
  const fileTreeLoading = ref(false);
  const fileSearchQ = ref("");
  const fileHideReady = ref(false);
  const hideEmpty = ref(readStorage("hs-hide-empty", "off") !== "off");
  const fileSort = ref<FileTreeSort>(
    normalizeSort(readStorage("hs-sort", "name")),
  );
  const expandedDirs = ref<Record<string, boolean>>({});

  const treeFileCount = computed(() => fileTree.value.length);
  const treeReadyCount = computed(() =>
    countFiles((file) => file.total > 0 && file.total === file.translated),
  );
  const treeEmptyCount = computed(() => countFiles((file) => file.total === 0));
  const treeRows = computed(() =>
    buildFileTree(fileTree.value, {
      query: fileSearchQ.value,
      hideEmpty: hideEmpty.value,
      hideReady: fileHideReady.value,
      sort: fileSort.value,
      expanded: expandedDirs.value,
    }),
  );

  function countFiles(predicate: (file: FileStats) => boolean): number {
    const query = fileSearchQ.value.trim().toLowerCase();
    return fileTree.value.filter(
      (file) =>
        (!query || file.path.toLowerCase().includes(query)) && predicate(file),
    ).length;
  }

  async function loadFileTree(): Promise<void> {
    if (!projectId.value) return;
    fileTreeLoading.value = true;
    try {
      const response = await api.fileTree(projectId.value);
      fileTree.value = response.files;
      onLoaded?.();
    } catch {
      // A stale project or a transient request failure should not surface as
      // an unhandled promise from background refreshes.
    } finally {
      fileTreeLoading.value = false;
    }
  }

  function toggleDir(path: string): void {
    expandedDirs.value[path] = !expandedDirs.value[path];
  }

  function toggleHideReady(): void {
    fileHideReady.value = !fileHideReady.value;
  }

  function toggleHideEmpty(): void {
    hideEmpty.value = !hideEmpty.value;
    writeStorage("hs-hide-empty", hideEmpty.value ? "on" : "off");
  }

  function setSort(sort: FileTreeSort): void {
    fileSort.value = sort;
    writeStorage("hs-sort", sort);
  }

  return {
    fileTree,
    fileTreeLoading,
    fileSearchQ,
    fileHideReady,
    hideEmpty,
    fileSort,
    expandedDirs,
    treeRows,
    treeFileCount,
    treeReadyCount,
    treeEmptyCount,
    loadFileTree,
    toggleDir,
    toggleHideReady,
    toggleHideEmpty,
    setSort,
  };
}

function normalizeSort(value: string): FileTreeSort {
  return value === "progress" || value === "need" ? value : "name";
}

function readStorage(key: string, fallback: string): string {
  try {
    return localStorage.getItem(key) || fallback;
  } catch {
    return fallback;
  }
}

function writeStorage(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Persistence is optional in private browsing and restricted shells.
  }
}
