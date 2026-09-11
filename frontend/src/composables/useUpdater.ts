import { computed, ref, type Ref } from "vue";
import { api } from "../api/client";
import type { Job, UpdateStatus } from "../api/types";

type StartJob = (job: Job) => void;
type ShowToast = (message: string) => void;

export function useUpdater(startJob: StartJob, showToast: ShowToast) {
  const upd = ref<UpdateStatus>({
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
  const updModal = ref(false);
  const updRestarting = ref(false);
  const updRestartDead = ref(false);
  const updLogBusy = ref(false);

  const updLabel = computed(() => {
    const status = upd.value;
    const sha = (status.currentSha || "").slice(0, 7);
    if (status.state === "toolchain_required") return "Компоненты обновления";
    if (status.updateAvailable) {
      return `v${status.version || "dev"} (+${status.behindBy}) ${sha}`;
    }
    return `v${status.version || "dev"} · ${sha}`;
  });

  const updTitle = computed(() => {
    const status = upd.value;
    if (status.state === "toolchain_required") {
      return "Для обновления потребуется один раз установить JDK, Git и Node.js";
    }
    if (status.state === "local_ahead") {
      return `Локальная версия новее origin/main\n${status.currentSha || ""}`;
    }
    if (status.state === "diverged") {
      return `История исходников расходится с origin/main\n${status.currentSha || ""}`;
    }
    if (!status.supported) {
      return `Обновления недоступны${status.reason ? `\n${status.reason}` : ""}`;
    }
    if (!status.updateAvailable) return `Актуально\n${status.currentSha || ""}`;
    return `Текущий: ${status.currentSha || ""}\nНа main: ${status.latestSha || ""}`;
  });

  async function loadUpdateStatus(): Promise<void> {
    try {
      upd.value = await api.updateStatus();
    } catch {
      // Update availability is optional and must not interrupt the workspace.
    }
  }

  async function runUpdate(): Promise<void> {
    updModal.value = false;
    try {
      startJob(await api.runUpdate());
    } catch (error) {
      showToast(errorMessage(error));
    }
  }

  async function copyUpdateLog(): Promise<void> {
    updLogBusy.value = true;
    try {
      const text = await api.logTail();
      if (!navigator.clipboard?.writeText)
        throw new Error("Буфер обмена недоступен");
      await navigator.clipboard.writeText(text);
      showToast("Журнал скопирован");
    } catch (error) {
      showToast(errorMessage(error));
    } finally {
      updLogBusy.value = false;
    }
  }

  return {
    upd,
    updModal,
    updRestarting,
    updRestartDead,
    updLogBusy,
    updLabel,
    updTitle,
    loadUpdateStatus,
    runUpdate,
    copyUpdateLog,
  };
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
