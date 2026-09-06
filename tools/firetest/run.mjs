// Firetest: headless frontend regression stand.
// Boots the real app (static/js) in jsdom with stubbed backend API on the real
// projects/test/project.json, then clicks through core scenarios:
// picker -> open project -> open file -> smart search -> scope switch -> summary.
// Usage: node tools/firetest/run.mjs            (needs jsdom, see README.md)
// Exit codes: 0 all green, 1 scenario failure, 3 jsdom missing.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {pathToFileURL, fileURLToPath} from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const WORK = path.join(os.tmpdir(), 'harmonia-firetest', 'work');
const DEPS = path.join(os.tmpdir(), 'harmonia-firetest', 'deps');

let JSDOM;
try {
  ({JSDOM} = await import('jsdom'));
} catch (e1) {
  try {
    const {createRequire} = await import('node:module');
    ({JSDOM} = createRequire(path.join(DEPS, 'stub.cjs'))('jsdom'));
  } catch (e2) {
    console.error('jsdom not found. Install once with:\n  npm i --prefix "' + DEPS + '" jsdom');
    process.exit(3);
  }
}

const sleep = ms => new Promise(r => setTimeout(r, ms));
async function flush(n = 8) { for (let i = 0; i < n; i++) await sleep(50); }

const failures = [];
function check(name, cond, extra = '') {
  console.log((cond ? 'PASS' : 'FAIL') + ' ' + name + (extra ? ' — ' + extra : ''));
  if (!cond) failures.push(name);
}

// ---- prepare pristine static copy (strip ?v= cache-busters, map 'vue' to vendor) ----
fs.rmSync(WORK, {recursive: true, force: true});
fs.mkdirSync(path.join(WORK, 'static'), {recursive: true});
fs.cpSync(path.join(ROOT, 'src', 'main', 'resources', 'static', 'js'), path.join(WORK, 'static', 'js'), {recursive: true});
for (const f of walk(path.join(WORK, 'static', 'js'))) {
  if (!f.endsWith('.js')) continue;
  let s = fs.readFileSync(f, 'utf-8').replace(/\?v=\d+/g, '');
  if (path.basename(f) === 'app.js') s = s.replace("from 'vue'", "from './vendor/vue.esm-browser.prod.js'");
  fs.writeFileSync(f, s);
}
function walk(dir) {
  const out = [];
  for (const e of fs.readdirSync(dir, {withFileTypes: true})) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else out.push(p);
  }
  return out;
}

// ---- jsdom + globals ----
const dom = new JSDOM('<!DOCTYPE html><html><head></head><body><div id="app"></div></body></html>',
  {url: 'http://127.0.0.1:8765/', pretendToBeVisual: true});
const {window} = dom;
globalThis.window = window;
globalThis.document = window.document;
for (const k of ['Document', 'DocumentFragment', 'Comment', 'Range', 'localStorage', 'Element',
  'HTMLElement', 'SVGElement', 'SVGSVGElement', 'Node', 'Text', 'Event', 'MouseEvent',
  'KeyboardEvent', 'CustomEvent', 'MutationObserver', 'getComputedStyle',
  'requestAnimationFrame', 'cancelAnimationFrame']) {
  try { globalThis[k] = window[k]; } catch (e) {}
}
window.Element.prototype.scrollIntoView = function () {};
window.matchMedia = () => ({matches: false, addEventListener() {}, removeEventListener() {}});
window.__errors = [];

// ---- stubbed backend on synthetic fixture + real source listing ----
// ---- synthetic fixture: no dependency on repo project files ----
function buildFixture() {
  const entries = [], occs = [];
  const add = (id, file, rk, src, tr, st, ri) => {
    entries.push({id, source: src, translation: tr, status: st, protected_tokens: []});
    occs.push({entry_id: id, file, row_index: ri, column_index: 1, column_name: 'Description', row_key: rk});
  };
  for (let i = 0; i < 60; i++) {
    const un = i === 0;
    add('p_test_' + String(i).padStart(3, '0'), 'ActionTransient.csv', String(i),
      'Test phrase number ' + i + ' with Duration word', un ? '' : 'Перевод ' + i,
      un ? 'new' : 'machine_translated', 4 + i);
  }
  for (let i = 0; i < 5; i++) {
    add('p_second_' + i, 'Second.csv', String(i),
      'Second file line ' + i, 'Вторая ' + i, 'approved', 4 + i);
  }
  occs.push({entry_id: 'p_test_001', file: 'Second.csv', row_index: 99, column_index: 1, column_name: 'Description', row_key: '7'});
  for (let i = 0; i < 10; i++) {
    occs.push({entry_id: 'p_test_002', file: 'Second.csv', row_index: 100 + i, column_index: 1, column_name: 'Description', row_key: String(20 + i)});
  }
  return {schema_version: 3, project: {}, source_locale: 'en', target_locale: 'ru',
    files: ['ActionTransient.csv', 'Second.csv'], selected_tables_translate: [], selected_tables_export: [],
    entries, occurrences: occs, pack: null, merge: null};
}
const projDoc = buildFixture();
const sourcesDir = path.join(ROOT, 'data', 'sources');
const srcVer = fs.readdirSync(sourcesDir).sort();
const srcFiles = fs.readdirSync(path.join(sourcesDir, srcVer[srcVer.length - 1], 'en')).filter(f => f.endsWith('.csv'));
const summary = {entries: projDoc.entries.length, translated: 64, untranslated: 1,
  files: 2, occurrences: projDoc.occurrences.length, by_status: {machine_translated: 59, approved: 5, new: 1}, output_dir: 'out'};
