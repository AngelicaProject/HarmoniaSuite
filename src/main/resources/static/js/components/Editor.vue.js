import {highlightTags, renderGamePreview, validateTags, warnTags, distinctTags, distinctAnon, findMissingTags, normalizedTag as normTag, tagKindOf as kindOfTag, tagKindLabel as kindLabel} from '../tags.js';
import {api} from '../api.js';
import CsvPreview from './CsvPreview.vue.js';
export default {
  components: {CsvPreview},
  props: ['entries', 'focusId', 'storeKey', 'csvOpen', 'csvRoot', 'pinRequest', 'rowContext', 'rowNext', 'loadRowPage'],
  emits: ['save', 'navigate', 'need-entry', 'resolve', 'reveal', 'file'],
  data() {
    let previewH = 240;
    try { previewH = Math.min(640, Math.max(80, Number(localStorage.getItem('hs-ed-preview-h')) || 240)); } catch (e) {}
    return {tabs: [], activeTab: null, drafts: {}, translation: '', status: 'untranslated', preview: false, jumpError: '', rev: 0, pendingOpen: null, pinned: null, pinData: null, pinLoading: false, pinError: '', previewH, tabMenu: null, statusOpen: false};
  },
  computed: {
    byId() { void this.rev; const m = {}; (this.entries || []).forEach(e => { m[e.id] = e; }); return m; },
    current() { return this.activeTab && this.activeTab.kind === 'file' && this.activeTab.phraseId ? (this.byId[this.activeTab.phraseId] || null) : null; },
    activeFile() { return (this.current && this.current.file) || ((this.activeTab && this.activeTab.kind === 'file' && this.activeTab.file) || ''); },
    fileEntries() {
      void this.rev;
      const f = this.activeFile;
      if (!f) return [];
      const byId = this.byId;
      const cells = [];
      for (const group of (this.rowContext && this.rowContext.groups || [])) {
        for (const cell of (group.cells || [])) cells.push(byId[cell.id] || cell);
      }
      return cells.filter(e => e.file === f)
        .sort((a, b) => (a.row_index - b.row_index) || (a.column_index - b.column_index));
    },
    filePos() {
      if (!this.current) return null;
      const group = (this.rowContext && this.rowContext.groups || [])
        .find(g => (g.cells || []).some(e => e.id === this.current.id));
      if (!group || typeof group.pos !== 'number') return null;
      return {i: group.pos + 1, n: this.rowContext.totalGroups || 0};
    },
    neighbors() {
      if (!this.current) return {prev: null, next: null};
      const i = this.fileEntries.findIndex(e => e.id === this.current.id);
      if (i < 0) return {prev: null, next: null};
      const txt = e => e ? {id: e.id, key: e.row_key || '', col: e.column_name || '', src: String(e.source || '').slice(0, 120)} : null;
      return {prev: txt(this.fileEntries[i - 1]), next: txt(this.fileEntries[i + 1])};
    },
    canNeighborPrev() {
      return !!(this.activeFile && this.rowContext && this.loadRowPage
        && Number(this.rowContext.page) > 0);
    },
    canNeighborNext() {
      const c = this.rowContext;
      const pageSize = Number(c && c.pageSize);
      if (!this.activeFile || !c || !this.loadRowPage || !Number.isFinite(pageSize) || pageSize <= 0) return false;
      return Number(c.page) + 1 < Math.ceil(Number(c.totalGroups || 0) / pageSize);
    },
    highlightedSource() { return highlightTags(this.current ? this.current.source : '', this.missingKeys); },
    highlightedTranslation() { return highlightTags(this.translation || ''); },
    gamePreview() {
      return renderGamePreview(this.translation || '', this.tagIssues)
        || '<span style="color:var(--faint);font-size:13px">Перевод пуст — введите текст, здесь появится игровой предпросмотр</span>';
    },
    missingKeys() { return this.current ? findMissingTags(this.current.source || '', this.translation || '') : []; },
    sourceTags() { const s = this.current ? this.current.source || '' : ''; return distinctAnon(s).concat(distinctTags(s)); },
    tagIssues() { return this.current ? validateTags(this.current.source || '', this.translation || '') : []; },
    tagWarnings() { return this.current ? warnTags(this.current.source || '', this.translation || '') : []; },
    conflictEntry() { return this.activeTab && this.activeTab.kind === 'conflict' ? (this.byId[this.activeTab.id] || null) : null; },
    highlightedConflictSource() { return highlightTags(this.conflictEntry ? this.conflictEntry.source || '' : '', []); },
    conflictOursPreview() {
      const src = this.conflictEntry ? this.conflictEntry.source || '' : '';
      const ours = this.conflictEntry ? this.conflictEntry.translation || '' : '';
      return renderGamePreview(ours, validateTags(src, ours))
        || '<span style="color:var(--faint);font-size:13px">Перевод пуст</span>';
    },
    conflictTheirsPreview() {
      const src = this.conflictEntry ? this.conflictEntry.source || '' : '';
      const th = this.activeTab && this.activeTab.theirs ? this.activeTab.theirs.translation || '' : '';
      return renderGamePreview(th, validateTags(src, th))
        || '<span style="color:var(--faint);font-size:13px">Перевод пуст</span>';
    },
    fieldDirty() {
      const e = this.current;
      if (!e) return false;
      return this.translation !== (e.translation || '') || this.status !== (e.status || 'untranslated');
    },
    tabKey() { return 'hs-tabs:' + (this.storeKey || 'default'); },
    statusOptions() {
      return [
        {value: 'untranslated', label: 'Не переведено'},
        {value: 'no_translation_required', label: 'Не требует перевода'},
        {value: 'machine_translated', label: 'Машинный перевод'},
        {value: 'stale', label: 'Устарело'},
        {value: 'human_reviewed', label: 'Проверено человеком'},
        {value: 'approved', label: 'Одобрено'}
      ];
    }
  },
  watch: {
    focusId(id) { if (id) this.openTab(id); },
    statusOpen(v) {
      if (v) document.addEventListener('click', this.closeStatusOutside, true);
      else document.removeEventListener('click', this.closeStatusOutside, true);
    },
    csvOpen(r) { if (r && r.file) this.openCsv(r.file); },
    pinRequest(r) { if (r && r.file) this.pinFile(r.file); },
    storeKey() { this.loadTabs(); },
    entries() {
      if (this.pendingOpen && this.byId[this.pendingOpen]) {
        const id = this.pendingOpen;
        this.pendingOpen = null;
        this.openTab(id);
      }
    }
  },
  mounted() { this.loadTabs(); if (this.focusId) this.openTab(this.focusId); document.addEventListener('click', this.closeTabMenuOutside, true); },
  beforeUnmount() { document.removeEventListener('click', this.closeTabMenuOutside, true); document.removeEventListener('click', this.closeStatusOutside, true); },
  methods: {
    esc(s) { return String(s).replace(/[&<>]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;'}[c])); },
    statusLabel(v) { const o = this.statusOptions.find(o => o.value === v); return o ? o.label : v; },
    statusDot(v) { return {approved: 'ok', human_reviewed: 'info', machine_translated: 'warn', stale: 'bad'}[v] || 'mut'; },
    closeStatusOutside(e) {
      if (this.statusOpen && e.target && e.target.closest && !e.target.closest('.st-wrap')) this.statusOpen = false;
    },
    tagKindOf(t) { return kindOfTag(t); },
    tagKindLabel(k) { return kindLabel(k); },
    normTag(t) { return normTag(t); },
    tabLabel(t) {
      if (t.kind === 'csv') return t.file;
      if (t.kind === 'file') return (t.file || '').split('/').pop() || t.file || 'файл';
      if (t.kind === 'conflict') return '≠ ' + ((t.file || '').split('/').pop() || t.id);
      const e = this.byId[t.id] || {};
      const s = String(e.source || '').slice(0, 26);
      const where = e.file ? (e.file.split('/').pop() + ':' + (e.row_key || '')) : '';
      return (where ? where + ' · ' : '') + (s || t.id);
    },
    tabTitle(t) {
      if (t.kind === 'csv') return 'Таблица: ' + t.file;
      if (t.kind === 'file') return 'Файл: ' + (t.file || '');
      if (t.kind === 'conflict') return 'Конфликт дельты: ' + (t.file || t.id);
      const e = this.byId[t.id] || {};
      return (e.file || '') + ' · ' + (e.row_key || '') + ' · ' + (e.column_name || '') + '\n' + String(e.source || t.id);
    },
    dirty(t) {
      if (!t || t.kind !== 'file') return false;
      for (const id of Object.keys(this.drafts)) {
        const d = this.drafts[id], e = this.byId[id];
        if (!d || !e || (e.file || '') !== t.file) continue;
        if (d.translation !== (e.translation || '') || d.status !== (e.status || 'untranslated')) return true;
      }
      return false;
    },
    stashDraft() {
      const c = this.current;
      if (!c) return;
      this.drafts[c.id] = {translation: this.translation, status: this.status, baseUpdatedAt: c.updatedAt || ''};
    },
    trimTabs(keep) {
      while (this.tabs.length > 30) {
        const i = this.tabs.findIndex(t => t !== this.activeTab && t !== keep);
        this.tabs.splice(i < 0 ? 0 : i, 1);
      }
    },
    persistTabs() {
      try {
        localStorage.setItem(this.tabKey, JSON.stringify(this.tabs.filter(t => t.kind !== 'conflict').map(t => t.kind === 'csv' ? {kind: 'csv', file: t.file} : t.kind === 'file' ? {kind: 'file', file: t.file, phraseId: t.phraseId || null} : t.id)));
      } catch (e) {}
    },
    loadTabs() {
      this.tabs = [];
      this.activeTab = null;
      this.translation = '';
      this.jumpError = '';
      this.drafts = {};
      const seen = new Set();
      try {
        const raw = JSON.parse(localStorage.getItem(this.tabKey) || '[]');
        if (Array.isArray(raw)) {
          for (const t of raw) {
            if (t && typeof t === 'object' && t.kind === 'csv' && t.file) {
              if (!seen.has('csv:' + t.file)) {
                seen.add('csv:' + t.file);
                this.tabs.push({kind: 'csv', file: t.file});
              }
            } else if (t && typeof t === 'object' && t.kind === 'file' && t.file) {
              if (seen.has('file:' + t.file)) continue;
              seen.add('file:' + t.file);
              const known = (t.phraseId && this.byId[t.phraseId])
                || (this.entries || []).some(e => (e.file || '') === t.file);
              if (known) this.tabs.push({kind: 'file', file: t.file,
                phraseId: t.phraseId && this.byId[t.phraseId] ? t.phraseId : null});
            } else if (typeof t === 'string' && this.byId[t]) {
              const file = this.byId[t].file || '';
              if (!file || seen.has('file:' + file)) continue;
              seen.add('file:' + file);
              this.tabs.push({kind: 'file', file, phraseId: t});
            }
          }
        }
      } catch (e) {}
      if (this.tabs.length) this.activateTab(this.tabs[0]);
    },
    openTab(id) {
      if (!this.byId[id]) {
        this.pendingOpen = id;
        this.jumpError = 'Загрузка фразы…';
        this.$emit('need-entry', id);
        return;
      }
      this.pendingOpen = null;
      this.jumpError = '';
      const file = this.byId[id].file || '';
      let t = this.tabs.find(t => t.kind === 'file' && t.file === file);
      if (!t) {
        t = {kind: 'file', file, phraseId: id};
        this.tabs.push(t);
        this.trimTabs(t);
        this.persistTabs();
      } else if (t.phraseId !== id) {
        t.phraseId = id;
        this.persistTabs();
      }
      this.activateTab(t);
    },
    async pinFile(file) {
      if (!file) return;
      this.pinned = file;
      this.pinData = null;
      this.pinError = '';
      this.pinLoading = true;
      try { this.pinData = await api.previewFull(this.csvRoot || '', file); }
      catch (e) { this.pinError = e.message; }
      this.pinLoading = false;
    },
    startPinResize(e) {
      e.preventDefault();
      const y0 = e.clientY, h0 = this.previewH;
      const move = ev => {
        this.previewH = Math.min(640, Math.max(80, Math.round(h0 + (y0 - ev.clientY))));
      };
      const up = () => {
        window.removeEventListener('mousemove', move);
        window.removeEventListener('mouseup', up);
        try { localStorage.setItem('hs-ed-preview-h', String(this.previewH)); } catch (err) {}
      };
      window.addEventListener('mousemove', move);
      window.addEventListener('mouseup', up);
    },
    async openCsv(file) {
      let t = this.tabs.find(t => t.kind === 'csv' && t.file === file);
      if (!t) {
        t = {kind: 'csv', file, data: null, loading: true, error: ''};
        this.tabs.push(t);
        this.trimTabs(t);
        this.persistTabs();
        try { t.data = await api.previewFull(this.csvRoot || '', file); }
        catch (e) { t.error = e.message; }
        t.loading = false;
      }
      this.activateTab(t);
    },
    async activateTab(t) {
      if (this.activeTab !== t) this.stashDraft();
      this.activeTab = t;
      this.jumpError = '';
      this.preview = false;
      if (t && t.kind === 'file') {
        if (!t.phraseId || !this.byId[t.phraseId]) {
          t.phraseId = this.pickPhrase(t.file);
          if (!t.phraseId && this.rowNext) {
            try {
              const found = await this.rowNext(t.file, -1, -1, '');
              t.phraseId = found && found.id ? found.id : null;
              await this.$nextTick();
            } catch (e) {
              this.jumpError = e.message || 'Не удалось найти первую фразу';
            }
          }
        }
        this.loadPhrase(t.phraseId);
        this.persistTabs();
        this.$emit('file', t.file);
      }
    },
    pickPhrase(file) {
      const list = this.fileEntries.filter(e => (e.file || '') === (file || ''));
      const un = list.find(e => !(e.translation && e.translation.trim()) || e.status === 'stale');
      return ((un || list[0] || {}).id || null);
    },
    loadPhrase(id) {
      const e = id ? this.byId[id] : null;
      if (!e) {
        this.translation = '';
        this.status = 'untranslated';
        return;
      }
      let d = this.drafts[id];
      if (d && !('baseUpdatedAt' in d)) d.baseUpdatedAt = e.updatedAt || '';
      if (d && d.baseUpdatedAt !== (e.updatedAt || '')) {
        delete this.drafts[id];
        d = null;
      }
      this.translation = d ? d.translation : (e.translation || '');
      this.status = d ? d.status : (e.status || 'untranslated');
    },
    tabMouse(t, e) {
      if (e && e.button === 1) {
        e.preventDefault();
        this.closeTab(t);
      }
    },
    closeTab(t, ev) {
      if (ev) ev.stopPropagation();
      const i = this.tabs.indexOf(t);
      if (i < 0) return;
      if (t === this.activeTab) this.stashDraft();
      this.tabs.splice(i, 1);
      this.persistTabs();
      if (t === this.activeTab) {
        const next = this.tabs[Math.min(i, this.tabs.length - 1)] || null;
        if (next) this.activateTab(next);
        else { this.activeTab = null; this.translation = ''; }
      }
    },
    noteChanged() { this.rev++; },
    jumpToPos(pos) { this.preview = false; this.$nextTick(() => { const ta = this.$refs.ta; if (!ta) return; ta.focus(); try { ta.setSelectionRange(pos, pos); } catch (e) {} }); },
    onPreviewClick(e) { const el = e.target.closest ? e.target.closest('[data-err]') : null; if (el && el.dataset && el.dataset.err !== undefined) this.jumpToPos(Number(el.dataset.err)); },
    needsWork(e) { return e.status !== 'no_translation_required' && (!(e.translation && e.translation.trim()) || e.status === 'stale'); },
    select(e) { this.openTab(e.id); },
    focusEntry(id) { this.openTab(id); },
    openConflictTab(c) {
      if (!c || !c.cell_id) return;
      let t = this.tabs.find(t => t.kind === 'conflict' && t.id === c.cell_id);
      if (!t) {
        t = {kind: 'conflict', id: c.cell_id, file: c.file_path || '',
          theirs: {translation: (c.theirs || {}).translation || '', status: (c.theirs || {}).status || ''}};
        this.tabs.push(t);
        this.trimTabs(t);
      }
      this.activateTab(t);
    },
    closeConflictTab(id) {
      const t = this.tabs.find(t => t.kind === 'conflict' && t.id === id);
      if (t) this.closeTab(t);
    },
    openTabMenu(t, e) {
      this.tabMenu = {t, x: Math.min(e.clientX, window.innerWidth - 200), y: Math.min(e.clientY, window.innerHeight - 150)};
    },
    closeTabMenuOutside(e) {
      if (this.tabMenu && e.target && !e.target.closest('.dz-menu')) this.tabMenu = null;
    },
    runTabMenu(act) {
      const t = this.tabMenu && this.tabMenu.t;
      this.tabMenu = null;
      if (!t) return;
      if (act === 'one') this.closeTab(t);
      else if (act === 'others') [...this.tabs].forEach(x => { if (x !== t) this.closeTab(x); });
      else if (act === 'all') [...this.tabs].forEach(x => this.closeTab(x));
    },
    navList() { return this.activeFile ? this.fileEntries : (this.entries || []); },
    async nextUntranslated() {
      if (!this.current) return;
      if (this.activeFile && this.rowNext) {
        try {
          const found = await this.rowNext(this.activeFile, this.current.row_index,
            this.current.column_index, (this.rowContext && this.rowContext.q) || '');
          if (found) {
            await this.$nextTick();
            this.select(found);
          } else {
            this.jumpError = 'Непереведённых фраз больше нет';
          }
        } catch (e) {
          this.jumpError = e.message || 'Не удалось найти следующую фразу';
        }
        return;
      }
      const list = this.navList();
      const i = list.findIndex(x => x.id === this.current.id);
      const found = list.slice(i + 1).find(x => this.needsWork(x)) || list.slice(0, Math.max(i, 0)).find(x => this.needsWork(x));
      if (found) this.select(found);
      else this.jumpError = 'Непереведённых фраз больше нет';
    },
    save(next = false) {
      if (!this.current) return;
      if (this.tagIssues.length) return;
      if (this.translation !== (this.current.translation || '')
        || this.status !== (this.current.status || 'untranslated')) {
        if ((this.translation || '').trim() && (this.status === 'untranslated' || this.status === 'no_translation_required')) this.status = 'human_reviewed';
      } else if (next) {
        this.nextUntranslated();
        return;
      } else {
        return;
      }
      this.$emit('save', {id: this.current.id, uuid: this.current.uuid, translation: this.translation, status: this.status});
      delete this.drafts[this.current.id];
      if (next) this.$nextTick(() => this.nextUntranslated());
    },
    insertTag(t) {
      if (!this.current) return;
      const open = typeof t === 'string' ? t : t.open;
      const close = typeof t === 'string' ? '' : (t.close || '');
      const ta = this.$refs.ta;
      if (!ta) { this.translation += open + close; return; }
      const s = ta.selectionStart ?? this.translation.length, e = ta.selectionEnd ?? this.translation.length;
      this.translation = this.translation.slice(0, s) + open + this.translation.slice(s, e) + close
        + this.translation.slice(e);
      this.$nextTick(() => { ta.focus(); const p = s + open.length + (e - s) + (s === e ? 0 : close.length); ta.setSelectionRange(p, p); });
    },
    async openNeighbor(delta) {
      if (!this.current) return;
      const list = this.navList();
      const i = list.findIndex(x => x.id === this.current.id);
      if (i >= 0 && i + delta >= 0 && i + delta < list.length) {
        this.select(list[i + delta]);
        return;
      }
      const context = this.rowContext;
      const pageSize = Number(context && context.pageSize);
      if (!this.activeFile || !this.loadRowPage || !context
          || !Number.isFinite(pageSize) || pageSize <= 0) return;
      const page = Number(context.page) + delta;
      const pages = Math.ceil(Number(context.totalGroups || 0) / pageSize);
      if (page < 0 || page >= pages) return;
      try {
        const loaded = await this.loadRowPage(this.activeFile, page, context.q || '');
        const cells = (loaded && loaded.groups || []).flatMap(g => g.cells || []);
        const candidate = delta > 0 ? cells[0] : cells[cells.length - 1];
        if (candidate) {
          await this.$nextTick();
          this.select(candidate);
        }
      } catch (e) {
        this.jumpError = e.message || 'Не удалось загрузить соседнюю страницу';
      }
    },
    moveByCell(delta) {
      return this.openNeighbor(delta);
    },
    next() {
      return this.moveByCell(1);
    },
    prev() {
      return this.moveByCell(-1);
    }
  },
  template: `
  <div style="display:flex;flex-direction:column;min-height:0;flex:1">
    <div class="ed-tabs" v-if="tabs.length">
      <div v-for="t in tabs" :key="t.kind+':'+(t.id||t.file)" class="ed-tab" :class="{active:t===activeTab}" @click="activateTab(t)" @mousedown="tabMouse(t,$event)" @contextmenu.prevent="openTabMenu(t,$event)" :title="tabTitle(t)">
        <span class="ed-tab-dot" v-if="dirty(t)" title="Есть несохранённые правки"></span><span class="ed-tab-label">{{tabLabel(t)}}</span><button class="ed-tab-x" @click="closeTab(t,$event)" title="Закрыть вкладку">×</button>
      </div>
    </div>
    <CsvPreview v-if="activeTab&&activeTab.kind==='csv'" embedded :file="activeTab.file" :data="activeTab.data" :loading="activeTab.loading" :error="activeTab.error" @close="closeTab(activeTab,$event)"/>
    <div v-if="tabMenu" class="dz-menu" style="position:fixed;z-index:302;bottom:auto" :style="{left:tabMenu.x+'px',top:tabMenu.y+'px'}"><div class="dz-menu-i" @click="runTabMenu('one')">Закрыть</div><div class="dz-menu-i" @click="runTabMenu('others')">Закрыть остальные</div><div class="dz-menu-i" @click="runTabMenu('all')">Закрыть все</div></div>
    <div v-else-if="activeTab&&activeTab.kind==='conflict'" style="display:flex;flex-direction:column;gap:8px;min-height:0;flex:1;overflow-y:auto;padding:2px">
      <div class="muted" style="font-size:11px;text-transform:uppercase;letter-spacing:.08em;font-weight:700">Конфликт дельты · {{(activeTab.file||'').split('/').pop()||activeTab.id}}</div>
      <div class="ed-grid">
        <div class="ed-col src">
          <div class="ed-col-label"><span>Оригинал</span><span class="muted">{{activeTab.id}}</span></div>
          <div class="ed-text" v-html="highlightedConflictSource"></div>
        </div>
        <div class="ed-col" style="display:flex;flex-direction:column;gap:8px">
          <div><div class="ed-col-label"><span>Наше ({{conflictEntry?conflictEntry.status:'—'}})</span></div><div class="ed-text" v-html="conflictOursPreview"></div></div>
          <div><div class="ed-col-label"><span>Их ({{activeTab.theirs.status||'—'}})</span></div><div class="ed-text" v-html="conflictTheirsPreview"></div></div>
        </div>
      </div>
      <div style="display:flex;gap:8px;flex-wrap:wrap"><button class="sm" @click="$emit('resolve',{id:activeTab.id,mode:'theirs',translation:activeTab.theirs.translation,status:activeTab.theirs.status})">Взять их</button><button class="ghost sm" @click="$emit('resolve',{id:activeTab.id,mode:'ours'})">Оставить наше</button></div>
    </div>
    <div v-else-if="!current" class="ft-empty" style="flex:1"><svg class="icon" viewBox="0 0 24 24"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z"/></svg><span>Выберите файл — он откроется новой вкладкой</span></div>
    <template v-else>
    <div class="editor-head">
      <h2><svg class="icon" viewBox="0 0 24 24"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z"/></svg>Редактор</h2>
      <span v-if="jumpError" style="color:var(--danger);font-size:12px">{{jumpError}}</span>
      <span class="grow"></span>
      <button class="ghost" @click="nextUntranslated" title="Следующая непереведённая"><svg class="icon" viewBox="0 0 24 24"><path d="M12 5v14M6 13l6 6 6-6"/></svg>Следующая</button>
    </div>
    <div class="ed-where"><span class="ed-where-file" @click="$emit('reveal', activeFile)" title="Показать в файлах проекта" style="cursor:pointer">{{activeFile}}</span><button class="ghost icon-btn sm" @click="pinFile(activeFile)" title="Закрепить превью таблицы под переводом"><svg class="icon" viewBox="0 0 24 24"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z"/><circle cx="12" cy="12" r="3"/></svg></button><span class="muted"> · строка {{current.row_key||((current.row_index||0)+1)}} · {{current.column_name||('кол.'+current.column_index)}}</span><span class="grow"></span><span v-if="filePos" class="muted">{{filePos.i}} из {{filePos.n}} в файле</span></div>
    <div v-if="neighbors.prev||neighbors.next||canNeighborPrev||canNeighborNext" class="ed-nbrs"><div v-if="neighbors.prev" class="ed-nbr" @click="openNeighbor(-1)" :title="neighbors.prev.src">↑ {{neighbors.prev.key}} · {{neighbors.prev.src}}</div><div v-else-if="canNeighborPrev" class="ed-nbr muted" @click="openNeighbor(-1)">↑ Предыдущая страница</div><div v-if="neighbors.next" class="ed-nbr" @click="openNeighbor(1)" :title="neighbors.next.src">↓ {{neighbors.next.key}} · {{neighbors.next.src}}</div><div v-else-if="canNeighborNext" class="ed-nbr muted" @click="openNeighbor(1)">↓ Следующая страница</div></div>
    <div class="ed-grid">
      <div class="ed-col src">
        <div class="ed-col-label"><span>Оригинал</span><span class="muted">{{current?current.id:''}}</span></div>
        <div class="ed-text" v-html="highlightedSource"></div>
        <div class="tag-chips" v-if="sourceTags.length">
          <button v-for="t in sourceTags" :key="t" :class="'tag-chip tk-'+tagKindOf(t)+(missingKeys.includes(normTag(t))?' tk-missing':'')" @click="insertTag(t)" :title="tagKindLabel(tagKindOf(t))+': '+t+' — вставить'">{{t}}</button>
        </div>
      </div>
      <div class="ed-col">
        <div class="ed-col-label"><span>Перевод · {{current?translation.length:0}} симв.<span v-if="fieldDirty" style="color:var(--accent)"> · изменено</span></span><span style="display:flex;gap:4px;align-items:center"><button class="mini-tab" :class="{active:!preview}" @click="preview=false">Текст</button><button class="mini-tab" :class="{active:preview}" @click="preview=true">Превью</button><span class="tag-state" :class="{bad:tagIssues.length,warn:!tagIssues.length&&tagWarnings.length}">теги: {{tagIssues.length?'✕ '+tagIssues.length:tagWarnings.length?'! '+tagWarnings.length:'✓'}}</span></span></div>
        <textarea v-show="!preview" ref="ta" class="ed-area" :style="fieldDirty?'border-color:var(--accent)':''" v-model="translation" @keydown.ctrl.enter.prevent="save(true)" @keydown.alt.arrowDown.prevent="next()" @keydown.alt.arrowUp.prevent="prev()" placeholder="Введите перевод…"></textarea>
        <div v-show="preview" class="ed-text" style="background:var(--input-bg)" v-html="gamePreview" @click="onPreviewClick"></div>
        <div class="tag-error" v-if="tagIssues.length"><button v-for="(it,i) in tagIssues" :key="i" class="tag-err-item" @click="jumpToPos(it.pos)" :title="'Перейти к позиции '+it.pos">{{it.message}}</button></div>
        <div class="tag-warn" v-else-if="tagWarnings.length">{{tagWarnings.join('; ')}}</div>
      </div>
      </div>
      <div class="ed-foot">
       <span :class="'ed-status-pill status-'+(current?current.status:'untranslated')">{{current?current.status:'—'}}</span>
        <div class="st-wrap">
         <button type="button" class="st-btn" @click="statusOpen=!statusOpen" @keydown.esc="statusOpen=false" :title="'Статус: '+statusLabel(status)"><i class="sum-dot" :class="statusDot(status)"></i><span>{{statusLabel(status)}}</span><svg class="icon" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg></button>
         <div v-if="statusOpen" class="dd-menu up st-menu">
           <div v-for="o in statusOptions" :key="o.value" class="dd-item" :class="{sel:o.value===status}" @click="status=o.value;statusOpen=false" :title="o.label"><i class="sum-dot" :class="statusDot(o.value)"></i><span class="dd-text">{{o.label}}</span></div>
         </div>
       </div>
       <button class="primary" @click="save(true)" :disabled="!fieldDirty||tagIssues.length>0" :title="tagIssues.length?'Исправьте теги перед сохранением':(fieldDirty?'Сохранить и перейти к следующей':'Нет изменений')"><svg class="icon" viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5"/></svg>Сохранить и далее</button>
       <button @click="save(false)" :disabled="!fieldDirty||tagIssues.length>0" :title="tagIssues.length?'Исправьте теги перед сохранением':(fieldDirty?'Сохранить':'Нет изменений')">Сохранить</button>
       <button @click="nextUntranslated" title="Следующая непереведённая"><svg class="icon" viewBox="0 0 24 24"><path d="M12 5v14M6 13l6 6 6-6"/></svg></button>
       <span class="grow"></span>
       <span class="muted" style="font-size:11px">Ctrl+Enter · Alt+↑/↓</span>
       <button class="ghost icon-btn sm" @click="prev()" title="Предыдущая"><svg class="icon" viewBox="0 0 24 24"><path d="M18 15l-6-6 6-6"/></svg></button>
       <button class="ghost icon-btn sm" @click="next()" title="Следующая"><svg class="icon" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg></button>
       <button class="ghost icon-btn sm" @click="translation=current?current.source:''" title="Скопировать оригинал"><svg class="icon" viewBox="0 0 24 24"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg></button>
     </div>
    </template>
    <div v-if="pinned" class="ed-pin">
     <div class="ed-pin-handle" @mousedown="startPinResize" title="Потяните, чтобы изменить высоту"></div>
     <div class="ed-pin-head"><span class="ed-where-file">{{pinned}}</span><span class="grow"></span><button class="ghost icon-btn sm" @click="pinFile(pinned)" title="Обновить"><svg class="icon" viewBox="0 0 24 24"><path d="M21 12a9 9 0 1 1-3-6.7L21 8"/><path d="M21 3v5h-5"/></svg></button><button class="ghost icon-btn sm" @click="pinned=null" title="Открепить"><svg class="icon" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>
     <div class="ed-pin-body" :style="'height:'+previewH+'px'"><CsvPreview embedded :file="pinned" :data="pinData" :loading="pinLoading" :error="pinError" @close="pinned=null"/></div>
    </div>
  </div>`
}
function esc(s) { return String(s).replace(/[&<>]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;'}[c])); }
