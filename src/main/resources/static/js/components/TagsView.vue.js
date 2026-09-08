import {TAG_CATALOG} from '../tag-catalog.js';
import {tagKindLabel} from '../tags.js';
import {UI_COLORS} from '../ui-colors.js';

const KINDS = ['break', 'color', 'fmt', 'logic', 'value', 'media', 'misc'];
const UI_TAG_NAMES = ['colortype', 'edgecolortype'];
const RGB_TAG_NAMES = ['color', 'edgecolor', 'shadowcolor'];
const RGB_PRESETS = ['#000000', '#333333', '#666666', '#999999', '#cccccc', '#ffffff',
  '#ff0000', '#cc0000', '#800000', '#ff6666', '#ff8000', '#ffcc00',
  '#ffff00', '#808000', '#ffff66', '#00ff00', '#00cc00', '#008000',
  '#88dcae', '#00ffff', '#0099ff', '#79c7ec', '#0000ff', '#000080',
  '#9900ff', '#ff00ff', '#ff99cc', '#800080', '#996633', '#663300',
  '#f08e37', '#c03000'];

function hexToArgbDec(hex) {
  const m = /^#([0-9a-fA-F]{6})$/.exec(hex || '');
  if (!m) return null;
  return ((255 * 16777216 + parseInt(m[1], 16)) >>> 0).toString();
}

