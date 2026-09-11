import { ref, watch, type Ref } from "vue";

import type { DockActiveMap } from "./useDockLayout";

export function useRenderedDockViews(active: Ref<DockActiveMap>) {
  const renderedViews = ref<Record<string, boolean>>({});

  watch(
    active,
    (currentActive) => {
      for (const view of Object.values(currentActive)) {
        if (view) renderedViews.value[view] = true;
      }
    },
    { deep: true, immediate: true },
  );

  function isRendered(view: string): boolean {
    return !!renderedViews.value[view];
  }

  return { renderedViews, isRendered };
}
