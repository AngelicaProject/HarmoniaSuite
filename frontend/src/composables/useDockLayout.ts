import { computed, nextTick, onUnmounted, ref } from "vue";

export type LayoutZone = "left" | "right" | "bottom";
export type DockLayoutMap = Record<LayoutZone, string[]>;
export type DockActiveMap = Record<LayoutZone, string | null>;

export interface DockView {
  title: string;
  icon: string;
}

interface DockLayoutOptions {
  onActivateView?: (view: string) => void;
  onToast?: (message: string) => void;
}

interface ContextZone {
  zone: LayoutZone;
  view: string | null;
  x: number;
  y: number;
}

interface AddMenu {
  zone: LayoutZone;
  x: number;
  y: number;
}

interface DragView {
  view: string;
  from: LayoutZone;
}

export interface DockDropPosition {
  zone: LayoutZone;
  index: number;
}

export function getDropIndicatorClass(
  dropPos: DockDropPosition | null,
  zone: LayoutZone,
  index: number,
): "" | "drop-before" {
  return dropPos?.zone === zone && dropPos.index === index ? "drop-before" : "";
}

export function moveDockView(
  layout: DockLayoutMap,
  active: DockActiveMap,
  view: string,
  from: LayoutZone,
  zone: LayoutZone,
  index: number | null,
): void {
  const source = layout[from];
  const sourceIndex = source.indexOf(view);
  if (sourceIndex >= 0) source.splice(sourceIndex, 1);
  const destination = layout[zone];
  let targetIndex =
    index == null
      ? destination.length
      : Math.max(0, Math.min(index, destination.length));
  if (from === zone && sourceIndex >= 0 && sourceIndex < targetIndex)
    targetIndex -= 1;
  destination.splice(targetIndex, 0, view);
  active[zone] = view;
  if (from !== zone && !layout[from].includes(active[from] || ""))
    active[from] = layout[from][0] || null;
}