globalThis.__runs = [];
globalThis.fetch = async (url, opt) => {
  const u = String(url);
  const json = (d) => ({json: async () => d});
  if (u === '/api/run' && opt && opt.method === 'POST') {
    try { globalThis.__runs.push(JSON.parse(opt.body)); } catch (e) {}
    return json({id: 'job-1'});
  }
  if (u.startsWith('/api/job')) return json({id: 'job-1', status: 'completed', action: 'extract', output: 'ok'});
  if (u.startsWith('/api/projects')) return json({projects: [
    {id: 'test', path: 'projects/test/project.json', files: 2, entries: 65, translated: 64},
    {id: 'empty', path: 'projects/empty/project.json', files: 0, entries: 0, translated: 0}]});
  if (u.startsWith('/api/project?')) {
    if (u.includes('empty')) return json({document: {schema_version: 3, project: {}, files: [], entries: [], occurrences: []},
      summary: {entries: 0, translated: 0, untranslated: 0, files: 0, occurrences: 0, by_status: {}, output_dir: 'out'}});
    return json({document: projDoc, summary});
  }
  if (u.startsWith('/api/files?')) return json({files: srcFiles});
  if (u.startsWith('/api/status')) return json({geminiConfigured: false, geminiModel: ''});
  if (u.startsWith('/api/search?')) return json({matches: []});
  if (u.startsWith('/api/preview')) {
    if (u.includes('full=true')) return json({file: 'ActionTransient.csv', rows: 3262, stringColumns: [1],
      head: [['key'], ['#'], ['offset'], ['Int32', 'String']],
      data: [['0', 'Sample text one'], ['1', 'Sample text two']], truncated: true});
    return json({preview: []});
  }
  if (u.startsWith('/api/selection')) {
    if (opt && opt.method === 'POST') {
      const b = JSON.parse(opt.body || '{}');
      globalThis.__selT = b.translate || [];
      globalThis.__selE = b.export || [];
    }
    return json({translate: globalThis.__selT || [], export: globalThis.__selE || []});
  }
  if (u.startsWith('/api/pack')) return json({pack: {}, errors: [], manifest: null});
  if (u === '/api/split-entry' && opt && opt.method === 'POST') {
    const b = JSON.parse(opt.body || '{}');
    const src = projDoc.entries.find(e => e.id === b.id);
    const occ = projDoc.occurrences.find(o => (o.entry_id || o.entryId) === b.id && o.file === b.file
      && String(o.row_key || o.rowKey || '') === String(b.row_key || b.rowKey || ''));
    if (!src || !occ) return json({error: 'not found'});
    const nid = 'p_ctx_001';
    const entry = {id: nid, source: src.source, translation: 'Второй контекст', status: 'approved', protected_tokens: [],
      context: {file: occ.file, row_key: occ.row_key, column_index: occ.column_index}};
    if (!projDoc.entries.some(e => e.id === nid)) projDoc.entries.push(entry);
    occ.entry_id = nid;
    return json({entries: [entry], occurrences: [{...occ}]});
  }
  if (u === '/api/save-entry' && opt && opt.method === 'POST') {
    const b = JSON.parse(opt.body || '{}');
    return json({entry: {id: b.id, translation: b.translation || '', status: b.status || 'new'}, warnings: []});
  }
  return json({error: 'firetest stub: ' + u});
};
window.fetch = globalThis.fetch;

