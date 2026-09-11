import { onUnmounted, ref } from "vue";

export const notificationMessage = ref("");

let notificationTimer: ReturnType<typeof setTimeout> | null = null;
let notificationRevision = 0;

export function clearNotification(): void {
  notificationRevision += 1;
  if (notificationTimer) clearTimeout(notificationTimer);
  notificationTimer = null;
  notificationMessage.value = "";
}

export function showNotification(message: string): void {
  const text = String(message || "").trim();
  if (!text) return;

  if (notificationTimer) clearTimeout(notificationTimer);
  const revision = ++notificationRevision;
  notificationMessage.value = text;
  notificationTimer = setTimeout(() => {
    if (revision !== notificationRevision) return;
    notificationMessage.value = "";
    notificationTimer = null;
  }, 3000);
}

export function useNotifications() {
  onUnmounted(clearNotification);

  return {
    toast: notificationMessage,
    showToast: showNotification,
    clearToast: clearNotification,
  };
}