export default {
  props: ['filter'],
  emits: ['update:filter', 'insert'],
  data() {
    return {
      cat: 'all',
      uiId: {colortype: '506', edgecolortype: '507'},
      rgb: {color: '#ffffff', edgecolor: '#000000', shadowcolor: '#000000'},
      paletteFor: null
    };
  },
  computed: {
    cats() {
      return KINDS.map(k => ({kind: k, label: tagKindLabel(k)}));
    },
    uiEntries() {
      return Object.entries(UI_COLORS)
        .map(([id, css]) => ({id, css}))
        .sort((a, b) => Number(a.id) - Number(b.id));
    },
    rgbPresets() {
      return RGB_PRESETS;
    },
    groups() {
      const q = (this.filter || '').toLowerCase().trim();
      const items = TAG_CATALOG.filter(t => (this.cat === 'all' || t.k === this.cat)
        && (!q || t.n.toLowerCase().includes(q) || t.d.toLowerCase().includes(q)
          || (t.ex || '').toLowerCase().includes(q)));
      return KINDS
        .map(k => ({kind: k, label: tagKindLabel(k), items: items.filter(t => t.k === k)}))
        .filter(g => g.items.length);
    }
  },
  methods: {
    isUiTag(t) {
      return UI_TAG_NAMES.includes(typeof t === 'string' ? t : t.n);
    },
    isRgbTag(t) {
      return RGB_TAG_NAMES.includes(typeof t === 'string' ? t : t.n);
    },
    uiColor(name) {
      const id = (this.uiId[name] || '').trim();
      return UI_COLORS[id] || null;
    },
    previewStyle(name) {
      if (this.isUiTag(name)) {
        const c = this.uiColor(name);
        if (!c) return '';
        return name === 'edgecolortype'
          ? 'text-shadow:1px 0 0 ' + c + ',-1px 0 0 ' + c + ',0 1px 0 ' + c + ',0 -1px 0 ' + c + ';'
          : 'color:' + c + ';';
      }
      const dec = hexToArgbDec(this.rgb[name]);
      if (dec == null) return '';
      const n = Number(dec);
      const css = '#' + [16, 8, 0].map(s => ((n >>> s) & 255).toString(16).padStart(2, '0')).join('');
      if (name === 'color') return 'color:' + css + ';';
      if (name === 'edgecolor') return 'text-shadow:1px 0 0 ' + css + ',-1px 0 0 ' + css + ',0 1px 0 ' + css + ',0 -1px 0 ' + css + ';';
      return 'text-shadow:2px 2px 0 ' + css + ';';
    },
    insertSkeleton(t) {
      if (this.isUiTag(t)) { this.insertUiTag(t); return; }
      if (this.isRgbTag(t)) { this.insertRgbTag(t); return; }
      if (!t.ins) return;
      this.$emit('insert', t.close ? {open: t.ins, close: t.close} : t.ins);
    },
    insertTitle(t) {
      if (this.isUiTag(t)) {
        const id = (this.uiId[t.n] || '').trim();
        return 'Вставить <' + t.n + '(' + (id || '…') + ')>';
      }
      if (this.isRgbTag(t)) {
        const dec = hexToArgbDec(this.rgb[t.n]);
        return 'Вставить <' + t.n + '(' + (dec || '…') + ')>';
      }
      return 'Вставить ' + t.ins + (t.close || '');
    },
    insertUiTag(t) {
      const id = (this.uiId[t.n] || '').trim();
      this.$emit('insert', id ? '<' + t.n + '(' + id + ')>' : t.ins);
    },
    togglePalette(name) {
      this.paletteFor = this.paletteFor === name ? null : name;
      document.removeEventListener('click', this.onDocClick, true);
      if (this.paletteFor) {
        this.$nextTick(() => document.addEventListener('click', this.onDocClick, true));
      }
    },
    onDocClick(e) {
      if (!this.paletteFor) return;
      if (e.target.closest && e.target.closest('.palette-pop,.pal-toggle')) return;
      this.paletteFor = null;
      document.removeEventListener('click', this.onDocClick, true);
    },
    pickUi(name, id) {
      this.uiId[name] = id;
    },
    pickRgb(name, hex) {
      this.rgb[name] = hex;
    },
    fmtSample(name) {
      if (name === 'italic') return '<i>Пример текста</i>';
      if (name === 'bold') return '<b>Пример текста</b>';
      if (name === 'ruby') return '<ruby>Пример текста<rt>чтение</rt></ruby>';
      return '';
    },
    insertRgbTag(t) {
      const dec = hexToArgbDec(this.rgb[t.n]);
      this.$emit('insert', dec ? '<' + t.n + '(' + dec + ')>' : t.ins);
    }
  },
  beforeUnmount() {
    document.removeEventListener('click', this.onDocClick, true);
  },
  template: `
  <div class="pane-body" style="display:flex;flex-direction:column">
    <div class="search-box" style="flex-direction:column;gap:8px">
      <div class="seg wrap"><button :class="{on:cat==='all'}" @click="cat='all'">Все</button><button v-for="c in cats" :key="c.kind" :class="{on:cat===c.kind}" @click="cat=c.kind">{{c.label}}</button></div>
      <input class="grow" :value="filter" @input="$emit('update:filter',$event.target.value)" placeholder="Фильтр тегов…">
    </div>
    <div class="results" style="flex:1">
      <div class="tag-ref-note">Кнопка + вставляет заготовку в курсор перевода. {{'\\<'}} — буквальный текст, не тег; {{'<-->'}} — тире, {{'<->'}} — мягкий перенос.</div>
      <div v-for="g in groups" :key="g.kind">
        <div v-if="cat==='all'" class="cat-head">{{g.label}}</div>
        <div v-for="t in g.items" :key="t.n" class="tag-ref">
          <div style="display:flex;align-items:center;gap:8px"><span :class="'tag-chip tk-'+t.k">{{t.n}}</span><span class="grow"></span><button v-if="t.ins" class="ghost icon-btn sm" @click="insertSkeleton(t)" :title="insertTitle(t)"><svg class="icon" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg></button></div>
          <div class="tag-ref-desc">{{t.d}}</div>
          <div class="tag-ref-ex">{{t.ex}}</div>
          <div v-if="t.k==='fmt'&&fmtSample(t.n)" class="color-preview" v-html="fmtSample(t.n)"></div>
          <div v-if="isUiTag(t)" style="display:flex;flex-direction:column;gap:8px;margin-top:8px">
            <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;position:relative">
              <input :value="uiId[t.n]" @input="uiId[t.n]=$event.target.value" placeholder="id строки UIColor" title="id строки UIColor" style="width:90px">
              <span class="tag-chip pal-toggle" @click="togglePalette(t.n)" :style="'background:' + (uiColor(t.n) || 'transparent') + ';min-width:22px;min-height:22px;border:1px solid var(--border);cursor:pointer'" :title="'Палитра UIColor, сейчас ' + ((uiId[t.n]||'').trim() || '—')"></span>
              <span v-if="paletteFor===t.n" class="palette-pop">
                <button v-for="c in uiEntries" :key="c.id" @click="pickUi(t.n,c.id)" :title="c.id+' '+c.css" :style="'width:22px;height:22px;border-radius:6px;border:1px solid var(--border);background:'+c.css+';cursor:pointer;padding:0;'+(String(uiId[t.n]).trim()===c.id?'outline:2px solid var(--primary);':'')"></button>
              </span>
              <span class="grow"></span>
              <button class="ghost sm" @click="insertUiTag(t)" :title="'Вставить <'+t.n+'('+((uiId[t.n]||'').trim()||'…')+')>'">Вставить</button>
              <button class="ghost sm" @click="$emit('insert','<'+t.n+'(0)>')" title="Сброс">0</button>
            </div>
            <div class="color-preview" :style="previewStyle(t.n)">Пример текста</div>
          </div>
          <div v-if="isRgbTag(t)" style="display:flex;flex-direction:column;gap:8px;margin-top:8px">
            <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;position:relative">
              <span class="tag-chip pal-toggle" @click="togglePalette(t.n)" :style="'background:' + (rgb[t.n] || 'transparent') + ';min-width:22px;min-height:22px;border:1px solid var(--border);cursor:pointer'" :title="'Палитра, сейчас ' + (rgb[t.n] || '—')"></span>
              <input :value="rgb[t.n]" @input="rgb[t.n]=$event.target.value" placeholder="#rrggbb" title="Hex — точный цвет" style="width:90px">
              <span v-if="paletteFor===t.n" class="palette-pop">
                <button v-for="c in rgbPresets" :key="c" @click="pickRgb(t.n,c)" :title="c" :style="'width:22px;height:22px;border-radius:6px;border:1px solid var(--border);background:'+c+';cursor:pointer;padding:0;'+(rgb[t.n]===c?'outline:2px solid var(--primary);':'')"></button>
              </span>
              <span class="grow"></span>
              <button class="ghost sm" @click="insertRgbTag(t)" :title="'Вставить <'+t.n+'(AARRGGBB)>'">Вставить</button>
            </div>
            <div class="color-preview" :style="previewStyle(t.n)">Пример текста</div>
          </div>
        </div>
      </div>
      <div v-if="!groups.length" class="ft-empty"><span>Ничего не найдено</span></div>
    </div>
  </div>`
}
