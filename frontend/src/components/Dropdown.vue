<script lang="ts">
import { defineComponent } from "vue";

type DropdownOption = { value: unknown; label: string };
let nextDropdownId = 0;

export default defineComponent({
  props: {
    modelValue: { default: "" },
    options: { type: Array as () => DropdownOption[], default: () => [] },
    title: { type: String, default: "" },
    width: { type: String, default: "" },
    menuAlign: { type: String, default: "left" },
  },
  emits: ["update:modelValue", "change"],
  data() {
    return {
      open: false,
      hl: -1,
      up: false,
      menuId: "dropdown-" + ++nextDropdownId,
    };
  },
  computed: {
    norm() {
      return (this.options || []).map((o): DropdownOption =>
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
  beforeUnmount() {
    this.close(false);
  },
  methods: {
    toggle() {
      if (this.open) this.close();
      else this.show();
    },
    show() {
      if (this.open) return;
      this.open = true;
      const current = this.cur;
      const i = current
        ? this.norm.findIndex((o) => String(o.value) === String(current.value))
        : 0;
      this.hl = i >= 0 ? i : 0;
      document.addEventListener("click", this.outside, true);
      document.addEventListener("keydown", this.onDocKey, true);
      this.$nextTick(() => {
        try {
          const r = (this.$el as HTMLElement).getBoundingClientRect();
          this.up = window.innerHeight - r.bottom < 200 && r.top > 220;
        } catch {
          this.up = false;
        }
        this.focusHighlighted();
      });
    },
    close(restoreFocus = true) {
      const wasOpen = this.open;
      this.open = false;
      document.removeEventListener("click", this.outside, true);
      document.removeEventListener("keydown", this.onDocKey, true);
      if (restoreFocus && wasOpen)
        this.$nextTick(() => {
          (this.$refs.trigger as HTMLElement | undefined)?.focus();
        });
    },
    focusHighlighted(): void {
      if (!this.open || this.hl < 0) return;
      const items = Array.from(
        this.$el.querySelectorAll('[role="option"]'),
      ) as HTMLElement[];
      items[this.hl]?.focus();
    },
    moveHighlight(delta: number): void {
      if (!this.norm.length) return;
      this.hl = Math.max(0, Math.min(this.norm.length - 1, this.hl + delta));
      this.$nextTick(() => this.focusHighlighted());
    },
    outside(e: MouseEvent): void {
      if (this.$el && !this.$el.contains(e.target as Node)) this.close();
    },
    onTriggerKey(e: KeyboardEvent): void {
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        if (!this.open) this.show();
      }
    },
    onDocKey(e: KeyboardEvent): void {
      if (!this.open) return;
      if (e.key === "Escape") {
        e.preventDefault();
        this.close();
      } else if (e.key === "ArrowDown") {
        e.preventDefault();
        this.moveHighlight(1);
      } else if (e.key === "ArrowUp") {
        e.preventDefault();
        this.moveHighlight(-1);
      } else if (e.key === "Enter" && this.norm[this.hl]) {
        e.preventDefault();
        this.pick(this.norm[this.hl]);
      }
    },
    pick(o: DropdownOption): void {
      this.$emit("update:modelValue", o.value);
      this.$emit("change", o.value);
      this.close();
    },
  },
});
</script>

<template>
  <div class="dd" :style="width ? 'width:' + width : ''">
    <button
      ref="trigger"
      type="button"
      class="dd-btn"
      @click="toggle"
      @keydown="onTriggerKey"
      :title="title || curLabel"
      aria-haspopup="listbox"
      :aria-expanded="open"
      :aria-controls="menuId"
      :aria-activedescendant="open && hl >= 0 ? menuId + '-' + hl : undefined"
    >
      <span class="dd-label">{{ curLabel }}</span
      ><svg class="icon" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6" /></svg>
    </button>
    <div
      v-if="open"
      :id="menuId"
      class="dd-menu"
      :class="[up ? 'up' : '', menuAlign]"
      role="listbox"
      :aria-label="title || 'Варианты'"
    >
      <div
        v-for="(o, i) in norm"
        :id="menuId + '-' + i"
        :key="String(o.value)"
        class="dd-item"
        :class="{
          sel: cur && String(o.value) === String(cur.value),
          hl: i === hl,
        }"
        role="option"
        :aria-selected="!!(cur && String(o.value) === String(cur.value))"
        :tabindex="i === hl ? 0 : -1"
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
