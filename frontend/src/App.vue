<script lang="ts">
import { defineComponent } from "vue";
import { computed, onMounted, onUnmounted, ref } from "vue";
import { api } from "./api/client";
import { ENTRY_STATUS } from "./domain/translationStatus";
import { buildContextMenuItems } from "./domain/contextMenu";
import Picker from "./components/Picker.vue";
import Editor from "./components/Editor.vue";
import Settings from "./components/Settings.vue";
import TranslateView from "./components/TranslateView.vue";
import SearchView from "./components/SearchView.vue";
import TagsView from "./components/TagsView.vue";
import SummaryView from "./components/SummaryView.vue";
import PackView from "./components/PackView.vue";
import ExportView from "./components/ExportView.vue";
import DeltaView from "./components/DeltaView.vue";
import LogView from "./components/LogView.vue";
import Dropdown from "./components/Dropdown.vue";
import AppTopBar from "./components/shell/AppTopBar.vue";
import JobBar from "./components/shell/JobBar.vue";
import UiToast from "./components/ui/UiToast.vue";
import { useFileTree } from "./composables/useFileTree";
import { useUpdater } from "./composables/useUpdater";
import { useCommandPalette } from "./composables/useCommandPalette";
import { useDockLayout } from "./composables/useDockLayout";
import { useJobs } from "./composables/useJobs";
import {
  useFileRows,
  type FileRowsController,
} from "./composables/useFileRows";
import { useFileSelections } from "./composables/useFileSelections";
import { useEntryMutations } from "./composables/useEntryMutations";
import { useProjectWorkspace } from "./composables/useProjectWorkspace";
import { useContextMenu } from "./composables/useContextMenu";
import { usePack } from "./composables/usePack";
import { useNotifications } from "./composables/useNotifications";
import { useWorkspaceNavigation } from "./composables/useWorkspaceNavigation";
import { VIEWS, VIEW_IDS } from "./workspace/views";
import type { Entry, FileStats, Job, DeltaConflict } from "./api/types";

interface EditorHandle {
  current?: Entry | null;
  insertTag?: (text: string) => void;
  openConflictTab?: (conflict: DeltaConflict) => void;
  closeConflictTab?: (id: string) => void;
  noteChanged?: () => void;
}

interface DeltaHandle {
  doPreview?: () => void;
}

