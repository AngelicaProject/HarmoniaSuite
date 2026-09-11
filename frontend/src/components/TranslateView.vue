<script lang="ts">
import { defineComponent } from "vue";
import { api } from "../api/client";
import type { AiModel } from "../api/types";
import Dropdown from "./Dropdown.vue";

interface TranslateTreeNode {
  name: string;
  path: string;
  dirs: Map<string, TranslateTreeNode>;
  files: string[];
}

interface TranslateDirRow {
  t: "d";
  key: string;
  path: string;
  depth: number;
  name: string;
  open: boolean;
  sum: number;
  files: string[];
  dir: TranslateTreeNode;
}

interface TranslateFileRow {
  t: "f";
  key: string;
  path: string;
  depth: number;
  need: number;
}

type TranslateRow = TranslateDirRow | TranslateFileRow;
export default defineComponent({
  components: { Dropdown },
  props: [
    "files",
    "selected",
    "trCount",
    "estimate",
    "pendingMap",
    "mapReady",
    "loading",
    "error",
    "root",
    "gemini",
    "job",
  ],
  emits: [
    "toggle",
    "selVisible",
    "clearSel",
    "refresh",
    "preview",
    "translate",
    "cancel",
  ],
  data() {
    return {
      filter: "",
      provider: "gemini",
      model: "",
      reasoning: "",
      reasonOpen: true,
      reasonStick: true,
      orModels: [] as AiModel[],
      orComboOpen: false,
      orComboQ: "",
      openDirs: {} as Record<string, boolean>,
      sort: "need",
    };
  },
  computed: {
    running() {
      return !!this.job && this.job.status === "running";
    },
    pendOf() {
      const m = this.pendingMap || {};
      return (f: string): number => m[f] || 0;
    },
    matchFiles() {
      const q = (this.filter || "").toLowerCase();
      const m = this.pendingMap || {};
      return (this.files || []).filter(
        (f: string) =>
          f.toLowerCase().includes(q) && (!this.mapReady || (m[f] || 0) > 0),
      );
    },
    tree() {
      const root: TranslateTreeNode = {
        name: "",
        path: "",
        dirs: new Map(),
        files: [],
      };
      for (const f of this.matchFiles) {
        const parts = (f || "").split("/");
        let node: TranslateTreeNode = root;
        for (let i = 0; i < parts.length - 1; i++) {
          let d = node.dirs.get(parts[i]);
          if (!d) {
            const created: TranslateTreeNode = {
              name: parts[i],
              path: parts.slice(0, i + 1).join("/"),
              dirs: new Map(),
              files: [],
            };
            node.dirs.set(parts[i], created);
            d = created;
          }
          node = d;
        }
        node.files.push(f);
      }
      return root;
    },
    rows() {
      const m = this.pendingMap || {};
      const q = (this.filter || "").trim();
      const rows: TranslateRow[] = [];
      const collect = (n: TranslateTreeNode): string[] => {
        const all = [...n.files];
        for (const c of n.dirs.values()) all.push(...collect(c));
        return all;
      };
      const emit = (node: TranslateTreeNode, depth: number): void => {
        const kids: TranslateRow[] = [];
        for (const d of node.dirs.values()) {
          const all = collect(d);
          kids.push({
            t: "d",
            key: "d:" + d.path,
            path: d.path,
            name: d.name,
            depth,
            open: !!q || !!this.openDirs[d.path],
            sum: all.reduce((a, f) => a + (m[f] || 0), 0),
            files: all,
            dir: d,
          });
        }
        for (const f of node.files) {
          kids.push({ t: "f", key: "f:" + f, path: f, depth, need: m[f] || 0 });
        }
        kids.sort((a, b) => {
          const ak = a.t === "d" ? a.name : a.path,
            bk = b.t === "d" ? b.name : b.path;
          if (this.sort === "name") return ak < bk ? -1 : 1;
          const an = a.t === "d" ? a.sum : a.need,
            bn = b.t === "d" ? b.sum : b.need;
          if (bn !== an) return bn - an;
          return ak < bk ? -1 : 1;
        });
        for (const k of kids) {
          rows.push(k);
          if (k.t === "d" && k.open) emit(k.dir, depth + 1);
        }
      };
      emit(this.tree, 0);
      return rows;
    },
    visibleFiles() {
      const out: string[] = [];
      for (const r of this.rows) {
        if (r.t === "d") out.push(...r.files);
        else out.push(r.path);
      }
      return out;
    },
    emptyHidden() {
      if (!this.mapReady) return 0;
      const q = (this.filter || "").toLowerCase();
      const m = this.pendingMap || {};
      let n = 0;
      for (const f of this.files || []) {
        if (f.toLowerCase().includes(q) && !(m[f] > 0)) n++;
      }
      return n;
    },
    keyMissing() {
      const g = this.gemini || {};
      return this.provider === "openrouter"
        ? !g.openrouterConfigured
        : !g.configured;
    },
    modelList() {
      const g = this.gemini || {};
      const list = (g.models && g.models.length ? g.models : [g.model]).filter(
        Boolean,
      );
      return list.length ? list : [""];
    },
    curModel() {
      if (this.provider === "openrouter")
        return this.model || (this.gemini && this.gemini.openrouterModel) || "";
      return this.model || (this.gemini && this.gemini.model) || "";
    },
    orOptions() {
      const out = (this.orModels || []).map((m: AiModel) => ({
        id: m.id,
        name: m.name || m.id,
      }));
      const def = (this.gemini && this.gemini.openrouterModel) || "";
      const has = (v: string): boolean => out.some((o) => o.id === v);
      if (this.model && !has(this.model))
        out.unshift({ id: this.model, name: this.model });
      if (def && !has(def))
        out.unshift({ id: def, name: def + " (по умолчанию)" });
      else if (def) {
        const d = out.find((o) => o.id === def);
        if (d && !d.name.endsWith("(по умолчанию)"))
          d.name += " (по умолчанию)";
      }
      return out;
    },
    orComboList() {
      const q = (this.orComboQ || "").trim().toLowerCase();
      const all = this.orOptions || [];
      const hit = q
        ? all.filter((m) =>
            (m.id + " " + (m.name || "")).toLowerCase().includes(q),
          )
        : all;
      return hit.slice(0, 80);
    },
    orComboMore() {
      const q = (this.orComboQ || "").trim().toLowerCase();
      const all = this.orOptions || [];
      const n = q
        ? all.filter((m) =>
            (m.id + " " + (m.name || "")).toLowerCase().includes(q),
          ).length
        : all.length;
      return n > 80 ? n - 80 : 0;
    },
    estEntries() {
      return (this.estimate && this.estimate.entries) || 0;
    },
    reasonDefaultLabel() {
      const r = (this.gemini && this.gemini.openrouterReasoning) || "";
      return r ? " (" + r + ")" : " (выкл)";
    },
    reasonLines() {
      const out = ((this.job && this.job.output) || "").split("\n");
      return out
        .filter((l: string) => l.startsWith("[REASONING]"))
        .map((l: string) => l.slice(11).trim());
    },
  },
  methods: {
    fmtNum(n: number): string {
      return Number(n || 0).toLocaleString("ru-RU");
    },
    toggleDir(path: string): void {
      this.openDirs = { ...this.openDirs, [path]: !this.openDirs[path] };
    },
    dirChecked(files: string[]): boolean {
      return files.length > 0 && files.every((f) => this.selected.has(f));
    },
    toggleDirSel(files: string[]): void {
      this.$emit("selVisible", !this.dirChecked(files), files);
    },
    toggleOrCombo() {
      if (this.orComboOpen) {
        this.closeOrCombo();
        return;
      }
      this.orComboOpen = true;
      this.orComboQ = "";
      setTimeout(() => {
        document.addEventListener("click", this.closeOrCombo, true);
        const input = this.$refs.orComboQ as HTMLInputElement | undefined;
        if (input) input.focus();
      }, 0);
    },
    closeOrCombo(e?: Event) {
      const target = e?.target as Element | null;
      if (target?.closest && target.closest(".or-combo,.or-combo-btn")) return;
      this.orComboOpen = false;
      document.removeEventListener("click", this.closeOrCombo, true);
    },
    pickOrModel(id: string): void {
      this.model = id;
      this.closeOrCombo();
    },
    onReasonScroll(): void {
      const el = this.$refs.reasonBox as HTMLElement | undefined;
      if (!el) return;
      this.reasonStick = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
    },
  },
  watch: {
    files() {
      this.openDirs = {};
    },
    reasonLines() {
      if (!this.reasonStick || !this.reasonOpen) return;
      this.$nextTick(() => {
        const el = this.$refs.reasonBox as HTMLElement | undefined;
        if (el) el.scrollTop = el.scrollHeight;
      });
    },
  },
  async mounted() {
    try {
      this.orModels = await api.aiModels();
    } catch (e) {}
  },
  unmounted() {
    document.removeEventListener("click", this.closeOrCombo, true);
  },
});
</script>

