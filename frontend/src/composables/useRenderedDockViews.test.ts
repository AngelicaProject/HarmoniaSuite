import { defineComponent, nextTick } from "vue";
import { mount, type VueWrapper } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { useDockLayout, type DockActiveMap } from "./useDockLayout";
import { useRenderedDockViews } from "./useRenderedDockViews";
import { VIEWS, VIEW_IDS } from "../workspace/views";

type Harness = ReturnType<typeof useDockLayout> &
  ReturnType<typeof useRenderedDockViews> & {
    active: DockActiveMap;
  };

const DockHarness = defineComponent({
  setup() {
    const dock = useDockLayout(VIEWS, VIEW_IDS);
    const rendered = useRenderedDockViews(dock.active);
    return { ...dock, ...rendered, viewIds: VIEW_IDS };
  },
  template: `
    <div>
      <template v-for="view in viewIds" :key="view">
        <div v-if="isRendered(view)" :data-testid="\`view-\${view}\`">{{ view }}</div>
      </template>
    </div>
  `,
});

describe("useRenderedDockViews", () => {
  let wrapper: VueWrapper;

  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    wrapper?.unmount();
  });

  it("renders a view that becomes active through a drag move", async () => {
    wrapper = mount(DockHarness);
    const dock = wrapper.vm as unknown as Harness;

    dock.onTabDragStart("left", "delta", {} as DragEvent);
    dock.onDrop("right", {
      preventDefault: () => undefined,
      stopPropagation: () => undefined,
    } as unknown as DragEvent);
    await nextTick();

    expect(dock.active.right).toBe("delta");
    expect(wrapper.get('[data-testid="view-delta"]').text()).toBe("delta");
  });

  it("renders an unopened neighboring view after closing the active tab", async () => {
    wrapper = mount(DockHarness);
    const dock = wrapper.vm as unknown as Harness;

    dock.closeTab("right", "translate");
    await nextTick();

    expect(dock.active.right).toBe("search");
    expect(wrapper.get('[data-testid="view-search"]').text()).toBe("search");
  });
});
