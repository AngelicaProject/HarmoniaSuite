<script lang="ts">
import { defineComponent, PropType } from "vue";
import { api } from "../api/client";
type PackViewMeta = {
  packId?: string;
  translationVersion?: string;
  gameVersion?: string;
  vendorId?: string;
  vendorName?: string;
  vendorUrl?: string;
  vendorContact?: string;
  title?: string;
  license?: string;
  homepage?: string;
  description?: string;
  changelog?: string;
  minPluginVersion?: string;
  authors?: Array<{ name?: string; role?: string; contact?: string }>;
};
type PackTextKey = Exclude<keyof PackViewMeta, "authors">;
export default defineComponent({
  props: {
    projectId: { type: String, required: true },
    pack: { type: Object as PropType<PackViewMeta>, required: true },
    errors: { type: Array as PropType<string[]>, default: () => [] },
    manifest: {
      type: Object as PropType<Record<string, unknown> | null>,
      default: null,
    },
    msg: { type: String, default: "" },
    compat: { type: String, default: "" },
    langs: { type: String, default: "" },
  },
  emits: [
    "update:compat",
    "update:langs",
    "save",
    "addAuthor",
    "delAuthor",
    "toast",
  ],
  data() {
    return { touched: {} as Record<string, boolean> };
  },
  computed: {
    manifestText() {
      return this.manifest ? JSON.stringify(this.manifest, null, 2) : "";
    },
    visibleErrors() {
      return (this.errors || []).filter((e) => !this.isStaleRequired(e));
    },
    staleCount() {
      return (this.errors || []).length - this.visibleErrors.length;
    },
    invalidSet() {
      const s = new Set<string>();
      for (const e of this.visibleErrors) {
        const k = this.errKey(e);
        if (k === "vendor") {
          s.add("vendorId");
          s.add("vendorName");
        } else if (k) s.add(k);
      }
      for (const k of Object.keys(this.touched || {})) s.delete(k);
      return s;
    },
  },
  watch: {
    errors() {
      this.touched = {};
    },
  },
  methods: {
    packValue(key: keyof PackViewMeta): string {
      return String(this.pack?.[key] || "");
    },
    setPackValue(key: PackTextKey, value: string) {
      this.pack[key] = value;
    },
    packAuthors(): Array<{ name?: string; role?: string; contact?: string }> {
      return this.pack?.authors || [];
    },
    isStaleRequired(e: string): boolean {
      if (!/обязател/.test(String(e || ""))) return false;
      const p = this.pack || {};
      const k = this.errKey(e);
      const filled = (v: unknown): boolean => !!String(v || "").trim();
      if (k === "packId") return filled(p.packId);
      if (k === "translationVersion") return filled(p.translationVersion);
      if (k === "gameVersion") return filled(p.gameVersion);
      if (k === "vendor") return filled(p.vendorId) && filled(p.vendorName);
      if (k === "authors")
        return (p.authors || []).some((a) => a && filled(a.name));
      return false;
    },
    errKey(e: string): string {
      const s = String(e || "");
      if (s.startsWith("packId")) return "packId";
      if (s.startsWith("translationVersion")) return "translationVersion";
      if (s.startsWith("gameVersion")) return "gameVersion";
      if (s.startsWith("vendor")) return "vendor";
      if (s.startsWith("authors")) return "authors";
      return "";
    },
    errFixed(e: string): boolean {
      const k = this.errKey(e);
      if (!k) return false;
      const t = this.touched || {};
      if (k === "vendor") return !!(t.vendorId && t.vendorName);
      return !!t[k];
    },
    goErr(e: string): void {
      const k = this.errKey(e);
      if (!k || !this.$el || !this.$el.querySelector) return;
      const el = this.$el.querySelector('[data-err-field="' + k + '"]');
      if (!el) return;
      try {
        el.scrollIntoView({ block: "center" });
        const inps = Array.from(el.querySelectorAll("input,textarea"));
        const bad: HTMLElement[] = [];
        inps.forEach((i) => {
          const input = i as HTMLElement;
          if (input.classList.contains("invalid")) bad.push(input);
        });
        (bad.length ? bad : [inps[0] as HTMLElement].filter(Boolean)).forEach(
          (i) => {
            i.classList.add("flash");
            setTimeout(() => {
              try {
                i.classList.remove("flash");
              } catch (e2) {}
            }, 2400);
          },
        );
        const inp = el.querySelector("input,textarea");
        if (inp) inp.focus({ preventScroll: true });
      } catch (err) {}
    },
    async downloadManifest() {
      try {
        const b = await api.downloadManifest(this.projectId);
        const u = URL.createObjectURL(b);
        const a = document.createElement("a");
        a.href = u;
        a.download = "manifest.json";
        a.click();
        URL.revokeObjectURL(u);
      } catch (e) {
        this.$emit("toast", e instanceof Error ? e.message : String(e));
      }
    },
  },
});
</script>