function click(el) {
  el.dispatchEvent(new window.MouseEvent('click', {bubbles: true}));
}
function typeInput(el, text) {
  el.value = text;
  el.dispatchEvent(new window.Event('input', {bubbles: true}));
}
const doc = () => globalThis.document;

// ---- boot ----
await import(pathToFileURL(path.join(WORK, 'static', 'js', 'app.js')).href);
await flush(10);
check('boot: picker shown', !!doc().querySelector('.dash'));

// ---- open project ----
{
  const card = [...doc().querySelectorAll('.pcard')].find(el => el.textContent.includes('test'));
  check('picker: test project card', !!card);
  if (card) {
    click(card);
    await flush(2);
    const openBtn = [...doc().querySelectorAll('button')].find(b => b.textContent.includes('Открыть'));
    check('picker: open button', !!openBtn);
    if (openBtn) { click(openBtn); await flush(20); }
  }
}
check('project: layout mounted', !!doc().querySelector('.layout'));
check('project: file tree has rows', doc().querySelectorAll('.filetree .ft-item').length > 0);

// ---- open file (regression: ReferenceError fl froze the tab) ----
{
  const row = [...doc().querySelectorAll('.filetree .ft-item')].find(el => el.textContent.includes('ActionTransient'));
  check('open file: row found', !!row);
  if (row) {
    click(row);
    await flush(20);
    check('open file: phrases mode', !!doc().querySelector('.ph-filters'));
    check('open file: phrase rows render', doc().querySelectorAll('.pane-body .ft-item').length > 0);
    const edHead = (doc().querySelector('.ed-col-label span') || {}).textContent || '';
    check('open file: editor focused on file', edHead.includes('ActionTransient'), edHead);
  }
}

// ---- editor tabs across phrases ----
{
  const host = doc().querySelector('.ph-filters').closest('.dz-host');
  const rows = [...host.querySelectorAll('.pane-body .ft-item')].slice(0, 3);
  rows.forEach(r => click(r));
  await flush(10);
  const nTabs = () => doc().querySelectorAll('.ed-tab').length;
  check('tabs: phrases open as tabs', nTabs() >= 2, String(nTabs()));
  if (nTabs()) {
    const x = doc().querySelector('.ed-tab .ed-tab-x');
    const n0 = nTabs();
    if (x) click(x);
    await flush(10);
    check('tabs: close works', nTabs() === n0 - 1, n0 + ' -> ' + nTabs());
  }
}

// ---- save phrase: no error, text kept ----
{
  const host = doc().querySelector('.ph-filters').closest('.dz-host');
  const rows = [...host.querySelectorAll('.pane-body .ft-item')];
  if (rows.length) click(rows[Math.min(1, rows.length - 1)]);
  await flush(10);
  const ta = doc().querySelector('.ed-area');
  check('save: editor textarea present', !!ta);
  if (ta) {
    typeInput(ta, 'Проверка');
    const sb = [...doc().querySelectorAll('.ed-foot button')].find(b => (b.textContent || '').trim() === 'Сохранить');
    check('save: button present', !!sb);
    if (sb) { click(sb); await flush(16); }
    const errs = [...doc().querySelectorAll('.toast')].map(e => e.textContent).filter(s => s.includes('Ошибка'));
    check('save: no error', errs.length === 0, errs.slice(0, 1).join(' | '));
    check('save: text kept', (doc().querySelector('.ed-area') || {}).value === 'Проверка');
  }
}

// ---- context split: shared phrase gets per-occurrence variant ----
{
  const gh = [...doc().querySelectorAll('.occ-ghead')].find(el => (el.textContent || '').includes('Second.csv'));
  if (gh) { click(gh); await flush(10); }
  const occRow = [...doc().querySelectorAll('.occ-item')].find(el => (el.textContent || '').includes('Second.csv'));
  check('split: second occurrence shown', !!occRow);
  const nt0 = doc().querySelectorAll('.ed-tab').length;
  const sp = occRow ? occRow.querySelector('.occ-split') : null;
  check('split: button present', !!sp);
  if (sp) { click(sp); await flush(12); }
  const nt1 = doc().querySelectorAll('.ed-tab').length;
  check('split: new tab opened', nt1 === nt0 + 1, nt0 + ' -> ' + nt1);
  const activeLabel = (doc().querySelector('.ed-tab.active .ed-tab-label') || {}).textContent || '';
  check('split: tab labeled by context', activeLabel.includes('Second.csv'), activeLabel);
  const segBtns = [...doc().querySelectorAll('.ph-filters .seg button')];
  if (segBtns.length === 2) { click(segBtns[1]); await flush(10); }
  check('split: ctx chip in list', !!doc().querySelector('.ctx-tag'));
}