interface ConflictResolution {
  id: string;
  mode: string;
  translation?: string;
  status: string;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

const App = defineComponent({
  components: {
    Picker,
    Editor,
    TranslateView,
    SearchView,
    TagsView,
    SummaryView,
    PackView,
    ExportView,
    DeltaView,
    LogView,
    Settings,
    Dropdown,
    AppTopBar,
    JobBar,
    UiToast,
  },
  setup() {
    const { toast, showToast } = useNotifications();
    const projectId = ref("");
    const showPicker = ref(true);
    const statsRev = ref(0);
    const exportRev = ref(0);
    const logText = ref("Готово к работе.");
    const showSettings = ref(false);
    const settingsSection = ref("sources");
    function openSettings(section = ""): void {
      settingsSection.value = section || settingsSection.value;
      showSettings.value = true;
    }
    const fileTreeState = useFileTree(projectId, () => {
      statsRev.value++;
    });
    const {
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
    } = fileTreeState;
    const leftMode = ref("files");
    const focusId = ref("");
    const focusFileFilter = ref("");
    const editorRef = ref<EditorHandle | null>(null);
    const deltaRef = ref<DeltaHandle | null>(null);
    let rowsController: FileRowsController | null = null;
    let loadPackForProject = async (): Promise<void> => {};
    let loadSelectionForProject = (): void => {};

    const workspace = useProjectWorkspace({
      projectId,
      fileTree: fileTreeState,
      fileRows: () => rowsController,
      loadSelection: () => loadSelectionForProject(),
      loadPack: () => loadPackForProject(),
      invalidateExport: () => exportRev.value++,
      openSettings,
      log: (message) => (logText.value += message),
      getFocusFileFilter: () => focusFileFilter.value,
      getPhraseSearchQuery: () => phraseSearchQ.value,
      isPhraseMode: () => leftMode.value === "phrases",
    });
    const {
      projectName,
      root,
      document: doc,
      summary,
      projectLoading,
      sourceFiles,
      sourceLoading,
      sourceError,
      sourceStatus,
      sourceLabel,
      geminiStatus,
      aiTitle,
      loadProject,
      loadSourceStatus,
      scanSource,
      loadGeminiStatus,
    } = workspace;

    const packState = usePack(projectId, sourceStatus, (message) => {
      logText.value += message;
    });
    loadPackForProject = packState.loadPack;
    const {
      pack,
      packErrors,
      packManifest,
      packMsg,
      packCompat,
      packLangs,
      loadPack,
      savePack,
      addAuthor: packAddAuthor,
      deleteAuthor: packDelAuthor,
    } = packState;

    const selections = useFileSelections(projectId, statsRev, sourceFiles);
    loadSelectionForProject = selections.loadSelection;
    const {
      selTranslate,
      selExport,
      selTranslateSet,
      selExportSet,
      trEstimate,
      trPendingMap,
      trPendingReady,
      toggleTranslate,
      selectVisibleForTranslation,
      clearTranslateSelection,
      toggleExport,
      clearExportSelection,
    } = selections;

    const rows = useFileRows({
      projectId,
      document: doc,
      scopeFile: () => focusFileFilter.value,
      activeEntryId: () => focusId.value || editorRef.value?.current?.id || "",
      onError: (error) => (logText.value += "\n" + errorMessage(error)),
    });
    rowsController = rows;
    const {
      fileRows,
      phrasePage,
      phraseSearchQ,
      rowGroupsPaged,
      rowGroupPages,
      rowContext,
      loadFilePreview,
      filePhrases,
      filePhraseGroups,
      ensureEntry,
      setRowGroupPage,
      setPhraseSearch,
    } = rows;

    const mutations = useEntryMutations({
      document: doc,
      fileTree,
      fileRows,
      findCachedEntry: rows.localEntry,
      replaceEntryInPreview: rows.replaceEntryInPreview,
      reconcilePending: selections.reconcilePending,
    });
    const { savedEntry, localEntry, applySavedEntry } = mutations;
    void loadGeminiStatus();
    let jobController: ReturnType<typeof useJobs>;
    const startJob = (details: Job) => jobController.startJob(details);
    const updater = useUpdater(startJob, showToast);
    const {
      upd,
      updModal,
      updRestarting,
      updRestartDead,
      updLogBusy,
      updLabel,
      updTitle,
      loadUpdateStatus,
      runUpdate,
      copyUpdateLog,
    } = updater;
    const jobs = useJobs({
      projectId,
      projectName,
      summary,
      logText,
      updModal,
      updRestarting,
      updRestartDead,
      loadFileTree,
      loadProject,
      loadSourceStatus,
      scanSource,
      loadUpdateStatus,
      showToast,
    });
    jobController = jobs;
    const { job, pendingPack, updateFailed, jobActive, cancelJob } = jobs;
    const badge = computed(() => {
      const s = summary.value;
      if (!s || s.entries === undefined) return "—";
      return `${s.translated ?? 0} / ${s.entries}`;
    });
    const theme = ref(
      document.documentElement.getAttribute("data-theme") || "dark",
    );

    // ---- IDE docking: state and interactions live in a dedicated composable ----
    const dock = useDockLayout(VIEWS, VIEW_IDS, {
      onActivateView: (view) => {
        if (view === "pack" && !pack.value) loadPack();
      },
      onToast: showToast,
    });
    const {
      layout,
      active,
      zoneVisible,
      railVisible,
      narrow,
      hiddenViews,
      layoutStyle,
      zoneStyle,
      zoneShown,
      activateView,
      toggleView,
      openZoneMenu,
      ctxZone,
      hideWidget,
      activeTitle,
      gotoView,
      addView,
      closeTab,
      resetLayout,
      menuFor,
      addMenu,
      openAddMenu,
      dropPos,
      onTabDragStart,
      onTabDragOver,
      onDrop,
      onDropOnTab,
      onDragEnd,
      dropClass,
      setHost,
      hostEl,
      startResize,
      startResizeY,
      toggleLeft,
      toggleRight,
      toggleBottom,
      hideZone,
      hidePanel,
    } = dock;

    function closeMenusOnDocClick(e: MouseEvent): void {
      const target = e.target instanceof Element ? e.target : null;
      const inside =
        target?.closest(".dz-menu") ||
        target?.closest(".top-menu-wrap") ||
        target?.closest(".dz-gearbtn") ||
        target?.closest(".dz-xbtn") ||
        target?.closest(".dz-ribtn");
      if (inside) return;
      if (menuFor.value) menuFor.value = null;
      if (addMenu.value) addMenu.value = null;
    }

    function toggleTheme() {
      theme.value = theme.value === "dark" ? "light" : "dark";
      localStorage.setItem("theme", theme.value);
      document.documentElement.setAttribute("data-theme", theme.value);
    }

    const entryById = computed<Map<string, Entry>>(() => {
      const m = new Map<string, Entry>();
      for (const e of doc.value?.entries || []) m.set(e.id, e);
      return m;
    });
    function progPct(f: FileStats | null | undefined): number {
      if (!f || !f.total) return 0;
      return Math.round((f.translated / f.total) * 100);
    }
    function dirPct(
      d: { total?: number; done?: number } | null | undefined,
    ): number {
      if (!d || !d.total) return 0;
      return Math.round(((d.done ?? 0) / d.total) * 100);
    }
    function fmtNum(n: number): string {
      return Number(n || 0).toLocaleString("ru-RU");
    }
    function pct1(done: number, total: number): string {
      if (!total) return "0%";
      const p = (done / total) * 100;
      return (
        (p >= 9.95 ? String(Math.round(p)) : p.toFixed(1).replace(".", ",")) +
        "%"
      );
    }
    function baseName(p: string): string {
      const s = String(p || "");
      const i = s.lastIndexOf("/");
      return i < 0 ? s : s.slice(i + 1);
    }
    function fileUn(f: FileStats): number {
      return (f.total || 0) - (f.translated || 0);
    }
    const projTotal = computed(() => summary.value?.entries ?? 0);
    const projDone = computed(() => summary.value?.translated ?? 0);
    const staleStatus = ENTRY_STATUS.STALE;
    const expandedFiles = ref<Record<string, boolean>>({});
    function expanded(f: string): boolean {
      return !!expandedFiles.value[f];
    }
    function toggleExpand(f: string): void {
      expandedFiles.value[f] = !expandedFiles.value[f];
      if (expandedFiles.value[f])
        loadFilePreview(f).catch(
          (e) => (logText.value += "\n" + errorMessage(e)),
        );
    }
    const tab = ref("translate");
    const followFiles = ref(
      (() => {
        try {
          return localStorage.getItem("hs-follow") !== "off";
        } catch {
          return true;
        }
      })(),
    );
    const followRow = ref(
      (() => {
        try {
          return localStorage.getItem("hs-follow-row") === "on";
        } catch {
          return false;
        }
      })(),
    );
    const followRowId = ref("");
    const navigation = useWorkspaceNavigation({
      projectId,
      fileTree,
      fileSearchQ,
      fileHideReady,
      hideEmpty,
      expandedDirs,
      leftMode,
      focusId,
      focusFileFilter,
      phrasePage,
      phraseSearchQ,
      tab,
      followFiles,
      followRow,
      followRowId,
      rows,
      isExpanded: expanded,
      toggleExpand,
      entryById: (id) => entryById.value.get(id) || null,
      log: (message) => (logText.value += message),
      showToast,
    });
    const {
      searchQ,
      matches,
      searchLoading,
      revealFile,
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
    } = navigation;
    const contextMenu = useContextMenu({
      getItems: (element) =>
        buildContextMenuItems(
          element.dataset.ctx,
          { path: element.dataset.path, id: element.dataset.id },
          {
            openFile,
            toggleExpand,
            isExpanded: expanded,
            toggleTranslate,
            isTranslateSelected: (path) => selTranslateSet.value.has(path),
            toggleExport,
            isExportSelected: (path) => selExportSet.value.has(path),
            focusPhrase,
            entryById: (id) => entryById.value.get(id) || null,
            copyText: (value) => contextMenu.copyText(value),
            logText: () => logText.value,
          },
        ),
      closeOtherMenus: () => {
        menuFor.value = null;
        addMenu.value = null;
      },
      showToast,
    });
    const { ctxMenu, runCtx } = contextMenu;
    const tagFilter = ref("");
    const csvRequest = ref<{ file: string; n: number } | null>(null);
    const previewPinRequest = ref<{ file: string; n: number } | null>(null);
    function openPreview(f: string): void {
      if (!f) return;
      previewPinRequest.value = { file: f, n: Date.now() };
    }
    function insertTagToEditor(text: string): void {
      const ed = editorRef.value;
      if (!ed || !ed.current) {
        showToast("Сначала выберите фразу в редакторе");
        return;
      }
      ed.insertTag?.(text);
    }

    const palette = useCommandPalette(projectId, fileTree, VIEWS, VIEW_IDS, {
      run,
      toggleLeft,
      toggleRight,
      toggleBottom,
      resetLayout,
      gotoView,
      openFile,
    });
    const {
      paletteOpen,
      paletteQ,
      paletteIdx,
      paletteInput,
      paletteResults,
      openPalette,
      closePalette,
      runPalette,
      onPaletteKey,
    } = palette;

    async function openConflict(c: DeltaConflict): Promise<void> {
      if (!c || !c.cellId) return;
      await ensureEntry(c.cellId);
      const ed = editorRef.value;
      if (!ed || !ed.openConflictTab) {
        showToast("Редактор недоступен");
        return;
      }
      ed.openConflictTab(c);
      logText.value += "\nКонфликт дельты: " + c.cellId;
    }

    async function onResolveConflict({
      id,
      mode,
      translation,
      status,
    }: ConflictResolution): Promise<void> {
      try {
        if (mode === "ours") {
          if (editorRef.value && editorRef.value.closeConflictTab)
            editorRef.value.closeConflictTab(id);
          showToast("Оставлен наш вариант");
          return;
        }
        const found = entryById.value.get(id) || null;
        const uuid =
          (found && found.uuid) ||
          (await api.entryByCell(projectId.value, id)).uuid;
        await onSave({ id, uuid, translation: translation ?? "", status });
        if (editorRef.value && editorRef.value.closeConflictTab)
          editorRef.value.closeConflictTab(id);
        showToast("Взято из дельты — применено");
        if (deltaRef.value && deltaRef.value.doPreview)
          deltaRef.value.doPreview();
      } catch (e) {
        showToast(errorMessage(e));
      }
    }

    // extract (right panel)
    async function runExtract(force = false): Promise<void> {
      try {
        const d = await api.startJob({
          action: "extract",
          projectId: projectId.value,
          root: root.value,
          files: [],
          force: !!force,
        });
        logText.value +=
          "\nЗапущено: обновление данных" +
          (force ? " (все таблицы)" : " (только изменённые файлы)");
        startJob(d);
      } catch (e) {
        logText.value += "\n" + errorMessage(e);
      }
    }

    async function run(
      action: string,
      extra: { model?: string; reasoning?: string } = {},
    ) {
      if (action === "gemini" && !geminiStatus.value.configured) {
        logText.value +=
          "\nGemini недоступен: задайте ключ в настройках (меню) или переменной GEMINI_API_KEY";
        return;
      }
      try {
        const d = await api.startJob({
          action,
          projectId: projectId.value,
          root: root.value,
          files:
            action === "gemini" || action === "openrouter"
              ? [...selTranslate.value]
              : action === "merge"
                ? [...selExport.value]
                : [],
          model:
            (action === "gemini" || action === "openrouter") &&
            extra &&
            extra.model
              ? extra.model
              : undefined,
          reasoning:
            (action === "gemini" || action === "openrouter") &&
            extra &&
            extra.reasoning
              ? extra.reasoning
              : undefined,
        });
        logText.value +=
          "\nЗапущено: " +
          ({
            extract: "обновление данных",
            gemini: "перевод Gemini",
            openrouter: "перевод OpenRouter",
            merge: "сборка CSV",
          }[action] || action);
        startJob(d);
      } catch (e) {
        pendingPack.value = false;
        logText.value += "\n" + errorMessage(e);
      }
    }

    async function buildPack() {
      if (jobActive()) {
        showToast("Дождитесь завершения текущей задачи");
        return;
      }
      await run("merge");
    }

    async function buildAndDownloadPack() {
      if (jobActive()) {
        showToast("Дождитесь завершения текущей задачи");
        return;
      }
      pendingPack.value = true;
      await run("merge");
    }

    function selTranslateVisible(v: boolean, list?: string[]): void {
      selectVisibleForTranslation(v, list || sourceFiles.value);
    }

    function clearTranslateSel(): void {
      clearTranslateSelection();
    }

    function clearExportScope(): void {
      clearExportSelection();
    }

    async function runTranslate(
      provider: string,
      model?: string,
      reasoning?: string,
    ): Promise<void> {
      if (jobActive()) {
        showToast("Дождитесь завершения текущей задачи");
        return;
      }
      if (!selTranslate.value.length) {
        showToast("Выберите таблицы для перевода");
        return;
      }
      if (
        provider === "openrouter" &&
        !geminiStatus.value.openrouterConfigured
      ) {
        logText.value +=
          "\nOpenRouter недоступен: задайте ключ в настройках (меню) или переменной OPENROUTER_API_KEY";
        return;
      }
      await run(provider === "openrouter" ? "openrouter" : "gemini", {
        model,
        reasoning,
      });
    }

    async function onSave({
      id,
      uuid,
      translation,
      status,
    }: {
      id: string;
      uuid: string;
      translation: string;
      status: string;
    }): Promise<void> {
      try {
        const previous = localEntry(id);
        const d = await api.patchEntry(
          projectId.value,
          uuid,
          translation,
          status,
        );
        if (d.entry) applySavedEntry(previous, d.entry);
        if (d.summary) summary.value = d.summary;
        if (editorRef.value && editorRef.value.noteChanged)
          editorRef.value.noteChanged();
        (d.warnings || []).forEach((w) => (logText.value += "\n[Тег] " + w));
      } catch (e) {
        showToast(errorMessage(e));
        logText.value += "\n" + errorMessage(e);
      }
    }

    async function onConfirm(p: string): Promise<void> {
      projectId.value = p;
      showPicker.value = false;
      packState.reset();
      await loadProject();
      await scanSource();
      if (!summary.value || !summary.value.entries) {
        logText.value += "\nНовый проект: извлекаю все строки игры…";
        await runExtract();
      }
    }

    let updateStatusTimer: ReturnType<typeof setInterval> | null = null;
    onMounted(() => {
      document.addEventListener("click", closeMenusOnDocClick, true);
      loadSourceStatus();
      loadUpdateStatus();
      updateStatusTimer = setInterval(loadUpdateStatus, 3600000);
      if (projectId.value && !showPicker.value) {
        loadProject().then(scanSource);
      }
    });
    onUnmounted(() => {
      document.removeEventListener("click", closeMenusOnDocClick, true);
      if (updateStatusTimer) clearInterval(updateStatusTimer);
    });

    return {
      projectId,
      projectName,
      root,
      showPicker,
      doc,
      summary,
      projectLoading,
      sourceFiles,
      sourceLoading,
      sourceError,
      selTranslate,
      trEstimate,
      trPendingMap,
      trPendingReady,
      selExport,
      selTranslateSet,
      selExportSet,
      logText,
      badge,
      theme,
      toggleTheme,
      layoutStyle,
      zoneStyle,
      zoneShown,
      startResize,
      startResizeY,
      toggleLeft,
      toggleRight,
      toggleBottom,
      hideZone,
      zoneVisible,
      railVisible,
      narrow,
      VIEWS,
      layout,
      active,
      hiddenViews,
      menuFor,
      addMenu,
      openAddMenu,
      dropPos,
      activateView,
      toggleView,
      openZoneMenu,
      ctxZone,
      hidePanel,
      hideWidget,
      gotoView,
      addView,
      closeTab,
      resetLayout,
      onTabDragStart,
      onTabDragOver,
      onDrop,
      onDropOnTab,
      onDragEnd,
      dropClass,
      setHost,
      hostEl,
      activeTitle,
      paletteOpen,
      paletteQ,
      paletteIdx,
      paletteInput,
      paletteResults,
      openPalette,
      closePalette,
      runPalette,
      onPaletteKey,
      job,
      exportRev,
      savedEntry,
      treeRows,
      treeFileCount,
      treeReadyCount,
      treeEmptyCount,
      fileTreeLoading,
      fileSearchQ,
      fileHideReady,
      toggleHideReady,
      hideEmpty,
      toggleHideEmpty,
      fileSort,
      setSort,
      toggleDir,
      expandedFiles,
      expanded,
      toggleExpand,
      filePhrases,
      filePhraseGroups,
      fileRows,
      fileUn,
      projTotal,
      projDone,
      staleStatus,
      progPct,
      dirPct,
      fmtNum,
      pct1,
      baseName,
      leftMode,
      entryById,
      openFile,
      backToFiles,
      rowGroupsPaged,
      rowGroupPages,
      phrasePage,
      phraseSearchQ,
      setRowGroupPage,
      setPhraseSearch,
      rowContext,
      focusPhrase,
      tab,
      focusId,
      focusFileFilter,
      editorRef,
      deltaRef,
      tagFilter,
      insertTagToEditor,
      openPreview,
      previewPinRequest,
      csvRequest,
      focusFile,
      editorRowNext,
      editorLoadRowPage,
      onNavigate,
      matches,
      searchQ,
      searchLoading,
      doSearch,
      openSearchResult,
      ensureEntry,
      openConflict,
      onResolveConflict,
      followFiles,
      toggleFollow,
      followRow,
      followRowId,
      toggleFollowRow,
      onEditorEntry,
      revealFile,
      onEditorFile,
      onRevealFile,
      ctxMenu,
      runCtx,
      run,
      buildPack,
      buildAndDownloadPack,
      runTranslate,
      toggleTranslate,
      selTranslateVisible,
      clearTranslateSel,
      toggleExport,
      clearExportScope,
      runExtract,
      cancelJob,
      geminiStatus,
      sourceStatus,
      sourceLabel,
      showSettings,
      settingsSection,
      openSettings,
      loadSourceStatus,
      loadGeminiStatus,
      aiTitle,
      upd,
      updModal,
      updRestarting,
      updRestartDead,
      updLogBusy,
      updateFailed,
      updLabel,
      updTitle,
      loadUpdateStatus,
      runUpdate,
      copyUpdateLog,
      onSave,
      onConfirm,
      scanSource,
      loadProject,
      toast,
      showToast,
      pack,
      packErrors,
      packManifest,
      packMsg,
      packCompat,
      packLangs,
      loadPack,
      savePack,
      packAddAuthor,
      packDelAuthor,
    };
  },
});

export default App;
</script>

<template>
  <Picker
    v-if="showPicker"
    v-model="projectId"
    :default-root="root"
    @confirm="onConfirm"
  />
  <Settings
    v-if="showSettings"
    :initial="settingsSection"
    :forced="!sourceStatus.configured"
    @close="showSettings = false"
    @changed="
      loadSourceStatus();
      loadGeminiStatus();
    "
  />
  <AppTopBar
    v-else
    :project-id="projectId"
    :project-name="projectName"
    :theme="theme"
    :menu-for="menuFor"
    :rail-visible="railVisible"
    @update:menu-for="menuFor = $event"
    @change-project="showPicker = true"
    @goto-view="gotoView"
    @run-extract="runExtract"
    @open-settings="openSettings"
    @reset-layout="resetLayout"
    @toggle-left="toggleLeft"
    @toggle-right="toggleRight"
    @toggle-bottom="toggleBottom"
    @open-palette="openPalette"
    @toggle-theme="toggleTheme"
  />
  <JobBar :job="job" @cancel="cancelJob" @close="job = null" />

