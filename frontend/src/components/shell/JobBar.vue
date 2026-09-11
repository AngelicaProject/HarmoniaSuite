<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from "vue";
import type { Job } from "../../api/types";

const props = defineProps<{
  job: Job | null;
}>();

const emit = defineEmits<{
  cancel: [];
  close: [];
}>();

const jobLog = ref<HTMLElement | null>(null);
const jobStick = ref(true);
const scrollTimer = ref<ReturnType<typeof setTimeout> | null>(null);

const jobMainOutput = computed(() =>
  ((props.job && props.job.output) || "")
    .split("\n")
    .filter((line) => !line.startsWith("[REASONING]"))
    .join("\n"),
);

function scrollToEnd(): void {
  const element = jobLog.value;
  if (element) element.scrollTop = element.scrollHeight;
}

watch(
  () => props.job?.output,
  () => {
    if (!jobStick.value) return;
    if (scrollTimer.value) clearTimeout(scrollTimer.value);
    scrollTimer.value = setTimeout(() => {
      scrollTimer.value = null;
      void nextTick(scrollToEnd);
    }, 0);
  },
);

watch(
  () => props.job?.id,
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

onUnmounted(() => {
  if (scrollTimer.value) clearTimeout(scrollTimer.value);
});
</script>

<template>
  <div v-if="job" class="jobbar" :class="job.status">
    <div class="job-row">
      <span class="job-spin" v-if="job.status === 'running'"></span
      ><b>{{
        {
          extract: "Обновление данных",
          gemini: "Перевод Gemini",
          openrouter: "Перевод OpenRouter",
          merge: "Сборка CSV",
          "sync-sources": "Синхронизация источников",
          update: "Обновление приложения",
        }[job.action] || job.action
      }}</b
      ><span class="muted job-status">{{
        job.status === "running"
          ? "выполняется…"
          : job.status === "queued"
            ? "в очереди…"
            : job.status === "completed"
              ? "готово"
              : job.status === "cancelled"
                ? "отменено"
                : "ошибка"
      }}</span
      ><button
        v-if="job.status === 'running' || job.status === 'queued'"
        class="ghost sm job-action"
        @click="emit('cancel')"
        title="Остановить"
      >
        Отмена</button
      ><button
        v-if="job.status !== 'running' && job.status !== 'queued'"
        class="ghost icon-btn sm job-action"
        @click="emit('close')"
        title="Закрыть"
      >
        <svg class="icon" viewBox="0 0 24 24">
          <path d="M6 6l12 12M18 6L6 18" />
        </svg>
      </button>
    </div>
    <pre ref="jobLog" class="job-log" @scroll="onJobScroll">{{
      jobMainOutput
    }}</pre>
  </div>
</template>
