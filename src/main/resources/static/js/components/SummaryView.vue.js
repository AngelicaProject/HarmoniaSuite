import {ENTRY_STATUS_SUMMARY} from '../translation-statuses.js';

export default {
  props: ['summary'],
  computed: {
    entries() {
      return this.summary?.entries || 0;
    },
    translated() {
      return this.summary?.translated || 0;
    },
    left() {
      return Math.max(0, this.entries - this.translated);
    },
    pct() {
      return this.entries ? this.translated / this.entries * 100 : 0;
    },
    pctText() {
      return this.share(this.translated);
    },
    barW() {
      if (!this.entries) return 0;
      return Math.max(this.pct, this.translated ? 1.5 : 0);
    },
    rows() {
      const by = this.summary?.by_status || {};
      const order = ENTRY_STATUS_SUMMARY.map(s => [s.value, s.label, s.dot]);
      const seen = new Set();
      const out = [];
      for (const [k, label, dot] of order) {
        if (by[k] != null && by[k] > 0) {
          seen.add(k);
          out.push({k, label, dot, v: by[k], share: this.share(by[k])});
        }
      }
      for (const k of Object.keys(by)) {
        if (!seen.has(k) && by[k] != null && by[k] > 0) out.push({k, label: k, dot: 'mut', v: by[k], share: this.share(by[k])});
      }
      return out;
    }
  },
  methods: {
    fmt(n) {
      return Number(n || 0).toLocaleString('ru-RU');
    },
    share(v) {
      if (!this.entries) return '0%';
      const p = v / this.entries * 100;
      return (p >= 9.95 ? String(Math.round(p)) : p.toFixed(1).replace('.', ',')) + '%';
    }
  },
  template: `
  <div class="pane-body summary-pad">
    <div v-if="summary" class="sum-wrap">
      <div class="sum-top">
        <div class="sum-head"><span>Готово</span><b>{{pctText}}</b></div>
        <div class="sum-big">{{fmt(translated)}} <span>/ {{fmt(entries)}}</span></div>
        <div class="sum-bar"><i :style="'width:'+barW+'%'"></i></div>
        <div class="sum-sub">Осталось {{fmt(left)}}</div>
      </div>
      <div class="sum-sec">По статусам</div>
      <div v-for="r in rows" :key="r.k" class="sum-srow">
        <i class="sum-dot" :class="r.dot"></i><span class="sum-lab">{{r.label}}</span><span class="grow"></span><span class="sum-share">{{r.share}}</span><b>{{fmt(r.v)}}</b>
      </div>
      <div class="sum-sec">Проект</div>
      <div class="sum-srow"><span class="sum-lab">Фраз</span><span class="grow"></span><b>{{fmt(entries)}}</b></div>
      <div class="sum-srow"><span class="sum-lab">Файлов</span><span class="grow"></span><b>{{fmt(summary.files || 0)}}</b></div>
    </div>
    <div v-else class="ft-empty"><svg class="icon" viewBox="0 0 24 24"><path d="M3 3v18h18"/><rect x="7" y="11" width="3" height="7"/><rect x="12" y="7" width="3" height="11"/><rect x="17" y="13" width="3" height="5"/></svg><span>Нет данных</span></div>
  </div>`
}
