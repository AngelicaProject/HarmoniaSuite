<script lang="ts">
import { defineComponent } from "vue";
import { computed, nextTick, onMounted, ref, shallowRef, watch } from "vue";
import { api } from "./api/client";
import {
  ENTRY_STATUS,
  isNoTranslationRequired,
  isTranslated as isEntryTranslated,
  needsWork as entryNeedsWork,
} from "./domain/translationStatus";
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
import { useFileTree } from "./composables/useFileTree";
import { useUpdater } from "./composables/useUpdater";
import { useCommandPalette } from "./composables/useCommandPalette";
import { useDockLayout } from "./composables/useDockLayout";
import { useJobs } from "./composables/useJobs";
import type {
  AiStatus,
  Entry,
  FileStats,
  Job,
  PackMeta,
  PackResponse,
  Summary,
  SourceSettings,
  DeltaConflict,
} from "./api/types";

interface ProjectDocument {
  files: FileStats[];
  entries: Entry[];
  _loadMs?: number;
}

interface DisplayRowGroup {
  row: number;
  rowKey?: string;
  key?: string;
  section?: number;
  cells: Entry[];
  un: number;
  pos?: number;
}

interface FileRowState {
  file: string;
  q: string;
  page: number;
  groups: DisplayRowGroup[];
  totalGroups: number;
  loading: boolean;
}

interface UiAiStatus extends AiStatus {
  configured: boolean;
  model: string;
  models: string[];
  openrouterConfigured: boolean;
  openrouterModel: string;
}

interface SearchMatch {
  id: string;
  file: string;
  rowKey: string;
  source: string;
  translation: string;
}

interface ContextItem {
  t: string;
  sel?: boolean;
  run: () => void | Promise<void>;
}

interface ContextMenuState {
  x: number;
  y: number;
  items: ContextItem[];
}

interface SavedEntry {
  previous: Entry | null;
  entry: Entry;
  fileStats: FileStats | null;
}

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

