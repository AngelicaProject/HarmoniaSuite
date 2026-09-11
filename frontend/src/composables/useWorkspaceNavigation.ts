import { nextTick, onUnmounted, ref, type Ref } from "vue";
import { api } from "../api/client";
import type { Entry, FileStats } from "../api/types";
import type { FileRowsController } from "./useFileRows";

export interface SearchMatch {
  id: string;
  file: string;
  rowKey: string;
  source: string;
  translation: string;
}

export interface WorkspaceNavigationOptions {
  projectId: Ref<string>;
  fileTree: Ref<FileStats[]>;
  fileSearchQ: Ref<string>;
  fileHideReady: Ref<boolean>;
  hideEmpty: Ref<boolean>;
  expandedDirs: Ref<Record<string, boolean>>;
  leftMode: Ref<string>;
  focusId: Ref<string>;
  focusFileFilter: Ref<string>;
  phrasePage: Ref<number>;
  phraseSearchQ: Ref<string>;
  tab: Ref<string>;
  followFiles: Ref<boolean>;
  followRow: Ref<boolean>;
  followRowId: Ref<string>;
  rows: FileRowsController;
  isExpanded: (file: string) => boolean;
  toggleExpand: (file: string) => void;
  entryById: (id: string) => Entry | null;
  log: (message: string) => void;
  showToast: (message: string) => void;
}

