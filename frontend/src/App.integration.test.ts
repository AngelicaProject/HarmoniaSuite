import { nextTick } from "vue";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "./api/client";
import App from "./App.vue";
import AppTopBar from "./components/shell/AppTopBar.vue";

vi.mock("./api/client", () => ({
  api: {
    status: vi.fn(),
    settings: vi.fn(),
    updateStatus: vi.fn(),
    aiSettings: vi.fn(),
    backups: vi.fn(),
    aiModels: vi.fn(),
  },
}));

describe("App settings shell integration", () => {
  let wrapper: VueWrapper | null = null;

  beforeEach(() => {
    vi.mocked(api.status).mockResolvedValue({
      geminiConfigured: false,
      geminiModel: "",
      geminiModels: [],
      openrouterConfigured: false,
      openrouterModel: "",
    });
    vi.mocked(api.settings).mockResolvedValue({
      gameValid: true,
      ready: true,
      configured: true,
      gameVersion: "7.0",
      gamePath: "C:/game",
    });
    vi.mocked(api.updateStatus).mockResolvedValue({
      supported: false,
      mode: "",
      version: "dev",
      needsToolchain: false,
      currentSha: "",
      latestSha: "",
      behindBy: 0,
      subjects: [],
      updateAvailable: false,
      state: "unavailable",
      reason: "",
    });
    vi.mocked(api.aiSettings).mockResolvedValue({ provider: "gemini" });
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
    wrapper?.unmount();
    wrapper = null;
    vi.clearAllMocks();
    document.body.innerHTML = "";
  });

  it("restores focus to the real AppTopBar settings opener", async () => {
    wrapper = mount(App, {
      attachTo: document.body,
      global: {
        stubs: {
          Picker: { template: "<div />" },
          Editor: true,
          TranslateView: true,
          SearchView: true,
          TagsView: true,
          SummaryView: true,
          PackView: true,
          ExportView: true,
          DeltaView: true,
          LogView: true,
          Dropdown: true,
          JobBar: true,
          UiToast: true,
        },
      },
    });
    await flushPromises();
    await nextTick();

    const topbar = wrapper.findComponent(AppTopBar);
    const settingsOpener = topbar.get('button[title="Меню"]');
    (settingsOpener.element as HTMLElement).focus();
    await settingsOpener.trigger("click");

    const settingsAction = topbar
      .findAll(".top-menu .dz-menu-i")
      .find((item) => item.text().includes("Настройки"));
    expect(settingsAction).toBeDefined();
    await settingsAction!.trigger("click");
    await flushPromises();
    await vi.dynamicImportSettled();
    await nextTick();

    const dialog = wrapper.get('[role="dialog"]');
    expect(wrapper.findComponent(AppTopBar).exists()).toBe(true);
    expect(dialog.element.contains(document.activeElement)).toBe(true);

    await dialog.get(".set-head button").trigger("click");
    await nextTick();

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
    expect(document.activeElement).toBe(settingsOpener.element);
  });
});