// ---- occurrences group by file, collapse, filter ----
{
  const tab2 = [...doc().querySelectorAll('.ed-tab')].find(t => (t.textContent || '').includes('Test phrase number 2'));
  check('occ: tab with many occurrences present', !!tab2);
  if (tab2) {
    click(tab2);
    await flush(10);
    const shown = () => doc().querySelectorAll('.occ-item').length;
    check('occ: only first group open', shown() === 1, String(shown()));
    const groups = () => [...doc().querySelectorAll('.occ-ghead')];
    check('occ: two file groups', groups().length === 2, String(groups().length));
    click(groups()[1]);
    await flush(10);
    check('occ: second group expands', shown() === 11, String(shown()));
    const f = doc().querySelector('.occ-filter');
    check('occ: filter present', !!f);
    if (f) {
      f.value = 'zzz-no-such';
      f.dispatchEvent(new window.Event('input', {bubbles: true}));
      await flush(10);
      check('occ: filter narrows', shown() === 0, String(shown()));
      f.value = '';
      f.dispatchEvent(new window.Event('input', {bubbles: true}));
      await flush(10);
    }
  }
}

// ---- project tree: expand file, inline rows open tabs ----
{
  const back = doc().querySelector('.pane-head button[title="К файлам"]');
  check('tree: back to files', !!back);
  if (back) { click(back); await flush(10); }
  const chev = doc().querySelector('.tree-chev');
  check('tree: expander present', !!chev);
  if (chev) {
    click(chev);
    await flush(10);
    const inrows = [...doc().querySelectorAll('.tree-phrase')];
    check('tree: inline rows expand', inrows.length > 0, String(inrows.length));
    if (inrows.length) {
      const pick = inrows[Math.min(5, inrows.length - 1)];
      const nt0 = doc().querySelectorAll('.ed-tab').length;
      click(pick);
      await flush(10);
      check('tree: inline click opens tab', doc().querySelectorAll('.ed-tab').length === nt0 + 1, nt0 + ' -> ' + doc().querySelectorAll('.ed-tab').length);
    }
    const dt = doc().querySelector('.done-toggle');
    check('tree: done toggle present', !!dt && (dt.textContent || '').includes('(1)'));
    if (dt) { click(dt); await flush(10); }
    check('tree: done file shown', [...doc().querySelectorAll('.filetree .ft-name')].some(el => el.textContent.includes('Second.csv')));
  }
  const hdr = [...doc().querySelectorAll('.filetree .ft-item')].find(el => el.textContent.includes('ActionTransient'));
  if (hdr) { click(hdr); await flush(15); }
  check('tree: header reopens phrases', !!doc().querySelector('.ph-filters'));
}
{
  const fvInput = doc().querySelector('input[placeholder^="Фильтр таблиц"]');
  check('csv preview: files filter present', !!fvInput);
  if (fvInput) {
    typeInput(fvInput, 'ActionTransient');
    await flush(10);
    const btn = doc().querySelector('.pv-btn');
    check('csv preview: eye button present', !!btn);
    if (btn) {
      click(btn);
      await flush(10);
      const tbl = doc().querySelector('.main-pane table.csv-table');
      check('csv preview: table opens in editor tab', !!tbl && tbl.querySelectorAll('tbody tr').length === 2);
      const tab = [...doc().querySelectorAll('.ed-tab')].find(t => (t.textContent || '').includes('ActionTransient'));
      check('csv preview: tab labeled by file', !!tab);
      if (tab) { const x = tab.querySelector('.ed-tab-x'); if (x) click(x); await flush(5); }
      check('csv preview: closes', ![...doc().querySelectorAll('.ed-tab')].some(t => (t.textContent || '').includes('ActionTransient')));
    }
    typeInput(fvInput, '');
    await flush(5);
  }
}

// ---- smart search filters the list ----
{
  const input = doc().querySelector('.ph-filters input[placeholder^="Поиск"]');
  check('search: field present', !!input);
  if (input) {
    const countText = () => (doc().querySelector('.ph-count') || {}).textContent || '';
    typeInput(input, 'zz-no-such-phrase-zzz');
    await flush(10);
    check('search: gibberish finds nothing', countText().includes('Найдено 0'), countText());
    typeInput(input, 'Duration');
    await flush(10);
    const m = countText().match(/Найдено (\d+)/);
    check('search: real word matches', !!m && +m[1] >= 55 && +m[1] <= 65, countText());
    typeInput(input, '12');
    await flush(10);
    const phraseRows = [...doc().querySelectorAll('.pane-body .ft-item')].filter(el => (el.textContent || '').includes('строка:'));
    const firstTxt = phraseRows.length ? phraseRows[0].textContent : '';
    check('search: exact rowKey ranks first', /строка: 12(,|$)/.test(firstTxt), firstTxt.slice(0, 80) || 'no rows');
    typeInput(input, '');
    await flush(10);
  }
}

