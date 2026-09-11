import { defineComponent, ref } from "vue";
import { mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "../api/client";
import type { Entry, FileStats } from "../api/types";
import type { FileRowsController } from "./useFileRows";
import { useWorkspaceNavigation } from "./useWorkspaceNavigation";

vi.mock("../api/client", () => ({
  api: {
    rowsNext: vi.fn(),
    rowsPosition: vi.fn(),
    entries: vi.fn(),
  },
}));

describe("useWorkspaceNavigation", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("opens a file, loads its first page, and focuses its first entry", async () => {
    const entry = {
      id: "entry-1",
      file: "ui/dialog.csv",
      rowIndex: 0,
    } as Entry;
    vi.mocked(api.rowsNext).mockResolvedValue(entry);

    const projectId = ref("project-1");
    const fileTree = ref<FileStats[]>([
      { path: "ui/dialog.csv", total: 1, translated: 0, untranslated: 1 },
    ]);
    const fileSearchQ = ref("");
    const fileHideReady = ref(false);
    const hideEmpty = ref(false);
    const expandedDirs = ref<Record<string, boolean>>({});
    const leftMode = ref("files");
    const focusId = ref("");
    const focusFileFilter = ref("");
    const phrasePage = ref(0);
    const phraseSearchQ = ref("");
    const tab = ref("search");
    const followFiles = ref(true);
    const followRow = ref(false);
    const followRowId = ref("");
    const fileRows = ref({
      file: "",
      q: "",
      page: 0,
      groups: [],
      totalGroups: 0,
      loading: false,
    });
    const rows = {
      rowGroupPageSize: 100,
      fileRows,
      loadFileRows: vi.fn().mockResolvedValue({
        ...fileRows.value,
        groups: [{ cells: [entry] }],
      }),
      mergeEntries: vi.fn(),
      ensureEntry: vi.fn(),
      localEntry: vi.fn().mockReturnValue(null),
    } as unknown as FileRowsController;
    const showToast = vi.fn();
    let navigation!: ReturnType<typeof useWorkspaceNavigation>;

    const wrapper = mount(
      defineComponent({
        setup() {
          navigation = useWorkspaceNavigation({
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
            isExpanded: () => false,
            toggleExpand: vi.fn(),
            entryById: () => null,
            log: vi.fn(),
            showToast,
          });
          return navigation;
        },
        template: "<div />",
      }),
    );

    await navigation.openFile("ui/dialog.csv");

    expect(rows.loadFileRows).toHaveBeenCalledWith("ui/dialog.csv", 0, "");
    expect(api.rowsNext).toHaveBeenCalledWith("project-1", {
      file: "ui/dialog.csv",
      afterRow: -1,
      afterCol: -1,
    });
    expect(rows.mergeEntries).toHaveBeenCalledWith([entry]);
    expect(focusFileFilter.value).toBe("ui/dialog.csv");
    expect(leftMode.value).toBe("phrases");
    expect(focusId.value).toBe("entry-1");

    wrapper.unmount();
  });
});
