import type { Entry } from "../api/types";

export interface ContextMenuItem {
  t: string;
  sel?: boolean;
  run: () => void | Promise<void>;
}

export interface ContextMenuActions {
  openFile: (path: string) => void | Promise<void>;
  toggleExpand: (path: string) => void;
  isExpanded: (path: string) => boolean;
  toggleTranslate: (path: string, selected: boolean) => void;
  isTranslateSelected: (path: string) => boolean;
  toggleExport: (path: string, selected: boolean) => void;
  isExportSelected: (path: string) => boolean;
  focusPhrase: (id: string) => void | Promise<void>;
  entryById: (id: string) => Entry | null;
  copyText: (value: unknown) => void | Promise<void>;
  logText: () => string;
}

export function buildContextMenuItems(
  kind: string | undefined,
  data: { path?: string; id?: string },
  actions: ContextMenuActions,
): ContextMenuItem[] | null {
  if (kind === "file") {
    const path = data.path || "";
    return [
      { t: "Открыть", run: () => actions.openFile(path) },
      {
        t: actions.isExpanded(path) ? "Свернуть строки" : "Показать строки",
        run: () => actions.toggleExpand(path),
      },
      { t: "Копировать путь", run: () => actions.copyText(path) },
      {
        t: "В перевод",
        sel: actions.isTranslateSelected(path),
        run: () =>
          actions.toggleTranslate(path, !actions.isTranslateSelected(path)),
      },
      {
        t: "В сборку",
        sel: actions.isExportSelected(path),
        run: () => actions.toggleExport(path, !actions.isExportSelected(path)),
      },
    ];
  }

  if (kind === "phrase") {
    const id = data.id || "";
    const entry = actions.entryById(id);
    const items: ContextMenuItem[] = [
      { t: "Открыть в редакторе", run: () => actions.focusPhrase(id) },
    ];
    if (entry) {
      items.push(
        { t: "Копировать оригинал", run: () => actions.copyText(entry.source) },
        {
          t: "Копировать перевод",
          run: () => actions.copyText(entry.translation || ""),
        },
        { t: "Копировать id", run: () => actions.copyText(entry.id) },
      );
    }
    return items;
  }

  if (kind === "log") {
    return [
      {
        t: "Копировать журнал",
        run: () => actions.copyText(actions.logText()),
      },
    ];
  }

  return null;
}
