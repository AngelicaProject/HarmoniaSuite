<script lang="ts">
import { defineComponent } from "vue";
export default defineComponent({
  props: {
    modelValue: { default: "" },
    options: { type: Array, default: () => [] },
    title: { type: String, default: "" },
    width: { type: String, default: "" },
    menuAlign: { type: String, default: "left" },
  },
  emits: ["update:modelValue", "change"],
  data() {
    return { open: false, hl: -1, up: false };
  },
  computed: {
    norm() {
      return (this.options || []).map((o) =>
        o && typeof o === "object" ? o : { value: o, label: String(o) },
      );
    },
    cur() {
      const v = String(this.modelValue ?? "");
      return this.norm.find((o) => String(o.value) === v) || null;
    },
    curLabel() {
      return this.cur ? this.cur.label : "—";
    },
  },
  methods: {
    toggle() {
      if (this.open) this.close();
      else this.show();
    },
    show() {
      this.open = true;
      const i = this.cur
        ? this.norm.findIndex((o) => String(o.value) === String(this.cur.value))
        : 0;
      this.hl = i >= 0 ? i : 0;
      document.addEventListener("click", this.outside, true);
      document.addEventListener("keydown", this.onDocKey, true);
      this.$nextTick(() => {
        try {
          const r = this.$el.getBoundingClientRect();
          this.up = window.innerHeight - r.bottom < 200 && r.top > 220;
        } catch (e) {
          this.up = false;
        }
      });
    },
    close() {
      this.open = false;
      document.removeEventListener("click", this.outside, true);
      document.removeEventListener("keydown", this.onDocKey, true);
    },
    outside(e) {
      if (this.$el && !this.$el.contains(e.target)) this.close();
    },
    onDocKey(e) {
      if (!this.open) return;
      if (e.key === "Escape") this.close();
      else if (e.key === "ArrowDown") {
        e.preventDefault();
        this.hl = Math.min(this.norm.length - 1, this.hl + 1);
      } else if (e.key === "ArrowUp") {
        e.preventDefault();
        this.hl = Math.max(0, this.hl - 1);
      } else if (e.key === "Enter" && this.norm[this.hl]) {
        e.preventDefault();
        this.pick(this.norm[this.hl]);
      }
    },
    pick(o) {
      this.$emit("update:modelValue", o.value);
      this.$emit("change", o.value);
      this.close();
    },
  },
  beforeUnmount() {
    document.removeEventListener("click", this.outside, true);
    document.removeEventListener("keydown", this.onDocKey, true);
  },
});
</script>

<template>
  <div class="dd" :style="width ? 'width:' + width : ''">
    <button
      type="button"
      class="dd-btn"
      @click="toggle"
      :title="title || curLabel"
    >
      <span class="dd-label">{{ curLabel }}</span
      ><svg class="icon" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6" /></svg>
    </button>
    <div v-if="open" class="dd-menu" :class="[up ? 'up' : '', menuAlign]">
      <div
        v-for="(o, i) in norm"
        :key="String(o.value)"
        class="dd-item"
        :class="{
          sel: cur && String(o.value) === String(cur.value),
          hl: i === hl,
        }"
        @click="pick(o)"
        @mouseenter="hl = Number(i)"
        :title="o.label"
      >
        <span class="dd-text">{{ o.label }}</span>
      </div>
      <div
        v-if="!norm.length"
        class="muted"
        style="padding: 7px 10px; font-size: 12px"
      >
        Нет вариантов
      </div>
    </div>
  </div>
</template>
