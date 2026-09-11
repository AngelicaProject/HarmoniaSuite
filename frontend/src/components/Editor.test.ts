import { nextTick } from "vue";
import { mount, type VueWrapper } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import Editor from "./Editor.vue";
import { ENTRY_STATUS } from "../domain/translationStatus";
import type { Entry } from "../api/types";

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

describe("Editor", () => {
  let wrapper: VueWrapper | null = null;

  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    wrapper?.unmount();
    wrapper = null;
  });

  function mountEditor(focusId = "one") {
    const entries = [entry("one", "Hello", 0), entry("two", "World", 1)];
    wrapper = mount(Editor, {
      props: {
        entries,
        focusId,
        rowContext: {
          file: "dialogue.csv",
          q: "",
          page: 0,
          pageSize: 60,
          totalGroups: 2,
          groups: [
            { row: 0, pos: 0, cells: [entries[0]], un: 1 },
            { row: 1, pos: 1, cells: [entries[1]], un: 1 },
          ],
        },
      },
      global: { stubs: { CsvPreview: true } },
      attachTo: document.body,
    });
    return wrapper;
  }

  it("emits the edited entry and auto-reviews a new translation", async () => {
    const editor = mountEditor();
    await nextTick();

    const textarea = editor.get("textarea");
    await textarea.setValue("Привет");
    await editor.get('button[title="Сохранить"]').trigger("click");

    expect(editor.emitted("save")).toEqual([
      [
        {
          id: "one",
          uuid: "one-uuid",
          translation: "Привет",
          status: ENTRY_STATUS.HUMAN_REVIEWED,
        },
      ],
    ]);
  });

  it("keeps a draft when moving to another entry and back", async () => {
    const editor = mountEditor();
    await nextTick();

    await editor.get("textarea").setValue("draft for one");
    await editor.setProps({ focusId: "two" });
    await nextTick();
    expect((editor.get("textarea").element as HTMLTextAreaElement).value).toBe(
      "",
    );

    await editor.setProps({ focusId: "one" });
    await nextTick();
    expect((editor.get("textarea").element as HTMLTextAreaElement).value).toBe(
      "draft for one",
    );
  });

  it("moves to the next entry with the editor keyboard shortcut", async () => {
    const editor = mountEditor();
    await nextTick();

    editor.get("textarea").element.dispatchEvent(
      new KeyboardEvent("keydown", {
        key: "ArrowDown",
        altKey: true,
        bubbles: true,
      }),
    );
    await nextTick();
    await nextTick();

    const entryEvents = editor.emitted("entry") || [];
    expect(entryEvents.at(-1)).toEqual(["two"]);
  });
});
