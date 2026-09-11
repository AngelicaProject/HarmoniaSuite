import { computed, onUnmounted, ref, type Ref } from "vue";
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
  let jobTimer: ReturnType<typeof setInterval> | null = null;
  let updWatch: ReturnType<typeof setInterval> | null = null;
  const deferredTimers = new Set<ReturnType<typeof setTimeout>>();

  function schedule(callback: () => void | Promise<void>, delay: number): void {
    const timer = setTimeout(() => {
      deferredTimers.delete(timer);
      void callback();
    }, delay);
    deferredTimers.add(timer);
  }

  function stopJobTimer(): void {
    const timer = jobTimer;
    if (timer) clearInterval(timer);
    jobTimer = null;
  }

  function stopUpdateWatch(): void {
    const timer = updWatch;
    if (timer) clearInterval(timer);
    updWatch = null;
  }

  const updateFailed = computed(
    () =>
      !!job.value &&
      job.value.action === "update" &&
      job.value.status === "failed",
  );

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
        stopUpdateWatch();
        location.reload();
      } catch {
        if (++failures >= 40) {
          stopUpdateWatch();
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
          stopJobTimer();
          await finishJob(current);
        }
      } catch {
        if (job.value?.action === "update") onUpdateGone();
        if (++failures >= 10) {
          stopJobTimer();
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
      schedule(options.loadUpdateStatus, 400);
    } else if (current.action === "sync-sources") {
      schedule(async () => {
        await options.loadSourceStatus();
        if (options.projectId.value) await options.loadProject();
        else await options.scanSource();
      }, 400);
    } else if (current.action === "merge") {
      schedule(async () => {
        await options.loadFileTree();
        try {
          const overview = await api.overview(options.projectId.value);
          if (overview.summary) options.summary.value = overview.summary;
        } catch {
          // Post-merge summary refresh is best effort.
        }
      }, 400);
    } else {
      schedule(options.loadProject, 400);
    }
  }

  function startJob(details: Job): void {
    options.updRestarting.value = false;
    options.updRestartDead.value = false;
    if (updWatch) {
      stopUpdateWatch();
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
    stopJobTimer();
    stopUpdateWatch();
    for (const timer of deferredTimers) clearTimeout(timer);
    deferredTimers.clear();
  });

  return {
    job,
    pendingPack,
    updateFailed,
    jobActive,
    startJob,
    cancelJob,
    downloadPackZip,
  };
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
