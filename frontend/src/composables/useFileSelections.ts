import { computed, onUnmounted, ref, watch, type Ref } from "vue";
import { api } from "../api/client";
import type { Entry } from "../api/types";
import {
  parsePersistedSelections,
  selectionStorageKey,
  serializeSelections,
  toggleSelection,
  toggleVisibleSelection,
  translationEstimate,
  updatePendingCount,
} from "../domain/selections";

export interface FileSelections {
  selTranslate: Ref<string[]>;
  selExport: Ref<string[]>;
  selTranslateSet: Readonly<Ref<Set<string>>>;
  selExportSet: Readonly<Ref<Set<string>>>;
  trPendingMap: Ref<Record<string, number>>;
  trPendingReady: Ref<boolean>;
  trEstimate: Readonly<Ref<{ entries: number }>>;
  loadSelection: () => void;
  fetchTrPending: () => Promise<void>;
  saveSelection: () => void;
  toggleTranslate: (file: string, selected: boolean) => void;
  selectVisibleForTranslation: (selected: boolean, files: string[]) => void;
  clearTranslateSelection: () => void;
  toggleExport: (file: string, selected: boolean) => void;
  clearExportSelection: () => void;
  reconcilePending: (previous: Entry | null, next: Entry) => void;
}

export function useFileSelections(
  projectId: Ref<string>,
  statsRevision: Ref<number>,
  sourceFiles: Ref<string[]>,
): FileSelections {
  const selTranslate = ref<string[]>([]);
  const selExport = ref<string[]>([]);
  const trPendingMap = ref<Record<string, number>>({});
  const trPendingReady = ref(false);
  let pendingTimer: ReturnType<typeof setTimeout> | null = null;

  const selTranslateSet = computed(() => new Set(selTranslate.value));
  const selExportSet = computed(() => new Set(selExport.value));
  const trEstimate = computed(() =>
    translationEstimate(selTranslate.value, trPendingMap.value),
  );

  function saveSelection(): void {
    try {
      localStorage.setItem(
        selectionStorageKey(projectId.value),
        serializeSelections({
          translate: selTranslate.value,
          export: selExport.value,
        }),
      );
    } catch {
      // Persistence is optional in restricted or private browsing contexts.
    }
  }

  function loadSelection(): void {
    try {
      const selections = parsePersistedSelections(
        localStorage.getItem(selectionStorageKey(projectId.value)),
      );
      selTranslate.value = selections.translate;
      selExport.value = selections.export;
    } catch {
      selTranslate.value = [];
      selExport.value = [];
    }
  }

  async function fetchTrPending(): Promise<void> {
    if (!projectId.value) {
      trPendingMap.value = {};
      trPendingReady.value = false;
      return;
    }
    try {
      const response = await api.pendingByFile(projectId.value);
      trPendingMap.value = (response && response.files) || {};
      trPendingReady.value = true;
    } catch {
      // The map is non-critical; the full source list remains usable.
    }
  }

  watch(statsRevision, () => {
    if (pendingTimer) clearTimeout(pendingTimer);
    pendingTimer = setTimeout(() => void fetchTrPending(), 300);
  });

  function toggleTranslate(file: string, selected: boolean): void {
    selTranslate.value = toggleSelection(selTranslate.value, file, selected);
    saveSelection();
  }

  function selectVisibleForTranslation(
    selected: boolean,
    files = sourceFiles.value,
  ): void {
    selTranslate.value = toggleVisibleSelection(
      selTranslate.value,
      files,
      selected,
    );
    saveSelection();
  }

  function clearTranslateSelection(): void {
    selTranslate.value = [];
    saveSelection();
  }

  function toggleExport(file: string, selected: boolean): void {
    selExport.value = toggleSelection(selExport.value, file, selected);
    saveSelection();
  }

  function clearExportSelection(): void {
    selExport.value = [];
    saveSelection();
  }

  function reconcilePending(previous: Entry | null, next: Entry): void {
    trPendingMap.value = updatePendingCount(
      trPendingMap.value,
      previous,
      next,
      trPendingReady.value,
    );
  }

  onUnmounted(() => {
    if (pendingTimer) clearTimeout(pendingTimer);
  });

  return {
    selTranslate,
    selExport,
    selTranslateSet,
    selExportSet,
    trPendingMap,
    trPendingReady,
    trEstimate,
    loadSelection,
    fetchTrPending,
    saveSelection,
    toggleTranslate,
    selectVisibleForTranslation,
    clearTranslateSelection,
    toggleExport,
    clearExportSelection,
    reconcilePending,
  };
}