<template>
  <div class="pane-body" style="display: flex; flex-direction: column">
    <div class="search-box" style="flex-direction: column; gap: 8px">
      <div
        class="muted"
        style="
          font-size: 11px;
          text-transform: uppercase;
          letter-spacing: 0.08em;
          font-weight: 700;
        "
      >
        Нейроперевод
      </div>
      <div class="muted" style="font-size: 12px">
        Провайдер
        <div style="margin-top: 6px">
          <Dropdown
            v-model="provider"
            width="100%"
            :options="[
              { value: 'gemini', label: 'Gemini' },
              { value: 'openrouter', label: 'OpenRouter' },
            ]"
          />
        </div>
      </div>
      <div
        v-if="provider !== 'openrouter'"
        class="muted"
        style="font-size: 12px"
      >
        Модель
        <div style="margin-top: 6px">
          <Dropdown
            :modelValue="curModel"
            @update:modelValue="model = $event"
            width="100%"
            :options="modelList"
          />
        </div>
      </div>
      <label v-else class="muted" style="font-size: 12px"
        >Модель
        <div
          v-if="orOptions.length"
          style="display: flex; gap: 6px; margin-top: 6px"
        >
          <div style="position: relative; flex: 1; min-width: 0">
            <button
              class="or-combo-btn"
              style="
                width: 100%;
                display: flex;
                justify-content: space-between;
                align-items: center;
                gap: 8px;
              "
              @click="toggleOrCombo"
            >
              <span
                style="
                  overflow: hidden;
                  text-overflow: ellipsis;
                  white-space: nowrap;
                "
                >{{ curModel || "Выберите модель…" }}</span
              ><svg class="icon" viewBox="0 0 24 24" style="flex-shrink: 0">
                <path d="M6 9l6 6 6-6" />
              </svg>
            </button>
            <div
              v-if="orComboOpen"
              class="dz-menu or-combo"
              style="
                top: calc(100% + 4px);
                left: 0;
                right: 0;
                bottom: auto;
                max-height: 260px;
                display: flex;
                flex-direction: column;
              "
            >
              <div style="padding: 6px">
                <input
                  ref="orComboQ"
                  class="grow"
                  style="width: 100%"
                  v-model="orComboQ"
                  placeholder="Поиск модели…"
                />
              </div>
              <div style="overflow: auto">
                <div
                  v-for="m in orComboList"
                  :key="m.id"
                  class="dz-menu-i"
                  @click="pickOrModel(m.id)"
                  :title="m.id"
                >
                  <span
                    style="
                      overflow: hidden;
                      text-overflow: ellipsis;
                      white-space: nowrap;
                    "
                    >{{ m.name || m.id }}</span
                  >
                </div>
                <div
                  v-if="orComboMore"
                  class="muted"
                  style="padding: 4px 10px; font-size: 12px"
                >
                  …и ещё {{ orComboMore }}
                </div>
                <div
                  v-if="!orComboList.length"
                  class="muted"
                  style="padding: 4px 10px; font-size: 12px"
                >
                  Ничего не найдено
                </div>
              </div>
            </div>
          </div>
          <button
            v-if="model"
            class="ghost icon-btn sm"
            @click="model = ''"
            title="Сбросить на модель по умолчанию"
            style="flex-shrink: 0"
          >
            ×
          </button>
        </div>
        <input
          v-else
          :value="curModel"
          @input="model = ($event.target as HTMLInputElement).value"
          style="margin-left: 8px; max-width: 100%"
          :placeholder="(gemini && gemini.openrouterModel) || ''"
        />
      </label>
      <div
        v-if="provider === 'openrouter'"
        class="muted"
        style="font-size: 12px"
      >
        Reasoning
        <div style="margin-top: 6px">
          <Dropdown
            v-model="reasoning"
            width="100%"
            :options="[
              { value: '', label: 'По умолчанию' + reasonDefaultLabel },
              { value: 'off', label: 'Выкл' },
              { value: 'low', label: 'Low' },
              { value: 'medium', label: 'Medium' },
              { value: 'high', label: 'High' },
            ]"
          />
        </div>
      </div>
      <div v-if="keyMissing" class="muted" style="font-size: 12px">
        Нет ключа: задайте его в настройках (меню).
      </div>
      <div style="display: flex; gap: 6px">
        <button
          v-if="!running"
          class="primary grow"
          @click="
            $emit('translate', provider, curModel, reasoning || undefined)
          "
          :disabled="keyMissing || !trCount"
          title="Перевести отмеченные таблицы"
        >
          Перевести (<span style="font-variant-numeric: tabular-nums">{{
            trCount
          }}</span
          >)
        </button>
        <button v-else class="grow" @click="$emit('cancel')" title="Остановить">
          Остановить
        </button>
      </div>
      <div
        v-if="reasonLines.length"
        style="
          border: 1px solid var(--border-soft);
          border-radius: 8px;
          padding: 6px 8px;
        "
      >
        <div
          class="muted"
          @click="reasonOpen = !reasonOpen"
          title="Свернуть/развернуть"
          style="
            font-size: 11px;
            text-transform: uppercase;
            letter-spacing: 0.08em;
            font-weight: 700;
            margin-bottom: 4px;
            cursor: pointer;
            display: flex;
            justify-content: space-between;
            align-items: center;
          "
        >
          <span>Мышление модели ({{ reasonLines.length }})</span
          ><span>{{ reasonOpen ? "▾" : "▸" }}</span>
        </div>
        <div
          v-show="reasonOpen"
          ref="reasonBox"
          @scroll="onReasonScroll"
          style="max-height: 180px; overflow: auto"
        >
          <div
            v-for="(r, i) in reasonLines.slice(-10)"
            :key="i"
            style="font-size: 12px; white-space: pre-wrap; margin-bottom: 6px"
          >
            {{ r }}
          </div>
        </div>
      </div>
      <div v-if="!trCount" class="muted" style="font-size: 12px">
        Отметьте таблицы для перевода — пустой набор не переводит ничего.
      </div>
      <div v-else class="muted" style="font-size: 12px">
        Файлов: {{ trCount }} · строк к переводу:
        <span style="font-variant-numeric: tabular-nums">{{
          fmtNum(estEntries)
        }}</span>
      </div>
      <div style="display: flex; gap: 6px">
        <input
          class="grow"
          :value="filter"
          @input="filter = ($event.target as HTMLInputElement).value"
          placeholder="Фильтр таблиц…"
        /><Dropdown
          v-model="sort"
          title="Сортировка"
          width="148px"
          :options="[
            { value: 'need', label: 'Недопереведённые' },
            { value: 'name', label: 'По имени' },
          ]"
        />
      </div>
      <div style="display: flex; gap: 6px">
        <button class="grow" @click="$emit('selVisible', true, visibleFiles)">
          Выбрать видимые</button
        ><button class="grow" @click="$emit('selVisible', false, visibleFiles)">
          Снять видимые</button
        ><button class="grow" @click="$emit('clearSel')">Снять все</button
        ><button
          class="ghost icon-btn"
          @click="$emit('refresh')"
          title="Обновить"
        >
          <svg class="icon" viewBox="0 0 24 24">
            <path d="M21 12a9 9 0 1 1-3-6.7L21 8" />
            <path d="M21 3v5h-5" />
          </svg>
        </button>
      </div>
    </div>
    <div class="results" style="flex: 1">
      <div v-if="loading" class="ft-empty">
        <span class="job-spin"></span><span>Загрузка файлов…</span>
      </div>
      <div v-else-if="error" class="ft-empty">
        <svg class="icon" viewBox="0 0 24 24">
          <path d="M12 2a10 10 0 1 0 10 10" />
          <path d="M12 8v5M12 16h.01" /></svg
        ><span>Ошибка загрузки файлов:<br />{{ error }}</span
        ><button
          class="ghost"
          @click="$emit('refresh')"
          style="margin-top: 8px"
        >
          Повторить
        </button>
      </div>
      <template v-else>
        <div class="muted" style="font-size: 11px; padding: 2px 4px 8px">
          Файлов: {{ rows.length }} из {{ files.length
          }}<span v-if="emptyHidden">
            (без строк к переводу: {{ emptyHidden }})</span
          >
        </div>
        <div v-for="r in rows" :key="r.key">
          <div
            v-if="r.t === 'd'"
            class="ft-item ft-dir"
            :style="'padding-left:' + (8 + r.depth * 16) + 'px'"
            @click="toggleDir(r.path)"
          >
            <span class="tree-chev" :title="r.open ? 'Свернуть' : 'Развернуть'"
              ><svg
                class="icon"
                viewBox="0 0 24 24"
                :style="r.open ? 'transform:rotate(90deg)' : ''"
              >
                <path d="M9 6l6 6-6 6" /></svg
            ></span>
            <input
              type="checkbox"
              :checked="dirChecked(r.files)"
              @click.stop
              @change="toggleDirSel(r.files)"
            />
            <svg class="icon ft-icon" viewBox="0 0 24 24">
              <path
                d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1 2 2H5a2 2 0 0 1-2-2V7z"
              />
            </svg>
            <span
              style="
                flex: 1;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
                font-size: 13px;
              "
              >{{ r.name }}</span
            >
            <span class="muted" style="font-size: 11px">{{
              fmtNum(r.sum)
            }}</span>
          </div>
          <div
            v-else
            class="ft-item"
            :class="{ proj: selected.has(r.path) }"
            :style="'padding-left:' + (8 + r.depth * 16) + 'px'"
            @click="$emit('toggle', r.path, !selected.has(r.path))"
            style="cursor: pointer"
          >
            <span style="width: 28px; height: 28px; flex-shrink: 0"></span>
            <input
              type="checkbox"
              :checked="selected.has(r.path)"
              @click.stop
              @change="
                $emit(
                  'toggle',
                  r.path,
                  ($event.target as HTMLInputElement).checked,
                )
              "
            />
            <svg class="icon ft-icon" viewBox="0 0 24 24">
              <path
                d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5z"
              />
              <path d="M14 3v5h5" />
            </svg>
            <span
              style="
                flex: 1;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
                font-size: 13px;
              "
              >{{ r.path
              }}<span v-if="mapReady" class="muted">
                · {{ fmtNum(pendOf(r.path)) }}</span
              ></span
            >
            <button
              class="ghost icon-btn sm pv-btn"
              @click.stop="$emit('preview', r.path)"
              :title="'Закрепить превью таблицы под переводом: ' + r.path"
            >
              <svg class="icon" viewBox="0 0 24 24">
                <path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z" />
                <circle cx="12" cy="12" r="3" />
              </svg>
            </button>
          </div>
        </div>
        <div v-if="!files.length" class="ft-empty">
          <svg class="icon" viewBox="0 0 24 24">
            <path
              d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 0 2 2V8l-5-5z"
            /></svg
          ><span>Нет файлов в {{ root || "активном корне" }}</span>
        </div>
      </template>
    </div>
  </div>
</template>
