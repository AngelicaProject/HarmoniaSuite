import { computed, nextTick, onUnmounted, ref, watch, type Ref } from "vue";
import { api } from "../api/client";
import type { Job, Summary } from "../api/types";

interface JobOptions {
  projectId: Ref<string>;
  projectName: Ref<string>;
  summary: Ref<Summary | null>;
  logText: Ref<string>;
  updModal: Ref<boolean>;
  updRestarting: Ref<boolean>;
  updRestartDead: Ref<boolean>;
  loadFileTree: () => Promise<void>;
  loadProject: () => Promise<void>;
  loadSourceStatus: () => Promise<void>;
  scanSource: () => Promise<void>;
  loadUpdateStatus: () => Promise<void>;
  showToast: (message: string) => void;
}

export function useJobs(options: JobOptions) {
  const job = ref<Job | null>(null);
  const pendingPack = ref(false);
  const jobLog = ref<HTMLElement | null>(null);
  const jobStick = ref(true);
  let jobTimer: ReturnType<typeof setInterval> | null = null;
  let updWatch: ReturnType<typeof setInterval> | null = null;

  const jobMainOutput = computed(() =>
    ((job.value && job.value.output) || "")
      .split("\n")
      .filter((line) => !line.startsWith("[REASONING]"))
      .join("\n"),
  );
  const updateFailed = computed(
    () =>
      !!job.value &&
      job.value.action === "update" &&
      job.value.status === "failed",
  );

  watch(
    () => job.value && job.value.output,
    () => {
      if (!jobStick.value) return;
      nextTick(() => {
        const element = jobLog.value;
        if (element) element.scrollTop = element.scrollHeight;
      });
    },
  );
  watch(
    () => job.value && job.value.id,
    () => {
      jobStick.value = true;
    },
  );

  function onJobScroll(): void {
    const element = jobLog.value;
    if (!element) return;
    jobStick.value =
      element.scrollHeight - element.scrollTop - element.clientHeight < 48;
  }

  async function downloadPackZip(): Promise<void> {
    const blob = await api.downloadExportZip(options.projectId.value);
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download =
      (options.projectName.value || options.projectId.value || "export") +
      ".zip";
    link.click();
    URL.revokeObjectURL(url);
  }

  function jobActive(): boolean {
    return !!(
      job.value &&
      (job.value.status === "running" || job.value.status === "queued")
    );
  }

  function onUpdateGone(): void {
    options.updRestarting.value = true;
    if (updWatch) return;
    let failures = 0;
    updWatch = setInterval(async () => {
      try {
        await api.version();
        clearInterval(updWatch);
        updWatch = null;
        location.reload();
      } catch {
        if (++failures >= 40) {
          clearInterval(updWatch);
          updWatch = null;
          options.updRestartDead.value = true;
        }
      }
    }, 3000);
  }

  function pollJob(id: string): void {
    if (jobTimer) clearInterval(jobTimer);
    let failures = 0;
    let lastLive = 0;
    jobTimer = setInterval(async () => {
      try {
        const current = await api.jobGet(id);
        failures = 0;
        job.value = current;
        if (
          (current.status === "running" || current.status === "queued") &&
          Date.now() - lastLive > 15000
        ) {
          lastLive = Date.now();
          void options.loadFileTree();
          try {
            const overview = await api.overview(options.projectId.value);
            if (overview.summary) options.summary.value = overview.summary;
          } catch {
            // Live summary refresh is best effort.
          }
        }
        if (current.status !== "running" && current.status !== "queued") {
          clearInterval(jobTimer);
          jobTimer = null;
          await finishJob(current);
        }
      } catch {
        if (job.value?.action === "update") onUpdateGone();
        if (++failures >= 10) {
          clearInterval(jobTimer);
          jobTimer = null;
          if (job.value) {
            job.value = {
              ...job.value,
              status: "error",
              output:
                (job.value.output || "") +
                "\nНет ответа сервера (перезапуск?) — задача потеряна, запустите заново",
            };
          }
        }
      }
    }, 700);
  }

  async function finishJob(current: Job): Promise<void> {
    if (pendingPack.value && current.action === "merge") {
      pendingPack.value = false;
      if (
        current.status === "completed" ||
        current.status === "completed_with_errors"
      ) {
        try {
          await downloadPackZip();
          options.logText.value += "\nПак Harmonia собран и скачан (.zip)";
        } catch (error) {
          const message = errorMessage(error);
          options.showToast(message);
          options.logText.value += "\n" + message;
        }
      } else {
        options.logText.value +=
          "\nСборка не завершена (" + current.status + ") — архив не скачан";
      }
    }

    if (current.action === "update") {
      if (current.status === "failed") options.updModal.value = true;
      setTimeout(options.loadUpdateStatus, 400);
    } else if (current.action === "sync-sources") {
      setTimeout(async () => {
        await options.loadSourceStatus();
        if (options.projectId.value) await options.loadProject();
        else await options.scanSource();
      }, 400);
    } else if (current.action === "merge") {
      setTimeout(async () => {
        await options.loadFileTree();
        try {
          const overview = await api.overview(options.projectId.value);
          if (overview.summary) options.summary.value = overview.summary;
        } catch {
          // Post-merge summary refresh is best effort.
        }
      }, 400);
    } else {
      setTimeout(options.loadProject, 400);
    }
  }

  function startJob(details: Job): void {
    options.updRestarting.value = false;
    options.updRestartDead.value = false;
    if (updWatch) {
      clearInterval(updWatch);
      updWatch = null;
    }
    job.value = {
      id: details.id,
      status: details.status || "running",
      action: details.action,
      output: "",
    };
    pollJob(details.id);
  }

  async function cancelJob(): Promise<void> {
    pendingPack.value = false;
    if (!job.value) return;
    try {
      job.value = await api.jobCancel(job.value.id);
    } catch (error) {
      options.logText.value += "\n" + errorMessage(error);
    }
  }

  onUnmounted(() => {
    if (jobTimer) clearInterval(jobTimer);
    if (updWatch) clearInterval(updWatch);
  });

  return {
    job,
    pendingPack,
    jobLog,
    jobMainOutput,
    updateFailed,
    onJobScroll,
    jobActive,
    startJob,
    cancelJob,
    downloadPackZip,
  };
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