export function useDockLayout(
  views: Readonly<Record<string, DockView>>,
  viewIds: readonly string[],
  options: DockLayoutOptions = {},
) {
  const zones: LayoutZone[] = ["left", "right", "bottom"];
  const defaults = defaultZones();
  const saved = readLayout();
  const layout = ref<DockLayoutMap>(defaults.layout);
  const active = ref<DockActiveMap>(defaults.active);
  const zoneVisible = ref<Record<LayoutZone, boolean>>({
    left: true,
    right: true,
    bottom: true,
  });
  const railVisible = ref<Record<LayoutZone, boolean>>({
    left: true,
    right: true,
    bottom: true,
  });
  const sideW = ref(300);
  const ctxW = ref(360);
  const bottomH = ref(190);
  const narrow = ref(false);
  const ctxZone = ref<ContextZone | null>(null);
  const dragView = ref<DragView | null>(null);
  const dropPos = ref<DockDropPosition | null>(null);
  const menuFor = ref<string | null>(null);
  const addMenu = ref<AddMenu | null>(null);
  const hosts = ref<Record<string, HTMLElement>>({});
  const layoutRev = ref(0);

  applySavedLayout(saved);
  for (const view of viewIds) {
    if (!zones.some((zone) => layout.value[zone].includes(view))) {
      layout.value.right.push(view);
    }
  }

  let mediaQuery: MediaQueryList | null = null;
  let mediaChange: ((event: MediaQueryListEvent) => void) | null = null;
  let resizeCleanup: (() => void) | null = null;
  try {
    mediaQuery = window.matchMedia("(max-width:1000px)");
    narrow.value = mediaQuery.matches;
    mediaChange = (event: MediaQueryListEvent): void => {
      narrow.value = event.matches;
    };
    if (typeof mediaQuery.addEventListener === "function")
      mediaQuery.addEventListener("change", mediaChange);
    else mediaQuery.addListener(mediaChange);
  } catch {
    // Older embedded browsers may not support MediaQueryList listeners.
  }

  onUnmounted(() => {
    resizeCleanup?.();
    if (!mediaQuery || !mediaChange) return;
    try {
      if (typeof mediaQuery.removeEventListener === "function")
        mediaQuery.removeEventListener("change", mediaChange);
      else mediaQuery.removeListener(mediaChange);
    } catch {
      // Listener cleanup is best effort in older embedded browsers.
    }
  });

  const hiddenViews = computed(() =>
    viewIds.filter(
      (view) => !zones.some((zone) => layout.value[zone].includes(view)),
    ),
  );

  const layoutStyle = computed(() => {
    if (narrow.value) return {};
    const leftOpen =
      railVisible.value.left &&
      zoneVisible.value.left &&
      layout.value.left.length > 0;
    const rightOpen =
      railVisible.value.right &&
      zoneVisible.value.right &&
      layout.value.right.length > 0;
    const left = !railVisible.value.left
      ? "0px"
      : leftOpen
        ? `${Math.max(180, Math.min(560, sideW.value))}px`
        : "50px";
    const right = !railVisible.value.right
      ? "0px"
      : rightOpen
        ? `${Math.max(240, Math.min(640, ctxW.value))}px`
        : "50px";
    const showBottom =
      railVisible.value.bottom &&
      zoneVisible.value.bottom &&
      layout.value.bottom.length > 0;
    const rows = showBottom
      ? `1fr ${Math.max(110, Math.min(480, bottomH.value))}px`
      : railVisible.value.bottom
        ? "1fr auto"
        : "1fr";
    return {
      gridTemplateColumns: `${left} 1fr ${right}`,
      gridTemplateRows: rows,
    };
  });

  function persistLayout(): void {
    try {
      localStorage.setItem(
        "hs-layout",
        JSON.stringify({
          v: 2,
          layout: layout.value,
          active: active.value,
          zoneVisible: zoneVisible.value,
          railVisible: railVisible.value,
          sideW: sideW.value,
          ctxW: ctxW.value,
          bottomH: bottomH.value,
        }),
      );
    } catch {
      // Layout persistence is optional.
    }
  }

  function zoneShown(zone: LayoutZone): boolean {
    return zoneVisible.value[zone] && layout.value[zone].length > 0;
  }

  function zoneStyle(zone: LayoutZone): Record<string, string> {
    return zone === "bottom"
      ? { height: `${Math.max(110, Math.min(480, bottomH.value))}px` }
      : {};
  }

  function activateView(zone: LayoutZone, view: string): void {
    active.value[zone] = view;
    options.onActivateView?.(view);
    persistLayout();
  }

  function toggleView(zone: LayoutZone, view: string): void {
    if (active.value[zone] === view && zoneShown(zone)) hideZone(zone);
    else {
      zoneVisible.value[zone] = true;
      activateView(zone, view);
    }
  }

  function openZoneMenu(
    zone: LayoutZone,
    event: MouseEvent,
    view: string | null = null,
  ): void {
    ctxZone.value = {
      zone,
      view,
      x: Math.min(event.clientX, window.innerWidth - 190),
      y: Math.min(event.clientY, window.innerHeight - 60),
    };
  }

  function hideWidget(): void {
    if (!ctxZone.value?.view) return;
    closeTab(ctxZone.value.zone, ctxZone.value.view);
    ctxZone.value = null;
  }

  function activeTitle(zone: LayoutZone): string {
    const view = active.value[zone];
    return view && views[view] ? views[view].title : "";
  }

  function gotoView(view: string): void {
    const zone = zones.find((candidate) =>
      layout.value[candidate].includes(view),
    );
    if (zone) {
      railVisible.value[zone] = true;
      zoneVisible.value[zone] = true;
      activateView(zone, view);
    } else {
      railVisible.value.right = true;
      zoneVisible.value.right = true;
      addView("right", view);
    }
  }

  function addView(zone: LayoutZone, view: string): void {
    if (!layout.value[zone].includes(view)) {
      for (const candidate of zones) {
        const index = layout.value[candidate].indexOf(view);
        if (index >= 0) layout.value[candidate].splice(index, 1);
      }
      layout.value[zone].push(view);
    }
    menuFor.value = null;
    addMenu.value = null;
    activateView(zone, view);
  }

  function closeTab(zone: LayoutZone, view: string): void {
    const items = layout.value[zone];
    const index = items.indexOf(view);
    if (index >= 0) items.splice(index, 1);
    if (active.value[zone] === view)
      active.value[zone] = items[Math.min(index, items.length - 1)] || null;
    layoutRev.value += 1;
    persistLayout();
  }

  function resetLayout(): void {
    const next = defaultZones();
    layout.value = next.layout;
    active.value = next.active;
    zoneVisible.value = { left: true, right: true, bottom: true };
    railVisible.value = { left: true, right: true, bottom: true };
    menuFor.value = null;
    addMenu.value = null;
    ctxZone.value = null;
    layoutRev.value += 1;
    persistLayout();
    options.onToast?.("Раскладка сброшена");
  }

  function openAddMenu(zone: LayoutZone, event: MouseEvent): void {
    if (addMenu.value?.zone === zone) {
      addMenu.value = null;
      return;
    }
    menuFor.value = null;
    addMenu.value = {
      zone,
      x: Math.min(event.clientX, window.innerWidth - 220),
      y: Math.min(event.clientY, window.innerHeight - 320),
    };
  }

  function onTabDragStart(
    zone: LayoutZone,
    view: string,
    event: DragEvent,
  ): void {
    dragView.value = { view, from: zone };
    event.dataTransfer?.setData("text/plain", view);
    if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
  }

  function onTabDragOver(
    zone: LayoutZone,
    index: number,
    event: DragEvent,
  ): void {
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
    dropPos.value = { zone, index };
  }

  function onDrop(zone: LayoutZone, event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    const drag = dragView.value;
    dragView.value = null;
    dropPos.value = null;
    if (drag) moveView(drag.view, drag.from, zone, null);
  }

  function onDropOnTab(
    zone: LayoutZone,
    index: number,
    event: DragEvent,
  ): void {
    event.preventDefault();
    event.stopPropagation();
    const drag = dragView.value;
    dragView.value = null;
    dropPos.value = null;
    if (drag) moveView(drag.view, drag.from, zone, index);
  }

  function onDragEnd(): void {
    dragView.value = null;
    dropPos.value = null;
  }

  function moveView(
    view: string,
    from: LayoutZone,
    zone: LayoutZone,
    index: number | null,
  ): void {
    moveDockView(layout.value, active.value, view, from, zone, index);
    layoutRev.value += 1;
    persistLayout();
  }

  function dropClass(zone: LayoutZone, index: number): string {
    return getDropIndicatorClass(dropPos.value, zone, index);
  }

  function setHost(view: string, value: unknown): void {
    if (value instanceof HTMLElement) {
      const element = value;
      if (hosts.value[view] !== element) {
        hosts.value[view] = element;
        layoutRev.value += 1;
      }
      return;
    }
    const current = hosts.value[view];
    if (!current) return;
    if (!current.isConnected) {
      delete hosts.value[view];
      layoutRev.value += 1;
    } else
      void nextTick(() => {
        if (hosts.value[view] && !hosts.value[view].isConnected)
          delete hosts.value[view];
      });
  }

  function hostEl(view: string): HTMLElement | null {
    layoutRev.value;
    const element = hosts.value[view];
    return element?.isConnected ? element : null;
  }

  function startResize(pane: "left" | "right", event: MouseEvent): void {
    if (narrow.value) return;
    event.preventDefault();
    resizeCleanup?.();
    const element = event.target instanceof HTMLElement ? event.target : null;
    element?.classList.add("on");
    const startX = event.clientX;
    const startWidth = pane === "left" ? sideW.value : ctxW.value;
    const move = (moveEvent: MouseEvent): void => {
      const delta = moveEvent.clientX - startX;
      if (pane === "left") sideW.value = startWidth + delta;
      else ctxW.value = startWidth - delta;
    };
    const cleanup = (): void => {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
      element?.classList.remove("on");
    };
    const up = (): void => {
      cleanup();
      resizeCleanup = null;
      persistLayout();
    };
    resizeCleanup = cleanup;
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
  }

  function startResizeY(event: MouseEvent): void {
    if (narrow.value) return;
    event.preventDefault();
    resizeCleanup?.();
    const startY = event.clientY;
    const startHeight = bottomH.value;
    const move = (moveEvent: MouseEvent): void => {
      bottomH.value = startHeight + (startY - moveEvent.clientY);
    };
    const cleanup = (): void => {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
    };
    const up = (): void => {
      cleanup();
      resizeCleanup = null;
      persistLayout();
    };
    resizeCleanup = cleanup;
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
  }

  const toggleLeft = (): void => toggleRail("left");
  const toggleRight = (): void => toggleRail("right");
  const toggleBottom = (): void => toggleRail("bottom");
  const hideZone = (zone: LayoutZone): void => {
    zoneVisible.value[zone] = false;
    menuFor.value = null;
    persistLayout();
  };
  const hidePanel = (zone: LayoutZone): void => {
    railVisible.value[zone] = false;
    ctxZone.value = null;
    persistLayout();
  };

  function toggleRail(zone: LayoutZone): void {
    railVisible.value[zone] = !railVisible.value[zone];
    persistLayout();
  }

  function applySavedLayout(value: Record<string, unknown> | null): void {
    if (!value) return;
    if (value.v === 2 && value.layout && typeof value.layout === "object") {
      const savedLayout = value.layout as Record<string, unknown>;
      for (const zone of zones) {
        layout.value[zone] = Array.isArray(savedLayout[zone])
          ? (savedLayout[zone] as unknown[]).filter(
              (view): view is string =>
                typeof view === "string" && viewIds.includes(view),
            )
          : [];
      }
      const savedActive =
        value.active && typeof value.active === "object"
          ? (value.active as Record<string, unknown>)
          : {};
      for (const zone of zones)
        active.value[zone] = layout.value[zone].includes(
          String(savedActive[zone]),
        )
          ? String(savedActive[zone])
          : layout.value[zone][0] || null;
      const savedZones =
        value.zoneVisible && typeof value.zoneVisible === "object"
          ? (value.zoneVisible as Record<string, unknown>)
          : {};
      const savedRails =
        value.railVisible && typeof value.railVisible === "object"
          ? (value.railVisible as Record<string, unknown>)
          : {};
      for (const zone of zones) {
        zoneVisible.value[zone] = savedZones[zone] !== false;
        railVisible.value[zone] = savedRails[zone] !== false;
      }
      sideW.value = Number(value.sideW) || 300;
      ctxW.value = Number(value.ctxW) || 360;
      bottomH.value = Number(value.bottomH) || 190;
    } else {
      sideW.value = Number(value.sideW) || 300;
      ctxW.value = Number(value.ctxW) || 360;
      zoneVisible.value = {
        left: value.leftVisible !== false,
        right: value.rightMode !== "hidden",
        bottom: true,
      };
    }
  }

  return {
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
  };
}

function defaultZones(): { layout: DockLayoutMap; active: DockActiveMap } {
  return {
    layout: {
      left: ["project", "delta"],
      right: ["translate", "search", "tags", "summary", "pack", "export"],
      bottom: ["log"],
    },
    active: { left: "project", right: "translate", bottom: "log" },
  };
}

function readLayout(): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(
      localStorage.getItem("hs-layout") || "null",
    );
    return value && typeof value === "object"
      ? (value as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}
