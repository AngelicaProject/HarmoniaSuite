export default {
  props: ['q', 'matches', 'loading'],
  emits: ['update:q', 'search', 'open'],
  methods: {
    hl(text) {
      const q = (this.q || '').trim();
      if (!q) return this.esc(text);
      const re = new RegExp('(' + q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'ig');
      return this.esc(text).replace(re, '<mark>$1</mark>');
    },
    esc(s) {
      return String(s).replace(/[&<>]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;'}[c]));
    }
  },
  template: `
  <div class="pane-body" style="display:flex;flex-direction:column">
    <div class="search-box"><svg class="icon ic-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg><input class="grow" :value="q" @input="$emit('update:q',$event.target.value)" placeholder="Фраза для поиска…" @keydown.enter="$emit('search')"><button class="primary" @click="$emit('search')" :disabled="loading">{{loading?'…':'Найти'}}</button></div>
    <div v-if="loading" class="muted" style="padding:14px;display:flex;align-items:center;gap:8px"><span class="job-spin"></span>Поиск…</div>
    <div v-else class="results"><div v-for="m in matches" :key="m.id" class="result" @click="$emit('open',m)"><b>{{m.file}}<span v-if="m.row_key">:{{m.row_key}}</span></b><div class="rtext" v-html="hl(m.source)"></div><div v-if="m.translation&&m.translation.trim()" class="rtext tr" v-html="hl(m.translation)"></div><div v-else class="rtext tr muted">— перевод отсутствует —</div></div><div v-if="!matches.length" class="ft-empty"><svg class="icon" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg><span>Введите запрос и нажмите Найти.<br>Клик по результату откроет фразу в редакторе.</span></div></div>
  </div>`
}