<template>
  <div class="pane-body summary-pad">
    <template v-if="!pack">
      <div class="ft-empty">
        <span class="job-spin"></span><span>Загрузка…</span>
      </div>
    </template>
    <template v-else>
      <div class="sum-sec" style="margin-top: 0">Пак</div>
      <div class="set-hint">
        Поля со * обязательны — без них ZIP не соберётся.
      </div>
      <div class="set-field" style="margin-top: 10px" data-err-field="packId">
        <span class="set-label">ID пака *</span>
        <input
          :value="packValue('packId')"
          @input="
            setPackValue('packId', ($event.target as HTMLInputElement).value);
            touched.packId = true;
          "
          class="set-control"
          :class="{ invalid: invalidSet.has('packId') }"
          placeholder="primer-ru-pack"
        />
        <div class="set-hint">
          Латиница, цифры, дефис и _. Папка в ZIP называется так же.
        </div>
      </div>
      <div class="set-row" style="margin-top: 14px; max-width: 560px">
        <div style="flex: 1; min-width: 0" data-err-field="translationVersion">
          <span class="set-label">Версия перевода *</span
          ><input
            :value="packValue('translationVersion')"
            @input="
              setPackValue(
                'translationVersion',
                ($event.target as HTMLInputElement).value,
              );
              touched.translationVersion = true;
            "
            class="set-control"
            :class="{ invalid: invalidSet.has('translationVersion') }"
            placeholder="1.0.0"
          />
        </div>
        <div style="flex: 1; min-width: 0" data-err-field="gameVersion">
          <span class="set-label">Версия игры *</span
          ><input
            :value="packValue('gameVersion')"
            @input="
              setPackValue(
                'gameVersion',
                ($event.target as HTMLInputElement).value,
              );
              touched.gameVersion = true;
            "
            class="set-control"
            :class="{ invalid: invalidSet.has('gameVersion') }"
            placeholder="2026.08.11.0000.0000"
          />
        </div>
      </div>
      <div class="set-hint">
        Версия игры — точная строка клиента (GameVersionString): Harmonia
        сравнивает её без нормализации.
      </div>
      <div class="set-field">
        <span class="set-label">Совместимые версии игры</span>
        <input
          :value="compat"
          @input="
            $emit('update:compat', ($event.target as HTMLInputElement).value)
          "
          class="set-control"
          placeholder="2026.08.11.0000.0000"
        />
        <div class="set-hint">
          Через запятую. Пусто — только версия игры выше.
        </div>
      </div>
      <div class="sum-sec">Издатель</div>
      <div
        class="set-row"
        style="margin-top: 10px; max-width: 560px"
        data-err-field="vendor"
      >
        <div style="flex: 1; min-width: 0">
          <span class="set-label">Vendor ID *</span
          ><input
            :value="packValue('vendorId')"
            @input="
              setPackValue(
                'vendorId',
                ($event.target as HTMLInputElement).value,
              );
              touched.vendorId = true;
            "
            class="set-control"
            :class="{ invalid: invalidSet.has('vendorId') }"
            placeholder="my-team"
          />
        </div>
        <div style="flex: 1; min-width: 0">
          <span class="set-label">Vendor name *</span
          ><input
            :value="packValue('vendorName')"
            @input="
              setPackValue(
                'vendorName',
                ($event.target as HTMLInputElement).value,
              );
              touched.vendorName = true;
            "
            class="set-control"
            :class="{ invalid: invalidSet.has('vendorName') }"
            placeholder="My Team"
          />
        </div>
      </div>
      <div class="set-field">
        <span class="set-label">Vendor URL</span>
        <input
          :value="packValue('vendorUrl')"
          @input="
            setPackValue('vendorUrl', ($event.target as HTMLInputElement).value)
          "
          class="set-control"
          placeholder="https://example.com"
        />
      </div>
      <div class="set-field">
        <span class="set-label">Vendor contact</span>
        <input
          :value="packValue('vendorContact')"
          @input="
            setPackValue(
              'vendorContact',
              ($event.target as HTMLInputElement).value,
            )
          "
          class="set-control"
          placeholder="contact"
        />
      </div>
      <div class="sum-sec">Описание</div>
      <div class="set-field" style="margin-top: 10px">
        <span class="set-label">Название</span>
        <input
          :value="packValue('title')"
          @input="
            setPackValue('title', ($event.target as HTMLInputElement).value)
          "
          class="set-control"
          placeholder="Example Pack"
        />
      </div>
      <div class="set-row" style="margin-top: 14px; max-width: 560px">
        <div style="flex: 1; min-width: 0">
          <span class="set-label">Языки</span
          ><input
            :value="langs"
            @input="
              $emit('update:langs', ($event.target as HTMLInputElement).value)
            "
            class="set-control"
            placeholder="ru"
          />
        </div>
        <div style="flex: 1; min-width: 0">
          <span class="set-label">Лицензия</span
          ><input
            :value="packValue('license')"
            @input="
              setPackValue('license', ($event.target as HTMLInputElement).value)
            "
            class="set-control"
            placeholder="CC-BY-4.0"
          />
        </div>
      </div>
      <div class="set-field">
        <span class="set-label">Homepage</span>
        <input
          :value="packValue('homepage')"
          @input="
            setPackValue('homepage', ($event.target as HTMLInputElement).value)
          "
          class="set-control"
          placeholder="https://example.com"
        />
      </div>
      <div class="set-field">
        <span class="set-label">Описание</span>
        <textarea
          :value="packValue('description')"
          @input="
            setPackValue(
              'description',
              ($event.target as HTMLTextAreaElement).value,
            )
          "
          rows="2"
          class="set-control"
          placeholder="Что переведено"
        ></textarea>
      </div>
      <div class="set-field">
        <span class="set-label">Changelog</span>
        <textarea
          :value="packValue('changelog')"
          @input="
            setPackValue(
              'changelog',
              ($event.target as HTMLTextAreaElement).value,
            )
          "
          rows="2"
          class="set-control"
          placeholder="1.0.0 — первый выпуск"
        ></textarea>
      </div>
      <div class="set-field">
        <span class="set-label">Min plugin version</span>
        <input
          :value="packValue('minPluginVersion')"
          @input="
            setPackValue(
              'minPluginVersion',
              ($event.target as HTMLInputElement).value,
            )
          "
          class="set-control"
          placeholder="1.0.0"
        />
      </div>
      <div class="sum-sec">Авторы *</div>
      <div data-err-field="authors">
        <div class="set-hint">Минимум один автор с именем.</div>
        <div
          v-for="(a, i) in packAuthors()"
          :key="i"
          style="display: flex; gap: 8px; margin-top: 8px; max-width: 560px"
        >
          <input
            class="grow"
            v-model="a.name"
            placeholder="Имя"
            style="flex: 2; min-width: 0"
            @input="touched.authors = true"
            :class="{ invalid: invalidSet.has('authors') }"
          />
          <input
            class="grow"
            v-model="a.role"
            placeholder="Роль"
            style="flex: 1; min-width: 0"
          />
          <button
            class="ghost icon-btn sm"
            @click="$emit('delAuthor', i)"
            title="Убрать"
            style="flex-shrink: 0"
          >
            <svg class="icon" viewBox="0 0 24 24">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
        <div class="set-actions">
          <button class="subtle" @click="$emit('addAuthor')">+ Автор</button>
        </div>
      </div>
      <div class="sum-sec">Проверка</div>
      <div v-if="visibleErrors.length" style="margin-top: 8px">
        <button
          v-for="e in visibleErrors"
          :key="e"
          class="form-error err-go"
          :class="{ nolink: !errKey(e), fixed: errFixed(e) }"
          @click="goErr(e)"
          :title="errKey(e) ? 'Перейти к полю' : '—'"
        >
          {{ e }}
        </button>
      </div>
      <div v-else-if="manifest" class="set-ok" style="margin-top: 8px">
        Манифест валиден — ZIP соберётся
      </div>
      <div
        v-if="staleCount && !visibleErrors.length && !manifest"
        class="set-hint"
        style="margin-top: 8px"
      >
        Ошибки исправлены — нажмите «Сохранить» для перепроверки.
      </div>
      <details v-if="manifest" class="pack-details">
        <summary>Показать manifest.json</summary>
        <pre class="log" style="max-height: 220px; margin-top: 8px">{{
          manifestText
        }}</pre>
      </details>
      <div
        class="set-foot"
        style="
          position: sticky;
          bottom: 0;
          background: var(--surface);
          padding-bottom: 12px;
        "
      >
        <button class="primary" @click="$emit('save')">Сохранить</button
        ><button
          class="subtle"
          @click="downloadManifest"
          title="Скачать manifest.json"
        >
          <svg class="icon" viewBox="0 0 24 24">
            <path d="M12 3v12M8 11l4 4 4-4" />
            <path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" /></svg
          >manifest.json
        </button>
      </div>
      <div v-if="msg" class="set-ok">{{ msg }}</div>
      <div class="set-hint">
        manifest.json записывается в вывод при сборке и включается в ZIP.
      </div>
    </template>
  </div>
</template>
