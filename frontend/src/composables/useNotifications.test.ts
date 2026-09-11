import { afterEach, describe, expect, it, vi } from "vitest";
import {
  clearNotification,
  notificationMessage,
  showNotification,
} from "./useNotifications";

describe("notifications", () => {
  afterEach(() => {
    clearNotification();
    vi.useRealTimers();
  });

  it("replaces the previous timeout when a newer toast is shown", () => {
    vi.useFakeTimers();

    showNotification("Первое");
    vi.advanceTimersByTime(2000);
    showNotification("Второе");

    vi.advanceTimersByTime(1000);
    expect(notificationMessage.value).toBe("Второе");

    vi.advanceTimersByTime(2000);
    expect(notificationMessage.value).toBe("");
  });
});
