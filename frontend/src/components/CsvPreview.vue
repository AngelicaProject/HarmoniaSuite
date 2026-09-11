<script lang="ts">
import { defineComponent } from "vue";
export default defineComponent({
  props: ["file", "data", "loading", "error", "embedded"],
  emits: ["close"],
  computed: {
    head() {
      return (this.data && this.data.head) || [];
    },
    names() {
      const h = this.head;
      return h.length > 1 ? h[1] : [];
    },
    types() {
      const h = this.head;
      return h.length ? h[h.length - 1] : [];
    },
    rows() {
      return (this.data && this.data.data) || [];
    },
    strCols() {
      return new Set(
        (this.data && (this.data.stringColumns ?? this.data.stringColumns)) ||
          [],
      );
    },
    total() {
      return (this.data && this.data.rows) || 0;
    },
    truncated() {
      return !!(this.data && this.data.truncated);
    },
  },
});
</script>

<template>
  <div
    :class="embedded ? 'csv-embed' : 'overlay'"
    @click.self="!embedded ? $emit('close') : null"
  >
    <div class="modal csv-modal">
      <div
        style="
          display: flex;
          justify-content: space-between;
          align-items: center;
          gap: 12px;
        "
      >
        <h2
          style="
            margin: 0;
            font-size: 16px;
            font-weight: 800;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
          "
        >
          {{ file }}
        </h2>
        <button class="ghost icon-btn" @click="$emit('close')" title="Закрыть">
          <svg class="icon" viewBox="0 0 24 24">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </div>
      <div class="muted" style="font-size: 12px; margin-top: 6px">
        Строк данных: {{ rows.length }}<span v-if="data"> / {{ total }}</span> ·
        String-колонки подсвечены<span v-if="truncated">
          · показано не всё (лимит)</span
        >
      </div>
      <div v-if="loading" class="ft-empty">
        <span class="job-spin"></span><span>Загрузка таблицы…</span>
      </div>
      <div v-else-if="error" class="ft-empty">
        <span>Не удалось загрузить: {{ error }}</span
        ><button class="ghost" @click="$emit('close')" style="margin-top: 8px">
          Закрыть
        </button>
      </div>
      <div v-else class="csv-wrap">
        <table class="csv-table">
          <thead>
            <tr>
              <th class="rk">#</th>
              <th
                v-for="(c, i) in names"
                :key="'n' + i"
                :class="{ str: strCols.has(i) }"
              >
                {{ c || "c" + i }}
              </th>
            </tr>
            <tr class="csv-types">
              <th class="rk"></th>
              <th
                v-for="(c, i) in types"
                :key="'t' + i"
                :class="{ str: strCols.has(i) }"
              >
                {{ c }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, ri) in rows" :key="ri">
              <td class="rk">{{ ri }}</td>
              <td
                v-for="(cell, ci) in r"
                :key="ci"
                :class="{ str: strCols.has(ci) }"
              >
                {{ cell }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
