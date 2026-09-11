import { onMounted, onUnmounted, ref } from "vue";
import type { ContextMenuItem } from "../domain/contextMenu";

export interface ContextMenuState {
  x: number;
  y: number;
  items: ContextMenuItem[];
}

export interface ContextMenuOptions {
  getItems: (element: HTMLElement) => ContextMenuItem[] | null;
  closeOtherMenus?: () => void;
  showToast?: (message: string) => void;
}

export function useContextMenu(options: ContextMenuOptions) {
  const ctxMenu = ref<ContextMenuState | null>(null);

  async function copyText(value: unknown): Promise<void> {
    const text = String(value ?? "");
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      try {
        const textarea = document.createElement("textarea");
        textarea.value = text;
        textarea.style.position = "fixed";
        textarea.style.opacity = "0";
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand("copy");
        textarea.remove();
      } catch {
        options.showToast?.("Не скопировалось");
        return;
      }
    }
    options.showToast?.("Скопировано");
  }

  function runCtx(item: ContextMenuItem): void {
    ctxMenu.value = null;
    void item?.run();
  }

  function onGlobalContextMenu(event: MouseEvent): void {
    if (event.defaultPrevented) return;
    const target = event.target instanceof Element ? event.target : null;
    if (target?.closest('input,textarea,select,[contenteditable="true"]'))
      return;
    const element = target?.closest("[data-ctx]") as HTMLElement | null;
    event.preventDefault();
    options.closeOtherMenus?.();
    if (!element) {
      ctxMenu.value = null;
      return;
    }
    const items = options.getItems(element);
    if (!items?.length) {
      ctxMenu.value = null;
      return;
    }
    ctxMenu.value = {
      x: Math.min(event.clientX, window.innerWidth - 230),
      y: Math.min(event.clientY, window.innerHeight - items.length * 34 - 16),
      items,
    };
  }

  function closeOnDocumentClick(event: MouseEvent): void {
    const target = event.target instanceof Element ? event.target : null;
    if (target?.closest(".dz-menu")) return;
    ctxMenu.value = null;
  }

  onMounted(() => {
    document.addEventListener("contextmenu", onGlobalContextMenu);
    document.addEventListener("click", closeOnDocumentClick, true);
  });
  onUnmounted(() => {
    document.removeEventListener("contextmenu", onGlobalContextMenu);
    document.removeEventListener("click", closeOnDocumentClick, true);
  });

  return { ctxMenu, runCtx, copyText };
}
