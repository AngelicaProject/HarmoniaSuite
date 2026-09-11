<script lang="ts">
import { defineComponent } from "vue";
import { api } from "../api/client";
import { ENTRY_STATUS } from "../domain/translationStatus";
export default defineComponent({
  props: ["projectId"],
  emits: ["toast", "refresh", "open-conflict"],
  data() {
    return {
      author: "",
      authorBad: false,
      expTimer: null as ReturnType<typeof setTimeout> | null,
      fileQ: "",
      fileList: [],
      fileLoading: false,
      checked: {},
      expOpen: {},
      expBusy: false,
      expInfo: "",
      checkedImp: {},
      impOpen: {},
      impFileName: "",
      impParsed: null,
      impBusy: false,
      result: null,
      resultMode: "",
      error: "",
    };
  },
  computed: {
    untranslatedStatus() {
      return ENTRY_STATUS.UNTRANSLATED;
    },
    visibleChecked() {
      return this.fileList.filter((f) => this.checked[f.path]);
    },
    impFiles() {
      const m = new Map();
      for (const r of (this.impParsed || {}).rows || []) {
        const p = (r && r.filePath) || "";
        m.set(p, (m.get(p) || 0) + 1);
      }
      return [...m.entries()]
        .map(([path, n]) => ({ path, n }))
        .sort((a, b) => (a.path < b.path ? -1 : 1));
    },
    expRows() {
      return this.flatRows(
        this.treeRoot(this.fileList.map((f) => f.path)),
        this.expOpen,
        (this.fileQ || "").trim(),
      );
    },
    impRows() {
      return this.flatRows(
        this.treeRoot(this.impFiles.map((f) => f.path)),
        this.impOpen,
        "",
      );
    },
    expVisibleFiles() {
      const out = [];
      for (const r of this.expRows) {
        if (r.t === "d") out.push(...r.files);
        else out.push(r.path);
      }
      return out;
    },
    expStats() {
      const m = {};
      for (const f of this.fileList || []) m[f.path] = f;
      return m;
    },
    impCounts() {
      const m = {};
      for (const f of this.impFiles || []) m[f.path] = f.n;
      return m;
    },
    resultCounts() {
      const r = this.result;
      if (!r) return "";
      const applied = this.resultMode === "preview" ? "будет влито" : "влито";
      return (
        applied +
        ": " +
        (r.applied ?? 0) +
        " · уже совпадают: " +
        (r.noop ?? 0) +
        " · отклонено: " +
        (r.skipped || []).length +
        " · решить вручную: " +
        (r.conflicts || []).length
      );
    },
  },
  watch: {
    projectId() {
      this.result = null;
      this.impParsed = null;
      this.impFileName = "";
      this.checkedImp = {};
      this.expInfo = "";
      this.error = "";
      this.checked = {};
      this.loadExportFiles();
    },
  },
  mounted() {
    this.author = this.loadAuthor();
    this.loadExportFiles();
  },
  methods: {
    loadAuthor() {
      try {
        return localStorage.getItem("hs-delta-author") || "";
      } catch (e) {
        return "";
      }
    },
    saveAuthor() {
      try {
        localStorage.setItem("hs-delta-author", this.author.trim());
      } catch (e) {}
    },
    scheduleExpSearch() {
      clearTimeout(this.expTimer);
      this.expTimer = setTimeout(() => this.loadExportFiles(), 300);
    },
    async loadExportFiles() {
      if (!this.projectId) return;
      this.fileLoading = true;
      this.error = "";
      try {
        const all = [];
        let offset = 0;
        const limit = 500;
        for (;;) {
          const d = await api.files(
            this.projectId,
            { readyOnly: true, q: this.fileQ.trim() || undefined },
            { offset, limit },
          );
          for (const f of d.files || [])
            all.push({
              path: f.path,
              translated: f.translated || 0,
              total: f.total || 0,
            });
          offset += (d.files || []).length;
          if (offset >= (d.total ?? 0) || !(d.files || []).length) break;
        }
        this.fileList = all;
      } catch (e) {
        this.error = e.message;
      }
      this.fileLoading = false;
    },
    toggleFile(path) {
      const c = Object.assign({}, this.checked);
      if (c[path]) delete c[path];
      else c[path] = true;
      this.checked = c;
    },
    toggleAllVisible() {
      const vis = this.expVisibleFiles;
      const all = vis.length > 0 && vis.every((p) => this.checked[p]);
      const c = Object.assign({}, this.checked);
      for (const p of vis) {
        if (all) delete c[p];
        else c[p] = true;
      }
      this.checked = c;
    },
    treeRoot(paths) {
      const root = { dirs: new Map(), files: [] };
      for (const f of paths || []) {
        const parts = (f || "").split("/");
        let node = root;
        for (let i = 0; i < parts.length - 1; i++) {
          let d = node.dirs.get(parts[i]);
          if (!d) {
            d = {
              name: parts[i],
              path: parts.slice(0, i + 1).join("/"),
              dirs: new Map(),
              files: [],
            };
            node.dirs.set(parts[i], d);
          }
          node = d;
        }
        node.files.push(f);
      }
      return root;
    },
    flatRows(root, open, forceOpen) {
      const rows = [];
      const collect = (n) => {
        const all = [...n.files];
        for (const c of n.dirs.values()) all.push(...collect(c));
        return all;
      };
      const emit = (node, depth) => {
        const kids = [];
        for (const d of node.dirs.values()) {
          kids.push({
            t: "d",
            key: "d:" + d.path,
            path: d.path,
            name: d.name,
            depth,
            open: !!forceOpen || !!open[d.path],
            files: collect(d),
            dir: d,
          });
        }
        for (const f of node.files)
          kids.push({ t: "f", key: "f:" + f, path: f, depth });
        kids.sort((a, b) => {
          const ak = a.t === "d" ? a.name : a.path,
            bk = b.t === "d" ? b.name : b.path;
          return ak < bk ? -1 : 1;
        });
        for (const k of kids) {
          rows.push(k);
          if (k.t === "d" && k.open) emit(k.dir, depth + 1);
        }
      };
      emit(root, 0);
      return rows;
    },
    toggleDir(openKey, path) {
      this[openKey] = Object.assign({}, this[openKey], {
        [path]: !this[openKey][path],
      });
    },
    dirChecked(store, files) {
      const s = this[store] || {};
      return files.length > 0 && files.every((f) => s[f]);
    },
    toggleDirSel(store, files) {
      const s = this[store] || {};
      const all = files.length > 0 && files.every((f) => s[f]);
      const c = Object.assign({}, s);
      for (const f of files) {
        if (all) delete c[f];
        else c[f] = true;
      }
      this[store] = c;
    },
    download(name, obj) {
      const b = new Blob([JSON.stringify(obj)], { type: "application/json" });
      const u = URL.createObjectURL(b);
      const a = document.createElement("a");
      a.href = u;
      a.download = name;
      a.click();
      URL.revokeObjectURL(u);
    },
    async exportDelta() {
      if (!this.projectId || this.expBusy) return;
      const files = Object.keys(this.checked);
      if (!files.length) {
        this.$emit("toast", "Выберите файлы для выгрузки");
        return;
      }
      if (!this.author.trim()) {
        this.authorBad = true;
        this.$emit("toast", "Укажите автора дельты — поле «Автор»");
        this.$nextTick(() => {
          try {
            const el = this.$refs.authorInput as HTMLInputElement | undefined;
            if (el) {
              if (el.scrollIntoView) el.scrollIntoView({ block: "center" });
              el.classList.add("flash");
              setTimeout(() => {
                try {
                  el.classList.remove("flash");
                } catch (e2) {}
              }, 2400);
              el.focus({ preventScroll: true });
            }
          } catch (e) {}
        });
        return;
      }
      this.authorBad = false;
      this.error = "";
      this.expInfo = "";
      this.expBusy = true;
      try {
        const rows = [];
        let header = null;
        for (const file of files) {
          let su = "",
            sc = "",
            complete = false,
            guard = 0;
          while (!complete && guard++ < 50) {
            const d = await api.deltaExport(this.projectId, {
              sinceUpdatedAt: su || undefined,
              sinceCellId: sc || undefined,
              files: file,
              limit: 20000,
              author: this.author.trim() || undefined,
            });
            header = d.header || header;
            for (const r of d.rows || []) rows.push(r);
            su = d.nextSinceUpdatedAt || "";
            sc = d.nextSinceCellId || "";
            complete = !!d.complete;
            if (!(d.rows || []).length && !complete) break;
          }
        }
        const stamp = new Date()
          .toISOString()
          .slice(0, 16)
          .replace(/[-:T]/g, "");
        this.download(
          "delta-" + (this.author.trim() || "anon") + "-" + stamp + ".json",
          {
            header: Object.assign({}, header, {
              author: this.author.trim() || (header && header.author) || null,
            }),
            rows,
          },
        );
        this.expInfo =
          "Файлов: " +
          files.length +
          " · строк: " +
          rows.length +
          " · sources_fp: " +
          ((header && header.sourcesFp) || "—").slice(0, 12);
      } catch (e) {
        this.error = e.message;
      }
      this.expBusy = false;
    },
    onFile(e) {
      this.error = "";
      this.result = null;
      const f = e.target.files && e.target.files[0];
      this.impParsed = null;
      this.impFileName = "";
      this.checkedImp = {};
      if (!f) return;
      const rd = new FileReader();
      rd.onload = () => {
        try {
          if (typeof rd.result !== "string")
            throw Error("Не удалось прочитать файл");
          const d = JSON.parse(rd.result);
          if (!d || !Array.isArray(d.rows))
            throw Error("В файле нет массива rows");
          const rows = d.rows.map((r) => ({
            cellId: r.cellId ?? r.cellId,
            filePath: r.filePath ?? r.filePath,
            source: r.source,
            translation: r.translation,
            status: r.status,
          }));
          const checkedImp = {};
          for (const r of rows) checkedImp[r.filePath || ""] = true;
          this.checkedImp = checkedImp;
          this.impParsed = { header: d.header || {}, rows };
          this.impFileName = f.name;
        } catch (err) {
          this.error = "Не читается дельта: " + err.message;
        }
      };
      rd.onerror = () => {
        this.error = "Не читается файл";
      };
      rd.readAsText(f);
      e.target.value = "";
    },
    toggleImpFile(path) {
      const c = Object.assign({}, this.checkedImp);
      if (c[path]) delete c[path];
      else c[path] = true;
      this.checkedImp = c;
    },
    impSelected() {
      return Object.keys(this.checkedImp);
    },
    impAuthor() {
      const p = this.impParsed || { header: {} };
      return (
        this.author.trim() || String((p.header && p.header.author) || "").trim()
      );
    },
    buildBody() {
      const p = this.impParsed || { header: {}, rows: [] };
      const sel = new Set(this.impSelected());
      return {
        author: this.impAuthor(),
        filesAllowlist: this.impFiles
          .filter((f) => sel.has(f.path))
          .map((f) => f.path),
        maxStatus: undefined,
        sourcesFp: p.header.sourcesFp,
        gameVersion: p.header.gameVersion,
        rows: p.rows,
      };
    },
    async doPreview() {
      if (!this.projectId || !this.impParsed || this.impBusy) return;
      if (!this.impSelected().length) {
        this.$emit("toast", "Отметьте хотя бы один файл дельты");
        return;
      }
      if (!this.impAuthor()) {
        this.$emit(
          "toast",
          "Укажите автора дельты — поле «Автор» или автор в файле",
        );
        return;
      }
      this.error = "";
      this.impBusy = true;
      try {
        this.result = await api.deltaPreview(this.projectId, this.buildBody());
        this.resultMode = "preview";
      } catch (e) {
        this.error = e.message;
      }
      this.impBusy = false;
    },
    async doImport() {
      if (!this.projectId || !this.impParsed || this.impBusy) return;
      if (!this.impSelected().length) {
        this.$emit("toast", "Отметьте хотя бы один файл дельты");
        return;
      }
      if (!this.impAuthor()) {
        this.$emit(
          "toast",
          "Укажите автора дельты — поле «Автор» или автор в файле",
        );
        return;
      }
      this.error = "";
      this.impBusy = true;
      try {
        const prev = await api.deltaPreview(this.projectId, this.buildBody());
        this.result = prev;
        this.resultMode = "preview";
        if (!(prev.applied > 0)) {
          const nc = (prev.conflicts || []).length;
          this.$emit(
            "toast",
            nc
              ? "Изменений для влития нет — разберите конфликты: " + nc
              : "Вливать нечего — по предпросмотру изменений нет",
          );
          return;
        }
        const n = (prev.conflicts || []).length;
        if (
          !window.confirm(
            "Влить дельту (" +
              prev.applied +
              " изменений, конфликтов: " +
              n +
              ")? Конфликты перезаписаны не будут.",
          )
        )
          return;
        const res = await api.deltaImport(this.projectId, this.buildBody());
        this.result = res;
        this.resultMode = "import";
        this.$emit("refresh");
        this.$emit("toast", "Дельта влита: " + (res.applied ?? 0));
      } catch (e) {
        this.error = e.message;
      } finally {
        this.impBusy = false;
      }
    },
    cap(list) {
      return (list || []).slice(0, 50);
    },
    cappedNote(list) {
      return (list || []).length > 50
        ? "…и ещё " + ((list || []).length - 50)
        : "";
    },
    skipReason(s) {
      const map = {
        bad_cellId: "битая строка: нет cell id",
        not_assigned: "файл не назначен вам",
        unknown_cell: "такой ячейки нет в проекте",
        bad_status: "неизвестный статус",
        status_above_cap: "статус выше разрешённого потолка",
        source_mismatch: "исходник изменился",
        tag_errors: "ошибка тегов",
      };
      return map[s.reason] || s.reason;
    },
    openDeltaFile() {
      const input = this.$refs.deltaFile as HTMLInputElement | undefined;
      input?.click();
    },
  },
});
</script>