type Timer = ReturnType<typeof setTimeout>;

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
  },
  setup() {
    const projectId = ref("");
    const projectName = ref("");
    const root = ref("");
    const showPicker = ref(true);
    const doc = shallowRef<ProjectDocument | null>(null);
    // Keep cache invalidation separate from revisions that cause network reloads.
    const cacheRev = ref(0);
    const statsRev = ref(0);
    const exportRev = ref(0);
    const savedEntry = shallowRef<SavedEntry | null>(null);
    const fileTreeState = useFileTree(projectId, () => {
      cacheRev.value++;
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
    const rowGroupPageSize = 60;
    const fileRows = ref<FileRowState>({
      file: "",
      q: "",
      page: 0,
      groups: [],
      totalGroups: 0,
      loading: false,
    });
    const filePreviewCache = new Map<
      string,
      { rows: Entry[]; total: number }
    >();
    let rowRequest = 0;
    let phraseSearchTimer: Timer | null = null;
    let trPendingTimer: Timer | null = null;
    const summary = ref<Summary | null>(null);
    const projectLoading = ref(false);
    const sourceFiles = ref<string[]>([]);
    const sourceLoading = ref(false);
    const sourceError = ref("");
    const selTranslate = ref<string[]>([]);
    const trPendingMap = ref<Record<string, number>>({});
    const trPendingReady = ref(false);
    async function fetchTrPending() {
      if (!projectId.value) {
        trPendingMap.value = {};
        trPendingReady.value = false;
        return;
      }
      try {
        const d = await api.pendingByFile(projectId.value);
        trPendingMap.value = (d && d.files) || {};
        trPendingReady.value = true;
      } catch {
        /* карта некритична — список покажем целиком */
      }
    }
    watch(statsRev, () => {
      if (trPendingTimer) clearTimeout(trPendingTimer);
      trPendingTimer = setTimeout(fetchTrPending, 300);
    });
    const trEstimate = computed(() => {
      let n = 0;
      const m = trPendingMap.value || {};
      for (const f of selTranslate.value) n += m[f] || 0;
      return { entries: n };
    });
    const selExport = ref<string[]>([]);
    const selTranslateSet = computed(() => new Set(selTranslate.value));
    const selExportSet = computed(() => new Set(selExport.value));
    function selStorageKey() {
      return "hs-sel-" + projectId.value;
    }
    function saveSelection() {
      try {
        localStorage.setItem(
          selStorageKey(),
          JSON.stringify({
            translate: selTranslate.value,
            export: selExport.value,
          }),
        );
      } catch (e) {}
    }
    function loadSelection() {
      try {
        const s = JSON.parse(localStorage.getItem(selStorageKey()) || "null");
        selTranslate.value = Array.isArray(s?.translate) ? s.translate : [];
        selExport.value = Array.isArray(s?.export) ? s.export : [];
      } catch (e) {
        selTranslate.value = [];
        selExport.value = [];
      }
    }
    const logText = ref("Готово к работе.");
    const geminiStatus = ref<UiAiStatus>({
      configured: false,
      model: "",
      models: [],
      openrouterConfigured: false,
      openrouterModel: "",
    });
    async function loadGeminiStatus() {
      try {
        const s = await api.status();
        geminiStatus.value = {
          configured: !!s.geminiConfigured,
          model: s.geminiModel || "",
          models: s.geminiModels || [],
          openrouterConfigured: !!s.openrouterConfigured,
          openrouterModel: s.openrouterModel || "",
        };
      } catch (e) {}
    }
    loadGeminiStatus();
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
    const {
      job,
      pendingPack,
      jobLog,
      jobMainOutput,
      updateFailed,
      onJobScroll,
      jobActive,
      cancelJob,
    } = jobs;
    const aiTitle = computed(() => {
      const g = geminiStatus.value;
      const gl = g.configured
        ? "ключ задан" + (g.model ? ", " + g.model : "")
        : "без ключа";
      const ol = g.openrouterConfigured
        ? "ключ задан" + (g.openrouterModel ? ", " + g.openrouterModel : "")
        : "без ключа";
      return "Gemini: " + gl + "\nOpenRouter: " + ol;
    });
    const sourceStatus = ref<SourceSettings>({
      gamePath: "",
      gameValid: false,
      gameVersion: "",
      activeRoot: "",
      ready: false,
      configured: false,
    });
    const showSettings = ref(false);
    const settingsSection = ref("sources");
    function openSettings(s = "") {
      settingsSection.value = s || settingsSection.value;
      showSettings.value = true;
    }
    async function loadSourceStatus() {
      try {
        const s = await api.settings();
        sourceStatus.value = s;
        if (s.activeRoot) root.value = s.activeRoot;
        if (!s.configured) openSettings("sources");
      } catch (e) {
        logText.value += "\nИсточники: " + errorMessage(e);
      }
    }
    const sourceLabel = computed(() => {
      const s = sourceStatus.value;
      if (!s.configured) return "источники не настроены";
      return s.gameVersion || s.activeRoot || "источники";
    });
    const badge = computed(() => {
      const s = summary.value;
      if (!s || s.entries === undefined) return "—";
      return `${s.translated ?? 0} / ${s.entries}`;
    });
    const rg = ref("");
    const theme = ref(
      document.documentElement.getAttribute("data-theme") || "dark",
    );

    // ---- IDE docking: state and interactions live in a dedicated composable ----
    const VIEWS: Record<string, { title: string; icon: string }> = {
      project: {
        title: "Проект",
        icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1 2 2H5a2 2 0 0 1-2-2V7z"/></svg>',
      },
      search: {
        title: "Поиск",
        icon: '<svg class="icon" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg>',
      },
      translate: {
        title: "AI перевод",
        icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"/><path d="M19 15l.9 2.1L22 18l-2.1.9L19 21l-.9-2.1L16 18l2.1-.9z"/></svg>',
      },
      tags: {
        title: "Теги",
        icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><circle cx="7" cy="7" r="1.2"/></svg>',
      },
      summary: {
        title: "Сводка",
        icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M3 3v18h18"/><rect x="7" y="11" width="3" height="7"/><rect x="12" y="7" width="3" height="11"/><rect x="17" y="13" width="3" height="5"/></svg>',
      },
      pack: {
        title: "Пак",
        icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M21 8l-9-5-9 5v8l9 5 9-5V8z"/><path d="M3 8l9 5 9-5M12 13v8"/></svg>',
      },
      export: {
        title: "Экспорт",
        icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M12 3v12M8 11l4 4 4-4"/><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/></svg>',
      },
      delta: {
        title: "Дельты",
        icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M7 8h11l-3-3M17 16H6l3 3"/></svg>',
      },
      log: {
        title: "Журнал",
        icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M4 6h16M4 12h16M4 18h10"/></svg>',
      },
    };
    const VIEW_IDS = [
      "project",
      "translate",
      "search",
      "tags",
      "summary",
      "pack",
      "export",
      "delta",
      "log",
    ];
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
      if (ctxMenu.value) ctxMenu.value = null;
    }

    const ctxMenu = ref<ContextMenuState | null>(null);
    async function copyText(t: unknown): Promise<void> {
      const s = String(t ?? "");
      try {
        await navigator.clipboard.writeText(s);
      } catch {
        try {
          const ta = document.createElement("textarea");
          ta.value = s;
          ta.style.position = "fixed";
          ta.style.opacity = "0";
          document.body.appendChild(ta);
          ta.select();
          document.execCommand("copy");
          ta.remove();
        } catch {
          showToast("Не скопировалось");
          return;
        }
      }
      showToast("Скопировано");
    }
    function ctxItems(el: HTMLElement): ContextItem[] | null {
      const kind = el.dataset.ctx;
      if (kind === "file") {
        const p = el.dataset.path || "";
        return [
          { t: "Открыть", run: () => openFile(p) },
          {
            t: expanded(p) ? "Свернуть строки" : "Показать строки",
            run: () => toggleExpand(p),
          },
          { t: "Копировать путь", run: () => copyText(p) },
          {
            t: "В перевод",
            sel: selTranslateSet.value.has(p),
            run: () => toggleTranslate(p, !selTranslateSet.value.has(p)),
          },
          {
            t: "В сборку",
            sel: selExportSet.value.has(p),
            run: () => toggleExport(p, !selExportSet.value.has(p)),
          },
        ];
      }
      if (kind === "phrase") {
        const id = el.dataset.id || "";
        const e = entryById.value.get(id) || null;
        const items: ContextItem[] = [
          { t: "Открыть в редакторе", run: () => focusPhrase(id) },
        ];
        if (e) {
          items.push({
            t: "Копировать оригинал",
            run: () => copyText(e.source || ""),
          });
          items.push({
            t: "Копировать перевод",
            run: () => copyText(e.translation || ""),
          });
          items.push({ t: "Копировать id", run: () => copyText(e.id || "") });
        }
        return items;
      }
      if (kind === "log")
        return [{ t: "Копировать журнал", run: () => copyText(logText.value) }];
      return null;
    }
    function onGlobalCtx(e: MouseEvent): void {
      if (e.defaultPrevented) return;
      const target = e.target instanceof Element ? e.target : null;
      if (target?.closest('input,textarea,select,[contenteditable="true"]'))
        return;
      const el = target?.closest("[data-ctx]") as HTMLElement | null;
      e.preventDefault();
      if (menuFor.value) menuFor.value = null;
      if (addMenu.value) addMenu.value = null;
      if (!el) {
        ctxMenu.value = null;
        return;
      }
      const items = ctxItems(el);
      if (!items || !items.length) {
        ctxMenu.value = null;
        return;
      }
      ctxMenu.value = {
        x: Math.min(e.clientX, window.innerWidth - 230),
        y: Math.min(e.clientY, window.innerHeight - items.length * 34 - 16),
        items,
      };
    }
    function runCtx(it: ContextItem): void {
      ctxMenu.value = null;
      if (it && it.run) it.run();
    }

    function toggleTheme() {
      theme.value = theme.value === "dark" ? "light" : "dark";
      localStorage.setItem("theme", theme.value);
      document.documentElement.setAttribute("data-theme", theme.value);
    }

    function mergeEntries(items: Entry[]): void {
      if (!items || !items.length) return;
      const entries = new Map((doc.value?.entries || []).map((e) => [e.id, e]));
      for (const entry of items)
        if (entry && entry.id) entries.set(entry.id, entry);
      doc.value = {
        files: doc.value?.files || [],
        entries: [...entries.values()],
        _loadMs: doc.value?._loadMs,
      };
      cacheRev.value++;
    }

    function isCountedAsTranslated(entry: Entry): boolean {
      return isEntryTranslated(entry);
    }

    function isPendingForTranslation(entry: Entry): boolean {
      return (
        !!entry &&
        String(entry.translation || "").trim() === "" &&
        !isNoTranslationRequired(entry.status)
      );
    }

    function localEntry(id: string): Entry | null {
      const known = (doc.value?.entries || []).find((entry) => entry.id === id);
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

    function replaceEntryInRows(entry: Entry): void {
      const state = fileRows.value;
      if (!entry || state.file !== entry.file) return;
      let changed = false;
      const groups = (state.groups || []).map((group) => {
        const old = (group.cells || []).find((cell) => cell.id === entry.id);
        if (!old) return group;
        changed = true;
        const cells = group.cells.map((cell) =>
          cell.id === entry.id ? entry : cell,
        );
        const un =
          (group.un || 0) -
          (needsWork(old) ? 1 : 0) +
          (needsWork(entry) ? 1 : 0);
        return { ...group, cells, un };
      });
      if (changed) fileRows.value = { ...state, groups };
    }

    function replaceEntryInPreview(entry: Entry): void {
      if (!entry) return;
      const preview = filePreviewCache.get(entry.file);
      if (!preview) return;
      const rows = preview.rows || [];
      const index = rows.findIndex((cell) => cell.id === entry.id);
      if (index >= 0) {
        preview.rows = [
          ...rows.slice(0, index),
          entry,
          ...rows.slice(index + 1),
        ];
      }
    }

    function updateLocalStats(previous: Entry | null, entry: Entry): void {
      if (!entry || !previous || previous.file !== entry.file) return;
      const delta =
        Number(isCountedAsTranslated(entry)) -
        Number(isCountedAsTranslated(previous));
      if (!delta) return;
      fileTree.value = fileTree.value.map((file) =>
        file.path === entry.file
          ? { ...file, translated: Math.max(0, (file.translated || 0) + delta) }
          : file,
      );
    }

    function updateLocalPending(previous: Entry | null, entry: Entry): void {
      if (
        !entry ||
        !previous ||
        previous.file !== entry.file ||
        !trPendingReady.value
      )
        return;
      const delta =
        Number(isPendingForTranslation(entry)) -
        Number(isPendingForTranslation(previous));
      if (!delta) return;
      const next = Math.max(0, (trPendingMap.value[entry.file] || 0) + delta);
      const map = { ...trPendingMap.value };
      if (next) map[entry.file] = next;
      else delete map[entry.file];
      trPendingMap.value = map;
    }

    function syncSavedEntry(previous: Entry | null, entry: Entry): void {
      if (!entry || !entry.id) return;
      updateLocalStats(previous, entry);
      updateLocalPending(previous, entry);
      mergeEntries([entry]);
      replaceEntryInRows(entry);
      replaceEntryInPreview(entry);
    }

    function decorateRowGroups(
      groups: DisplayRowGroup[],
      page: number,
    ): DisplayRowGroup[] {
      return (groups || []).map((group, i) => {
        const pos = page * rowGroupPageSize + i;
        return { ...group, pos, section: Math.floor(pos / 100) };
      });
    }

    async function loadFileRows(
      file: string,
      page = 0,
      q = "",
    ): Promise<FileRowState | null> {
      if (!file || !projectId.value) return null;
      const cleanPage = Math.max(0, page | 0);
      const cleanQ = String(q || "").trim();
      const previousPageIds = new Set(
        (fileRows.value.groups || []).flatMap((g) =>
          (g.cells || []).map((cell) => cell.id),
        ),
      );
      const activeEntryId = focusId.value || editorRef.value?.current?.id || "";
      const request = ++rowRequest;
      fileRows.value = { ...fileRows.value, loading: true };
      try {
        const d = await api.rowsPage(projectId.value, {
          file,
          offset: cleanPage * rowGroupPageSize,
          limit: rowGroupPageSize,
          q: cleanQ,
        });
        if (request !== rowRequest) return null;
        const groups = decorateRowGroups(d.groups || [], cleanPage);
        const pageEntries = groups.flatMap((g) => g.cells || []);
        const entries = new Map<string, Entry>();
        for (const entry of doc.value?.entries || []) {
          if (
            entry &&
            entry.id &&
            (!previousPageIds.has(entry.id) || entry.id === activeEntryId)
          )
            entries.set(entry.id, entry);
        }
        for (const entry of pageEntries) {
          if (entry && entry.id) entries.set(entry.id, entry);
        }
        doc.value = {
          files: doc.value?.files || [],
          entries: [...entries.values()],
          _loadMs: doc.value?._loadMs,
        };
        cacheRev.value++;
        fileRows.value = {
          file,
          q: cleanQ,
          page: cleanPage,
          groups,
          totalGroups: Number(d.totalGroups || 0),
          loading: false,
        };
        return fileRows.value;
      } catch (e) {
        if (request === rowRequest)
          fileRows.value = { ...fileRows.value, loading: false };
        throw e;
      }
    }

    async function loadFilePreview(file: string): Promise<void> {
      if (!file || !projectId.value || filePreviewCache.has(file)) return;
      const d = await api.entries(projectId.value, { file }, { limit: 100 });
      filePreviewCache.set(file, {
        rows: d.entries || [],
        total: d.total || 0,
      });
      cacheRev.value++;
    }

    async function ensureEntry(id: string): Promise<Entry | null> {
      if (!id || !projectId.value) return null;
      const e = (doc.value?.entries || []).find((x) => x.id === id);
      if (e) return e;
      const loaded = await api.entryByCell(projectId.value, id);
      if (loaded && loaded.id) mergeEntries([loaded]);
      return loaded || null;
    }

    async function loadProject() {
      projectLoading.value = true;
      try {
        const t0 = performance.now();
        const ov = await api.overview(projectId.value);
        const t1 = Math.round(performance.now() - t0);
        doc.value = { files: [], entries: [] };
        fileRows.value = {
          file: "",
          q: "",
          page: 0,
          groups: [],
          totalGroups: 0,
          loading: false,
        };
        filePreviewCache.clear();
        rowRequest++;
        doc.value._loadMs = t1;
        summary.value = ov.summary || null;
        loadSelection();
        cacheRev.value++;
        if (ov.inputRoot) root.value = ov.inputRoot;
        if (ov.name) projectName.value = ov.name;
        logText.value +=
          "\nПроект загружен: " +
          projectId.value +
          " (" +
          (ov.summary?.entries ?? 0) +
          " фраз, " +
          doc.value._loadMs +
          " мс сеть+разбор)";
        if (!pack.value) await loadPack();
        expandedDirs.value = {};
        await loadFileTree();
        exportRev.value++;
        if (focusFileFilter.value && leftMode.value === "phrases") {
          await loadFileRows(focusFileFilter.value, 0, phraseSearchQ.value);
        }
      } catch (e) {
        logText.value += "\nОшибка: " + errorMessage(e);
      }
      projectLoading.value = false;
    }

    async function scanSource() {
      sourceLoading.value = true;
      sourceError.value = "";
      sourceFiles.value = [];
      try {
        const f0 = performance.now();
        const d = await api.sourceFiles(root.value);
        sourceFiles.value = d.files || [];
        logText.value +=
          "\nИсходники: " +
          sourceFiles.value.length +
          " файлов (" +
          Math.round(performance.now() - f0) +
          " мс)";
        rg.value = "активен";
      } catch (e) {
        sourceError.value = errorMessage(e) || "не удалось загрузить файлы";
        logText.value += "\n" + errorMessage(e);
      }
      sourceLoading.value = false;
    }

    const entryById = computed<Map<string, Entry>>(() => {
      const m = new Map<string, Entry>();
      for (const e of doc.value?.entries || []) m.set(e.id, e);
      return m;
    });
    function scopeFile(): string {
      return focusFileFilter.value || "";
    }
    const leftMode = ref("files");
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
    function needsWork(e: Entry): boolean {
      return entryNeedsWork(e);
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
    function filePhraseGroups(f: string): DisplayRowGroup[] {
      const groups = new Map<number, DisplayRowGroup>();
      for (const e of filePhrases(f).rows || []) {
        let g = groups.get(e.rowIndex);
        if (!g) {
          g = { row: e.rowIndex, key: e.rowKey, cells: [], un: 0 };
          groups.set(e.rowIndex, g);
        }
        g.cells.push(e);
        if (needsWork(e)) g.un++;
      }
      const out = [...groups.values()].sort((a, b) => a.row - b.row);
      out.forEach((g) => g.cells.sort((a, b) => a.columnIndex - b.columnIndex));
      return out;
    }
    function filePhrases(f: string): { rows: Entry[]; total: number } {
      cacheRev.value;
      const preview = filePreviewCache.get(f);
      if (!preview) return { rows: [], total: 0 };
      const un: Entry[] = [],
        done: Entry[] = [];
      for (const e of preview.rows || []) (needsWork(e) ? un : done).push(e);
      return { rows: un.concat(done).slice(0, 100), total: preview.total || 0 };
    }
    const phrasePage = ref(0);
    const phraseSearchQ = ref("");
    const fileRowGroups = computed(() => {
      return fileRows.value.file === scopeFile() ? fileRows.value.groups : [];
    });
    const rowGroupsPaged = computed(() => fileRowGroups.value);
    const rowGroupPages = computed(
      () => Math.ceil(fileRows.value.totalGroups / rowGroupPageSize) || 1,
    );
    const rowContext = computed(() => ({
      file: fileRows.value.file,
      q: fileRows.value.q,
      page: fileRows.value.page,
      pageSize: rowGroupPageSize,
      totalGroups: fileRows.value.totalGroups,
      groups: fileRows.value.groups,
    }));

    async function setRowGroupPage(page: number): Promise<void> {
      const f = scopeFile();
      if (!f) return;
      phrasePage.value = Math.max(0, Math.min(rowGroupPages.value - 1, page));
      try {
        await loadFileRows(f, phrasePage.value, phraseSearchQ.value);
      } catch (e) {
        logText.value += "\n" + errorMessage(e);
      }
    }

    function setPhraseSearch(value: string): void {
      phraseSearchQ.value = value || "";
      phrasePage.value = 0;
      if (phraseSearchTimer) clearTimeout(phraseSearchTimer);
      phraseSearchTimer = setTimeout(() => {
        const f = scopeFile();
        if (!f) return;
        loadFileRows(f, 0, phraseSearchQ.value).catch(
          (e) => (logText.value += "\n" + errorMessage(e)),
        );
      }, 250);
    }

    async function openFile(f: string): Promise<void> {
      focusFileFilter.value = f;
      leftMode.value = "phrases";
      phrasePage.value = 0;
      phraseSearchQ.value = "";
      const page = await loadFileRows(f, 0, "");
      let first: Entry | null = null;
      try {
        first = await api.rowsNext(projectId.value, {
          file: f,
          afterRow: -1,
          afterCol: -1,
        });
        if (first) mergeEntries([first]);
      } catch (e) {
        logText.value += "\n" + errorMessage(e);
      }
      const fallback =
        page && page.groups.length ? page.groups[0].cells[0] : null;
      const target = first || fallback;
      if (target) setFocusId(target.id);
    }

    function backToFiles(): void {
      leftMode.value = "files";
      focusFileFilter.value = "";
      phraseSearchQ.value = "";
      followRowId.value = "";
    }

    async function focusPhrase(id: string): Promise<void> {
      try {
        const e = await ensureEntry(id);
        if (!e) return;
        setFocusId(id);
        const f = e.file || (entryById.value.get(id) || {}).file || "";
        if (f && leftMode.value === "files") {
          await revealInFiles(f, false, id);
        } else if (
          f &&
          leftMode.value === "phrases" &&
          (!fileRows.value.groups ||
            !fileRows.value.groups.some((g) =>
              (g.cells || []).some((c) => c.id === id),
            ))
        ) {
          const pos = await api.rowsPosition(projectId.value, {
            file: f,
            rowIndex: e.rowIndex,
            q: phraseSearchQ.value,
          });
          phrasePage.value = Math.floor(Number(pos || 0) / rowGroupPageSize);
          await loadFileRows(f, phrasePage.value, phraseSearchQ.value);
        }
      } catch (err) {
        logText.value +=
          "\n" + (errorMessage(err) || "Не удалось открыть фразу");
      }
    }

    const tab = ref("translate");
    const focusId = ref("");
    const focusFileFilter = ref("");
    const editorRef = ref<EditorHandle | null>(null);
    const deltaRef = ref<DeltaHandle | null>(null);
    const followFiles = ref(
      (() => {
        try {
          return localStorage.getItem("hs-follow") !== "off";
        } catch (e) {
          return true;
        }
      })(),
    );
    const followRow = ref(
      (() => {
        try {
          return localStorage.getItem("hs-follow-row") === "on";
        } catch (e) {
          return false;
        }
      })(),
    );
    const followRowId = ref("");
    const revealFile = ref("");
    let lastReveal = "";
    let revealTimer: Timer | null = null;
    function toggleFollow() {
      followFiles.value = !followFiles.value;
      try {
        localStorage.setItem("hs-follow", followFiles.value ? "on" : "off");
      } catch (e) {}
    }
    function toggleFollowRow() {
      followRow.value = !followRow.value;
      try {
        localStorage.setItem("hs-follow-row", followRow.value ? "on" : "off");
      } catch (e) {}
      if (!followRow.value) {
        rowFollowRequest++;
        followRowId.value = "";
      } else if (leftMode.value === "phrases" && focusId.value) {
        onEditorEntry(focusId.value, true);
      }
    }
    function setFocusId(id: string): void {
      focusId.value = id;
      if (followRow.value && leftMode.value === "phrases")
        followRowId.value = id;
    }
    let rowFollowRequest = 0;
    async function onEditorEntry(id: string, force = false): Promise<void> {
      const request = ++rowFollowRequest;
      if (!id) return;
      const previousId = focusId.value;
      const previous = previousId ? localEntry(previousId) : null;
      setFocusId(id);
      if (
        !followRow.value ||
        leftMode.value !== "phrases" ||
        (!force && id === previousId)
      )
        return;
      const entry = await ensureEntry(id);
      if (
        !entry ||
        request !== rowFollowRequest ||
        !followRow.value ||
        focusId.value !== id
      )
        return;
      const sameRow =
        previous &&
        previous.file === entry.file &&
        Number(previous.rowIndex) === Number(entry.rowIndex);
      if (sameRow && !force) return;
      if (
        leftMode.value === "phrases" &&
        focusFileFilter.value !== entry.file
      ) {
        focusFileFilter.value = entry.file || "";
        phrasePage.value = 0;
        phraseSearchQ.value = "";
      }
      await focusPhrase(id);
      if (
        request !== rowFollowRequest ||
        !followRow.value ||
        focusId.value !== id
      )
        return;
      await nextTick();
      try {
        const el =
          document.querySelector(".dock.left .ft-rowcard.active") ||
          document.querySelector(".dock.left .ft-cell.active");
        if (el && el.scrollIntoView) el.scrollIntoView({ block: "center" });
      } catch (e) {}
    }
    async function revealInFiles(
      file: string,
      force: boolean,
      phraseId = "",
    ): Promise<void> {
      if (!file || !projectId.value) return;
      if (leftMode.value !== "files") {
        if (!force) return;
        leftMode.value = "files";
        followRowId.value = "";
      }
      const known = (fileTree.value || []).find((f) => f.path === file);
      if (!known) return;
      if (fileSearchQ.value) fileSearchQ.value = "";
      if (hideEmpty.value && (known.total || 0) === 0) hideEmpty.value = false;
      if (fileHideReady.value && (known.total || 0) <= (known.translated || 0))
        fileHideReady.value = false;
      const parts = file.split("/");
      for (let i = 1; i < parts.length; i++) {
        expandedDirs.value[parts.slice(0, i).join("/")] = true;
      }
      if (!expanded(file)) toggleExpand(file);
      lastReveal = file;
      revealFile.value = file;
      if (revealTimer) clearTimeout(revealTimer);
      revealTimer = setTimeout(() => {
        if (revealFile.value === file) revealFile.value = "";
      }, 4000);
      await nextTick();
      try {
        const q = phraseId
          ? '.filetree .tree-phrases [data-id="' + CSS.escape(phraseId) + '"]'
          : '.filetree [data-fp="' + CSS.escape(file) + '"]';
        const el = document.querySelector(q);
        if (el && el.scrollIntoView) {
          el.scrollIntoView({ block: "center" });
          if (phraseId) {
            el.classList.add("flash");
            setTimeout(() => {
              try {
                el.classList.remove("flash");
              } catch (e2) {}
            }, 2400);
          }
        } else if (phraseId) {
          const fel = document.querySelector(
            '.filetree [data-fp="' + CSS.escape(file) + '"]',
          );
          if (fel && fel.scrollIntoView)
            fel.scrollIntoView({ block: "center" });
        }
      } catch (e) {}
    }
    function onEditorFile(file: string): void {
      if (!followFiles.value || !file || file === lastReveal) return;
      revealInFiles(file, false);
    }
    async function onRevealFile(file: string): Promise<void> {
      if (!file) {
        showToast("Нет активного файла");
        return;
      }
      await revealInFiles(file, true);
    }
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
      runPalette,
      onPaletteKey,
    } = palette;

    async function focusFile(f: string): Promise<void> {
      focusFileFilter.value = f || "";
      tab.value = "translate";
      if (f) {
        leftMode.value = "phrases";
        phrasePage.value = 0;
        phraseSearchQ.value = "";
        const page = await loadFileRows(f, 0, "");
        let first: Entry | null = null;
        try {
          first = await api.rowsNext(projectId.value, {
            file: f,
            afterRow: -1,
            afterCol: -1,
          });
          if (first) mergeEntries([first]);
        } catch (e) {
          logText.value += "\n" + errorMessage(e);
        }
        const fallback =
          page && page.groups.length ? page.groups[0].cells[0] : null;
        const target = first || fallback;
        if (target) setFocusId(target.id);
      }
    }

    async function editorRowNext(
      file: string,
      afterRow: number,
      afterCol: number,
      q: string,
    ): Promise<Entry | null> {
      const entry = await api.rowsNext(projectId.value, {
        file,
        afterRow,
        afterCol,
        q,
      });
      if (!entry || !entry.id) return entry;
      mergeEntries([entry]);
      const inPage =
        fileRows.value.file === file &&
        (fileRows.value.groups || []).some((g) =>
          (g.cells || []).some((c) => c.id === entry.id),
        );
      if (!inPage) {
        const pos = await api.rowsPosition(projectId.value, {
          file,
          rowIndex: entry.rowIndex,
          q,
        });
        const page = Math.floor(Number(pos || 0) / rowGroupPageSize);
        phrasePage.value = page;
        await loadFileRows(file, page, q || "");
      }
      return entry;
    }

    async function editorLoadRowPage(
      file: string,
      page: number,
      q: string,
    ): Promise<FileRowState | null> {
      return loadFileRows(file, page, q);
    }

    function onNavigate(f: string): void {
      focusFile(f);
    }

    // search
    const searchQ = ref("");
    const matches = ref<SearchMatch[]>([]);
    const searchLoading = ref(false);

    async function doSearch() {
      if (!searchQ.value.trim()) return;
      searchLoading.value = true;
      try {
        const d = await api.entries(
          projectId.value,
          { q: searchQ.value.trim() },
          { limit: 50 },
        );
        matches.value = (d.entries || []).map((e: Entry) => ({
          id: e.id,
          file: e.file || "",
          rowKey: e.rowKey || "",
          source: e.source || "",
          translation: e.translation || "",
        }));
      } catch (e) {
        logText.value += "\n" + errorMessage(e);
      }
      searchLoading.value = false;
    }

    async function openSearchResult(m: SearchMatch): Promise<void> {
      if (!m || !m.id) {
        logText.value += "\nФраза не найдена";
        return;
      }
      try {
        const loaded = await ensureEntry(m.id);
        const found = loaded || entryById.value.get(m.id) || null;
        const f = (found && found.file) || m.file || "";
        let targetPage = 0;
        if (f && found) {
          const pos = await api.rowsPosition(projectId.value, {
            file: f,
            rowIndex: found.rowIndex,
          });
          targetPage = Math.floor(Number(pos || 0) / rowGroupPageSize);
          await loadFileRows(f, targetPage, "");
        }
        tab.value = "translate";
        focusFileFilter.value = f;
        leftMode.value = "phrases";
        phrasePage.value = targetPage;
        phraseSearchQ.value = "";
        setFocusId(m.id);
        await nextTick();
        try {
          const el = document.querySelector(
            '.dock.left [data-id="' + CSS.escape(m.id) + '"]',
          );
          if (el && el.scrollIntoView) {
            el.scrollIntoView({ block: "center" });
            el.classList.add("flash");
            setTimeout(() => {
              try {
                el.classList.remove("flash");
              } catch (e2) {}
            }, 2400);
          }
        } catch {}
        logText.value += "\nПереход по поиску: " + (f ? f + ", " : "") + m.id;
      } catch (e) {
        logText.value +=
          "\n" + (errorMessage(e) || "Не удалось открыть результат поиска");
      }
    }

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

    function toggleTranslate(f: string, v: boolean): void {
      const s = new Set(selTranslate.value);
      v ? s.add(f) : s.delete(f);
      selTranslate.value = [...s];
      saveSelection();
    }

    function selTranslateVisible(v: boolean, list?: string[]): void {
      const s = new Set(selTranslate.value);
      (list || sourceFiles.value).forEach((f) => (v ? s.add(f) : s.delete(f)));
      selTranslate.value = [...s];
      saveSelection();
    }
    function clearTranslateSel() {
      selTranslate.value = [];
      saveSelection();
    }

    function toggleExport(f: string, v: boolean): void {
      const s = new Set(selExport.value);
      v ? s.add(f) : s.delete(f);
      selExport.value = [...s];
      saveSelection();
    }

    function clearExportScope() {
      selExport.value = [];
      saveSelection();
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
        syncSavedEntry(previous, d.entry);
        if (d.summary) summary.value = d.summary;
        const fileStats =
          d.entry && fileTree.value.find((file) => file.path === d.entry.file);
        savedEntry.value = d.entry
          ? {
              previous,
              entry: d.entry,
              fileStats: fileStats ? { ...fileStats } : null,
            }
          : null;
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
      pack.value = null;
      await loadProject();
      await scanSource();
      if (!summary.value || !summary.value.entries) {
        logText.value += "\nНовый проект: извлекаю все строки игры…";
        await runExtract();
      }
    }

    const toast = ref("");

    function showToast(m: string): void {
      toast.value = m;
      setTimeout(() => (toast.value = ""), 3000);
    }

    // pack settings (Harmonia manifest.json)
    const pack = ref<PackMeta | null>(null);
    const packErrors = ref<string[]>([]);
    const packManifest = ref<Record<string, unknown> | null>(null);
    const packMsg = ref("");
    const packCompat = ref("");
    const packLangs = ref("");

    function blankPack(): PackMeta {
      return {
        packId: "",
        translationVersion: "",
        gameVersion: "",
        compatibleGameVersions: [],
        vendorId: "",
        vendorName: "",
        vendorUrl: "",
        vendorContact: "",
        authors: [],
        languages: ["ru"],
        title: "",
        description: "",
        changelog: "",
        homepage: "",
        license: "",
        minPluginVersion: "",
      };
    }

    function applyPack(d: PackResponse): void {
      pack.value = Object.assign(blankPack(), d.pack || {});
      const currentPack = pack.value;
      if (!currentPack) return;
      if (!currentPack.gameVersion && sourceStatus.value.gameVersion)
        currentPack.gameVersion = sourceStatus.value.gameVersion;
      if (!Array.isArray(currentPack.authors)) currentPack.authors = [];
      packCompat.value = (currentPack.compatibleGameVersions || []).join(", ");
      packLangs.value = (currentPack.languages || []).join(", ");
      packErrors.value = d.errors || [];
      packManifest.value = d.manifest || null;
    }

    async function loadPack() {
      try {
        applyPack(await api.getPack(projectId.value));
      } catch (e) {
        packMsg.value = errorMessage(e);
      }
    }

    function packAddAuthor() {
      if (!pack.value) return;
      const authors = pack.value.authors || (pack.value.authors = []);
      authors.push({ name: "", role: "" });
    }

    function packDelAuthor(i: number): void {
      if (!pack.value) return;
      pack.value.authors?.splice(i, 1);
    }

    async function savePack() {
      if (!pack.value) return;
      packMsg.value = "";
      const p = Object.assign({}, pack.value, {
        compatibleGameVersions: packCompat.value
          .split(",")
          .map((s: string) => s.trim())
          .filter(Boolean),
        languages: packLangs.value
          .split(",")
          .map((s: string) => s.trim())
          .filter(Boolean),
        authors: (pack.value.authors || [])
          .filter((a) => a && (a.name || "").trim())
          .map((a) => ({
            name: (a.name || "").trim(),
            role: (a.role || "").trim(),
          })),
      });
      try {
        const d = await api.savePack(projectId.value, p);
        applyPack(d);
        packMsg.value =
          d.errors && d.errors.length
            ? "Сохранено, но манифест невалиден"
            : "Сохранено";
        logText.value += "\nНастройки пака сохранены";
      } catch (e) {
        packMsg.value = errorMessage(e);
      }
    }

    function esc(s: unknown): string {
      return String(s).replace(
        /[&<>]/g,
        (c) =>
          (
            ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }) as Record<
              string,
              string
            >
          )[c],
      );
    }

    onMounted(() => {
      document.addEventListener("click", closeMenusOnDocClick, true);
      document.addEventListener("contextmenu", onGlobalCtx);
      loadSourceStatus();
      loadUpdateStatus();
      setInterval(loadUpdateStatus, 3600000);
      if (projectId.value && !showPicker.value) {
        loadProject().then(scanSource);
      }
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
      rg,
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
      fileRowGroups,
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
      jobLog,
      jobMainOutput,
      onJobScroll,
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
  <div class="topbar ide" v-else>
    <div class="tb-group tb-left">
      <div class="top-menu-wrap">
        <button
          class="ghost icon-btn"
          @click="menuFor = menuFor === 'burger' ? null : 'burger'"
          title="Меню"
        >
          <svg class="icon" viewBox="0 0 24 24">
            <path d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>
        <div v-if="menuFor === 'burger'" class="dz-menu top-menu left">
          <div class="dz-menu-h">Проект</div>
          <div
            class="dz-menu-i"
            @click="
              showPicker = true;
              menuFor = null;
            "
          >
            Сменить проект…
          </div>
          <div
            class="dz-menu-i"
            @click="
              gotoView('summary');
              menuFor = null;
            "
          >
            Сводка перевода
          </div>
          <div
            class="dz-menu-i"
            @click="
              runExtract();
              menuFor = null;
            "
          >
            Обновить данные игры
          </div>
          <div
            class="dz-menu-i"
            @click="
              openSettings();
              menuFor = null;
            "
          >
            Настройки…
          </div>
          <div
            class="dz-menu-i dim"
            @click="
              runExtract(true);
              menuFor = null;
            "
          >
            Обновить всё принудительно
          </div>
          <div class="dz-menu-i dim" @click="resetLayout()">
            Сбросить раскладку
          </div>
        </div>
      </div>
      <div class="brand">
        <img class="logo" src="/img/yuki-icon.png" alt="Yuki" />
        <span>Harmonia Suite</span>
      </div>
      <button class="proj-pill" @click="showPicker = true" :title="projectId">
        <span class="proj-name">{{ projectName || projectId || "—" }}</span
        ><svg class="icon" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6" /></svg>
      </button>
    </div>
    <div class="toolbar tb-group tb-right">
      <div class="top-menu-wrap">
        <button
          class="ghost icon-btn"
          @click="menuFor = menuFor === 'view' ? null : 'view'"
          title="Вид: панели и раскладка"
        >
          <svg class="icon" viewBox="0 0 24 24">
            <rect x="3" y="4" width="18" height="16" rx="2" />
            <path d="M9 4v16M15 4v16" />
          </svg>
        </button>
        <div v-if="menuFor === 'view'" class="dz-menu top-menu">
          <div class="dz-menu-h">Панели</div>
          <div
            class="dz-menu-i"
            :class="{ off: !railVisible.left }"
            @click="toggleLeft()"
          >
            Левая
          </div>
          <div
            class="dz-menu-i"
            :class="{ off: !railVisible.right }"
            @click="toggleRight()"
          >
            Правая
          </div>
          <div
            class="dz-menu-i"
            :class="{ off: !railVisible.bottom }"
            @click="toggleBottom()"
          >
            Нижняя
          </div>
          <div class="dz-menu-i dim" @click="resetLayout()">
            Сбросить раскладку
          </div>
        </div>
      </div>
      <button
        class="ghost icon-btn"
        @click="openPalette"
        title="Быстрый переход (Ctrl+K)"
      >
        <svg class="icon" viewBox="0 0 24 24">
          <circle cx="11" cy="11" r="7" />
          <path d="M21 21l-4.3-4.3" />
        </svg>
      </button>
      <button
        class="theme-btn icon-btn"
        @click="toggleTheme"
        :title="theme === 'dark' ? 'Светлая тема' : 'Тёмная тема'"
      >
        <svg class="icon" viewBox="0 0 24 24">
          <circle cx="12" cy="12" r="4.5" />
          <path
            d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"
          />
        </svg>
      </button>
      <button
        class="ghost icon-btn"
        @click="showPicker = true"
        title="Сменить проект"
      >
        <svg class="icon" viewBox="0 0 24 24">
          <path
            d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"
          />
        </svg>
      </button>
    </div>
  </div>

  <div v-if="job" class="jobbar" :class="job.status">
    <div class="job-row">
      <span class="job-spin" v-if="job.status === 'running'"></span
      ><b>{{
        {
          extract: "Обновление данных",
          gemini: "Перевод Gemini",
          openrouter: "Перевод OpenRouter",
          merge: "Сборка CSV",
          "sync-sources": "Синхронизация источников",
          update: "Обновление приложения",
        }[job.action] || job.action
      }}</b
      ><span class="muted" style="margin-left: 8px">{{
        job.status === "running"
          ? "выполняется…"
          : job.status === "queued"
            ? "в очереди…"
            : job.status === "completed"
              ? "готово"
              : job.status === "cancelled"
                ? "отменено"
                : "ошибка"
      }}</span
      ><button
        v-if="job.status === 'running' || job.status === 'queued'"
        class="ghost sm"
        style="margin-left: auto"
        @click="cancelJob"
        title="Остановить"
      >
        Отмена</button
      ><button
        v-if="job.status !== 'running' && job.status !== 'queued'"
        class="ghost icon-btn sm"
        style="margin-left: auto"
        @click="job = null"
        title="Закрыть"
      >
        <svg class="icon" viewBox="0 0 24 24">
          <path d="M6 6l12 12M18 6L6 18" />
        </svg>
      </button>
    </div>
    <pre ref="jobLog" class="job-log" @scroll="onJobScroll">{{
      jobMainOutput
    }}</pre>
  </div>

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

  <div v-if="paletteOpen" class="overlay" @click.self="paletteOpen = false">
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
  <div v-if="toast" class="toast">{{ toast }}</div>
</template>
