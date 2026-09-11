export interface WorkspaceView {
  title: string;
  icon: string;
}

export const VIEWS: Readonly<Record<string, WorkspaceView>> = {
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

export const VIEW_IDS = [
  "project",
  "translate",
  "search",
  "tags",
  "summary",
  "pack",
  "export",
  "delta",
  "log",
] as const;