  <div class="layout" v-if="!showPicker" :style="layoutStyle">
    <div
      v-if="job && job.action === 'extract' && job.status === 'running'"
      class="load-veil"
    ></div>
    <div v-if="projectLoading" class="load-veil">
      <span class="job-spin"></span><span>Загрузка проекта…</span>
    </div>
    <section class="dock left" v-show="railVisible.left">
      <div
        class="hsplit right"
        @mousedown="(e) => startResize('left', e)"
        title="Потяните, чтобы изменить ширину"
      ></div>
      <div
        class="dz-rail"
        @contextmenu.prevent="openZoneMenu('left', $event)"
        @dragover.prevent="(e) => onTabDragOver('left', layout.left.length, e)"
        @drop="(e) => onDrop('left', e)"
        :class="{
          'drop-end':
            dropPos &&
            dropPos.zone === 'left' &&
            dropPos.index === layout.left.length,
        }"
      >
        <button
          v-for="(v, i) in layout.left"
          :key="v"
          class="dz-ribtn"
          :class="[{ active: active.left === v }, dropClass('left', i)]"
          draggable="true"
          @dragstart="(e) => onTabDragStart('left', v, e)"
          @dragend="onDragEnd"
          @dragover.prevent="(e) => onTabDragOver('left', i, e)"
          @drop.stop="(e) => onDropOnTab('left', i, e)"
          @click="toggleView('left', v)"
          @contextmenu.prevent.stop="openZoneMenu('left', $event, v)"
          :title="VIEWS[v].title"
        >
          <span class="dz-ic" v-html="VIEWS[v].icon"></span>
        </button>
        <span class="grow"></span>
        <button
          class="dz-ribtn dz-add"
          @click="openAddMenu('left', $event)"
          title="Добавить панель"
        >
          +
        </button>
      </div>
      <div class="dz-main" v-show="zoneShown('left')">
        <div class="dz-head">
          <span>{{ activeTitle("left") }}</span
          ><span class="grow"></span
          ><button
            class="dz-gearbtn"
            @click="menuFor = menuFor === 'head:left' ? null : 'head:left'"
            title="Настройки зоны"
          >
            <svg class="icon" viewBox="0 0 24 24">
              <path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3" />
              <path d="M1 14h6M9 8h6M17 16h6" />
            </svg></button
          ><button
            class="dz-xbtn"
            @click="active.left && closeTab('left', active.left)"
            title="Убрать панель"
          >
            ×
          </button>
          <div v-if="menuFor === 'head:left'" class="dz-menu head-menu">
            <div class="dz-menu-i" @click="hideZone('left')">
              Свернуть панель
            </div>
            <div class="dz-menu-i" @click="openAddMenu('left', $event)">
              Добавить панель…
            </div>
            <div class="dz-menu-i dim" @click="resetLayout()">
              Сбросить раскладку
            </div>
          </div>
        </div>
        <div class="dz-body">
          <div
            v-for="v in layout.left"
            :key="v"
            class="dz-host"
            v-show="active.left === v"
            :ref="(el) => setHost(v, el)"
          ></div>
        </div>
      </div>
    </section>
    <Teleport v-if="hostEl('project')" :to="hostEl('project')"
      ><div style="display: contents">
        <div class="pane-head">
          <button
            v-if="leftMode === 'phrases'"
            class="ghost icon-btn sm"
            @click="backToFiles"
            title="К файлам"
          >
            <svg class="icon" viewBox="0 0 24 24">
              <path d="M15 18l-6-6 6-6" />
            </svg>
          </button>
          <h3 v-if="leftMode === 'files'">Файлы проекта</h3>
          <h3
            v-else
            style="
              white-space: nowrap;
              overflow: hidden;
              text-overflow: ellipsis;
            "
            :title="focusFileFilter"
          >
            {{ focusFileFilter || "Фразы" }}
          </h3>
          <span class="grow"></span>
        </div>
        <div v-if="leftMode === 'files'" class="proj-stats">
          <div class="proj-num">
            {{ fmtNum(projDone) }} <span>/ {{ fmtNum(projTotal) }}</span
            ><b class="proj-pct">{{ pct1(projDone, projTotal) }}</b>
          </div>
          <div class="sum-bar">
            <i
              :style="
                'width:' +
                (projTotal
                  ? Math.max((projDone / projTotal) * 100, projDone ? 1.5 : 0)
                  : 0) +
                '%'
              "
            ></i>
          </div>
          <div class="proj-sub">
            Осталось {{ fmtNum(projTotal - projDone) }} · файлов
            {{ fmtNum(treeFileCount)
            }}<span
              v-if="
                summary && summary.byStatus && summary.byStatus[staleStatus]
              "
            >
              · устар. {{ fmtNum(summary.byStatus[staleStatus]) }}</span
            >
          </div>
        </div>
        <div v-if="leftMode === 'files'" class="ft-search">
          <svg class="icon ic-search" viewBox="0 0 24 24">
            <circle cx="11" cy="11" r="7" />
            <path d="M21 21l-4.3-4.3" /></svg
          ><input
            class="grow"
            :value="fileSearchQ"
            @input="fileSearchQ = ($event.target as HTMLInputElement).value"
            placeholder="Поиск файлов…"
          /><Dropdown
            :modelValue="fileSort"
            @update:modelValue="setSort"
            title="Сортировка"
            width="148px"
            :options="[
              { value: 'need', label: 'Недопереведённые' },
              { value: 'name', label: 'По имени' },
              { value: 'progress', label: 'По прогрессу' },
            ]"
          /><button
            class="ghost icon-btn sm"
            @click="toggleFollow"
            :style="followFiles ? '' : 'opacity:.4'"
            :title="
              followFiles
                ? 'Не следить за редактором'
                : 'Следить за редактором: список сам находит файл из редактора'
            "
          >
            <svg class="icon" viewBox="0 0 24 24">
              <circle cx="12" cy="12" r="3" />
              <path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
            </svg>
          </button>
        </div>
        <div v-else-if="focusFileFilter" class="ft-search">
          <svg class="icon ic-search" viewBox="0 0 24 24">
            <circle cx="11" cy="11" r="7" />
            <path d="M21 21l-4.3-4.3" /></svg
          ><input
            class="grow"
            :value="phraseSearchQ"
            @input="setPhraseSearch(($event.target as HTMLInputElement).value)"
            placeholder="Поиск по файлу: текст, строка, колонка, статус…"
          /><button
            v-if="phraseSearchQ"
            class="ghost icon-btn sm"
            @click="setPhraseSearch('')"
            title="Очистить"
          >
            <svg class="icon" viewBox="0 0 24 24">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
        <div class="pane-body">
          <div v-if="!doc" class="ft-empty">
            <svg class="icon" viewBox="0 0 24 24">
              <path
                d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"
              /></svg
            ><span>Проект не загружен</span>
          </div>
          <div v-else-if="leftMode === 'files'">
            <div v-if="!treeFileCount && !fileTreeLoading" class="ft-empty">
              <svg class="icon" viewBox="0 0 24 24">
                <path
                  d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1 2 2H5a2 2 0 0 1-2-2V7z"
                />
                <path d="M12 11v6M9 14h6" /></svg
              ><span>Проект пуст — строк пока нет.</span
              ><button
                class="primary"
                @click="runExtract()"
                style="margin-top: 10px"
              >
                Обновить данные игры
              </button>
            </div>
            <div v-else class="filetree">
              <div v-for="r in treeRows" :key="r.key">
                <div
                  v-if="r.type === 'dir'"
                  class="ft-item ft-dir"
                  :style="'padding-left:' + (8 + r.depth * 16) + 'px'"
                  @click="toggleDir(r.dir.path)"
                >
                  <span
                    class="tree-chev"
                    :title="r.open ? 'Свернуть' : 'Развернуть'"
                    ><svg
                      class="icon"
                      viewBox="0 0 24 24"
                      :style="r.open ? 'transform:rotate(90deg)' : ''"
                    >
                      <path d="M9 6l6 6-6 6" /></svg
                  ></span>
                  <svg class="icon ft-icon" viewBox="0 0 24 24">
                    <path
                      d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1 2 2H5a2 2 0 0 1-2-2V7z"
                    />
                  </svg>
                  <div style="flex: 1; min-width: 0">
                    <div class="ft-name">{{ r.dir.name }}</div>
                    <div class="ft-prog">
                      <i :style="'width:' + dirPct(r.dir) + '%'"></i>
                    </div>
                  </div>
                  <span class="ft-count"
                    >{{ r.dir.done }}/{{ r.dir.total }}</span
                  ><span v-if="r.dir.total - r.dir.done" class="ft-un"
                    >{{ r.dir.total - r.dir.done }} неперев.</span
                  >
                </div>
                <div v-else class="ft-group">
                  <div
                    class="ft-item"
                    :data-fp="r.file.path"
                    data-ctx="file"
                    :data-path="r.file.path"
                    :style="'padding-left:' + (8 + r.depth * 16) + 'px'"
                    :class="{
                      active:
                        focusFileFilter === r.file.path ||
                        revealFile === r.file.path,
                      reveal: revealFile === r.file.path,
                    }"
                    @click="openFile(r.file.path)"
                  >
                    <span
                      class="tree-chev"
                      @click.stop="toggleExpand(r.file.path)"
                      :title="
                        expanded(r.file.path)
                          ? 'Свернуть строки'
                          : 'Показать строки'
                      "
                      ><svg
                        class="icon"
                        viewBox="0 0 24 24"
                        :style="
                          expanded(r.file.path) ? 'transform:rotate(90deg)' : ''
                        "
                      >
                        <path d="M9 6l6 6-6 6" /></svg
                    ></span>
                    <svg class="icon ft-icon" viewBox="0 0 24 24">
                      <path
                        d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5z"
                      />
                      <path d="M14 3v5h5" />
                    </svg>
                    <div style="flex: 1; min-width: 0">
                      <div class="ft-name" :title="r.file.path">
                        {{ baseName(r.file.path) }}
                      </div>
                      <div class="ft-prog">
                        <i :style="'width:' + progPct(r.file) + '%'"></i>
                      </div>
                    </div>
                    <span class="ft-count"
                      >{{ r.file.translated }}/{{ r.file.total }}</span
                    ><span v-if="fileUn(r.file)" class="ft-un"
                      >{{ fileUn(r.file) }} неперев.</span
                    >
                  </div>
                  <div v-if="expanded(r.file.path)" class="tree-phrases">
                    <div
                      v-for="g in filePhraseGroups(r.file.path)"
                      :key="g.row"
                      class="tree-ph-group"
                    >
                      <div class="muted tree-ph-rowkey">
                        {{ g.key || "row " + (g.row + 1)
                        }}<span v-if="g.un"> · {{ g.un }} неперев.</span>
                      </div>
                      <div
                        v-for="c in g.cells"
                        :key="c.id"
                        class="tree-phrase"
                        data-ctx="phrase"
                        :data-id="c.id"
                        :class="{ active: focusId === c.id }"
                        @click="focusPhrase(c.id)"
                      >
                        <span :class="'ed-status-pill status-' + c.status">{{
                          c.status
                        }}</span>
                        <div class="tree-ph-text">
                          <div class="tree-ph-src">{{ c.source }}</div>
                          <div v-if="c.columnName" class="muted">
                            {{ c.columnName }}
                          </div>
                        </div>
                      </div>
                    </div>
                    <button
                      v-if="filePhrases(r.file.path).total > 100"
                      class="ghost tree-more"
                      @click="openFile(r.file.path)"
                    >
                      Все {{ filePhrases(r.file.path).total }} фраз →
                    </button>
                  </div>
                </div>
              </div>
            </div>
            <button
              v-if="treeReadyCount"
              class="ghost done-toggle"
              @click="toggleHideReady"
            >
              {{
                fileHideReady
                  ? "Показать готовые (" + treeReadyCount + ")"
                  : "Скрыть готовые (" + treeReadyCount + ")"
              }}
            </button>
            <button
              v-if="treeEmptyCount"
              class="ghost done-toggle"
              @click="toggleHideEmpty"
            >
              {{
                hideEmpty
                  ? "Показать пустые (" + treeEmptyCount + ")"
                  : "Скрыть пустые (" + treeEmptyCount + ")"
              }}
            </button>
          </div>
          <div v-else>
            <div v-if="!focusFileFilter" class="ft-empty">
              <svg class="icon" viewBox="0 0 24 24">
                <path
                  d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2-2V7z"
                /></svg
              ><span>Выберите файл в списке</span>
            </div>
            <div v-else-if="!fileRows.totalGroups" class="ft-empty">
              <svg class="icon" viewBox="0 0 24 24">
                <path d="M4 6h16M4 12h16M4 18h10" /></svg
              ><span>{{
                phraseSearchQ ? "Ничего не найдено" : "В файле нет строк"
              }}</span>
            </div>
            <template v-else>
              <template v-for="(g, gi) in rowGroupsPaged" :key="g.row">
                <div
                  v-if="
                    gi === 0 ||
                    (g.section ?? 0) !== (rowGroupsPaged[gi - 1].section ?? 0)
                  "
                  class="ft-section"
                >
                  Строки {{ (g.section ?? 0) * 100 + 1 }}–{{
                    Math.min(((g.section ?? 0) + 1) * 100, fileRows.totalGroups)
                  }}
                </div>
                <div
                  class="ft-rowcard"
                  :class="{
                    active: g.cells.some((c) => c.id === followRowId),
                    done: !g.un,
                  }"
                >
                  <div
                    class="ft-rowhead"
                    @click="focusPhrase(g.cells[0].id)"
                    :title="'rowIndex ' + g.row"
                  >
                    <span class="ft-rowkey">{{
                      g.rowKey || "row " + (g.row + 1)
                    }}</span
                    ><span v-if="g.cells.length > 1" class="muted"
                      >{{ g.cells.length }} кол.</span
                    ><span style="flex: 1"></span
                    ><span v-if="g.un" class="ft-un">{{ g.un }} неперев.</span
                    ><span v-else class="ft-ok">✓</span>
                  </div>
                  <div
                    v-for="c in g.cells"
                    :key="c.id"
                    class="ft-cell"
                    data-ctx="phrase"
                    :data-id="c.id"
                    :class="{ active: followRowId === c.id }"
                    @click="focusPhrase(c.id)"
                    title="Редактировать"
                  >
                    <span :class="'ed-status-pill status-' + c.status">{{
                      c.status
                    }}</span>
                    <div style="flex: 1; min-width: 0">
                      <div class="ft-cell-src">{{ c.source }}</div>
                      <div v-if="c.translation" class="ft-cell-tr">
                        {{ c.translation }}
                      </div>
                      <div v-if="c.columnName" class="muted ft-cell-col">
                        {{ c.columnName }}
                      </div>
                    </div>
                  </div>
                </div>
              </template>
              <div
                v-if="rowGroupPages > 1"
                class="ft-actions"
                style="border: none; padding-top: 6px"
              >
                <button
                  class="ghost icon-btn"
                  @click="setRowGroupPage(phrasePage - 1)"
                  :disabled="phrasePage === 0"
                  title="Назад"
                >
                  <svg class="icon" viewBox="0 0 24 24">
                    <path d="M15 18l-6-6 6-6" />
                  </svg></button
                ><span class="muted" style="font-size: 11px"
                  >{{ phrasePage + 1 }}/{{ rowGroupPages }}</span
                ><button
                  class="ghost icon-btn"
                  @click="setRowGroupPage(phrasePage + 1)"
                  :disabled="phrasePage >= rowGroupPages - 1"
                  title="Вперёд"
                >
                  <svg class="icon" viewBox="0 0 24 24">
                    <path d="M9 6l6 6 6-6" />
                  </svg>
                </button>
              </div>
            </template>
          </div>
        </div></div
    ></Teleport>
    <main class="main-pane">
      <Editor
        ref="editorRef"
        :entries="doc ? doc.entries : []"
        :focusId="focusId"
        :store-key="projectId"
        :csv-open="csvRequest"
        :csv-root="root"
        :pin-request="previewPinRequest"
        :row-context="rowContext"
        :row-next="editorRowNext"
        :load-row-page="editorLoadRowPage"
        :follow-row="followRow"
        @save="onSave"
        @navigate="onNavigate"
        @need-entry="ensureEntry"
        @resolve="onResolveConflict"
        @file="onEditorFile"
        @entry="onEditorEntry"
        @toggle-follow-row="toggleFollowRow"
        @reveal="onRevealFile"
      />
    </main>
    <section class="dock right" v-show="railVisible.right">
      <div
        class="hsplit left"
        @mousedown="(e) => startResize('right', e)"
        title="Потяните, чтобы изменить ширину"
      ></div>
      <div
        class="dz-rail right-edge"
        @contextmenu.prevent="openZoneMenu('right', $event)"
        @dragover.prevent="
          (e) => onTabDragOver('right', layout.right.length, e)
        "
        @drop="(e) => onDrop('right', e)"
        :class="{
          'drop-end':
            dropPos &&
            dropPos.zone === 'right' &&
            dropPos.index === layout.right.length,
        }"
      >
        <button
          v-for="(v, i) in layout.right"
          :key="v"
          class="dz-ribtn"
          :class="[{ active: active.right === v }, dropClass('right', i)]"
          draggable="true"
          @dragstart="(e) => onTabDragStart('right', v, e)"
          @dragend="onDragEnd"
          @dragover.prevent="(e) => onTabDragOver('right', i, e)"
          @drop.stop="(e) => onDropOnTab('right', i, e)"
          @click="toggleView('right', v)"
          @contextmenu.prevent.stop="openZoneMenu('right', $event, v)"
          :title="VIEWS[v].title"
        >
          <span class="dz-ic" v-html="VIEWS[v].icon"></span>
        </button>
        <span class="grow"></span>
        <button
          class="dz-ribtn dz-add"
          @click="openAddMenu('right', $event)"
          title="Добавить панель"
        >
          +
        </button>
      </div>
      <div class="dz-main" v-show="zoneShown('right')">
        <div class="dz-head">
          <span>{{ activeTitle("right") }}</span
          ><span class="grow"></span
          ><button
            class="dz-gearbtn"
            @click="menuFor = menuFor === 'head:right' ? null : 'head:right'"
            title="Настройки зоны"
          >
            <svg class="icon" viewBox="0 0 24 24">
              <path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3" />
              <path d="M1 14h6M9 8h6M17 16h6" />
            </svg></button
          ><button
            class="dz-xbtn"
            @click="active.right && closeTab('right', active.right)"
            title="Убрать панель"
          >
            ×
          </button>
          <div v-if="menuFor === 'head:right'" class="dz-menu head-menu">
            <div class="dz-menu-i" @click="hideZone('right')">
              Свернуть панель
            </div>
            <div class="dz-menu-i" @click="openAddMenu('right', $event)">
              Добавить панель…
            </div>
            <div class="dz-menu-i dim" @click="resetLayout()">
              Сбросить раскладку
            </div>
          </div>
        </div>
        <div class="dz-body">
          <div
            v-for="v in layout.right"
            :key="v"
            class="dz-host"
            v-show="active.right === v"
            :ref="(el) => setHost(v, el)"
          ></div>
        </div>
      </div>
    </section>
    <section
      class="dock bottom"
      v-show="railVisible.bottom"
      :style="zoneStyle('bottom')"
    >
      <div
        v-if="!narrow"
        class="hsplit top"
        @mousedown="startResizeY"
        title="Потяните, чтобы изменить высоту"
      ></div>
      <div
        class="dz-rail"
        @contextmenu.prevent="openZoneMenu('bottom', $event)"
        @dragover.prevent="
          (e) => onTabDragOver('bottom', layout.bottom.length, e)
        "
        @drop="(e) => onDrop('bottom', e)"
        :class="{
          'drop-end':
            dropPos &&
            dropPos.zone === 'bottom' &&
            dropPos.index === layout.bottom.length,
        }"
      >
        <button
          v-for="(v, i) in layout.bottom"
          :key="v"
          class="dz-ribtn"
          :class="[{ active: active.bottom === v }, dropClass('bottom', i)]"
          draggable="true"
          @dragstart="(e) => onTabDragStart('bottom', v, e)"
          @dragend="onDragEnd"
          @dragover.prevent="(e) => onTabDragOver('bottom', i, e)"
          @drop.stop="(e) => onDropOnTab('bottom', i, e)"
          @click="toggleView('bottom', v)"
          @contextmenu.prevent.stop="openZoneMenu('bottom', $event, v)"
          :title="VIEWS[v].title"
        >
          <span class="dz-ic" v-html="VIEWS[v].icon"></span>
        </button>
        <span class="grow"></span>
        <button
          class="dz-ribtn dz-add"
          @click="openAddMenu('bottom', $event)"
          title="Добавить панель"
        >
          +
        </button>
      </div>
      <div class="dz-main" v-show="zoneShown('bottom')">
        <div class="dz-head">
          <span>{{ activeTitle("bottom") }}</span
          ><span class="grow"></span
          ><button
            class="dz-gearbtn"
            @click="menuFor = menuFor === 'head:bottom' ? null : 'head:bottom'"
            title="Настройки зоны"
          >
            <svg class="icon" viewBox="0 0 24 24">
              <path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3" />
              <path d="M1 14h6M9 8h6M17 16h6" />
            </svg></button
          ><button
            class="dz-xbtn"
            @click="active.bottom && closeTab('bottom', active.bottom)"
            title="Убрать панель"
          >
            ×
          </button>
          <div v-if="menuFor === 'head:bottom'" class="dz-menu head-menu">
            <div class="dz-menu-i" @click="hideZone('bottom')">
              Свернуть панель
            </div>
            <div class="dz-menu-i" @click="openAddMenu('bottom', $event)">
              Добавить панель…
            </div>
            <div class="dz-menu-i dim" @click="resetLayout()">
              Сбросить раскладку
            </div>
          </div>
        </div>
        <div class="dz-body">
          <div
            v-for="v in layout.bottom"
            :key="v"
            class="dz-host"
            v-show="active.bottom === v"
            :ref="(el) => setHost(v, el)"
          ></div>
        </div>
      </div>
    </section>
    <Teleport v-if="hostEl('log')" :to="hostEl('log')">
      <LogView :log="logText" data-ctx="log" />
    </Teleport>
    <Teleport v-if="hostEl('translate')" :to="hostEl('translate')">
      <TranslateView
        :files="sourceFiles"
        :selected="selTranslateSet"
        :tr-count="selTranslate.length"
        :estimate="trEstimate"
        :pending-map="trPendingMap"
        :map-ready="trPendingReady"
        :loading="sourceLoading"
        :error="sourceError"
        :root="root"
        :gemini="geminiStatus"
        :job="job"
        @toggle="toggleTranslate"
        @sel-visible="selTranslateVisible"
        @clear-sel="clearTranslateSel"
        @refresh="scanSource"
        @preview="openPreview"
        @translate="runTranslate"
        @cancel="cancelJob"
      />
    </Teleport>
    <Teleport v-if="hostEl('search')" :to="hostEl('search')">
      <SearchView
        :q="searchQ"
        @update:q="searchQ = $event"
        :matches="matches"
        :loading="searchLoading"
        @search="doSearch"
        @open="openSearchResult"
      />
    </Teleport>
    <Teleport v-if="hostEl('tags')" :to="hostEl('tags')">
      <TagsView
        :filter="tagFilter"
        @update:filter="tagFilter = $event"
        @insert="insertTagToEditor"
      />
    </Teleport>
    <Teleport v-if="hostEl('summary')" :to="hostEl('summary')">
      <SummaryView :summary="summary" />
    </Teleport>
    <Teleport v-if="hostEl('pack')" :to="hostEl('pack')">
      <PackView
        :project-id="projectId"
        :pack="pack"
        :errors="packErrors"
        :manifest="packManifest"
        :msg="packMsg"
        :compat="packCompat"
        :langs="packLangs"
        @update:compat="packCompat = $event"
        @update:langs="packLangs = $event"
        @save="savePack"
        @add-author="packAddAuthor"
        @del-author="packDelAuthor"
        @toast="showToast"
      />
    </Teleport>
    <Teleport v-if="hostEl('export')" :to="hostEl('export')">
      <ExportView
        :project-id="projectId"
        :job="job"
        :scope="selExportSet"
        :data-rev="exportRev"
        :entry-update="savedEntry"
        @build="buildPack"
        @build-download="buildAndDownloadPack"
        @toast="showToast"
        @toggle-scope="toggleExport"
        @clear-scope="clearExportScope"
      />
    </Teleport>
    <Teleport v-if="hostEl('delta')" :to="hostEl('delta')">
      <DeltaView
        ref="deltaRef"
        :project-id="projectId"
        @toast="showToast"
        @refresh="loadProject"
        @open-conflict="openConflict"
      />
    </Teleport>
  </div>

  <footer class="statusbar" v-if="!showPicker">
    <span class="sb-item sb-proj" :title="projectId">{{
      projectName || projectId || "—"
    }}</span>
    <span
      class="sb-item sb-badge"
      @click="gotoView('summary')"
      title="Сводка"
      >{{ badge }}</span
    >
    <span v-if="job" class="sb-item"
      >{{
        {
          extract: "Обновление",
          gemini: "Gemini",
          openrouter: "OpenRouter",
          merge: "Сборка",
          "sync-sources": "Синхронизация",
          update: "Обновление",
        }[job.action] || job.action
      }}:
      {{
        job.status === "running"
          ? "…"
          : job.status === "queued"
            ? "в очереди"
            : job.status
      }}</span
    >
    <span
      class="sb-item"
      @click="openSettings('sources')"
      :title="(sourceStatus.activeRoot || '') + ' — настроить источники'"
      ><span
        class="status-dot"
        :class="sourceStatus.ready ? 'on' : 'off'"
      ></span
      >{{ sourceLabel }}</span
    >
    <span class="grow"></span>
    <span class="sb-item sb-ai" @click="openSettings('ai')" :title="aiTitle"
      ><span
        class="status-dot"
        :class="geminiStatus.configured ? 'on' : 'off'"
      ></span
      >Gemini<span class="sb-sep">·</span
      ><span
        class="status-dot"
        :class="geminiStatus.openrouterConfigured ? 'on' : 'off'"
      ></span
      >OpenRouter</span
    >
    <span
      v-if="upd.supported || upd.version"
      class="sb-item sb-upd"
      :class="{ 'sb-warn': upd.updateAvailable }"
      @click="
        upd.updateAvailable ||
        upd.needsToolchain ||
        ['local_ahead', 'diverged', 'check_failed'].includes(upd.state)
          ? (updModal = true)
          : loadUpdateStatus()
      "
      :title="updTitle"
      >{{ updLabel }}</span
    >
  </footer>

  <div v-if="paletteOpen" class="overlay" @click.self="closePalette">
    <div class="modal palette">
      <div class="search-box" style="border: none; padding: 0 0 8px">
        <svg class="icon ic-search" viewBox="0 0 24 24">
          <circle cx="11" cy="11" r="7" />
          <path d="M21 21l-4.3-4.3" /></svg
        ><input
          ref="paletteInput"
          class="grow"
          :value="paletteQ"
          @input="
            paletteQ = ($event.target as HTMLInputElement).value;
            paletteIdx = 0;
          "
          placeholder="Файл, фраза или команда…"
          @keydown="onPaletteKey"
        />
      </div>
      <div class="palette-list">
        <div
          v-for="(it, ri) in paletteResults"
          :key="it.key"
          class="palette-item"
          :class="{ active: ri === paletteIdx }"
          @mouseenter="paletteIdx = ri"
          @click="runPalette(it)"
        >
          <span class="palette-hint">{{ it.hint }}</span
          ><span class="palette-text">{{ it.t }}</span>
        </div>
        <div v-if="!paletteResults.length" class="ft-empty">
          <span>Ничего не найдено</span>
        </div>
      </div>
    </div>
  </div>

  <div
    v-if="ctxZone"
    style="position: fixed; inset: 0; z-index: 300"
    @click="ctxZone = null"
    @contextmenu.prevent="ctxZone = null"
  ></div>
  <div
    v-if="ctxZone"
    class="dz-menu"
    style="position: fixed; z-index: 301; bottom: auto"
    :style="{ left: ctxZone.x + 'px', top: ctxZone.y + 'px' }"
  >
    <div v-if="ctxZone.view" class="dz-menu-i" @click="hideWidget()">
      Скрыть виджет
    </div>
    <div v-else class="dz-menu-i" @click="hidePanel(ctxZone.zone)">
      Скрыть панель
    </div>
  </div>
  <div
    v-if="addMenu"
    class="dz-menu"
    style="position: fixed; z-index: 301; bottom: auto"
    :style="{ left: addMenu.x + 'px', top: addMenu.y + 'px' }"
  >
    <div class="dz-menu-h">Добавить панель</div>
    <div
      v-if="!hiddenViews.length"
      class="muted"
      style="padding: 4px 10px; font-size: 12px"
    >
      Все панели размещены
    </div>
    <div
      v-for="v in hiddenViews"
      :key="v"
      class="dz-menu-i"
      @click="
        addView(addMenu.zone, v);
        addMenu = null;
      "
    >
      <span class="dz-ic" v-html="VIEWS[v].icon"></span>{{ VIEWS[v].title }}
    </div>
    <div
      class="dz-menu-i dim"
      @click="
        resetLayout();
        addMenu = null;
      "
    >
      Сбросить раскладку
    </div>
  </div>
  <div
    v-if="ctxMenu"
    class="dz-menu"
    style="position: fixed; z-index: 302; bottom: auto"
    :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }"
  >
    <div
      v-for="(it, i) in ctxMenu.items"
      :key="i"
      class="dz-menu-i"
      :class="{ sel: it.sel }"
      @click="runCtx(it)"
    >
      {{ it.t }}
    </div>
  </div>
  <div v-if="updModal" class="overlay" @click.self="updModal = false">
    <div class="modal upd-modal">
      <div class="upd-title">
        {{
          updateFailed
            ? "Обновление не выполнено"
            : upd.state === "local_ahead"
              ? "Локальная версия новее"
              : upd.state === "diverged"
                ? "История исходников расходится"
                : upd.state === "check_failed"
                  ? "Проверка обновления не выполнена"
                  : "Доступно новое обновление"
        }}
      </div>
      <template v-if="updateFailed">
        <div class="muted">
          Причина записана в журнал приложения. Скопируйте хвост журнала для
          диагностики.
        </div>
        <div class="set-actions">
          <button class="primary" @click="copyUpdateLog" :disabled="updLogBusy">
            {{ updLogBusy ? "Копирование…" : "Скопировать журнал" }}</button
          ><button class="ghost" @click="updModal = false">Закрыть</button>
        </div>
      </template>
      <template
        v-else-if="
          ['local_ahead', 'diverged', 'check_failed'].includes(upd.state)
        "
      >
        <div class="muted">
          {{ upd.reason || "Обновление сейчас недоступно" }}
        </div>
        <div class="set-actions">
          <button class="ghost" @click="updModal = false">Закрыть</button>
        </div>
      </template>
      <template v-else>
        <div v-if="upd.needsToolchain" class="muted">
          Для обновления один раз установятся JDK, Git и Node.js
        </div>
        <div v-if="!upd.needsToolchain" class="muted">
          v{{ upd.version }} · {{ (upd.currentSha || "").slice(0, 7) }} →
          {{ (upd.latestSha || "").slice(0, 7) }} · коммитов: {{ upd.behindBy }}
        </div>
        <template v-if="!upd.needsToolchain">
          <div class="upd-sec">В ЭТОМ ОБНОВЛЕНИИ</div>
          <ul class="upd-list">
            <li v-for="(s, i) in upd.subjects || []" :key="i">{{ s }}</li>
          </ul>
          <div v-if="upd.behindBy > (upd.subjects || []).length" class="muted">
            + ещё {{ upd.behindBy - (upd.subjects || []).length }} изменений
          </div>
        </template>
        <div class="set-actions">
          <button class="primary" @click="runUpdate">Обновить сейчас</button
          ><button class="ghost" @click="updModal = false">
            Возможно позже
          </button>
        </div>
      </template>
    </div>
  </div>
  <div v-if="updRestarting" class="overlay">
    <div class="modal upd-modal">
      <div class="upd-title">Перезапуск…</div>
      <div class="muted">
        Новая версия запускается — страница обновится сама
      </div>
      <div v-if="updRestartDead" class="muted">
        Не удалось запустить новую версию за 2 минуты — запусти приложение
        вручную
      </div>
    </div>
  </div>
  <UiToast :message="toast" />
</template>