type Timer = ReturnType<typeof setTimeout>;

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function useWorkspaceNavigation(options: WorkspaceNavigationOptions) {
  const searchQ = ref("");
  const matches = ref<SearchMatch[]>([]);
  const searchLoading = ref(false);
  const revealFile = ref("");
  let lastReveal = "";
  let revealTimer: Timer | null = null;
  let rowFollowRequest = 0;
  const flashTimers = new Set<Timer>();

  function setFocusId(id: string): void {
    options.focusId.value = id;
    if (options.followRow.value && options.leftMode.value === "phrases")
      options.followRowId.value = id;
  }

  function expanded(file: string): boolean {
    return options.isExpanded(file);
  }

  function flashElement(element: Element): void {
    element.classList.add("flash");
    const timer = setTimeout(() => {
      flashTimers.delete(timer);
      element.classList.remove("flash");
    }, 2400);
    flashTimers.add(timer);
  }

  async function revealInFiles(
    file: string,
    force: boolean,
    phraseId = "",
  ): Promise<void> {
    if (!file || !options.projectId.value) return;
    if (options.leftMode.value !== "files") {
      if (!force) return;
      options.leftMode.value = "files";
      options.followRowId.value = "";
    }
    const known = (options.fileTree.value || []).find((f) => f.path === file);
    if (!known) return;
    if (options.fileSearchQ.value) options.fileSearchQ.value = "";
    if (options.hideEmpty.value && (known.total || 0) === 0)
      options.hideEmpty.value = false;
    if (
      options.fileHideReady.value &&
      (known.total || 0) <= (known.translated || 0)
    )
      options.fileHideReady.value = false;
    const parts = file.split("/");
    for (let i = 1; i < parts.length; i++) {
      options.expandedDirs.value[parts.slice(0, i).join("/")] = true;
    }
    if (!expanded(file)) options.toggleExpand(file);
    lastReveal = file;
    revealFile.value = file;
    if (revealTimer) clearTimeout(revealTimer);
    revealTimer = setTimeout(() => {
      if (revealFile.value === file) revealFile.value = "";
      revealTimer = null;
    }, 4000);
    await nextTick();
    try {
      const q = phraseId
        ? '.filetree .tree-phrases [data-id="' + CSS.escape(phraseId) + '"]'
        : '.filetree [data-fp="' + CSS.escape(file) + '"]';
      const element = document.querySelector(q);
      if (element && element.scrollIntoView) {
        element.scrollIntoView({ block: "center" });
        if (phraseId) flashElement(element);
      } else if (phraseId) {
        const fileElement = document.querySelector(
          '.filetree [data-fp="' + CSS.escape(file) + '"]',
        );
        if (fileElement && fileElement.scrollIntoView)
          fileElement.scrollIntoView({ block: "center" });
      }
    } catch {
      // Scrolling is best effort for embedded browsers.
    }
  }

  async function openFile(file: string): Promise<void> {
    options.focusFileFilter.value = file;
    options.leftMode.value = "phrases";
    options.phrasePage.value = 0;
    options.phraseSearchQ.value = "";
    await loadFileAndFocusFirst(file);
  }

  async function loadFileAndFocusFirst(file: string): Promise<void> {
    const page = await options.rows.loadFileRows(file, 0, "");
    let first: Entry | null = null;
    try {
      first = await api.rowsNext(options.projectId.value, {
        file,
        afterRow: -1,
        afterCol: -1,
      });
      if (first) options.rows.mergeEntries([first]);
    } catch (error) {
      options.log("\n" + errorMessage(error));
    }
    const fallback =
      page && page.groups.length ? page.groups[0].cells[0] : null;
    const target = first || fallback;
    if (target) setFocusId(target.id);
  }

  function backToFiles(): void {
    options.leftMode.value = "files";
    options.focusFileFilter.value = "";
    options.phraseSearchQ.value = "";
    options.followRowId.value = "";
  }

  async function focusPhrase(id: string): Promise<void> {
    try {
      const entry = await options.rows.ensureEntry(id);
      if (!entry) return;
      setFocusId(id);
      const file = entry.file || options.entryById(id)?.file || "";
      if (file && options.leftMode.value === "files") {
        await revealInFiles(file, false, id);
      } else if (
        file &&
        options.leftMode.value === "phrases" &&
        !options.rows.fileRows.value.groups.some((group) =>
          (group.cells || []).some((cell) => cell.id === id),
        )
      ) {
        const position = await api.rowsPosition(options.projectId.value, {
          file,
          rowIndex: entry.rowIndex,
          q: options.phraseSearchQ.value,
        });
        options.phrasePage.value = Math.floor(
          Number(position || 0) / options.rows.rowGroupPageSize,
        );
        await options.rows.loadFileRows(
          file,
          options.phrasePage.value,
          options.phraseSearchQ.value,
        );
      }
    } catch (error) {
      options.log("\n" + (errorMessage(error) || "Не удалось открыть фразу"));
    }
  }

  function toggleFollow(): void {
    options.followFiles.value = !options.followFiles.value;
    try {
      localStorage.setItem(
        "hs-follow",
        options.followFiles.value ? "on" : "off",
      );
    } catch {
      // Preference persistence is optional.
    }
  }

  function toggleFollowRow(): void {
    options.followRow.value = !options.followRow.value;
    try {
      localStorage.setItem(
        "hs-follow-row",
        options.followRow.value ? "on" : "off",
      );
    } catch {
      // Preference persistence is optional.
    }
    if (!options.followRow.value) {
      rowFollowRequest += 1;
      options.followRowId.value = "";
    } else if (options.leftMode.value === "phrases" && options.focusId.value) {
      void onEditorEntry(options.focusId.value, true);
    }
  }

  async function onEditorEntry(id: string, force = false): Promise<void> {
    const request = ++rowFollowRequest;
    if (!id) return;
    const previousId = options.focusId.value;
    const previous = previousId ? options.rows.localEntry(previousId) : null;
    setFocusId(id);
    if (
      !options.followRow.value ||
      options.leftMode.value !== "phrases" ||
      (!force && id === previousId)
    )
      return;
    const entry = await options.rows.ensureEntry(id);
    if (
      !entry ||
      request !== rowFollowRequest ||
      !options.followRow.value ||
      options.focusId.value !== id
    )
      return;
    const sameRow =
      previous &&
      previous.file === entry.file &&
      Number(previous.rowIndex) === Number(entry.rowIndex);
    if (sameRow && !force) return;
    if (
      options.leftMode.value === "phrases" &&
      options.focusFileFilter.value !== entry.file
    ) {
      options.focusFileFilter.value = entry.file || "";
      options.phrasePage.value = 0;
      options.phraseSearchQ.value = "";
    }
    await focusPhrase(id);
    if (
      request !== rowFollowRequest ||
      !options.followRow.value ||
      options.focusId.value !== id
    )
      return;
    await nextTick();
    try {
      const element =
        document.querySelector(".dock.left .ft-rowcard.active") ||
        document.querySelector(".dock.left .ft-cell.active");
      if (element && element.scrollIntoView)
        element.scrollIntoView({ block: "center" });
    } catch {
      // Scrolling is best effort for embedded browsers.
    }
  }

  function onEditorFile(file: string): void {
    if (!options.followFiles.value || !file || file === lastReveal) return;
    void revealInFiles(file, false);
  }

  async function onRevealFile(file: string): Promise<void> {
    if (!file) {
      options.showToast("Нет активного файла");
      return;
    }
    await revealInFiles(file, true);
  }

  async function focusFile(file: string): Promise<void> {
    options.focusFileFilter.value = file || "";
    options.tab.value = "translate";
    if (!file) return;
    options.leftMode.value = "phrases";
    options.phrasePage.value = 0;
    options.phraseSearchQ.value = "";
    await loadFileAndFocusFirst(file);
  }

  async function editorRowNext(
    file: string,
    afterRow: number,
    afterCol: number,
    query: string,
  ): Promise<Entry | null> {
    const entry = await api.rowsNext(options.projectId.value, {
      file,
      afterRow,
      afterCol,
      q: query,
    });
    if (!entry || !entry.id) return entry;
    options.rows.mergeEntries([entry]);
    const inPage =
      options.rows.fileRows.value.file === file &&
      options.rows.fileRows.value.groups.some((group) =>
        (group.cells || []).some((cell) => cell.id === entry.id),
      );
    if (!inPage) {
      const position = await api.rowsPosition(options.projectId.value, {
        file,
        rowIndex: entry.rowIndex,
        q: query,
      });
      const page = Math.floor(
        Number(position || 0) / options.rows.rowGroupPageSize,
      );
      options.phrasePage.value = page;
      await options.rows.loadFileRows(file, page, query || "");
    }
    return entry;
  }

  function editorLoadRowPage(
    file: string,
    page: number,
    query: string,
  ): Promise<unknown> {
    return options.rows.loadFileRows(file, page, query);
  }

  function onNavigate(file: string): void {
    void focusFile(file);
  }

  async function doSearch(): Promise<void> {
    if (!searchQ.value.trim()) return;
    searchLoading.value = true;
    try {
      const data = await api.entries(
        options.projectId.value,
        { q: searchQ.value.trim() },
        { limit: 50 },
      );
      matches.value = (data.entries || []).map((entry: Entry) => ({
        id: entry.id,
        file: entry.file || "",
        rowKey: entry.rowKey || "",
        source: entry.source || "",
        translation: entry.translation || "",
      }));
    } catch (error) {
      options.log("\n" + errorMessage(error));
    } finally {
      searchLoading.value = false;
    }
  }

  async function openSearchResult(match: SearchMatch): Promise<void> {
    if (!match || !match.id) {
      options.log("\nФраза не найдена");
      return;
    }
    try {
      const loaded = await options.rows.ensureEntry(match.id);
      const found = loaded || options.entryById(match.id);
      const file = (found && found.file) || match.file || "";
      let targetPage = 0;
      if (file && found) {
        const position = await api.rowsPosition(options.projectId.value, {
          file,
          rowIndex: found.rowIndex,
        });
        targetPage = Math.floor(
          Number(position || 0) / options.rows.rowGroupPageSize,
        );
        await options.rows.loadFileRows(file, targetPage, "");
      }
      options.tab.value = "translate";
      options.focusFileFilter.value = file;
      options.leftMode.value = "phrases";
      options.phrasePage.value = targetPage;
      options.phraseSearchQ.value = "";
      setFocusId(match.id);
      await nextTick();
      try {
        const element = document.querySelector(
          '.dock.left [data-id="' + CSS.escape(match.id) + '"]',
        );
        if (element && element.scrollIntoView) {
          element.scrollIntoView({ block: "center" });
          flashElement(element);
        }
      } catch {
        // Scrolling is best effort for embedded browsers.
      }
      options.log(
        "\nПереход по поиску: " + (file ? file + ", " : "") + match.id,
      );
    } catch (error) {
      options.log(
        "\n" + (errorMessage(error) || "Не удалось открыть результат поиска"),
      );
    }
  }

  onUnmounted(() => {
    rowFollowRequest += 1;
    if (revealTimer) clearTimeout(revealTimer);
    for (const timer of flashTimers) clearTimeout(timer);
    flashTimers.clear();
  });

  return {
    searchQ,
    matches,
    searchLoading,
    revealFile,
    expanded,
    setFocusId,
    openFile,
    backToFiles,
    focusPhrase,
    toggleFollow,
    toggleFollowRow,
    onEditorEntry,
    onEditorFile,
    onRevealFile,
    focusFile,
    editorRowNext,
    editorLoadRowPage,
    onNavigate,
    doSearch,
    openSearchResult,
  };
}
