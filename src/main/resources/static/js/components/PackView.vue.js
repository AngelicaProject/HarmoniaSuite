import {api} from '../api.js';
export default {
  props: ['projectId', 'pack', 'errors', 'manifest', 'msg', 'compat', 'langs'],
  emits: ['update:compat', 'update:langs', 'save', 'addAuthor', 'delAuthor', 'toast'],
  data() { return {touched: {}}; },
  computed: {
    manifestText() {
      return this.manifest ? JSON.stringify(this.manifest, null, 2) : '';
    },
    visibleErrors() {
      return (this.errors || []).filter(e => !this.isStaleRequired(e));
    },
    staleCount() {
      return (this.errors || []).length - this.visibleErrors.length;
    },
    invalidSet() {
      const s = new Set();
      for (const e of this.visibleErrors) {
        const k = this.errKey(e);
        if (k === 'vendor') {
          s.add('vendor_id');
          s.add('vendor_name');
        } else if (k) s.add(k);
      }
      for (const k of Object.keys(this.touched || {})) s.delete(k);
      return s;
    }
  },
  watch: {
    errors() { this.touched = {}; }
  },
  methods: {
    isStaleRequired(e) {
      if (!/обязател/.test(String(e || ''))) return false;
      const p = this.pack || {};
      const k = this.errKey(e);
      const filled = v => !!String(v || '').trim();
      if (k === 'pack_id') return filled(p.pack_id);
      if (k === 'translation_version') return filled(p.translation_version);
      if (k === 'game_version') return filled(p.game_version);
      if (k === 'vendor') return filled(p.vendor_id) && filled(p.vendor_name);
      if (k === 'authors') return (p.authors || []).some(a => a && filled(a.name));
      return false;
    },
    errKey(e) {
      const s = String(e || '');
      if (s.startsWith('packId')) return 'pack_id';
      if (s.startsWith('translationVersion')) return 'translation_version';
      if (s.startsWith('gameVersion')) return 'game_version';
      if (s.startsWith('vendor')) return 'vendor';
      if (s.startsWith('authors')) return 'authors';
      return '';
    },
    errFixed(e) {
      const k = this.errKey(e);
      if (!k) return false;
      const t = this.touched || {};
      if (k === 'vendor') return !!(t.vendor_id && t.vendor_name);
      return !!t[k];
    },
    goErr(e) {
      const k = this.errKey(e);
      if (!k || !this.$el || !this.$el.querySelector) return;
      const el = this.$el.querySelector('[data-err-field="' + k + '"]');
      if (!el) return;
      try {
        el.scrollIntoView({block: 'center'});
        const inps = el.querySelectorAll('input,textarea');
        const bad = [];
        inps.forEach(i => { if (i.classList.contains('invalid')) bad.push(i); });
        (bad.length ? bad : [inps[0]].filter(Boolean)).forEach(i => {
          i.classList.add('flash');
          setTimeout(() => { try { i.classList.remove('flash'); } catch (e2) {} }, 2400);
        });
        const inp = el.querySelector('input,textarea');
        if (inp) inp.focus({preventScroll: true});
      } catch (err) {}
    },
    async downloadManifest() {
      try {
        const r = await fetch(api.exportManifestUrl(this.projectId));
        if (!r.ok) {
          const j = await r.json().catch(() => ({}));
          throw Error(j.error || 'Манифест не готов — заполните вкладку «Пак»');
        }
        const b = await r.blob();
        const u = URL.createObjectURL(b);
        const a = document.createElement('a');
        a.href = u;
        a.download = 'manifest.json';
        a.click();
        URL.revokeObjectURL(u);
      } catch (e) {
        this.$emit('toast', e.message);
      }
    }
  },
  template: `
  <div class="pane-body summary-pad">
    <template v-if="!pack">
      <div class="ft-empty"><span class="job-spin"></span><span>Загрузка…</span></div>
    </template>
    <template v-else>
    <div class="sum-sec" style="margin-top:0">Пак</div>
    <div class="set-hint">Поля со * обязательны — без них ZIP не соберётся.</div>
    <div class="set-field" style="margin-top:10px" data-err-field="pack_id">
      <span class="set-label">ID пака *</span>
      <input :value="pack.pack_id" @input="pack.pack_id=$event.target.value;touched.pack_id=true" class="set-control" :class="{invalid:invalidSet.has('pack_id')}" placeholder="primer-ru-pack">
      <div class="set-hint">Латиница, цифры, дефис и _. Папка в ZIP называется так же.</div>
    </div>
    <div class="set-row" style="margin-top:14px;max-width:560px">
      <div style="flex:1;min-width:0" data-err-field="translation_version"><span class="set-label">Версия перевода *</span><input :value="pack.translation_version" @input="pack.translation_version=$event.target.value;touched.translation_version=true" class="set-control" :class="{invalid:invalidSet.has('translation_version')}" placeholder="1.0.0"></div>
      <div style="flex:1;min-width:0" data-err-field="game_version"><span class="set-label">Версия игры *</span><input :value="pack.game_version" @input="pack.game_version=$event.target.value;touched.game_version=true" class="set-control" :class="{invalid:invalidSet.has('game_version')}" placeholder="2026.08.11.0000.0000"></div>
    </div>
    <div class="set-hint">Версия игры — точная строка клиента (GameVersionString): Harmonia сравнивает её без нормализации.</div>
    <div class="set-field">
      <span class="set-label">Совместимые версии игры</span>
      <input :value="compat" @input="$emit('update:compat',$event.target.value)" class="set-control" placeholder="2026.08.11.0000.0000">
      <div class="set-hint">Через запятую. Пусто — только версия игры выше.</div>
    </div>
    <div class="sum-sec">Издатель</div>
    <div class="set-row" style="margin-top:10px;max-width:560px" data-err-field="vendor">
      <div style="flex:1;min-width:0"><span class="set-label">Vendor ID *</span><input :value="pack.vendor_id" @input="pack.vendor_id=$event.target.value;touched.vendor_id=true" class="set-control" :class="{invalid:invalidSet.has('vendor_id')}" placeholder="my-team"></div>
      <div style="flex:1;min-width:0"><span class="set-label">Vendor name *</span><input :value="pack.vendor_name" @input="pack.vendor_name=$event.target.value;touched.vendor_name=true" class="set-control" :class="{invalid:invalidSet.has('vendor_name')}" placeholder="My Team"></div>
    </div>
    <div class="set-field">
      <span class="set-label">Vendor URL</span>
      <input :value="pack.vendor_url" @input="pack.vendor_url=$event.target.value" class="set-control" placeholder="https://example.com">
    </div>
    <div class="set-field">
      <span class="set-label">Vendor contact</span>
      <input :value="pack.vendor_contact" @input="pack.vendor_contact=$event.target.value" class="set-control" placeholder="contact">
    </div>
    <div class="sum-sec">Описание</div>
    <div class="set-field" style="margin-top:10px">
      <span class="set-label">Название</span>
      <input :value="pack.title" @input="pack.title=$event.target.value" class="set-control" placeholder="Example Pack">
    </div>
    <div class="set-row" style="margin-top:14px;max-width:560px">
      <div style="flex:1;min-width:0"><span class="set-label">Языки</span><input :value="langs" @input="$emit('update:langs',$event.target.value)" class="set-control" placeholder="ru"></div>
      <div style="flex:1;min-width:0"><span class="set-label">Лицензия</span><input :value="pack.license" @input="pack.license=$event.target.value" class="set-control" placeholder="CC-BY-4.0"></div>
    </div>
    <div class="set-field">
      <span class="set-label">Homepage</span>
      <input :value="pack.homepage" @input="pack.homepage=$event.target.value" class="set-control" placeholder="https://example.com">
    </div>
    <div class="set-field">
      <span class="set-label">Описание</span>
      <textarea :value="pack.description" @input="pack.description=$event.target.value" rows="2" class="set-control" placeholder="Что переведено"></textarea>
    </div>
    <div class="set-field">
      <span class="set-label">Changelog</span>
      <textarea :value="pack.changelog" @input="pack.changelog=$event.target.value" rows="2" class="set-control" placeholder="1.0.0 — первый выпуск"></textarea>
    </div>
    <div class="set-field">
      <span class="set-label">Min plugin version</span>
      <input :value="pack.min_plugin_version" @input="pack.min_plugin_version=$event.target.value" class="set-control" placeholder="1.0.0">
    </div>
    <div class="sum-sec">Авторы *</div>
    <div data-err-field="authors">
    <div class="set-hint">Минимум один автор с именем.</div>
    <div v-for="(a,i) in pack.authors" :key="i" style="display:flex;gap:8px;margin-top:8px;max-width:560px">
      <input class="grow" v-model="a.name" placeholder="Имя" style="flex:2;min-width:0" @input="touched.authors=true" :class="{invalid:invalidSet.has('authors')}">
      <input class="grow" v-model="a.role" placeholder="Роль" style="flex:1;min-width:0">
      <button class="ghost icon-btn sm" @click="$emit('delAuthor',i)" title="Убрать" style="flex-shrink:0"><svg class="icon" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button>
    </div>
    <div class="set-actions"><button class="subtle" @click="$emit('addAuthor')">+ Автор</button></div>
    </div>
    <div class="sum-sec">Проверка</div>
    <div v-if="visibleErrors.length" style="margin-top:8px"><button v-for="e in visibleErrors" :key="e" class="form-error err-go" :class="{nolink:!errKey(e),fixed:errFixed(e)}" @click="goErr(e)" :title="errKey(e)?'Перейти к полю':'—'">{{e}}</button></div>
    <div v-else-if="manifest" class="set-ok" style="margin-top:8px">Манифест валиден — ZIP соберётся</div>
    <div v-if="staleCount&&!visibleErrors.length&&!manifest" class="set-hint" style="margin-top:8px">Ошибки исправлены — нажмите «Сохранить» для перепроверки.</div>
    <details v-if="manifest" class="pack-details"><summary>Показать manifest.json</summary><pre class="log" style="max-height:220px;margin-top:8px">{{manifestText}}</pre></details>
    <div class="set-foot" style="position:sticky;bottom:0;background:var(--surface);padding-bottom:12px"><button class="primary" @click="$emit('save')">Сохранить</button><button class="subtle" @click="downloadManifest" title="Скачать manifest.json"><svg class="icon" viewBox="0 0 24 24"><path d="M12 3v12M8 11l4 4 4-4"/><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/></svg>manifest.json</button></div>
    <div v-if="msg" class="set-ok">{{msg}}</div>
    <div class="set-hint">manifest.json записывается в вывод при сборке и включается в ZIP.</div>
    </template>
  </div>`
}
