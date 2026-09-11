import { computed, onMounted, onUnmounted, ref, watch, type Ref } from "vue";
import { api } from "../api/client";
import type { FileStats } from "../api/types";

export interface PaletteView {
  title: string;
}

export interface PaletteItem {
  key: string;
  t: string;
  hint: string;
  score?: number;
  run: () => void | Promise<void>;
}

interface PaletteActions {
  run: (action: string) => void | Promise<void>;
  toggleLeft: () => void;
  toggleRight: () => void;
  toggleBottom: () => void;
  resetLayout: () => void;
  gotoView: (view: string) => void;
  openFile: (file: string) => void | Promise<void>;
}

export function fuzzyScore(query: string, source: string): number {
  const text = source.toLowerCase();
  const needle = query.toLowerCase().trim();
  if (!needle) return 0;
  if (text.startsWith(needle)) return 0;
  const index = text.indexOf(needle);
  if (index >= 0) return 1 + index / 1000;
  let needleIndex = 0;
  let gaps = 0;
  let lastIndex = -1;
  for (
    let index = 0;
    index < text.length && needleIndex < needle.length;
    index += 1
  ) {
    if (text[index] === needle[needleIndex]) {
      if (lastIndex >= 0) gaps += index - lastIndex - 1;
      lastIndex = index;
      needleIndex += 1;
    }
  }
  return needleIndex >= needle.length ? 2 + gaps / 100 : Infinity;
}

export function useCommandPalette(
  projectId: Ref<string>,
  fileTree: Ref<FileStats[]>,
  views: Readonly<Record<string, PaletteView>>,
  viewIds: readonly string[],
  actions: PaletteActions,
) {
  const paletteOpen = ref(false);
  const paletteQ = ref("");
  const paletteIdx = ref(0);
  const paletteInput = ref<HTMLInputElement | null>(null);
  const paletteFiles = ref<string[]>([]);
  let paletteTimer: ReturnType<typeof setTimeout> | null = null;

  const paletteResults = computed<PaletteItem[]>(() => {
    const query = paletteQ.value.trim();
    const commands: PaletteItem[] = [
      {
        key: "command:gemini",
        t: "Gemini: перевести всё",
        hint: "команда",
        run: () => actions.run("gemini"),
      },
      {
        key: "command:merge",
        t: "Собрать CSV",
        hint: "команда",
        run: () => actions.run("merge"),
      },
      {
        key: "command:left",
        t: "Левая панель: скрыть/показать",
        hint: "команда",
        run: actions.toggleLeft,
      },
      {
        key: "command:right",
        t: "Правая панель: скрыть/показать",
        hint: "команда",
        run: actions.toggleRight,
      },
      {
        key: "command:bottom",
        t: "Нижняя панель: скрыть/показать",
        hint: "команда",
        run: actions.toggleBottom,
      },
      {
        key: "command:reset",
        t: "Сбросить раскладку",
        hint: "команда",
        run: actions.resetLayout,
      },
      ...viewIds.map((view) => ({
        key: `view:${view}`,
        t: `Панель: ${views[view].title}`,
        hint: "панель",
        run: () => actions.gotoView(view),
      })),
    ];
    if (!query) return commands;

    const results: PaletteItem[] = [];
    for (const command of commands) {
      const score = fuzzyScore(query, command.t);
      if (score !== Infinity) results.push({ ...command, score });
    }
    for (const file of fileTree.value) {
      const score = fuzzyScore(query, file.path);
      if (score !== Infinity) {
        results.push({
          key: `file:${file.path}`,
          t: file.path,
          hint: "файл",
          score: score + 0.01,
          run: () => actions.openFile(file.path),
        });
      }
    }
    for (const file of paletteFiles.value) {
      results.push({
        key: `file:${file}`,
        t: file,
        hint: "файл",
        score: 0.02,
        run: () => actions.openFile(file),
      });
    }
    return results
      .sort((left, right) => (left.score || 0) - (right.score || 0))
      .slice(0, 25);
  });

  watch(paletteQ, () => {
    if (paletteTimer) clearTimeout(paletteTimer);
    const query = paletteQ.value.trim();
    paletteFiles.value = [];
    if (!query || !projectId.value) return;
    paletteTimer = setTimeout(async () => {
      try {
        const response = await api.files(
          projectId.value,
          { q: query },
          { limit: 25 },
        );
        paletteFiles.value = response.files.map((file) => file.path);
      } catch {
        paletteFiles.value = [];
      }
    }, 300);
  });

  function openPalette(): void {
    paletteOpen.value = true;
    paletteQ.value = "";
    paletteIdx.value = 0;
    setTimeout(() => paletteInput.value?.focus(), 0);
  }

  function runPalette(item: PaletteItem | undefined): void {
    paletteOpen.value = false;
    void item?.run();
  }

  function onPaletteKey(event: KeyboardEvent): void {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      paletteIdx.value = Math.min(
        paletteIdx.value + 1,
        paletteResults.value.length - 1,
      );
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      paletteIdx.value = Math.max(0, paletteIdx.value - 1);
    } else if (event.key === "Enter") {
      runPalette(paletteResults.value[paletteIdx.value]);
    }
  }

  const onWindowKey = (event: KeyboardEvent): void => {
    if (event.ctrlKey || event.metaKey) {
      if (!event.shiftKey && !event.altKey && event.code === "KeyK") {
        event.preventDefault();
        openPalette();
      }
    } else if (event.key === "Escape" && paletteOpen.value) {
      paletteOpen.value = false;
    }
  };

  onMounted(() => window.addEventListener("keydown", onWindowKey));
  onUnmounted(() => {
    window.removeEventListener("keydown", onWindowKey);
    if (paletteTimer) clearTimeout(paletteTimer);
  });

  return {
    paletteOpen,
    paletteQ,
    paletteIdx,
    paletteInput,
    paletteResults,
    openPalette,
    runPalette,
    onPaletteKey,
  };
}
