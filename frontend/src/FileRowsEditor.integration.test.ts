import { computed, defineComponent, onMounted, ref, nextTick } from "vue";
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Editor from "./components/Editor.vue";
import { useEntryMutations } from "./composables/useEntryMutations";
import {
  useFileRows,
  type FileRowsController,
} from "./composables/useFileRows";
import { useProjectWorkspace } from "./composables/useProjectWorkspace";
import { ENTRY_STATUS } from "./domain/translationStatus";
import type { Entry, FileStats } from "./api/types";

const mockedApi = vi.hoisted(() => ({
  overview: vi.fn(),
  rowsPage: vi.fn(),
  patchEntry: vi.fn(),
}));

vi.mock("./api/client", () => ({ api: mockedApi }));

function entry(id: string, source: string, rowIndex: number): Entry {
  return {
    uuid: `${id}-uuid`,
    id,
    source,
    translation: "",
    status: ENTRY_STATUS.UNTRANSLATED,
    file: "dialogue.csv",
    rowKey: `row-${rowIndex}`,
    columnIndex: 0,
    columnName: "text",
    rowIndex,
  };
}

describe("file rows and editor integration", () => {
  beforeEach(() => {
    mockedApi.overview.mockResolvedValue({
      id: "project-1",
      name: "Demo",
      summary: {
        entries: 1,
        files: 1,
        translated: 0,
        untranslated: 1,
        byStatus: {},
      },
    });
    mockedApi.rowsPage.mockResolvedValue({
      groups: [
        {
          row: 0,
          rowKey: "row-0",
          cells: [entry("entry-1", "Hello", 0)],
          un: 1,
        },
      ],
      totalGroups: 1,
      offset: 0,
      limit: 60,
    });
    mockedApi.patchEntry.mockResolvedValue({
      ok: true,
      entry: {
        ...entry("entry-1", "Hello", 0),
        translation: "Saved by server",
        status: ENTRY_STATUS.HUMAN_REVIEWED,
        updatedAt: "2026-09-11T10:00:00Z",
      },
      warnings: [],
    });
  });

  it("loads rows, saves through the API, and reconciles the local document", async () => {
    const Harness = defineComponent({
      components: { Editor },
      setup() {
        const projectId = ref("project-1");
        const fileTree = ref<FileStats[]>([]);
        const expandedDirs = ref<Record<string, boolean>>({});
        let rowsController: FileRowsController | null = null;
        const workspace = useProjectWorkspace({
          projectId,
          fileTree: {
            expandedDirs,
            loadFileTree: vi.fn().mockResolvedValue(undefined),
          },
          fileRows: () => rowsController,
          loadSelection: vi.fn(),
          loadPack: vi.fn().mockResolvedValue(undefined),
          invalidateExport: vi.fn(),
          openSettings: vi.fn(),
          log: vi.fn(),
          getFocusFileFilter: () => "",
          getPhraseSearchQuery: () => "",
          isPhraseMode: () => false,
        });
        const rows = useFileRows({
          projectId,
          document: workspace.document,
          scopeFile: () => "dialogue.csv",
          activeEntryId: () => "entry-1",
        });
        rowsController = rows;
        const mutations = useEntryMutations({
          document: workspace.document,
          fileTree,
          fileRows: rows.fileRows,
          findCachedEntry: rows.localEntry,
          replaceEntryInPreview: rows.replaceEntryInPreview,
          reconcilePending: vi.fn(),
        });
        const focusId = ref("");
        const ready = ref(false);
        const entries = computed(() => workspace.document.value?.entries || []);

        async function save(payload: {
          id: string;
          uuid: string;
          translation: string;
          status: string;
        }): Promise<void> {
          const previous = mutations.localEntry(payload.id);
          const response = await mockedApi.patchEntry(
            projectId.value,
            payload.uuid,
            payload.translation,
            payload.status,
          );
          mutations.applySavedEntry(previous, response.entry);
        }

        onMounted(async () => {
          await workspace.loadProject();
          await rows.loadFileRows("dialogue.csv");
          focusId.value = "entry-1";
          ready.value = true;
        });

        const rowContext = rows.rowContext;
        return { entries, focusId, ready, rowContext, save };
      },
      template: `
        <Editor
          v-if="ready"
          :entries="entries"
          :focus-id="focusId"
          :row-context="rowContext"
          @save="save"
        />
        <output data-testid="saved-value">{{ entries[0]?.translation }}</output>
      `,
    });

    const wrapper = mount(Harness, {
      global: { stubs: { CsvPreview: true } },
    });
    await flushPromises();
    await nextTick();

    const textarea = wrapper.get("textarea");
    await textarea.setValue("Saved by server");
    await wrapper.get('button[title="Сохранить"]').trigger("click");
    await flushPromises();
    await nextTick();

    expect(mockedApi.patchEntry).toHaveBeenCalledWith(
      "project-1",
      "entry-1-uuid",
      "Saved by server",
      ENTRY_STATUS.HUMAN_REVIEWED,
    );
    expect(wrapper.get('[data-testid="saved-value"]').text()).toBe(
      "Saved by server",
    );
    expect(wrapper.get("textarea").element).toHaveProperty(
      "value",
      "Saved by server",
    );
  });
});
