import { nextTick } from "vue";
import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "../api/client";
import Settings from "./Settings.vue";

vi.mock("../api/client", () => ({
  api: {
    settings: vi.fn(),
    aiSettings: vi.fn(),
    backups: vi.fn(),
    aiModels: vi.fn(),
  },
}));

describe("Settings dialog", () => {
  beforeEach(() => {
    vi.mocked(api.settings).mockResolvedValue({
      gameValid: false,
      ready: false,
      configured: false,
      gameVersion: "",
    });
    vi.mocked(api.aiSettings).mockResolvedValue({
      provider: "gemini",
    });
    vi.mocked(api.backups).mockResolvedValue({
      backups: [],
      retention: 10,
      autoIntervalMinutes: 0,
      usedBytes: 0,
      estimatedBytes: 0,
    });
    vi.mocked(api.aiModels).mockResolvedValue([]);
  });

  afterEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = "";
  });

  it("traps Tab focus, closes on Escape, and restores the opener focus", async () => {
    const opener = document.createElement("button");
    document.body.append(opener);
    opener.focus();

    const wrapper = mount(Settings, {
      attachTo: document.body,
      props: { initial: "sources", forced: false },
    });
    await flushPromises();
    await nextTick();

    const dialog = wrapper.get('[role="dialog"]');
    expect(dialog.attributes("aria-modal")).toBe("true");
    expect(dialog.attributes("aria-labelledby")).toBe("settings-title");
    expect(dialog.element.contains(document.activeElement)).toBe(true);

    const focusable = Array.from(
      dialog.element.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
      ),
    );
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    expect(first).toBeDefined();
    expect(last).toBeDefined();

    first.focus();
    await dialog.trigger("keydown", { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(last);

    last.focus();
    await dialog.trigger("keydown", { key: "Tab" });
    expect(document.activeElement).toBe(first);

    await dialog.trigger("keydown", { key: "Escape" });
    expect(wrapper.emitted("close")).toHaveLength(1);

    wrapper.unmount();
    expect(document.activeElement).toBe(opener);
  });

  it("keeps a forced dialog open on Escape", async () => {
    const wrapper = mount(Settings, {
      attachTo: document.body,
      props: { initial: "sources", forced: true },
    });
    await flushPromises();
    await nextTick();

    await wrapper.get('[role="dialog"]').trigger("keydown", {
      key: "Escape",
    });
    expect(wrapper.emitted("close")).toBeUndefined();

    wrapper.unmount();
  });
});
