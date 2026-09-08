import {api} from '../api.js';
export default {
  props: ['initial', 'forced'],
  emits: ['close', 'changed'],
  data() {
    return {
      section: this.initial || 'sources',
      gamePath: '',
      srcSt: {}, srcError: '', srcSaved: '', srcSaving: false,
      syncJob: null, syncTimer: null,
      provider: 'gemini', geminiKey: '', openrouterKey: '', openrouterModel: '',
      openrouterReasoning: '', aiSt: {}, aiError: '', aiSaved: '', aiSaving: false,
      orModels: [], orComboOpen: false, orComboQ: '',
      checkBusy: '', checkMsg: {gemini: null, openrouter: null},
      bkItems: [], bkRetention: 10, bkInput: '10', bkUsed: 0, bkEstimate: 0,
      bkAuto: 0, bkAutoInput: '0',
      bkError: '', bkSaved: '', bkBusy: false,
    };
  },
  computed: {
    sections() {
      return [{id: 'sources', title: 'Источники данных'}, {id: 'ai', title: 'ИИ-перевод'}, {id: 'backup', title: 'База данных'}];
    },
    syncRunning() {
      return !!this.syncJob && (this.syncJob.status === 'running' || this.syncJob.status === 'queued');
    },
    syncTail() {
      const out = (this.syncJob && this.syncJob.output) || '';
      return out.split('\n').filter(l => l.trim() !== '').slice(-12).join('\n');
    },
    orModelName() {
      const cur = (this.openrouterModel || '').trim();
      if (!cur) return 'Выберите модель…';
      const found = (this.orModels || []).find(m => m.id === cur);
      return (found && found.name) || cur;
    },
    orComboList() {
      const q = (this.orComboQ || '').trim().toLowerCase();
      const all = this.orModels || [];
      const hit = q ? all.filter(m => (m.id + ' ' + (m.name || '')).toLowerCase().includes(q)) : all;
      return hit.slice(0, 80);
    },
    orComboMore() {
      const q = (this.orComboQ || '').trim().toLowerCase();
      const all = this.orModels || [];
      const n = q ? all.filter(m => (m.id + ' ' + (m.name || '')).toLowerCase().includes(q)).length : all.length;
      return n > 80 ? n - 80 : 0;
    },
    liveEstimate() {
      const n = parseInt(this.bkInput, 10) || 0;
      const per = this.bkItems.length ? (this.bkItems[0].size || 0)
        : (this.bkRetention ? Math.round((this.bkEstimate || 0) / this.bkRetention) : 0);
      return n * per;
    },
    fillPct() {
      const n = Math.min(Math.max(parseInt(this.bkInput, 10) || 1, 1), 100);
      return ((n - 1) / 99 * 100).toFixed(1);
    },
    bkSlide(e) {
      const el = e && e.target;
      if (!el || !el.style) return;
      const n = Math.min(Math.max(parseInt(el.value, 10) || 1, 1), 100);
      el.style.setProperty('--fill', ((n - 1) / 99 * 100).toFixed(1) + '%');
    },
    keyPlaceholder() {
      if (this.aiSt.geminiKeySet) return this.aiSt.geminiKeyHint || '••••';
      return this.aiSt.geminiKeySource === 'env' ? 'Ключ из окружения (GEMINI_API_KEY)' : 'Не задан';
    },
    orKeyPlaceholder() {
      if (this.aiSt.openrouterKeySet) return this.aiSt.openrouterKeyHint || '••••';
      return this.aiSt.openrouterKeySource === 'env' ? 'Ключ из окружения (OPENROUTER_API_KEY)' : 'Не задан';
    },
  },
  watch: {
    syncTail() {
      this.$nextTick(() => {
        const el = this.$refs.syncBox;
        if (el) el.scrollTop = el.scrollHeight;
      });
    }
  },
  async mounted() {
    await Promise.all([this.srcReload(), this.aiReload(), this.bkReload()]);
    try {
      this.orModels = await api.aiModels();
    } catch (e) {}
  },
  unmounted() {
    if (this.syncTimer) clearInterval(this.syncTimer);
    document.removeEventListener('click', this.closeOrCombo, true);
  },
  methods: {
    async srcReload() {
      try {
        const s = await api.settings();
        this.srcSt = s;
        if (s.gamePath) this.gamePath = s.gamePath;
      } catch (e) {
        this.srcError = e.message;
      }
    },
    async detect() {
      this.srcError = '';
      try {
        const d = await api.detectSettings();
        if (d.gamePath) this.gamePath = d.gamePath;
        else this.srcError = 'Игра не найдена — укажите путь вручную';
      } catch (e) {
        this.srcError = e.message;
      }
    },
    async srcSave() {
      this.srcError = '';
      this.srcSaved = '';
      this.srcSaving = true;
      try {
        this.srcSt = await api.saveSettings({gamePath: this.gamePath.trim()});
        this.srcSaved = 'Сохранено';
        this.$emit('changed');
      } catch (e) {
        this.srcError = e.message;
      }
      this.srcSaving = false;
    },
    async runSync() {
      await this.srcSave();
      if (this.srcError) return;
      try {
        const d = await api.startJob({action: 'sync-sources'});
        this.syncJob = {id: d.id, status: d.status || 'running', output: ''};
        this.pollSync();
      } catch (e) {
        this.srcError = e.message;
      }
    },
    pollSync() {
      if (this.syncTimer) clearInterval(this.syncTimer);
      this.syncTimer = setInterval(async () => {
        const j = this.syncJob;
        if (!j) {
          clearInterval(this.syncTimer);
          this.syncTimer = null;
          return;
        }
        try {
          const d = await api.jobGet(j.id);
          j.output = d.output || '';
          j.status = d.status;
          if (d.status && d.status !== 'running' && d.status !== 'queued') {
            clearInterval(this.syncTimer);
            this.syncTimer = null;
            await this.srcReload();
            this.$emit('changed');
          }
        } catch (e) {
          j.output += '\n' + e.message;
        }
      }, 700);
    },
    async cancelSync() {
      if (!this.syncJob) return;
      try {
        await api.jobCancel(this.syncJob.id);
      } catch (e) {
        this.srcError = e.message;
      }
    },
    async aiReload() {
      try {
        const s = await api.aiSettings();
        this.aiSt = s;
        this.provider = s.provider || 'gemini';
        if (s.openrouterModel) this.openrouterModel = s.openrouterModel;
        this.openrouterReasoning = s.openrouterReasoning || '';
      } catch (e) {
        this.aiError = e.message;
      }
    },
    async aiSave() {
      this.aiError = '';
      this.aiSaved = '';
      this.aiSaving = true;
      try {
        this.aiSt = await api.saveAiSettings({provider: this.provider,
          geminiKey: this.geminiKey || undefined,
          openrouterKey: this.openrouterKey || undefined,
          openrouterModel: this.openrouterModel.trim() || undefined,
          openrouterReasoning: this.openrouterReasoning || ''});
        this.geminiKey = '';
        this.openrouterKey = '';
        if (this.aiSt.openrouterModel) this.openrouterModel = this.aiSt.openrouterModel;
        this.aiSaved = 'Сохранено — перезапуск не нужен';
        this.$emit('changed');
      } catch (e) {
        this.aiError = e.message;
      }
      this.aiSaving = false;
    },
    toggleOrCombo() {
      if (this.orComboOpen) {
        this.closeOrCombo();
        return;
      }
      this.orComboOpen = true;
      this.orComboQ = '';
      setTimeout(() => {
        document.addEventListener('click', this.closeOrCombo, true);
        if (this.$refs.orComboQ) this.$refs.orComboQ.focus();
      }, 0);
    },
    closeOrCombo(e) {
      if (e && e.target && e.target.closest && e.target.closest('.or-combo,.or-combo-btn')) return;
      this.orComboOpen = false;
      document.removeEventListener('click', this.closeOrCombo, true);
    },
    pickOrModel(id) {
      this.openrouterModel = id;
      this.closeOrCombo();
    },
    async clearKey(which) {
      this.aiError = '';
      this.aiSaved = '';
      try {
        if (which === 'gemini') {
          this.aiSt = await api.saveAiSettings({provider: this.provider, geminiKey: ''});
          this.geminiKey = '';
        } else {
          this.aiSt = await api.saveAiSettings({provider: this.provider, openrouterKey: ''});
          this.openrouterKey = '';
        }
        this.aiSaved = 'Ключ стёрт';
        this.$emit('changed');
      } catch (e) {
        this.aiError = e.message;
      }
    },
    async checkKey(which) {
      const key = which === 'gemini' ? this.geminiKey.trim() : this.openrouterKey.trim();
      this.checkBusy = which;
      this.checkMsg[which] = null;
      try {
        const r = await api.checkAiKey({provider: which, key: key || undefined});
        this.checkMsg[which] = {ok: !!r.ok, text: r.message || (r.ok ? 'Доступ OK' : 'Нет доступа')};
      } catch (e) {
        this.checkMsg[which] = {ok: false, text: e.message};
      }
      this.checkBusy = '';
    },
    async applyAll() {
      await this.srcSave();
      await this.aiSave();
    },
    fmtBytes(n) {
      n = Number(n || 0);
      if (n < 1024) return n + ' Б';
      if (n < 1048576) return (n / 1024).toFixed(1).replace('.', ',') + ' КБ';
      if (n < 1073741824) return (n / 1048576).toFixed(1).replace('.', ',') + ' МБ';
      return (n / 1073741824).toFixed(1).replace('.', ',') + ' ГБ';
    },
    fmtInterval(min) {
      min = Number(min || 0);
      if (!min) return 'выключено';
      if (min % 1440 === 0) return 'каждые ' + (min / 1440) + ' сут';
      if (min % 60 === 0) return 'каждые ' + (min / 60) + ' ч';
      return 'каждые ' + min + ' мин';
    },
    dlUrl(name) {
      return api.backupDownloadUrl(name);
    },
    async bkReload() {
      try {
        const d = await api.backups();
        this.bkItems = d.backups || [];
        this.bkRetention = d.retention;
        this.bkInput = String(d.retention);
        this.bkAuto = d.autoIntervalMinutes ?? 0;
        this.bkAutoInput = String(this.bkAuto);
        this.bkUsed = d.usedBytes;
        this.bkEstimate = d.estimatedBytes;
      } catch (e) {
        this.bkError = e.message;
      }
    },
    async bkCreate() {
      this.bkError = '';
      this.bkSaved = '';
      this.bkBusy = true;
      try {
        await api.createBackup();
        await this.bkReload();
        this.bkSaved = 'Резервная копия создана';
      } catch (e) {
        this.bkError = e.message;
      }
      this.bkBusy = false;
    },
    async bkSaveRetention(quiet) {
      this.bkError = '';
      this.bkSaved = '';
      const sent = parseInt(this.bkInput, 10);
      try {
        const d = await api.saveBackupSettings(sent);
        if (parseInt(this.bkInput, 10) !== sent) return;
        this.bkItems = d.backups || [];
        this.bkRetention = d.retention;
        this.bkInput = String(d.retention);
        this.bkAuto = d.autoIntervalMinutes ?? this.bkAuto;
        this.bkAutoInput = String(this.bkAuto);
        this.bkUsed = d.usedBytes;
        this.bkEstimate = d.estimatedBytes;
        if (!quiet) this.bkSaved = 'Сохранено — перезапуск не нужен';
      } catch (e) {
        this.bkError = e.message;
      }
    },
    async bkSaveAuto() {
      this.bkError = '';
      this.bkSaved = '';
      const sent = parseInt(this.bkAutoInput, 10);
      if (isNaN(sent)) {
        this.bkAutoInput = String(this.bkAuto);
        return;
      }
      try {
        const d = await api.saveBackupSettings(undefined, sent);
        if (parseInt(this.bkAutoInput, 10) !== sent) return;
        this.bkItems = d.backups || [];
        this.bkAuto = d.autoIntervalMinutes ?? 0;
        this.bkAutoInput = String(this.bkAuto);
        this.bkUsed = d.usedBytes;
        this.bkEstimate = d.estimatedBytes;
        this.bkSaved = 'Сохранено — перезапуск не нужен';
      } catch (e) {
        this.bkError = e.message;
      }
    },
    async bkOpenFolder() {
      this.bkError = '';
      try {
        await api.openBackupFolder();
      } catch (e) {
        this.bkError = e.message;
      }
    },
    async bkDelete(name) {
      if (!confirm('Удалить резервную копию ' + name + '?')) return;
      this.bkError = '';
      this.bkSaved = '';
      try {
        await api.deleteBackup(name);
        await this.bkReload();
      } catch (e) {
        this.bkError = e.message;
      }
    },
    async ok() {
      await this.applyAll();
      if (this.srcError) {
        this.section = 'sources';
        return;
      }
      if (this.aiError) {
        this.section = 'ai';
        return;
      }
      this.$emit('close');
    },
  },
  template: `
  <div class="overlay" style="z-index:1500">
    <div class="modal set-modal">
      <div class="set-head"><h2 class="set-title">Настройки</h2><button v-if="!forced" class="ghost icon-btn sm" @click="$emit('close')" title="Закрыть"><svg class="icon" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>
      <div class="set-body">
        <div class="set-nav">
          <button v-for="s in sections" :key="s.id" class="set-nav-i" :class="{active:section===s.id}" @click="section=s.id"><svg v-if="s.id==='sources'" class="icon" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"/></svg><svg v-else-if="s.id==='ai'" class="icon" viewBox="0 0 24 24"><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"/><path d="M19 15l.9 2.1L22 18l-2.1.9L19 21l-.9-2.1L16 18l2.1-.9z"/></svg><svg v-else class="icon" viewBox="0 0 24 24"><path d="M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zm0 0v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/></svg>{{s.title}}</button>
        </div>
        <div class="set-content">
          <template v-if="section==='sources'">
            <div class="set-field">
              <span class="set-label">Путь к игре</span>
              <div class="set-row"><input class="grow" v-model="gamePath" placeholder="C:/Program Files (x86)/Steam/steamapps/common/FINAL FANTASY XIV Online"><button class="subtle" @click="detect">Найти</button></div>
              <div class="set-hint">Версия: {{srcSt.gameVersion||'—'}}</div>
            </div>
            <div class="set-hint">Источники: {{srcSt.ready?'готовы':'не синхронизированы'}}</div>
            <div v-if="srcError" class="form-error">{{srcError}}</div>
            <div v-else-if="srcSaved" class="set-ok">{{srcSaved}}</div>
            <div class="set-actions"><button class="primary" @click="srcSave" :disabled="srcSaving">Сохранить</button><button @click="runSync" :disabled="syncRunning">Синхронизировать</button></div>
            <div v-if="syncJob" class="set-field">
              <div class="set-hint">Синхронизация: {{syncRunning?'выполняется…':syncJob.status}}</div>
              <pre ref="syncBox" class="log" style="max-height:220px;white-space:pre-wrap;margin-top:6px">{{syncTail}}</pre>
              <div class="set-actions"><button v-if="syncRunning" class="ghost" @click="cancelSync">Отмена</button></div>
            </div>
          </template>
          <template v-else-if="section==='ai'">
            <p class="set-intro">Провайдер нейроперевода и ключи. Ключ из настроек важнее переменной окружения; пустое поле оставляет сохранённый ключ как есть, стереть — кнопкой «Стереть».</p>
            <div class="set-field">
              <span class="set-label">Провайдер</span>
              <div class="seg" style="max-width:340px">
                <button :class="{on:provider==='gemini'}" @click="provider='gemini'">Gemini</button>
                <button :class="{on:provider==='openrouter'}" @click="provider='openrouter'">OpenRouter</button>
              </div>
            </div>
            <template v-if="provider==='gemini'">
              <div class="set-field">
                <span class="set-label">API-ключ Gemini</span>
                <div class="set-row"><input v-model="geminiKey" type="password" class="grow" :placeholder="keyPlaceholder" autocomplete="off"><button class="subtle" @click="checkKey('gemini')" :disabled="checkBusy==='gemini'">Проверить</button><button v-if="aiSt.geminiKeySet" class="ghost" @click="clearKey('gemini')" title="Стереть сохранённый ключ">Стереть</button></div>
                <div class="set-keyline"><i class="status-dot" :class="aiSt.geminiKeySet||aiSt.geminiKeySource==='env'?'on':'off'"></i><span>{{aiSt.geminiKeySet?'Ключ настроен':aiSt.geminiKeySource==='env'?'Ключ из окружения (GEMINI_API_KEY)':'Ключ не задан'}}</span><span v-if="checkMsg.gemini" :style="{color: checkMsg.gemini.ok ? 'var(--success)' : 'var(--danger)'}">· {{checkMsg.gemini.text}}</span></div>
                <div class="set-hint">Модель выбирается при запуске перевода</div>
              </div>
            </template>
            <template v-else>
              <div class="set-field">
                <span class="set-label">API-ключ OpenRouter</span>
                <div class="set-row"><input v-model="openrouterKey" type="password" class="grow" :placeholder="orKeyPlaceholder" autocomplete="off"><button class="subtle" @click="checkKey('openrouter')" :disabled="checkBusy==='openrouter'">Проверить</button><button v-if="aiSt.openrouterKeySet" class="ghost" @click="clearKey('openrouter')" title="Стереть сохранённый ключ">Стереть</button></div>
                <div class="set-keyline"><i class="status-dot" :class="aiSt.openrouterKeySet||aiSt.openrouterKeySource==='env'?'on':'off'"></i><span>{{aiSt.openrouterKeySet?'Ключ настроен':aiSt.openrouterKeySource==='env'?'Ключ из окружения (OPENROUTER_API_KEY)':'Ключ не задан'}}</span><span v-if="checkMsg.openrouter" :style="{color: checkMsg.openrouter.ok ? 'var(--success)' : 'var(--danger)'}">· {{checkMsg.openrouter.text}}</span></div>
              </div>
              <div class="set-field">
                <span class="set-label">Модель по умолчанию</span>
                <div v-if="orModels.length" style="position:relative">
                  <button class="set-combo or-combo-btn" @click="toggleOrCombo"><span>{{orModelName}}</span><svg class="icon" viewBox="0 0 24 24" style="flex-shrink:0"><path d="M6 9l6 6 6-6"/></svg></button>
                  <div v-if="orComboOpen" class="dz-menu or-combo" style="top:calc(100% + 4px);left:0;right:0;bottom:auto;max-height:260px;display:flex;flex-direction:column">
                    <div style="padding:6px"><input ref="orComboQ" class="grow" style="width:100%" v-model="orComboQ" placeholder="Поиск модели…"></div>
                    <div style="overflow:auto">
                      <div v-for="m in orComboList" :key="m.id" class="dz-menu-i" @click="pickOrModel(m.id)" :title="m.id"><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{m.name || m.id}}</span></div>
                      <div v-if="orComboMore" class="muted" style="padding:4px 10px;font-size:12px">…и ещё {{orComboMore}}</div>
                      <div v-if="!orComboList.length" class="muted" style="padding:4px 10px;font-size:12px">Ничего не найдено</div>
                    </div>
                  </div>
                </div>
                <input v-else v-model="openrouterModel" class="set-control" placeholder="google/gemini-flash-1.5">
                <div class="set-hint">ID модели OpenRouter; при запуске можно указать другую. Фактическая модель при автовыборе пишется в журнал перевода</div>
              </div>
              <div class="set-field">
                <span class="set-label">Reasoning</span>
                <div class="seg" style="max-width:420px">
                  <button :class="{on:!openrouterReasoning}" @click="openrouterReasoning=''">Выкл</button>
                  <button :class="{on:openrouterReasoning==='low'}" @click="openrouterReasoning='low'">Low</button>
                  <button :class="{on:openrouterReasoning==='medium'}" @click="openrouterReasoning='medium'">Medium</button>
                  <button :class="{on:openrouterReasoning==='high'}" @click="openrouterReasoning='high'">High</button>
                </div>
                <div class="set-hint">Передаётся только моделям с поддержкой reasoning (видно из каталога); остальным параметр пропускается</div>
              </div>
            </template>
            <div v-if="aiError" class="form-error">{{aiError}}</div>
            <div v-else-if="aiSaved" class="set-ok">{{aiSaved}}</div>
          </template>
          <template v-else-if="section==='backup'">
            <div class="set-field">
              <span class="set-label">Резервные копии базы данных</span>
              <div class="set-row"><button class="subtle" @click="bkCreate" :disabled="bkBusy">Создать резервную копию</button><button class="subtle" @click="bkOpenFolder">Открыть папку</button></div>
              <div class="set-hint">Снимок базы целиком; хранится в каталоге backups рядом с базой</div>
            </div>
            <div class="set-field">
              <span class="set-label">Хранить копий</span>
              <div class="set-row" style="align-items:center"><input v-model="bkInput" type="range" min="1" max="100" step="1" class="grow bk-range" :style="{'--fill': fillPct + '%'}" @input="bkSlide" @change="bkSaveRetention(true)"><b style="min-width:36px;text-align:right;user-select:none;-webkit-user-select:none" @mousedown.prevent>{{bkInput}}</b></div>
              <div class="set-hint">≈ {{fmtBytes(liveEstimate)}} при {{bkInput}} копиях (сейчас занято {{fmtBytes(bkUsed)}})</div>
            </div>
            <div class="set-field">
              <span class="set-label">Автоматическое создание резервных копий</span>
              <div class="set-row" style="align-items:center"><input v-model="bkAutoInput" type="number" min="0" max="10080" step="1" class="grow bk-num" style="max-width:84px" @change="bkSaveAuto"><span class="set-hint" style="margin:0">мин, 0 — выкл</span></div>
              <div class="set-hint">Автоматическое создание {{fmtInterval(bkAuto)}} · создание копии вручную сбрасывает таймер</div>
            </div>
            <div v-if="bkError" class="form-error">{{bkError}}</div>
            <div v-else-if="bkSaved" class="set-ok">{{bkSaved}}</div>
            <div v-for="b in bkItems" :key="b.name" class="set-row" style="align-items:center;margin-top:4px"><span class="grow" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap" :title="b.name">{{b.name}} · {{fmtBytes(b.size)}}</span><a class="subtle" style="text-decoration:none;padding:4px 10px" :href="dlUrl(b.name)" download>Скачать</a><button class="ghost" @click="bkDelete(b.name)">Удалить</button></div>
            <div v-if="!bkItems.length && !bkError" class="set-hint">Резервных копий пока нет</div>
          </template>
        </div>
      </div>
      <div class="set-foot"><button class="primary" @click="ok">OK</button><button v-if="!forced" @click="$emit('close')">Отмена</button><button class="ghost" @click="applyAll">Применить</button></div>
    </div>
  </div>`
}
