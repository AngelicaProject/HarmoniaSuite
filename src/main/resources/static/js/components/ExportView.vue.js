import {api} from '../api.js';
export default {
  props: ['projectId', 'job', 'scope', 'dataRev', 'entryUpdate'],
  emits: ['build', 'build-download', 'toast', 'toggle-scope', 'clear-scope'],
  data() {
    return {candidates: [], built: {}, manifestBuilt: false, loading: false, error: '', page: 0, pageSize: 50};
  },
  computed: {
    building() {
      return !!this.job && this.job.status === 'running' && this.job.action === 'merge';
    },
    builtCount() {
      return Object.keys(this.built).length;
    },
    paged() {
      return this.candidates.slice(this.page * this.pageSize, (this.page + 1) * this.pageSize);
    },
    pages() {
      return Math.ceil(this.candidates.length / this.pageSize) || 1;
    }
  },
  watch: {
    projectId() {
      this.load();
    },
    dataRev() {
      this.load();
    },
    entryUpdate(update) {
      this.applyEntryUpdate(update);
    },
    job(nv) {
      if (nv && nv.status !== 'running' && (nv.action === 'merge' || nv.action === 'gemini')) this.load();
    }
  },
  mounted() {
    this.load();
  },
  methods: {
    isTranslated(entry) {
      return !!entry && ((String(entry.translation || '').trim() !== '' && entry.status !== 'stale')
        || entry.status === 'no_translation_required');
    },
    applyEntryUpdate(update) {
      const entry = update && update.entry;
      if (!entry || !entry.file) return;
      const stats = update.fileStats;
      const index = this.candidates.findIndex(file => file.path === entry.file);
      if (index < 0) {
        if (stats && stats.translated > 0) {
          this.candidates.push({path: entry.file, translated: stats.translated, total: stats.total});
          this.candidates.sort((a, b) => b.translated - a.translated);
        }
        return;
      }
      const current = this.candidates[index];
      const delta = Number(this.isTranslated(entry)) - Number(this.isTranslated(update.previous));
      const next = stats
        ? {...current, translated: stats.translated, total: stats.total}
        : {...current, translated: Math.max(0, current.translated + delta)};
      if (next.translated > 0) {
        this.candidates.splice(index, 1, next);
        this.candidates.sort((a, b) => b.translated - a.translated);
      } else {
        this.candidates.splice(index, 1);
      }
      delete this.built[entry.file];
    },
    async load() {
      this.page = 0;
      await Promise.all([this.loadCandidates(), this.loadBuilt()]);
    },
    async loadCandidates() {
      if (!this.projectId) return;
      this.loading = true;
      this.error = '';
      try {
        const all = [];
        let offset = 0;
        const limit = 500;
        for (;;) {
          const d = await api.files(this.projectId, {readyOnly: true}, {offset, limit});
          for (const f of (d.files || [])) {
            all.push({path: f.path, translated: f.translated || 0, total: f.total || 0});
          }
          offset += (d.files || []).length;
          if (offset >= (d.total ?? 0) || !(d.files || []).length) break;
        }
        all.sort((a, b) => b.translated - a.translated);
        this.candidates = all;
      } catch (e) {
        this.error = e.message;
      }
      this.loading = false;
    },
    async loadBuilt() {
      if (!this.projectId) return;
      try {
        const d = await api.exportList(this.projectId);
        const m = {};
        for (const f of (d.files || [])) m[f.name] = f.size;
        this.built = m;
        this.manifestBuilt = !!d.manifest;
      } catch (e) {
        this.$emit('toast', e.message);
      }
    },
    isBuilt(f) {
      return Object.prototype.hasOwnProperty.call(this.built, f);
    },
    inScope(f) {
      return (this.scope || new Set()).has(f);
    },
    async tryDownload(f) {
      if (!this.isBuilt(f)) {
        this.$emit('toast', 'Файл не собран — нажмите «Собрать пак и скачать»');
        return;
      }
      try {
        const r = await fetch(api.exportCsvUrl(this.projectId, f.split('/').pop()));
        if (!r.ok) throw Error('Файл не собран — нажмите «Собрать пак и скачать»');
        const b = await r.blob();
        const u = URL.createObjectURL(b);
        const a = document.createElement('a');
        a.href = u;
        a.download = f.split('/').pop();
        a.click();
        URL.revokeObjectURL(u);
      } catch (e) {
        this.$emit('toast', e.message);
      }
    }
  },
  template: `
  <div class="pane-body summary-pad">
    <div class="muted" style="font-size:11px;text-transform:uppercase;letter-spacing:.08em;font-weight:700">Пак перевода Harmonia</div>
    <div class="muted" style="font-size:12px;margin:6px 0">Файлов с переводами: {{candidates.length}} · собрано: {{builtCount}} · manifest.json: {{manifestBuilt?'да':'нет'}}</div>
    <div class="muted" style="font-size:12px;margin-bottom:8px">Выбор для сборки: {{scope&&scope.size?scope.size+' (собираются только отмеченные)':'все таблицы'}}</div>
    <div style="margin:8px 0"><button class="primary grow" style="width:100%" @click="$emit('build-download')" :disabled="building||!candidates.length"><svg class="icon" viewBox="0 0 24 24"><path d="M21 8l-9-5-9 5 9 5 9-5z"/><path d="M3 13l9 5 9-5"/></svg>{{building?'Сборка…':'Собрать пак и скачать'}}</button></div>
    <div style="display:flex;gap:8px;margin:8px 0;align-items:center"><button :style="{visibility: scope&&scope.size ? 'visible' : 'hidden'}" class="sm" @click="$emit('clear-scope')" title="Сбросить выбор — собирать все таблицы">Сбросить выбор</button><span style="flex:1"></span><button class="sm" @click="$emit('build')" :disabled="building||!candidates.length" title="Собрать CSV в exported_csv без скачивания">Только собрать</button></div>
    <div v-if="!manifestBuilt" class="muted" style="font-size:12px;margin-bottom:8px">ZIP без манифеста Harmonia отклонит — заполните вкладку «Пак».</div>
    <div v-if="loading" class="ft-empty"><span class="job-spin"></span><span>Загрузка…</span></div>
    <div v-else-if="error" class="ft-empty"><span>Ошибка: {{error}}</span></div>
    <div v-else-if="candidates.length" style="display:flex;flex-direction:column;gap:8px">
      <div v-for="f in paged" :key="f.path" class="export-row" @click="$emit('toggle-scope',f.path,!inScope(f.path))" style="cursor:pointer"><input type="checkbox" :checked="inScope(f.path)" @click.stop @change="$emit('toggle-scope',f.path,$event.target.checked)" title="Включить в сборку"><span style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis;display:flex;align-items:center;gap:8px;flex:1;min-width:0"><svg class="icon ft-icon" viewBox="0 0 24 24"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5z"/><path d="M14 3v5h5"/></svg><span style="overflow:hidden;text-overflow:ellipsis">{{f.path}}</span></span><span class="muted" style="font-size:11px;white-space:nowrap">{{f.translated}}/{{f.total}}</span><span v-if="isBuilt(f.path)" class="ft-ok export-badge">собран</span><span v-else class="muted export-badge">не собран</span><button v-if="isBuilt(f.path)" class="ghost icon-btn sm" @click.stop="tryDownload(f.path)" title="Скачать CSV"><svg class="icon" viewBox="0 0 24 24"><path d="M12 3v12M8 11l4 4 4-4"/><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/></svg></button></div>
      <div v-if="pages>1" style="display:flex;gap:8px;align-items:center;justify-content:center"><button class="ghost icon-btn" @click="page=Math.max(0,page-1)" :disabled="page===0"><svg class="icon" viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg></button><span class="muted">{{page+1}}/{{pages}}</span><button class="ghost icon-btn" @click="page=Math.min(pages-1,page+1)" :disabled="page>=pages-1"><svg class="icon" viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg></button></div>
    </div>
    <div v-else class="ft-empty"><svg class="icon" viewBox="0 0 24 24"><path d="M21 8l-9-5-9 5 9 5 9-5z"/><path d="M3 13l9 5 9-5"/></svg><span>Нет файлов с переводами.<br>Переведите строки в редакторе или через Gemini.</span></div>
  </div>`
}
