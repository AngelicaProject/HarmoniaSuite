import { computed, ref, shallowRef, type Ref } from "vue";
import { api } from "../api/client";
import type { AiStatus, SourceSettings, Summary } from "../api/types";
import type { FileRowsController } from "./useFileRows";
import type { FileTreeState } from "./useFileTree";
import type { ProjectDocument } from "../domain/workspace";

export interface UiAiStatus extends AiStatus {
  configured: boolean;
  model: string;
  models: string[];
  openrouterConfigured: boolean;
  openrouterModel: string;
}

export interface ProjectWorkspaceOptions {
  projectId?: Ref<string>;
  fileTree: Pick<FileTreeState, "loadFileTree" | "expandedDirs">;
  fileRows: () => FileRowsController | null;
  loadSelection: () => void;
  loadPack: () => Promise<void>;
  invalidateExport: () => void;
  invalidateCache?: () => void;
  openSettings: (section?: string) => void;
  log: (message: string) => void;
  getFocusFileFilter: () => string;
  getPhraseSearchQuery: () => string;
  isPhraseMode: () => boolean;
}

export interface ProjectWorkspace {
  projectId: Ref<string>;
  projectName: Ref<string>;
  root: Ref<string>;
  document: Ref<ProjectDocument | null>;
  summary: Ref<Summary | null>;
  projectLoading: Ref<boolean>;
  sourceFiles: Ref<string[]>;
  sourceLoading: Ref<boolean>;
  sourceError: Ref<string>;
  sourceStatus: Ref<SourceSettings>;
  sourceLabel: Readonly<Ref<string>>;
  geminiStatus: Ref<UiAiStatus>;
  aiTitle: Readonly<Ref<string>>;
  loadProject: () => Promise<void>;
  loadSourceStatus: () => Promise<void>;
  scanSource: () => Promise<void>;
  loadGeminiStatus: () => Promise<void>;
}

export function useProjectWorkspace(
  options: ProjectWorkspaceOptions,
): ProjectWorkspace {
  const projectId = options.projectId || ref("");
  const projectName = ref("");
  const root = ref("");
  const document = shallowRef<ProjectDocument | null>(null);
  const summary = ref<Summary | null>(null);
  const projectLoading = ref(false);
  const sourceFiles = ref<string[]>([]);
  const sourceLoading = ref(false);
  const sourceError = ref("");
  const sourceStatus = ref<SourceSettings>({
    gamePath: "",
    gameValid: false,
    gameVersion: "",
    activeRoot: "",
    ready: false,
    configured: false,
  });
  const geminiStatus = ref<UiAiStatus>({
    configured: false,
    model: "",
    models: [],
    openrouterConfigured: false,
    openrouterModel: "",
  });

  const sourceLabel = computed(() => {
    const status = sourceStatus.value;
    if (!status.configured) return "источники не настроены";
    return status.gameVersion || status.activeRoot || "источники";
  });
  const aiTitle = computed(() => {
    const status = geminiStatus.value;
    const gemini = status.configured
      ? "ключ задан" + (status.model ? ", " + status.model : "")
      : "без ключа";
    const openrouter = status.openrouterConfigured
      ? "ключ задан" +
        (status.openrouterModel ? ", " + status.openrouterModel : "")
      : "без ключа";
    return "Gemini: " + gemini + "\nOpenRouter: " + openrouter;
  });

  async function loadGeminiStatus(): Promise<void> {
    try {
      const status = await api.status();
      geminiStatus.value = {
        configured: !!status.geminiConfigured,
        model: status.geminiModel || "",
        models: status.geminiModels || [],
        openrouterConfigured: !!status.openrouterConfigured,
        openrouterModel: status.openrouterModel || "",
      };
    } catch {
      // Settings status is best effort and the settings screen remains usable.
    }
  }

  async function loadSourceStatus(): Promise<void> {
    try {
      const status = await api.settings();
      sourceStatus.value = status;
      if (status.activeRoot) root.value = status.activeRoot;
      if (!status.configured) options.openSettings("sources");
    } catch (error) {
      options.log("\nИсточники: " + errorMessage(error));
    }
  }

  async function scanSource(): Promise<void> {
    sourceLoading.value = true;
    sourceError.value = "";
    sourceFiles.value = [];
    try {
      const startedAt = performance.now();
      const response = await api.sourceFiles(root.value);
      sourceFiles.value = response.files || [];
      options.log(
        "\nИсходники: " +
          sourceFiles.value.length +
          " файлов (" +
          Math.round(performance.now() - startedAt) +
          " мс)",
      );
    } catch (error) {
      sourceError.value = errorMessage(error) || "не удалось загрузить файлы";
      options.log("\n" + errorMessage(error));
    } finally {
      sourceLoading.value = false;
    }
  }

  async function loadProject(): Promise<void> {
    projectLoading.value = true;
    try {
      const startedAt = performance.now();
      const overview = await api.overview(projectId.value);
      const loadMs = Math.round(performance.now() - startedAt);
      document.value = { files: [], entries: [], _loadMs: loadMs };
      options.fileRows()?.reset();
      summary.value = overview.summary || null;
      options.loadSelection();
      options.invalidateCache?.();
      if (overview.inputRoot) root.value = overview.inputRoot;
      if (overview.name) projectName.value = overview.name;
      options.log(
        "\nПроект загружен: " +
          projectId.value +
          " (" +
          (overview.summary?.entries ?? 0) +
          " фраз, " +
          loadMs +
          " мс сеть+разбор)",
      );
      await options.loadPack();
      options.fileTree.expandedDirs.value = {};
      await options.fileTree.loadFileTree();
      options.invalidateExport();
      const file = options.getFocusFileFilter();
      if (file && options.isPhraseMode()) {
        await options
          .fileRows()
          ?.loadFileRows(file, 0, options.getPhraseSearchQuery());
      }
    } catch (error) {
      options.log("\nОшибка: " + errorMessage(error));
    } finally {
      projectLoading.value = false;
    }
  }

  return {
    projectId,
    projectName,
    root,
    document,
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
  };
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
