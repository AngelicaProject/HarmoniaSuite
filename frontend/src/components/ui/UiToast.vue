<script setup lang="ts">
defineProps<{
  message: string;
}>();
</script>

<template>
  <div v-if="message" class="toast" role="status" aria-live="polite">
    {{ message }}
  </div>
</template>
