<script setup lang="ts">
const productName = "Harmonia";
</script>

<template>
  <main class="app-shell">
    <section class="welcome-card" aria-labelledby="app-title">
      <p class="eyebrow">Harmonia Suite</p>
      <h1 id="app-title">{{ productName }}</h1>
      <p class="message">
        The legacy project and translation editor has been removed. The
        canonical source pipeline is under reconstruction.
      </p>
      <p class="muted">Workspace and translation features are forthcoming.</p>
    </section>
  </main>
</template>