// ---- scope switch file -> project ----
{
  const btns = [...doc().querySelectorAll('.ph-filters .seg button')];
  check('scope: switch present', btns.length === 2);
  if (btns.length === 2) {
    click(btns[1]);
    await flush(10);
    const title = (doc().querySelector('.pane-head h3') || {}).textContent || '';
    check('scope: project title', title.includes('Весь проект'), title);
    click(btns[0]);
    await flush(10);
  }
}

// ---- table selections: translate vs export are independent ----
{
  const trBtn = [...doc().querySelectorAll('.seg button')].find(b => (b.textContent || '').includes('Для перевода'));
  const exBtn = [...doc().querySelectorAll('.seg button')].find(b => (b.textContent || '').includes('Для экспорта'));
  check('tables: mode switch present', !!trBtn && !!exBtn);
  const tablesPane = [...doc().querySelectorAll('.pane-body')].find(p => (p.textContent || '').includes('Для перевода'));
  check('tables: no update button inside selection panel', !!tablesPane && ![...tablesPane.querySelectorAll('button')].some(b => (b.textContent || '').includes('Обновить данные игры')));
  if (trBtn && exBtn) {
    const num = s => +((s.match(/\((\d+)\)/) || [])[1] || 0);
    click(exBtn);
    await flush(5);
    const box = doc().querySelector('.pane-body .ft-item input[type="checkbox"]');
    check('tables: checkbox present', !!box);
    if (box) {
      const t0 = num(trBtn.textContent), e0 = num(exBtn.textContent);
      box.checked = !box.checked;
      box.dispatchEvent(new window.Event('change', {bubbles: true}));
      await flush(16);
      const t1 = num(trBtn.textContent), e1 = num(exBtn.textContent);
      check('tables: export set toggles alone', Math.abs(e1 - e0) === 1 && t1 === t0, `${t0}/${e0} -> ${t1}/${e1}`);
      check('tables: selection persisted to backend', (globalThis.__selE || []).length === e1);
    }
    click(trBtn);
    await flush(5);
  }
}

// ---- summary panel opens, no dead field ----
{
  const badge = doc().querySelector('.tb-center .badge');
  check('summary: badge present', !!badge);
  if (badge) {
    click(badge);
    await flush(10);
    const body = doc().body.textContent || '';
    check('summary: panel shows progress', body.includes('Переведено') && body.includes('По статусам'));
    check('summary: no literal undefined', !body.includes('undefined'));
  }
}

// ---- empty project auto-starts full update on open ----
{
  const burger = doc().querySelector('.top-menu-wrap button[title="Меню"]');
  check('auto update: burger present', !!burger);
  if (burger) {
    click(burger);
    await flush(5);
    const upd = [...doc().querySelectorAll('.dz-menu-i')].find(el => (el.textContent || '').includes('Обновить данные игры'));
    check('auto update: burger has update item', !!upd);
    const sw = [...doc().querySelectorAll('.dz-menu-i')].find(el => (el.textContent || '').includes('Сменить проект'));
    check('auto update: switch project item', !!sw);
    if (sw) { click(sw); await flush(10); }
  }
  const ecard = [...doc().querySelectorAll('.pcard')].find(el => el.textContent.includes('empty'));
  check('auto update: empty project listed', !!ecard);
  if (ecard) {
    click(ecard);
    await flush(2);
    const ob = [...doc().querySelectorAll('button')].find(b => b.textContent.includes('Открыть'));
    if (ob) click(ob);
    await flush(12);
    const runs = globalThis.__runs || [];
    check('auto update: full extract started', runs.some(r => r.action === 'extract'), JSON.stringify(runs));
  }
}

// ---- vue runtime errors surface as toast banners via app errorHandler ----
const errToasts = [...doc().querySelectorAll('.toast')].map(e => e.textContent).filter(s => s.includes('Ошибка'));
check('no vue runtime errors', errToasts.length === 0, errToasts.slice(0, 2).join(' | '));

console.log(failures.length ? `\n${failures.length} FAILURES` : '\nALL GREEN');
process.exit(failures.length ? 1 : 0);