<template>
  <div class="pane-body summary-pad">
    <div
      class="muted"
      style="
        font-size: 11px;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        font-weight: 700;
      "
    >
      Дельты для командной работы
    </div>
    <div
      style="
        display: flex;
        gap: 8px;
        margin: 8px 0;
        align-items: center;
        flex-wrap: wrap;
      "
    >
      <span class="muted" style="font-size: 12px">Автор</span
      ><input
        ref="authorInput"
        class="grow"
        v-model="author"
        :class="{ invalid: authorBad }"
        @input="authorBad = false"
        @change="saveAuthor"
        placeholder="Имя переводчика"
        style="max-width: 220px"
      />
    </div>

    <div
      class="muted"
      style="
        font-size: 11px;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        font-weight: 700;
        margin-top: 12px;
      "
    >
      Выгрузка по файлам
    </div>
    <div class="muted" style="font-size: 12px; margin: 6px 0">
      Показаны только файлы с переводами. Нетронутые строки (пусто +
      {{ untranslatedStatus }}) не выгружаются. Выбрано:
      {{ visibleChecked.length }}
    </div>
    <div
      style="
        display: flex;
        gap: 8px;
        margin: 8px 0;
        align-items: center;
        flex-wrap: wrap;
      "
    >
      <input
        class="grow"
        v-model="fileQ"
        @input="scheduleExpSearch"
        placeholder="Поиск файлов…"
        style="max-width: 320px"
      /><button class="ghost sm" @click="toggleAllVisible">
        Выбрать все видимые</button
      ><button
        class="sm"
        @click="exportDelta"
        :disabled="expBusy || !projectId || !visibleChecked.length"
      >
        {{ expBusy ? "Выгрузка…" : "Выгрузить выбранные (.json)" }}
      </button>
    </div>
    <div v-if="fileLoading" class="ft-empty">
      <span class="job-spin"></span><span>Загрузка…</span>
    </div>
    <div
      v-else-if="expRows.length"
      style="
        display: flex;
        flex-direction: column;
        gap: 4px;
        max-height: 320px;
        overflow-y: auto;
      "
    >
      <div v-for="r in expRows" :key="r.key">
        <div
          v-if="r.t === 'd'"
          class="export-row"
          :style="'cursor:pointer;padding-left:' + (8 + r.depth * 16) + 'px'"
          @click="toggleDir('expOpen', r.path)"
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
            :checked="dirChecked('checked', r.files)"
            @click.stop
            @change="toggleDirSel('checked', r.files)"
          />
          <svg class="icon ft-icon" viewBox="0 0 24 24">
            <path
              d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1 2 2H5a2 2 0 0 1-2-2V7z"
            />
          </svg>
          <span
            style="
              overflow: hidden;
              text-overflow: ellipsis;
              flex: 1;
              min-width: 0;
              font-size: 12px;
            "
            >{{ r.name }}</span
          ><span class="muted" style="font-size: 11px; white-space: nowrap"
            >файлов: {{ r.files.length }}</span
          >
        </div>
        <div
          v-else
          class="export-row"
          @click="toggleFile(r.path)"
          :style="'cursor:pointer;padding-left:' + (8 + r.depth * 16) + 'px'"
        >
          <span style="width: 28px; height: 28px; flex-shrink: 0"></span
          ><input
            type="checkbox"
            :checked="!!checked[r.path]"
            @click.stop
            @change="toggleFile(r.path)"
          /><span
            style="
              overflow: hidden;
              text-overflow: ellipsis;
              flex: 1;
              min-width: 0;
              font-size: 12px;
            "
            >{{ r.path }}</span
          ><span class="muted" style="font-size: 11px; white-space: nowrap"
            >{{ (expStats[r.path] || {}).translated || 0 }}/{{
              (expStats[r.path] || {}).total || 0
            }}</span
          >
        </div>
      </div>
    </div>
    <div v-if="expInfo" class="muted" style="font-size: 12px; margin-top: 6px">
      {{ expInfo }}
    </div>

    <div
      class="muted"
      style="
        font-size: 11px;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        font-weight: 700;
        margin-top: 12px;
      "
    >
      Вливание
    </div>
    <div
      style="
        display: flex;
        gap: 8px;
        margin: 8px 0;
        align-items: center;
        flex-wrap: wrap;
      "
    >
      <button class="subtle" @click="openDeltaFile">Выбрать файл дельты</button
      ><input
        ref="deltaFile"
        type="file"
        accept=".json,application/json"
        @change="onFile"
        style="display: none"
      /><span class="muted" style="font-size: 12px">{{
        impFileName || "файл не выбран"
      }}</span>
    </div>
    <div
      v-if="impParsed"
      class="muted"
      style="font-size: 12px; margin-bottom: 8px"
    >
      Строк: {{ impParsed.rows.length }} · sources_fp:
      {{ ((impParsed.header || {}).sourcesFp || "—").slice(0, 12) }} · автор:
      {{ (impParsed.header || {}).author || "—" }}
    </div>
    <div v-if="impParsed" style="margin: 8px 0">
      <div
        class="muted"
        style="
          font-size: 11px;
          text-transform: uppercase;
          letter-spacing: 0.08em;
          font-weight: 700;
        "
      >
        Файлы в дельте — вливать отмеченные
      </div>
      <div
        style="
          display: flex;
          flex-direction: column;
          gap: 4px;
          max-height: 200px;
          overflow-y: auto;
          margin-top: 4px;
        "
      >
        <div v-for="r in impRows" :key="r.key">
          <div
            v-if="r.t === 'd'"
            class="export-row"
            :style="'cursor:pointer;padding-left:' + (8 + r.depth * 16) + 'px'"
            @click="toggleDir('impOpen', r.path)"
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
              :checked="dirChecked('checkedImp', r.files)"
              @click.stop
              @change="toggleDirSel('checkedImp', r.files)"
            />
            <svg class="icon ft-icon" viewBox="0 0 24 24">
              <path
                d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1 2 2H5a2 2 0 0 1-2-2V7z"
              />
            </svg>
            <span
              style="
                overflow: hidden;
                text-overflow: ellipsis;
                flex: 1;
                min-width: 0;
                font-size: 12px;
              "
              >{{ r.name }}</span
            ><span class="muted" style="font-size: 11px; white-space: nowrap"
              >файлов: {{ r.files.length }}</span
            >
          </div>
          <div
            v-else
            class="export-row"
            @click="toggleImpFile(r.path)"
            :style="'cursor:pointer;padding-left:' + (8 + r.depth * 16) + 'px'"
          >
            <span style="width: 28px; height: 28px; flex-shrink: 0"></span
            ><input
              type="checkbox"
              :checked="!!checkedImp[r.path]"
              @click.stop
              @change="toggleImpFile(r.path)"
            /><span
              style="
                overflow: hidden;
                text-overflow: ellipsis;
                flex: 1;
                min-width: 0;
                font-size: 12px;
              "
              >{{ r.path || "(без файла)" }}</span
            ><span class="muted" style="font-size: 11px; white-space: nowrap">{{
              impCounts[r.path] || 0
            }}</span>
          </div>
        </div>
      </div>
    </div>
    <div
      style="
        display: flex;
        gap: 8px;
        margin: 8px 0;
        align-items: center;
        flex-wrap: wrap;
      "
    >
      <button class="sm" @click="doPreview" :disabled="impBusy || !impParsed">
        Предпросмотр</button
      ><button
        class="primary"
        @click="doImport"
        :disabled="
          impBusy ||
          !impParsed ||
          (result &&
            resultMode === 'preview' &&
            !(result.applied > 0) &&
            !(result.conflicts || []).length)
        "
        :title="
          result &&
          resultMode === 'preview' &&
          !(result.applied > 0) &&
          !(result.conflicts || []).length
            ? 'По предпросмотру изменений нет — вливать нечего'
            : 'Влить дельту'
        "
      >
        {{ impBusy ? "Работаю…" : "Влить" }}
      </button>
    </div>

    <div v-if="error" class="ft-empty">
      <span>Ошибка: {{ error }}</span>
    </div>
    <div v-if="result" style="margin-top: 8px">
      <div class="muted" style="font-size: 12px">
        {{
          resultMode === "preview"
            ? "Предпросмотр — в базу ничего не записано"
            : "Влитие завершено"
        }}: {{ resultCounts
        }}<button
          v-if="resultMode === 'import'"
          class="ghost sm"
          style="margin-left: 8px"
          @click="doPreview"
          :disabled="impBusy || !impParsed"
          title="Пересчитать предпросмотр после правок в редакторе"
        >
          Обновить
        </button>
      </div>
      <div v-if="result.summary" class="muted" style="font-size: 12px">
        {{ resultMode === "preview" ? "Сейчас в проекте" : "В проекте" }} —
        переведено: {{ result.summary.translated }} /
        {{ result.summary.entries }}
      </div>
      <div v-if="(result.conflicts || []).length" style="margin-top: 8px">
        <div
          class="muted"
          style="
            font-size: 11px;
            text-transform: uppercase;
            letter-spacing: 0.08em;
            font-weight: 700;
          "
        >
          Конфликты — разобрать вручную в редакторе
        </div>
        <div
          v-for="c in cap(result.conflicts)"
          :key="c.cellId"
          class="export-row"
          style="cursor: pointer"
          @click="$emit('open-conflict', c)"
          :title="c.cellId"
        >
          <span
            style="
              overflow: hidden;
              text-overflow: ellipsis;
              flex: 1;
              min-width: 0;
              font-size: 12px;
            "
            >{{ c.filePath }}
            <span class="muted"
              >· наше ({{ c.ours.status }}) / их ({{ c.theirs.status }})</span
            ></span
          ><span class="muted" style="font-size: 11px">→</span>
        </div>
        <div
          v-if="cappedNote(result.conflicts)"
          class="muted"
          style="font-size: 12px"
        >
          {{ cappedNote(result.conflicts) }}
        </div>
      </div>
      <div v-if="(result.skipped || []).length" style="margin-top: 8px">
        <div
          class="muted"
          style="
            font-size: 11px;
            text-transform: uppercase;
            letter-spacing: 0.08em;
            font-weight: 700;
          "
        >
          Отклонено — не влито
        </div>
        <div
          v-for="(s, ix) in cap(result.skipped)"
          :key="s.cellId || ix"
          class="muted"
          style="font-size: 12px"
        >
          {{ s.filePath }} · {{ skipReason(s)
          }}{{ s.detail ? " · " + s.detail : "" }}
        </div>
        <div
          v-if="cappedNote(result.skipped)"
          class="muted"
          style="font-size: 12px"
        >
          {{ cappedNote(result.skipped) }}
        </div>
      </div>
      <div v-if="(result.warnings || []).length" style="margin-top: 8px">
        <div
          class="muted"
          style="
            font-size: 11px;
            text-transform: uppercase;
            letter-spacing: 0.08em;
            font-weight: 700;
          "
        >
          Теги не сошлись — влито, проверить вручную
        </div>
        <div
          v-for="w in cap(result.warnings)"
          :key="w.cellId"
          class="muted"
          style="font-size: 12px"
        >
          {{ w.filePath }} · {{ (w.warnings || []).join("; ") }}
        </div>
        <div
          v-if="cappedNote(result.warnings)"
          class="muted"
          style="font-size: 12px"
        >
          {{ cappedNote(result.warnings) }}
        </div>
      </div>
    </div>
  </div>
</template>
