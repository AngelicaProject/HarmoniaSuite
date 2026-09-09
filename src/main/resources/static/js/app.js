import {computed, createApp, nextTick, onMounted, ref, shallowRef, watch} from 'vue';
import {api} from './api.js';
import Picker from './components/Picker.vue.js';
import Editor from './components/Editor.vue.js';
import Settings from './components/Settings.vue.js';
import TranslateView from './components/TranslateView.vue.js';
import SearchView from './components/SearchView.vue.js';
import TagsView from './components/TagsView.vue.js';
import SummaryView from './components/SummaryView.vue.js';
import PackView from './components/PackView.vue.js';
import ExportView from './components/ExportView.vue.js';
import DeltaView from './components/DeltaView.vue.js';
import LogView from './components/LogView.vue.js';
import Dropdown from './components/Dropdown.vue.js';

const App = {
    components: {Picker, Editor, TranslateView, SearchView, TagsView, SummaryView, PackView, ExportView, DeltaView, LogView, Settings, Dropdown},
    setup() {
        const projectId = ref('');
        const projectName = ref('');
        const root = ref('');
        const showPicker = ref(true);
        const doc = shallowRef(null);
        const dataRev = ref(0);
        const fileTree = ref([]);
        const fileTreeLoading = ref(false);
        const fileSearchQ = ref('');
        const fileHideReady = ref(false);
        const hideEmpty = ref((() => { try { return localStorage.getItem('hs-hide-empty') !== 'off'; } catch (e) { return true; } })());
        function toggleHideEmpty() {
            hideEmpty.value = !hideEmpty.value;
            try { localStorage.setItem('hs-hide-empty', hideEmpty.value ? 'on' : 'off'); } catch (e) {}
        }
        const fileSort = ref((() => { try { return localStorage.getItem('hs-sort') || 'name'; } catch (e) { return 'name'; } })());
        function setSort(v) {
            fileSort.value = v;
            try { localStorage.setItem('hs-sort', v); } catch (e) {}
        }
        const expandedDirs = ref({});
        const rowGroupPageSize = 60;
        const fileRows = ref({file: '', q: '', page: 0, groups: [], totalGroups: 0, loading: false});
        const filePreviewCache = new Map();
        let rowRequest = 0;
        let phraseSearchTimer = null;
        let statsTimer = null;
        function refreshStats() {
            clearTimeout(statsTimer);
            statsTimer = setTimeout(() => loadFileTree(), 250);
        }
        async function loadFileTree() {
            if (!projectId.value) return;
            fileTreeLoading.value = true;
            try {
                const d = await api.fileTree(projectId.value);
                fileTree.value = d.files || [];
                dataRev.value++;
            } catch (e) {
            }
            fileTreeLoading.value = false;
        }
        function toggleDir(d) { expandedDirs.value[d] = !expandedDirs.value[d]; }
        function toggleHideReady() { fileHideReady.value = !fileHideReady.value; }
        const treeFileCount = computed(() => (fileTree.value || []).length);
        const treeReadyCount = computed(() => {
            const q = fileSearchQ.value.trim().toLowerCase();
            let n = 0;
            for (const f of (fileTree.value || [])) {
                if (q && !(f.path || '').toLowerCase().includes(q)) continue;
                if ((f.total || 0) > 0 && (f.total || 0) === (f.translated || 0)) n++;
            }
            return n;
        });
        const treeEmptyCount = computed(() => {
            const q = fileSearchQ.value.trim().toLowerCase();
            let n = 0;
            for (const f of (fileTree.value || [])) {
                if (q && !(f.path || '').toLowerCase().includes(q)) continue;
                if ((f.total || 0) === 0) n++;
            }
            return n;
        });
        const treeRows = computed(() => {
            const q = fileSearchQ.value.trim().toLowerCase();
            const items = fileTree.value || [];
            const vis = f => {
                if (q && !(f.path || '').toLowerCase().includes(q)) return false;
                if (hideEmpty.value && (f.total || 0) === 0) return false;
                if (fileHideReady.value && (f.total || 0) <= (f.translated || 0)) return false;
                return true;
            };
            const needOf = f => (f.total || 0) - (f.translated || 0);
            const kidName = k => k.type === 'dir' ? k.dir.name : k.file.path;
            const kidPct = k => {
                const t = k.type === 'dir' ? (k.dir.total || 0) : (k.file.total || 0);
                const d = k.type === 'dir' ? (k.dir.done || 0) : (k.file.translated || 0);
                return t ? d / t : 0;
            };
            function sortKids(kids) {
                if (fileSort.value === 'name') kids.sort((a, b) => (kidName(a) < kidName(b) ? -1 : 1));
                else if (fileSort.value === 'progress') kids.sort((a, b) => (kidPct(b) - kidPct(a)) || ((kidName(a) < kidName(b)) ? -1 : 1));
                else kids.sort((a, b) => (b.need - a.need) || ((kidName(a) < kidName(b)) ? -1 : 1));
            }
            const root = {dirs: new Map(), files: []};
            for (const f of items) {
                if (!vis(f)) continue;
                const parts = (f.path || '').split('/');
                let node = root;
                for (let i = 0; i < parts.length - 1; i++) {
                    let d = node.dirs.get(parts[i]);
                    if (!d) {
                        d = {name: parts[i], path: parts.slice(0, i + 1).join('/'), dirs: new Map(), files: []};
                        node.dirs.set(parts[i], d);
                    }
                    node = d;
                }
                node.files.push(f);
            }
            const rows = [];
            function fold(d) {
                let t = 0, dn = 0;
                for (const f of d.files) { t += f.total || 0; dn += f.translated || 0; }
                for (const c of d.dirs.values()) { const s = fold(c); t += s.total; dn += s.done; }
                d.total = t; d.done = dn;
                return d;
            }
            function emit(node, depth) {
                const kids = [];
                for (const d of node.dirs.values()) {
                    fold(d);
                    kids.push({type: 'dir', depth, key: 'd:' + d.path, dir: d, open: !!q || !!expandedDirs.value[d.path], need: (d.total || 0) - (d.done || 0)});
                }
                for (const f of node.files) kids.push({type: 'file', depth, key: 'f:' + f.path, file: f, need: needOf(f)});
                sortKids(kids);
                for (const k of kids) {
                    rows.push(k);
                    if (k.type === 'dir' && k.open) emit(k.dir, depth + 1);
                }
            }
            emit(root, 0);
            return rows;
        });
        const summary = ref(null);
        const projectLoading = ref(false);
        const sourceFiles = ref([]);
        const sourceLoading = ref(false);
        const sourceError = ref('');
        const selTranslate = ref([]);
        const trPendingMap = ref({});
        const trPendingReady = ref(false);
        let trPendingTimer = null;
        async function fetchTrPending() {
            if (!projectId.value) { trPendingMap.value = {}; trPendingReady.value = false; return; }
            try {
                const d = await api.pendingByFile(projectId.value);
                trPendingMap.value = (d && d.files) || {};
                trPendingReady.value = true;
            }
            catch (e) { /* карта некритична — список покажем целиком */ }
        }
        watch(dataRev, () => {
            clearTimeout(trPendingTimer);
            trPendingTimer = setTimeout(fetchTrPending, 300);
        });
        const trEstimate = computed(() => {
            let n = 0;
            const m = trPendingMap.value || {};
            for (const f of selTranslate.value) n += m[f] || 0;
            return {entries: n};
        });
        const selExport = ref([]);
        const selTranslateSet = computed(() => new Set(selTranslate.value));
        const selExportSet = computed(() => new Set(selExport.value));
        function selStorageKey() { return 'hs-sel-' + projectId.value; }
        function saveSelection() {
            try { localStorage.setItem(selStorageKey(), JSON.stringify({translate: selTranslate.value, export: selExport.value})); } catch (e) {}
        }
        function loadSelection() {
            try {
                const s = JSON.parse(localStorage.getItem(selStorageKey()) || 'null');
                selTranslate.value = Array.isArray(s?.translate) ? s.translate : [];
                selExport.value = Array.isArray(s?.export) ? s.export : [];
            } catch (e) { selTranslate.value = []; selExport.value = []; }
        }
        const logText = ref('Готово к работе.');
        const geminiStatus = ref({configured: false, model: '', models: []});
        async function loadGeminiStatus() {
            try {
                const s = await api.status();
                geminiStatus.value = {configured: !!s.geminiConfigured, model: s.geminiModel || '', models: s.geminiModels || [],
                    openrouterConfigured: !!s.openrouterConfigured, openrouterModel: s.openrouterModel || ''};
            } catch (e) {
            }
        }
        loadGeminiStatus();
        const upd = ref({supported: false, mode: '', version: '', needsToolchain: false, currentSha: '', latestSha: '', behindBy: 0, subjects: [], updateAvailable: false});
        const updModal = ref(false);
        const updRestarting = ref(false);
        const updRestartDead = ref(false);
        const updLogBusy = ref(false);
        async function loadUpdateStatus() {
            try {
                upd.value = await api.updateStatus();
            } catch (e) {
            }
        }
        const updLabel = computed(() => {
            const u = upd.value || {};
            const sha = (u.currentSha || '').slice(0, 7);
            if (u.updateAvailable) return 'v' + (u.version || 'dev') + ' (+' + u.behindBy + ') ' + sha;
            return 'v' + (u.version || 'dev') + ' · ' + sha;
        });
        const updTitle = computed(() => {
            const u = upd.value || {};
            if (!u.supported) return 'Обновления недоступны';
            if (!u.updateAvailable) return 'Актуально\n' + (u.currentSha || '');
            return 'Текущий: ' + (u.currentSha || '') + '\nНа main: ' + (u.latestSha || '');
        });
        async function runUpdate() {
            updModal.value = false;
            try {
                startJob(await api.runUpdate());
            } catch (e) {
                showToast(e.message);
            }
        }
        async function copyUpdateLog() {
            updLogBusy.value = true;
            try {
                const text = await api.logTail();
                if (!navigator.clipboard || !navigator.clipboard.writeText) {
                    throw Error('Буфер обмена недоступен');
                }
                await navigator.clipboard.writeText(text);
                showToast('Журнал скопирован');
            } catch (e) {
                showToast(e.message);
            }
            updLogBusy.value = false;
        }
        const aiTitle = computed(() => {
            const g = geminiStatus.value || {};
            const gl = g.configured ? ('ключ задан' + (g.model ? ', ' + g.model : '')) : 'без ключа';
            const ol = g.openrouterConfigured ? ('ключ задан' + (g.openrouterModel ? ', ' + g.openrouterModel : '')) : 'без ключа';
            return 'Gemini: ' + gl + '\nOpenRouter: ' + ol;
        });
        const sourceStatus = ref({gamePath: '', gameValid: false, gameVersion: '', activeRoot: '', ready: false, configured: false});
        const showSettings = ref(false);
        const settingsSection = ref('sources');
        function openSettings(s) {
            settingsSection.value = s || settingsSection.value;
            showSettings.value = true;
        }
        async function loadSourceStatus() {
            try {
                const s = await api.settings();
                sourceStatus.value = s;
                if (s.activeRoot) root.value = s.activeRoot;
                if (!s.configured) openSettings('sources');
            } catch (e) {
                logText.value += '\nИсточники: ' + e.message;
            }
        }
        const sourceLabel = computed(() => {
            const s = sourceStatus.value || {};
            if (!s.configured) return 'источники не настроены';
            return s.gameVersion || s.activeRoot || 'источники';
        });
        const badge = computed(() => {
            const s = summary.value;
            if (!s || s.entries === undefined) return '—';
            return `${s.translated ?? 0} / ${s.entries}`;
        });
        const rg = ref('');
        const theme = ref(document.documentElement.getAttribute('data-theme') || 'dark');

        // ---- IDE docking: views move between left/right/bottom zones ----
        const VIEWS = {
            project: {title: 'Проект', icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1 2 2H5a2 2 0 0 1-2-2V7z"/></svg>'},
            search: {title: 'Поиск', icon: '<svg class="icon" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg>'},
            translate: {title: 'AI перевод', icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"/><path d="M19 15l.9 2.1L22 18l-2.1.9L19 21l-.9-2.1L16 18l2.1-.9z"/></svg>'},
            tags: {title: 'Теги', icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><circle cx="7" cy="7" r="1.2"/></svg>'},
            summary: {title: 'Сводка', icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M3 3v18h18"/><rect x="7" y="11" width="3" height="7"/><rect x="12" y="7" width="3" height="11"/><rect x="17" y="13" width="3" height="5"/></svg>'},
            pack: {title: 'Пак', icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M21 8l-9-5-9 5v8l9 5 9-5V8z"/><path d="M3 8l9 5 9-5M12 13v8"/></svg>'},
            export: {title: 'Экспорт', icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M12 3v12M8 11l4 4 4-4"/><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/></svg>'},
            delta: {title: 'Дельты', icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M7 8h11l-3-3M17 16H6l3 3"/></svg>'},
            log: {title: 'Журнал', icon: '<svg class="icon" viewBox="0 0 24 24"><path d="M4 6h16M4 12h16M4 18h10"/></svg>'}
        };
        const VIEW_IDS = ['project', 'translate', 'search', 'tags', 'summary', 'pack', 'export', 'delta', 'log'];
        const defaultZones = () => ({
            layout: {left: ['project', 'delta'], right: ['translate', 'search', 'tags', 'summary', 'pack', 'export'], bottom: ['log']},
            active: {left: 'project', right: 'translate', bottom: 'log'}
        });
        const savedLayout = (() => { try { return JSON.parse(localStorage.getItem('hs-layout') || 'null'); } catch (e) { return null; } })();
        const layout = ref(defaultZones().layout);
        const active = ref(defaultZones().active);
        const zoneVisible = ref({left: true, right: true, bottom: true});
        const railVisible = ref({left: true, right: true, bottom: true});
        const sideW = ref(300), ctxW = ref(360), bottomH = ref(190);
        if (savedLayout) {
            if (savedLayout.v === 2 && savedLayout.layout) {
                for (const z of ['left', 'right', 'bottom']) {
                    const arr = (savedLayout.layout[z] || []).filter(v => VIEW_IDS.includes(v));
                    layout.value[z] = arr;
                }
                for (const z of ['left', 'right', 'bottom']) {
                    if (savedLayout.active && layout.value[z].includes(savedLayout.active[z])) active.value[z] = savedLayout.active[z];
                    else active.value[z] = layout.value[z][0] || null;
                }
                if (savedLayout.zoneVisible) zoneVisible.value = {left: savedLayout.zoneVisible.left !== false, right: savedLayout.zoneVisible.right !== false, bottom: savedLayout.zoneVisible.bottom !== false};
                if (savedLayout.railVisible) railVisible.value = {left: savedLayout.railVisible.left !== false, right: savedLayout.railVisible.right !== false, bottom: savedLayout.railVisible.bottom !== false};
                sideW.value = savedLayout.sideW || 300;
                ctxW.value = savedLayout.ctxW || 360;
                bottomH.value = savedLayout.bottomH || 190;
            } else {
                sideW.value = savedLayout.sideW || 300;
                ctxW.value = savedLayout.ctxW || 360;
                zoneVisible.value = {left: savedLayout.leftVisible !== false, right: (savedLayout.rightMode || 'dock') !== 'hidden', bottom: true};
            }
        }
        // every view lives in exactly one zone
        for (const v of VIEW_IDS) {
            if (!layout.value.left.includes(v) && !layout.value.right.includes(v) && !layout.value.bottom.includes(v)) {
                layout.value.right.push(v);
            }
        }

        const narrow = ref(window.matchMedia('(max-width:1000px)').matches);
        try { window.matchMedia('(max-width:1000px)').addEventListener('change', e => narrow.value = e.matches); } catch (e) {}

        function persistLayout() {
            try {
                localStorage.setItem('hs-layout', JSON.stringify({v: 2, layout: layout.value, active: active.value,
                    zoneVisible: zoneVisible.value, railVisible: railVisible.value,
                    sideW: sideW.value, ctxW: ctxW.value, bottomH: bottomH.value}));
            } catch (e) {}
        }

        const hiddenViews = computed(() => VIEW_IDS.filter(v =>
            !layout.value.left.includes(v) && !layout.value.right.includes(v) && !layout.value.bottom.includes(v)));

        const layoutStyle = computed(() => {
            if (narrow.value) return {};
            const leftOpen = railVisible.value.left && zoneVisible.value.left && layout.value.left.length > 0;
            const rightOpen = railVisible.value.right && zoneVisible.value.right && layout.value.right.length > 0;
            const left = !railVisible.value.left ? '0px' : (leftOpen ? Math.max(180, Math.min(560, sideW.value)) + 'px' : '50px');
            const right = !railVisible.value.right ? '0px' : (rightOpen
                ? Math.max(240, Math.min(640, ctxW.value)) + 'px' : '50px');
            const showBottom = railVisible.value.bottom && zoneVisible.value.bottom !== false && layout.value.bottom.length > 0;
            const bottomRail = railVisible.value.bottom;
            const rows = showBottom ? '1fr ' + Math.max(110, Math.min(480, bottomH.value)) + 'px' : (bottomRail ? '1fr auto' : '1fr');
            return {gridTemplateColumns: left + ' 1fr ' + right, gridTemplateRows: rows};
        });


        function zoneShown(z) {
            if (z === 'bottom') return zoneVisible.value.bottom !== false && layout.value.bottom.length > 0;
            return zoneVisible.value[z] && layout.value[z].length > 0;
        }

        function zoneStyle(z) {
            if (z === 'bottom') return {height: Math.max(110, Math.min(480, bottomH.value)) + 'px'};
            return {};
        }

        function activateView(zone, view) {
            active.value[zone] = view;
            if (view === 'pack' && !pack.value) loadPack();
            persistLayout();
        }

        function toggleView(zone, view) {
            if (active.value[zone] === view && zoneShown(zone)) hideZone(zone);
            else {
                if (zone === 'bottom') zoneVisible.value.bottom = true;
                else zoneVisible.value[zone] = true;
                activateView(zone, view);
            }
        }

        const ctxZone = ref(null);
        function openZoneMenu(zone, e, view) {
            ctxZone.value = {
                zone,
                view: view || null,
                x: Math.min(e.clientX, window.innerWidth - 190),
                y: Math.min(e.clientY, window.innerHeight - 60)
            };
        }
        function hideWidget() {
            if (!ctxZone.value || !ctxZone.value.view) return;
            closeTab(ctxZone.value.zone, ctxZone.value.view);
            ctxZone.value = null;
        }

        function activeTitle(zone) {
            const v = active.value[zone];
            return v && VIEWS[v] ? VIEWS[v].title : '';
        }

        function gotoView(view) {
            for (const z of ['left', 'right', 'bottom']) {
                if (layout.value[z].includes(view)) {
                    railVisible.value[z] = true;
                    zoneVisible.value[z] = true;
                    activateView(z, view);
                    return;
                }
            }
            railVisible.value.right = true;
            zoneVisible.value.right = true;
            addView('right', view);
        }

        function addView(zone, view) {
            if (!layout.value[zone].includes(view)) {
                for (const z of ['left', 'right', 'bottom']) {
                    const i = layout.value[z].indexOf(view);
                    if (i >= 0) layout.value[z].splice(i, 1);
                }
                layout.value[zone].push(view);
            }
            menuFor.value = null;
            addMenu.value = null;
            activateView(zone, view);
        }

        function closeTab(zone, view) {
            const arr = layout.value[zone];
            const i = arr.indexOf(view);
            if (i >= 0) arr.splice(i, 1);
            if (active.value[zone] === view) active.value[zone] = arr[Math.min(i, arr.length - 1)] || null;
            layoutRev.value++;
            persistLayout();
        }

        function resetLayout() {
            const d = defaultZones();
            layout.value = d.layout;
            active.value = d.active;
            zoneVisible.value = {left: true, right: true, bottom: true};
            railVisible.value = {left: true, right: true, bottom: true};
            menuFor.value = null;
            addMenu.value = null;
            ctxZone.value = null;
            layoutRev.value++;
            persistLayout();
            showToast('Раскладка сброшена');
        }

        // drag-and-drop tabs between zones
        const dragView = ref(null);
        const dropPos = ref(null);
        const menuFor = ref(null);
        const addMenu = ref(null);
        function openAddMenu(zone, e) {
            if (addMenu.value && addMenu.value.zone === zone) { addMenu.value = null; return; }
            menuFor.value = null;
            addMenu.value = {
                zone,
                x: Math.min(e.clientX, window.innerWidth - 220),
                y: Math.min(e.clientY, window.innerHeight - 320)
            };
        }
        function onTabDragStart(zone, view, e) {
            dragView.value = {view, from: zone};
            e.dataTransfer.effectAllowed = 'move';
            try { e.dataTransfer.setData('text/plain', view); } catch (err) {}
        }
        function onTabDragOver(zone, index, e) {
            e.preventDefault();
            e.dataTransfer.dropEffect = 'move';
            dropPos.value = {zone, index};
        }
        function onDrop(zone, e) {
            e.preventDefault();
            e.stopPropagation();
            const d = dragView.value;
            dragView.value = null;
            dropPos.value = null;
            if (!d) return;
            moveView(d.view, d.from, zone, null);
        }
        function onDropOnTab(zone, index, e) {
            e.preventDefault();
            e.stopPropagation();
            const d = dragView.value;
            dragView.value = null;
            dropPos.value = null;
            if (!d) return;
            moveView(d.view, d.from, zone, index);
        }
        function onDragEnd() { dragView.value = null; dropPos.value = null; }
        function moveView(view, from, zone, index) {
            const src = layout.value[from];
            const si = src.indexOf(view);
            if (si >= 0) src.splice(si, 1);
            const dst = layout.value[zone];
            let idx = index == null ? dst.length : Math.max(0, Math.min(index, dst.length));
            if (from === zone && si >= 0 && si < idx) idx--;
            dst.splice(idx, 0, view);
            active.value[zone] = view;
            if (!dst.includes(active.value[from]) && from !== zone) active.value[from] = layout.value[from][0] || null;
            else if (from === zone && !dst.includes(active.value[zone])) active.value[zone] = dst[0] || null;
            if (view === 'pack' && !pack.value) loadPack();
            layoutRev.value++;
            persistLayout();
        }
        function dropClass(zone, i) {
            if (!dropPos.value || dropPos.value.zone !== zone) return '';
            return dropPos.value.index === i ? 'drop-before' : '';
        }

        // teleport hosts: view content follows its host element
        const hosts = {};
        const layoutRev = ref(0);
        function setHost(view, el) {
            if (el) {
                if (hosts[view] !== el) { hosts[view] = el; layoutRev.value++; }
            } else {
                const cur = hosts[view];
                if (!cur) return;
                if (!cur.isConnected) { delete hosts[view]; layoutRev.value++; }
                else nextTick(() => {
                    const c = hosts[view];
                    if (c && !c.isConnected) { delete hosts[view]; layoutRev.value++; }
                });
            }
        }
        function hostEl(view) {
            layoutRev.value;
            const el = hosts[view];
            return el && el.isConnected ? el : null;
        }

        function startResize(pane, e) {
            if (narrow.value) return;
            e.preventDefault();
            const el = e.target.closest ? e.target : null;
            if (el && el.classList) el.classList.add('on');
            const x0 = e.clientX, w0 = pane === 'left' ? sideW.value : ctxW.value;
            const move = ev => {
                const dx = ev.clientX - x0;
                if (pane === 'left') sideW.value = w0 + dx;
                else ctxW.value = w0 - dx;
            };
            const up = () => {
                window.removeEventListener('mousemove', move);
                window.removeEventListener('mouseup', up);
                if (el && el.classList) el.classList.remove('on');
                persistLayout();
            };
            window.addEventListener('mousemove', move);
            window.addEventListener('mouseup', up);
        }

        function startResizeY(e) {
            if (narrow.value) return;
            e.preventDefault();
            const y0 = e.clientY, h0 = bottomH.value;
            const move = ev => { bottomH.value = h0 + (y0 - ev.clientY); };
            const up = () => {
                window.removeEventListener('mousemove', move);
                window.removeEventListener('mouseup', up);
                persistLayout();
            };
            window.addEventListener('mousemove', move);
            window.addEventListener('mouseup', up);
        }

        function toggleLeft() { railVisible.value.left = !railVisible.value.left; persistLayout(); }
        function toggleRight() { railVisible.value.right = !railVisible.value.right; persistLayout(); }
        function toggleBottom() { railVisible.value.bottom = !railVisible.value.bottom; persistLayout(); }
        function hideZone(zone) { zoneVisible.value[zone] = false; menuFor.value = null; persistLayout(); }
        function hidePanel(zone) { railVisible.value[zone] = false; ctxZone.value = null; persistLayout(); }

        function closeMenusOnDocClick(e) {
            const inside = e.target.closest && (e.target.closest('.dz-menu') || e.target.closest('.top-menu-wrap')
                || e.target.closest('.dz-gearbtn') || e.target.closest('.dz-xbtn') || e.target.closest('.dz-ribtn'));
            if (inside) return;
            if (menuFor.value) menuFor.value = null;
            if (addMenu.value) addMenu.value = null;
            if (ctxMenu.value) ctxMenu.value = null;
        }

        const ctxMenu = ref(null);
        async function copyText(t) {
            const s = String(t ?? '');
            try {
                await navigator.clipboard.writeText(s);
            } catch (e) {
                try {
                    const ta = document.createElement('textarea');
                    ta.value = s;
                    ta.style.position = 'fixed';
                    ta.style.opacity = '0';
                    document.body.appendChild(ta);
                    ta.select();
                    document.execCommand('copy');
                    ta.remove();
                } catch (e2) {
                    showToast('Не скопировалось');
                    return;
                }
            }
            showToast('Скопировано');
        }
        function ctxItems(el) {
            const kind = el.dataset.ctx;
            if (kind === 'file') {
                const p = el.dataset.path || '';
                return [
                    {t: 'Открыть', run: () => openFile(p)},
                    {t: expanded(p) ? 'Свернуть строки' : 'Показать строки', run: () => toggleExpand(p)},
                    {t: 'Копировать путь', run: () => copyText(p)},
                    {t: 'В перевод', sel: selTranslateSet.value.has(p), run: () => toggleTranslate(p, !selTranslateSet.value.has(p))},
                    {t: 'В сборку', sel: selExportSet.value.has(p), run: () => toggleExport(p, !selExportSet.value.has(p))}
                ];
            }
            if (kind === 'phrase') {
                const id = el.dataset.id || '';
                const e = entryById.value.get(id) || null;
                const items = [{t: 'Открыть в редакторе', run: () => focusPhrase(id)}];
                if (e) {
                    items.push({t: 'Копировать оригинал', run: () => copyText(e.source || '')});
                    items.push({t: 'Копировать перевод', run: () => copyText(e.translation || '')});
                    items.push({t: 'Копировать id', run: () => copyText(e.id || '')});
                }
                return items;
            }
            if (kind === 'log') return [{t: 'Копировать журнал', run: () => copyText(logText.value)}];
            return null;
        }
        function onGlobalCtx(e) {
            if (!e || e.defaultPrevented) return;
            const t = e.target;
            if (t && t.closest && t.closest('input,textarea,select,[contenteditable="true"]')) return;
            const el = t && t.closest ? t.closest('[data-ctx]') : null;
            e.preventDefault();
            if (menuFor.value) menuFor.value = null;
            if (addMenu.value) addMenu.value = null;
            if (!el) {
                ctxMenu.value = null;
                return;
            }
            const items = ctxItems(el);
            if (!items || !items.length) {
                ctxMenu.value = null;
                return;
            }
            ctxMenu.value = {x: Math.min(e.clientX, window.innerWidth - 230), y: Math.min(e.clientY, window.innerHeight - items.length * 34 - 16), items};
        }
        function runCtx(it) {
            ctxMenu.value = null;
            if (it && it.run) it.run();
        }

        function toggleTheme() {
            theme.value = theme.value === 'dark' ? 'light' : 'dark';
            localStorage.setItem('theme', theme.value);
            document.documentElement.setAttribute('data-theme', theme.value);
        }

        // live job progress
        const job = ref(null);
        let jobTimer = null;
        const pendingPack = ref(false);
        const jobLog = ref(null);
        const jobStick = ref(true);
        const jobMainOutput = computed(() => ((job.value && job.value.output) || '')
            .split('\n').filter(l => !l.startsWith('[REASONING]')).join('\n'));
        const updateFailed = computed(() => !!job.value && job.value.action === 'update' && job.value.status === 'failed');
        function onJobScroll() {
            const el = jobLog.value;
            if (!el) return;
            jobStick.value = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
        }
        watch(() => job.value && job.value.output, () => {
            if (!jobStick.value) return;
            nextTick(() => {
                const el = jobLog.value;
                if (el) el.scrollTop = el.scrollHeight;
            });
        });
        watch(() => job.value && job.value.id, () => {
            jobStick.value = true;
        });

        async function downloadPackZip() {
            const r = await fetch(api.exportZipUrl(projectId.value));
            if (!r.ok) {
                const j = await r.json().catch(() => ({}));
                throw Error(j.error || 'Архив не готов');
            }
            const b = await r.blob();
            const u = URL.createObjectURL(b);
            const a = document.createElement('a');
            a.href = u;
            a.download = (projectName.value || projectId.value || 'export') + '.zip';
            a.click();
            URL.revokeObjectURL(u);
        }

        function jobActive() {
            return !!(job.value && (job.value.status === 'running' || job.value.status === 'queued'));
        }

        function pollJob(id) {
            if (jobTimer) clearInterval(jobTimer);
            let fails = 0;
            let lastLive = 0;
            jobTimer = setInterval(async () => {
                try {
                    const d = await api.jobGet(id);
                    fails = 0;
                    job.value = d;
                    if ((d.status === 'running' || d.status === 'queued') && Date.now() - lastLive > 15000) {
                        lastLive = Date.now();
                        loadFileTree();
                        try {
                            const ov = await api.overview(projectId.value);
                            if (ov.summary) summary.value = ov.summary;
                        } catch (e) {}
                    }
                    if (d.status !== 'running' && d.status !== 'queued') {
                        clearInterval(jobTimer);
                        jobTimer = null;
                        if (pendingPack.value && d.action === 'merge') {
                            pendingPack.value = false;
                            if (d.status === 'completed' || d.status === 'completed_with_errors') {
                                try {
                                    await downloadPackZip();
                                    logText.value += '\nПак Harmonia собран и скачан (.zip)';
                                } catch (e) {
                                    showToast(e.message);
                                    logText.value += '\n' + e.message;
                                }
                            } else {
                                logText.value += '\nСборка не завершена (' + d.status + ') — архив не скачан';
                            }
                        }
                        if (d.action === 'update') {
                            if (d.status === 'failed') updModal.value = true;
                            setTimeout(loadUpdateStatus, 400);
                        } else if (d.action === 'sync-sources') {
                            setTimeout(async () => {
                                await loadSourceStatus();
                                if (projectId.value) loadProject();
                                else scanSource();
                            }, 400);
                        } else if (d.action === 'merge') {
                            setTimeout(async () => {
                                await loadFileTree();
                                try {
                                    const ov = await api.overview(projectId.value);
                                    if (ov.summary) summary.value = ov.summary;
                                } catch (e) {}
                            }, 400);
                        } else {
                            setTimeout(loadProject, 400);
                        }
                    }
                } catch (e) {
                    if (job.value && job.value.action === 'update') onUpdateGone();
                    if (++fails >= 10) {
                        clearInterval(jobTimer);
                        jobTimer = null;
                        job.value = {...(job.value || {}), status: 'error', output: ((job.value && job.value.output) || '') + '\nНет ответа сервера (перезапуск?) — задача потеряна, запустите заново'};
                    }
                }
            }, 700);
        }

        let updWatch = null;
        function onUpdateGone() {
            updRestarting.value = true;
            if (updWatch) return;
            let updWatchFails = 0;
            updWatch = setInterval(async () => {
                try {
                    await api.version();
                    clearInterval(updWatch);
                    updWatch = null;
                    location.reload();
                } catch (e) {
                    if (++updWatchFails >= 40) {
                        clearInterval(updWatch);
                        updWatch = null;
                        updRestartDead.value = true;
                    }
                }
            }, 3000);
        }
        function startJob(d) {
            updRestarting.value = false;
            updRestartDead.value = false;
            if (updWatch) {
                clearInterval(updWatch);
                updWatch = null;
            }
            job.value = {status: d.status || 'running', action: d.action, output: ''};
            pollJob(d.id);
        }

        async function cancelJob() {
            pendingPack.value = false;
            if (!job.value) return;
            try {
                const d = await api.jobCancel(job.value.id);
                job.value = d;
            } catch (e) {
                logText.value += '\n' + e.message;
            }
        }

        function mergeEntries(items) {
            if (!items || !items.length) return;
            const entries = new Map((doc.value?.entries || []).map(e => [e.id, e]));
            for (const entry of items) if (entry && entry.id) entries.set(entry.id, entry);
            doc.value = {...(doc.value || {}), entries: [...entries.values()]};
            dataRev.value++;
        }

        function decorateRowGroups(groups, page) {
            return (groups || []).map((group, i) => {
                const pos = page * rowGroupPageSize + i;
                return {...group, pos, section: Math.floor(pos / 100)};
            });
        }

        async function loadFileRows(file, page = 0, q = '') {
            if (!file || !projectId.value) return null;
            const cleanPage = Math.max(0, page | 0);
            const cleanQ = String(q || '').trim();
            const previousPageIds = new Set((fileRows.value.groups || []).flatMap(g =>
                (g.cells || []).map(cell => cell.id)));
            const request = ++rowRequest;
            fileRows.value = {file, q: cleanQ, page: cleanPage, groups: [], totalGroups: 0, loading: true};
            try {
                const d = await api.rowsPage(projectId.value, {
                    file, offset: cleanPage * rowGroupPageSize, limit: rowGroupPageSize, q: cleanQ
                });
                if (request !== rowRequest) return null;
                const groups = decorateRowGroups(d.groups || [], cleanPage);
                const pageEntries = groups.flatMap(g => g.cells || []);
                const entries = new Map();
                for (const entry of (doc.value?.entries || [])) {
                    if (entry && entry.id && !previousPageIds.has(entry.id)) entries.set(entry.id, entry);
                }
                for (const entry of pageEntries) {
                    if (entry && entry.id) entries.set(entry.id, entry);
                }
                doc.value = {...(doc.value || {}), entries: [...entries.values()]};
                dataRev.value++;
                fileRows.value = {file, q: cleanQ, page: cleanPage, groups,
                    totalGroups: Number(d.total_groups || 0), loading: false};
                return fileRows.value;
            } catch (e) {
                if (request === rowRequest) fileRows.value = {file, q: cleanQ, page: cleanPage,
                    groups: [], totalGroups: 0, loading: false};
                throw e;
            }
        }

        async function loadFilePreview(file) {
            if (!file || !projectId.value || filePreviewCache.has(file)) return;
            const d = await api.entries(projectId.value, {file}, {limit: 100});
            filePreviewCache.set(file, {rows: d.entries || [], total: d.total || 0});
            dataRev.value++;
        }

        async function ensureEntry(id) {
            if (!id || !projectId.value) return null;
            const e = (doc.value?.entries || []).find(x => x.id === id);
            if (e) return e;
            const loaded = await api.entryByCell(projectId.value, id);
            if (loaded && loaded.id) mergeEntries([loaded]);
            return loaded || null;
        }

        async function loadProject() {
            projectLoading.value = true;
            try {
                const t0 = performance.now();
                const ov = await api.overview(projectId.value);
                const t1 = Math.round(performance.now() - t0);
                doc.value = {files: [], entries: []};
                fileRows.value = {file: '', q: '', page: 0, groups: [], totalGroups: 0, loading: false};
                filePreviewCache.clear();
                rowRequest++;
                doc.value._loadMs = t1;
                summary.value = ov.summary || null;
                loadSelection();
                dataRev.value++;
                if (ov.input_root) root.value = ov.input_root;
                if (ov.name) projectName.value = ov.name;
                logText.value += '\nПроект загружен: ' + projectId.value + ' (' + (ov.summary?.entries ?? 0) + ' фраз, ' + doc.value._loadMs + ' мс сеть+разбор)';
                if (!pack.value) await loadPack();
                expandedDirs.value = {};
                await loadFileTree();
                if (focusFileFilter.value && leftMode.value === 'phrases') {
                    await loadFileRows(focusFileFilter.value, 0, phraseSearchQ.value);
                }
            } catch (e) {
                logText.value += '\nОшибка: ' + e.message;
            }
            projectLoading.value = false;
        }

        async function scanSource() {
            sourceLoading.value = true;
            sourceError.value = '';
            sourceFiles.value = [];
            try {
                const f0 = performance.now();
                const d = await api.sourceFiles(root.value);
                sourceFiles.value = d.files || [];
                logText.value += '\nИсходники: ' + sourceFiles.value.length + ' файлов (' + Math.round(performance.now() - f0) + ' мс)';
                rg.value = 'активен';
            } catch (e) {
                sourceError.value = e.message || 'не удалось загрузить файлы';
                logText.value += '\n' + e.message;
            }
            sourceLoading.value = false;
        }

        const entryById = computed(() => {
            const m = new Map();
            for (const e of (doc.value?.entries || [])) m.set(e.id, e);
            return m;
        });
        function scopeFile() { return focusFileFilter.value || ''; }
        const leftMode = ref('files');
        function progPct(f) {
            if (!f || !f.total) return 0;
            return Math.round(f.translated / f.total * 100);
        }
        function dirPct(d) {
            if (!d || !d.total) return 0;
            return Math.round(d.done / d.total * 100);
        }
        function fmtNum(n) { return Number(n || 0).toLocaleString('ru-RU'); }
        function pct1(done, total) {
            if (!total) return '0%';
            const p = done / total * 100;
            return (p >= 9.95 ? String(Math.round(p)) : p.toFixed(1).replace('.', ',')) + '%';
        }
        function baseName(p) {
            const s = String(p || '');
            const i = s.lastIndexOf('/');
            return i < 0 ? s : s.slice(i + 1);
        }
        function fileUn(f) { return (f.total || 0) - (f.translated || 0); }
        function needsWork(e) { return e.status !== 'no_translation_required' && (!(e.translation && e.translation.trim()) || e.status === 'stale'); }
        const projTotal = computed(() => summary.value?.entries ?? 0);
        const projDone = computed(() => summary.value?.translated ?? 0);
        const expandedFiles = ref({});
        function expanded(f) { return !!expandedFiles.value[f]; }
        function toggleExpand(f) {
            expandedFiles.value[f] = !expandedFiles.value[f];
            if (expandedFiles.value[f]) loadFilePreview(f).catch(e => logText.value += '\n' + e.message);
        }
        function filePhraseGroups(f) {
            const groups = new Map();
            for (const e of (filePhrases(f).rows || [])) {
                let g = groups.get(e.row_index);
                if (!g) { g = {row: e.row_index, key: e.row_key, cells: [], un: 0}; groups.set(e.row_index, g); }
                g.cells.push(e);
                if (needsWork(e)) g.un++;
            }
            const out = [...groups.values()].sort((a, b) => a.row - b.row);
            out.forEach(g => g.cells.sort((a, b) => a.column_index - b.column_index));
            return out;
        }
        function filePhrases(f) {
            dataRev.value;
            const preview = filePreviewCache.get(f);
            if (!preview) return {rows: [], total: 0};
            const un = [], done = [];
            for (const e of preview.rows || []) (needsWork(e) ? un : done).push(e);
            return {rows: un.concat(done).slice(0, 100), total: preview.total || 0};
        }
        const phrasePage = ref(0);
        const phraseSearchQ = ref('');
        const fileRowGroups = computed(() => {
            return fileRows.value.file === scopeFile() ? fileRows.value.groups : [];
        });
        const rowGroupsPaged = computed(() => fileRowGroups.value);
        const rowGroupPages = computed(() => Math.ceil(fileRows.value.totalGroups / rowGroupPageSize) || 1);
        const rowContext = computed(() => ({
            file: fileRows.value.file,
            q: fileRows.value.q,
            page: fileRows.value.page,
            pageSize: rowGroupPageSize,
            totalGroups: fileRows.value.totalGroups,
            groups: fileRows.value.groups
        }));

        async function setRowGroupPage(page) {
            const f = scopeFile();
            if (!f) return;
            phrasePage.value = Math.max(0, Math.min(rowGroupPages.value - 1, page));
            try {
                await loadFileRows(f, phrasePage.value, phraseSearchQ.value);
            } catch (e) {
                logText.value += '\n' + e.message;
            }
        }

        function setPhraseSearch(value) {
            phraseSearchQ.value = value || '';
            phrasePage.value = 0;
            clearTimeout(phraseSearchTimer);
            phraseSearchTimer = setTimeout(() => {
                const f = scopeFile();
                if (!f) return;
                loadFileRows(f, 0, phraseSearchQ.value).catch(e => logText.value += '\n' + e.message);
            }, 250);
        }

        async function openFile(f) {
            focusFileFilter.value = f;
            leftMode.value = 'phrases';
            phrasePage.value = 0;
            phraseSearchQ.value = '';
            const page = await loadFileRows(f, 0, '');
            let first = null;
            try {
                first = await api.rowsNext(projectId.value, {file: f, afterRow: -1, afterCol: -1});
                if (first) mergeEntries([first]);
            } catch (e) {
                logText.value += '\n' + e.message;
            }
            const fallback = page && page.groups.length ? page.groups[0].cells[0] : null;
            if (first || fallback) focusId.value = (first || fallback).id;
        }

        function backToFiles() {
            leftMode.value = 'files';
            focusFileFilter.value = '';
            phraseSearchQ.value = '';
        }

        async function focusPhrase(id) {
            try {
                const e = await ensureEntry(id);
                if (!e) return;
                focusId.value = id;
                const f = e.file || ((entryById.value.get(id) || {}).file || '');
                if (f && leftMode.value === 'files') {
                    await revealInFiles(f, false, id);
                } else if (f && leftMode.value === 'phrases'
                        && (!fileRows.value.groups || !fileRows.value.groups.some(g => (g.cells || []).some(c => c.id === id)))) {
                    const pos = await api.rowsPosition(projectId.value, {file: f, rowIndex: e.row_index, q: phraseSearchQ.value});
                    phrasePage.value = Math.floor(Number(pos || 0) / rowGroupPageSize);
                    await loadFileRows(f, phrasePage.value, phraseSearchQ.value);
                }
            } catch (err) {
                logText.value += '\n' + (err.message || 'Не удалось открыть фразу');
            }
        }

        const tab = ref('translate');
        const focusId = ref('');
        const focusFileFilter = ref('');
        const editorRef = ref(null);
        const deltaRef = ref(null);
        const followFiles = ref((() => { try { return localStorage.getItem('hs-follow') !== 'off'; } catch (e) { return true; } })());
        const revealFile = ref('');
        let lastReveal = '';
        let revealTimer = null;
        function toggleFollow() {
            followFiles.value = !followFiles.value;
            try { localStorage.setItem('hs-follow', followFiles.value ? 'on' : 'off'); } catch (e) {}
        }
        async function revealInFiles(file, force, phraseId) {
            if (!file || !projectId.value) return;
            if (leftMode.value !== 'files') {
                if (!force) return;
                leftMode.value = 'files';
            }
            const known = (fileTree.value || []).find(f => f.path === file);
            if (!known) return;
            if (fileSearchQ.value) fileSearchQ.value = '';
            if (hideEmpty.value && (known.total || 0) === 0) hideEmpty.value = false;
            if (fileHideReady.value && (known.total || 0) <= (known.translated || 0)) fileHideReady.value = false;
            const parts = file.split('/');
            for (let i = 1; i < parts.length; i++) {
                expandedDirs.value[parts.slice(0, i).join('/')] = true;
            }
            if (!expanded(file)) toggleExpand(file);
            lastReveal = file;
            revealFile.value = file;
            clearTimeout(revealTimer);
            revealTimer = setTimeout(() => { if (revealFile.value === file) revealFile.value = ''; }, 4000);
            await nextTick();
            try {
                const q = phraseId
                    ? '.filetree .tree-phrases [data-id="' + CSS.escape(phraseId) + '"]'
                    : '.filetree [data-fp="' + CSS.escape(file) + '"]';
                const el = document.querySelector(q);
                if (el && el.scrollIntoView) {
                    el.scrollIntoView({block: 'center'});
                    if (phraseId) {
                        el.classList.add('flash');
                        setTimeout(() => { try { el.classList.remove('flash'); } catch (e2) {} }, 2400);
                    }
                } else if (phraseId) {
                    const fel = document.querySelector('.filetree [data-fp="' + CSS.escape(file) + '"]');
                    if (fel && fel.scrollIntoView) fel.scrollIntoView({block: 'center'});
                }
            } catch (e) {}
        }
        function onEditorFile(file) {
            if (!followFiles.value || !file || file === lastReveal) return;
            revealInFiles(file, false);
        }
        async function onRevealFile(file) {
            if (!file) {
                showToast('Нет активного файла');
                return;
            }
            await revealInFiles(file, true);
        }
        const tagFilter = ref('');
        const csvRequest = ref(null);
        const previewPinRequest = ref(null);
        function openPreview(f) {
            if (!f) return;
            previewPinRequest.value = {file: f, n: Date.now()};
        }
        function insertTagToEditor(text) {
            const ed = editorRef.value;
            if (!ed || !ed.current) {
                showToast('Сначала выберите фразу в редакторе');
                return;
            }
            ed.insertTag(text);
        }

        // command palette (Ctrl+K): files, phrases, commands
        const paletteOpen = ref(false);
        const paletteQ = ref('');
        const paletteIdx = ref(0);
        const paletteInput = ref(null);

        function fuzzyScore(q, s) {
            const a = String(s || '').toLowerCase(), b = String(q || '').toLowerCase().trim();
            if (!b) return 0;
            if (a.startsWith(b)) return 0;
            const at = a.indexOf(b);
            if (at >= 0) return 1 + at / 1000;
            let qi = 0, gaps = 0, last = -1;
            for (let k = 0; k < a.length && qi < b.length; k++) {
                if (a[k] === b[qi]) {
                    if (last >= 0) gaps += k - last - 1;
                    last = k;
                    qi++;
                }
            }
            return qi >= b.length ? 2 + gaps / 100 : Infinity;
        }

        const paletteResults = computed(() => {
            const q = paletteQ.value.trim();
            const cmds = [
                {t: 'Gemini: перевести всё', hint: 'команда', run: () => run('gemini')},
                {t: 'Собрать CSV', hint: 'команда', run: () => run('merge')},
                {t: 'Левая панель: скрыть/показать', hint: 'команда', run: toggleLeft},
                {t: 'Правая панель: скрыть/показать', hint: 'команда', run: toggleRight},
                {t: 'Нижняя панель: скрыть/показать', hint: 'команда', run: toggleBottom},
                {t: 'Сбросить раскладку', hint: 'команда', run: resetLayout},
                ...VIEW_IDS.map(v => ({t: 'Панель: ' + VIEWS[v].title, hint: 'панель', run: () => gotoView(v)}))
            ];
            if (!q) return cmds.map((c, i) => ({...c, key: 'c' + i}));
            const out = [];
            cmds.forEach((c, i) => {
                const s = fuzzyScore(q, c.t);
                if (s !== Infinity) out.push({...c, key: 'c' + i, score: s});
            });
            (fileTree.value || []).forEach(f => {
                const s = fuzzyScore(q, f.path);
                if (s !== Infinity) out.push({t: f.path, hint: 'файл', key: 'f' + f.path, score: s + 0.01, run: () => openFile(f.path)});
            });
            palFiles.value.forEach(path => {
                out.push({t: path, hint: 'файл', key: 'f' + path, score: 0.02, run: () => openFile(path)});
            });
            return out.sort((a, b) => a.score - b.score).slice(0, 25);
        });
        const palFiles = ref([]);
        let palTimer = null;
        watch(paletteQ, () => {
            clearTimeout(palTimer);
            const q = paletteQ.value.trim();
            palFiles.value = [];
            if (!q || !projectId.value) return;
            palTimer = setTimeout(async () => {
                try {
                    const d = await api.files(projectId.value, {q}, {limit: 25});
                    palFiles.value = (d.files || []).map(f => f.path);
                } catch (e) { palFiles.value = []; }
            }, 300);
        });

        function openPalette() {
            paletteOpen.value = true;
            paletteQ.value = '';
            paletteIdx.value = 0;
            setTimeout(() => { if (paletteInput.value) paletteInput.value.focus(); }, 0);
        }

        function runPalette(it) {
            paletteOpen.value = false;
            if (it && it.run) it.run();
        }

        function onPaletteKey(e) {
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                paletteIdx.value = Math.min(paletteIdx.value + 1, paletteResults.value.length - 1);
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                paletteIdx.value = Math.max(0, paletteIdx.value - 1);
            } else if (e.key === 'Enter') {
                const it = paletteResults.value[paletteIdx.value];
                if (it) runPalette(it);
            }
        }

        onMounted(() => {
            window.addEventListener('keydown', e => {
                if ((e.ctrlKey || e.metaKey) && !e.shiftKey && !e.altKey && e.code === 'KeyK') { e.preventDefault(); openPalette(); }
                else if (e.key === 'Escape' && paletteOpen.value) paletteOpen.value = false;
            });
        });

        async function focusFile(f) {
            focusFileFilter.value = f || '';
            tab.value = 'translate';
            if (f) {
                leftMode.value = 'phrases';
                phrasePage.value = 0;
                phraseSearchQ.value = '';
                const page = await loadFileRows(f, 0, '');
                let first = null;
                try {
                    first = await api.rowsNext(projectId.value, {file: f, afterRow: -1, afterCol: -1});
                    if (first) mergeEntries([first]);
                } catch (e) {
                    logText.value += '\n' + e.message;
                }
                const fallback = page && page.groups.length ? page.groups[0].cells[0] : null;
                if (first || fallback) focusId.value = (first || fallback).id;
            }
        }

        async function editorRowNext(file, afterRow, afterCol, q) {
            const entry = await api.rowsNext(projectId.value, {file, afterRow, afterCol, q});
            if (!entry || !entry.id) return entry;
            mergeEntries([entry]);
            const inPage = fileRows.value.file === file
                && (fileRows.value.groups || []).some(g => (g.cells || []).some(c => c.id === entry.id));
            if (!inPage) {
                const pos = await api.rowsPosition(projectId.value, {file, rowIndex: entry.row_index, q});
                const page = Math.floor(Number(pos || 0) / rowGroupPageSize);
                phrasePage.value = page;
                await loadFileRows(file, page, q || '');
            }
            return entry;
        }

        async function editorLoadRowPage(file, page, q) {
            return loadFileRows(file, page, q);
        }

        function onNavigate(f) {
            focusFile(f);
        }

        // search
        const searchQ = ref('');
        const matches = ref([]);
        const searchLoading = ref(false);

        async function doSearch() {
            if (!searchQ.value.trim()) return;
            searchLoading.value = true;
            try {
                const d = await api.entries(projectId.value, {q: searchQ.value.trim()}, {limit: 50});
                matches.value = (d.entries || []).map(e => ({
                    id: e.id,
                    file: e.file || '',
                    row_key: e.row_key || '',
                    source: e.source || '',
                    translation: e.translation || ''
                }));
            } catch (e) {
                logText.value += '\n' + e.message;
            }
            searchLoading.value = false;
        }

        async function openSearchResult(m) {
            if (!m || !m.id) {
                logText.value += '\nФраза не найдена';
                return;
            }
            try {
                const loaded = await ensureEntry(m.id);
                const found = loaded || entryById.value.get(m.id) || null;
                const f = (found && found.file) || m.file || '';
                let targetPage = 0;
                if (f && found) {
                    const pos = await api.rowsPosition(projectId.value, {file: f, rowIndex: found.row_index});
                    targetPage = Math.floor(Number(pos || 0) / rowGroupPageSize);
                    await loadFileRows(f, targetPage, '');
                }
                tab.value = 'translate';
                focusFileFilter.value = f;
                leftMode.value = 'phrases';
                phrasePage.value = targetPage;
                phraseSearchQ.value = '';
                focusId.value = m.id;
                await nextTick();
                try {
                    const el = document.querySelector('.dock.left [data-id="' + CSS.escape(m.id) + '"]');
                    if (el && el.scrollIntoView) {
                        el.scrollIntoView({block: 'center'});
                        el.classList.add('flash');
                        setTimeout(() => { try { el.classList.remove('flash'); } catch (e2) {} }, 2400);
                    }
                } catch (e) {}
                logText.value += '\nПереход по поиску: ' + (f ? f + ', ' : '') + m.id;
            } catch (e) {
                logText.value += '\n' + (e.message || 'Не удалось открыть результат поиска');
            }
        }

        async function openConflict(c) {
            if (!c || !c.cell_id) return;
            await ensureEntry(c.cell_id);
            const ed = editorRef.value;
            if (!ed || !ed.openConflictTab) {
                showToast('Редактор недоступен');
                return;
            }
            ed.openConflictTab(c);
            logText.value += '\nКонфликт дельты: ' + c.cell_id;
        }

        async function onResolveConflict({id, mode, translation, status}) {
            try {
                if (mode === 'ours') {
                    if (editorRef.value && editorRef.value.closeConflictTab) editorRef.value.closeConflictTab(id);
                    showToast('Оставлен наш вариант');
                    return;
                }
                const found = entryById.value.get(id) || null;
                const uuid = (found && found.uuid) || (await api.entryByCell(projectId.value, id)).uuid;
                await onSave({id, uuid, translation, status});
                if (editorRef.value && editorRef.value.closeConflictTab) editorRef.value.closeConflictTab(id);
                showToast('Взято из дельты — применено');
                if (deltaRef.value && deltaRef.value.doPreview) deltaRef.value.doPreview();
            } catch (e) {
                showToast(e.message);
            }
        }

        // extract (right panel)
        async function runExtract(force) {
            try {
                const d = await api.startJob({
                    action: 'extract',
                    projectId: projectId.value,
                    root: root.value,
                    files: [],
                    force: !!force
                });
                logText.value += '\nЗапущено: обновление данных' + (force ? ' (все таблицы)' : ' (только изменённые файлы)');
                startJob(d);
            } catch (e) {
                logText.value += '\n' + e.message;
            }
        }

        async function run(action, extra) {
            if (action === 'gemini' && !geminiStatus.value.configured) {
                logText.value += '\nGemini недоступен: задайте ключ в настройках (меню) или переменной GEMINI_API_KEY';
                return;
            }
            try {
                const d = await api.startJob({
                    action,
                    projectId: projectId.value,
                    root: root.value,
                    files: action === 'gemini' || action === 'openrouter' ? [...selTranslate.value] : action === 'merge' ? [...selExport.value] : [],
                    model: (action === 'gemini' || action === 'openrouter') && extra && extra.model ? extra.model : undefined,
                    reasoning: (action === 'gemini' || action === 'openrouter') && extra && extra.reasoning ? extra.reasoning : undefined
                });
                logText.value += '\nЗапущено: ' + ({extract:'обновление данных',gemini:'перевод Gemini',openrouter:'перевод OpenRouter',merge:'сборка CSV'}[action] || action);
                startJob(d);
            } catch (e) {
                pendingPack.value = false;
                logText.value += '\n' + e.message;
            }
        }

        async function buildPack() {
            if (jobActive()) {
                showToast('Дождитесь завершения текущей задачи');
                return;
            }
            await run('merge');
        }

        async function buildAndDownloadPack() {
            if (jobActive()) {
                showToast('Дождитесь завершения текущей задачи');
                return;
            }
            pendingPack.value = true;
            await run('merge');
        }

        function toggleTranslate(f, v) {
            const s = new Set(selTranslate.value);
            v ? s.add(f) : s.delete(f);
            selTranslate.value = [...s];
            saveSelection();
        }

        function selTranslateVisible(v, list) {
            const s = new Set(selTranslate.value);
            (list || sourceFiles.value).forEach(f => v ? s.add(f) : s.delete(f));
            selTranslate.value = [...s];
            saveSelection();
        }
        function clearTranslateSel() {
            selTranslate.value = [];
            saveSelection();
        }

        function toggleExport(f, v) {
            const s = new Set(selExport.value);
            v ? s.add(f) : s.delete(f);
            selExport.value = [...s];
            saveSelection();
        }

        function clearExportScope() {
            selExport.value = [];
            saveSelection();
        }

        async function runTranslate(provider, model, reasoning) {
            if (jobActive()) {
                showToast('Дождитесь завершения текущей задачи');
                return;
            }
            if (!selTranslate.value.length) {
                showToast('Выберите таблицы для перевода');
                return;
            }
            if (provider === 'openrouter' && !geminiStatus.value.openrouterConfigured) {
                logText.value += '\nOpenRouter недоступен: задайте ключ в настройках (меню) или переменной OPENROUTER_API_KEY';
                return;
            }
            await run(provider === 'openrouter' ? 'openrouter' : 'gemini', {model, reasoning});
        }

        async function onSave({id, uuid, translation, status}) {
            try {
                const d = await api.patchEntry(projectId.value, uuid, translation, status);
                mergeEntries([d.entry]);
                if (d.summary) summary.value = d.summary;
                refreshStats();
                if (d.entry && fileRows.value.file === d.entry.file) {
                    await loadFileRows(fileRows.value.file, fileRows.value.page, fileRows.value.q);
                }
                if (editorRef.value && editorRef.value.noteChanged) editorRef.value.noteChanged();
                (d.warnings || []).forEach(w => logText.value += '\n[Тег] ' + w);
            } catch (e) {
                showToast(e.message);
                logText.value += '\n' + e.message;
            }
        }

        async function onConfirm(p) {
            projectId.value = p;
            showPicker.value = false;
            pack.value = null;
            await loadProject();
            await scanSource();
            if (!summary.value || !summary.value.entries) {
                logText.value += '\nНовый проект: извлекаю все строки игры…';
                await runExtract();
            }
        }

        const toast = ref('');

        function showToast(m) {
            toast.value = m;
            setTimeout(() => toast.value = '', 3000);
        }

        // pack settings (Harmonia manifest.json)
        const pack = ref(null);
        const packErrors = ref([]);
        const packManifest = ref(null);
        const packMsg = ref('');
        const packCompat = ref('');
        const packLangs = ref('');

        function blankPack() {
            return {pack_id: '', translation_version: '', game_version: '', compatible_game_versions: [], vendor_id: '', vendor_name: '', vendor_url: '', vendor_contact: '', authors: [], languages: ['ru'], title: '', description: '', changelog: '', homepage: '', license: '', min_plugin_version: ''};
        }

        function applyPack(d) {
            pack.value = Object.assign(blankPack(), d.pack || {});
            if (!pack.value.game_version && sourceStatus.value.gameVersion) pack.value.game_version = sourceStatus.value.gameVersion;
            if (!Array.isArray(pack.value.authors)) pack.value.authors = [];
            packCompat.value = (pack.value.compatible_game_versions || []).join(', ');
            packLangs.value = (pack.value.languages || []).join(', ');
            packErrors.value = d.errors || [];
            packManifest.value = d.manifest || null;
        }

        async function loadPack() {
            try {
                applyPack(await api.getPack(projectId.value));
            } catch (e) {
                packMsg.value = e.message;
            }
        }

        function packAddAuthor() {
            if (!pack.value) return;
            pack.value.authors.push({name: '', role: ''});
        }

        function packDelAuthor(i) {
            if (!pack.value) return;
            pack.value.authors.splice(i, 1);
        }

        async function savePack() {
            if (!pack.value) return;
            packMsg.value = '';
            const p = Object.assign({}, pack.value, {
                compatible_game_versions: packCompat.value.split(',').map(s => s.trim()).filter(Boolean),
                languages: packLangs.value.split(',').map(s => s.trim()).filter(Boolean),
                authors: (pack.value.authors || []).filter(a => a && (a.name || '').trim()).map(a => ({name: (a.name || '').trim(), role: (a.role || '').trim()}))
            });
            try {
                const d = await api.savePack(projectId.value, p);
                applyPack(d);
                packMsg.value = (d.errors && d.errors.length) ? 'Сохранено, но манифест невалиден' : 'Сохранено';
                logText.value += '\nНастройки пака сохранены';
            } catch (e) {
                packMsg.value = e.message;
            }
        }

        function esc(s) {
            return String(s).replace(/[&<>]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;'}[c]));
        }

        onMounted(() => {
            document.addEventListener('click', closeMenusOnDocClick, true);
            document.addEventListener('contextmenu', onGlobalCtx);
            loadSourceStatus();
            loadUpdateStatus();
            setInterval(loadUpdateStatus, 3600000);
            if (projectId.value && !showPicker.value) {
                loadProject().then(scanSource);
            }
        });

        return {
            projectId,
            projectName,
            root,
            showPicker,
            doc,
            summary,
            projectLoading,
            sourceFiles,
            sourceLoading,
            sourceError,
            selTranslate,
            trEstimate,
            trPendingMap,
            trPendingReady,
            selExport,
            selTranslateSet,
            selExportSet,
            logText,
            badge,
            rg,
            theme,
            toggleTheme,
            layoutStyle,
            zoneStyle,
            zoneShown,
            startResize,
            startResizeY,
            toggleLeft,
            toggleRight,
            toggleBottom,
            hideZone,
            zoneVisible,
            railVisible,
            narrow,
            VIEWS,
            layout,
            active,
            hiddenViews,
            menuFor,
            addMenu,
            openAddMenu,
            dropPos,
            activateView,
            toggleView,
            openZoneMenu,
            ctxZone,
            hidePanel,
            hideWidget,
            gotoView,
            addView,
            closeTab,
            resetLayout,
            onTabDragStart,
            onTabDragOver,
            onDrop,
            onDropOnTab,
            onDragEnd,
            dropClass,
            setHost,
            hostEl,
            activeTitle,
            paletteOpen,
            paletteQ,
            paletteIdx,
            paletteInput,
            paletteResults,
            openPalette,
            runPalette,
            onPaletteKey,
            job,
            dataRev,
            treeRows,
            treeFileCount,
            treeReadyCount,
            treeEmptyCount,
            fileTreeLoading,
            fileSearchQ,
            fileHideReady,
            toggleHideReady,
            hideEmpty,
            toggleHideEmpty,
            fileSort,
            setSort,
            toggleDir,
            expandedFiles,
            expanded,
            toggleExpand,
            filePhrases,
            filePhraseGroups,
            fileRows,
            fileUn,
            projTotal,
            projDone,
            progPct,
            dirPct,
            fmtNum,
            pct1,
            baseName,
            leftMode,
            entryById,
            openFile,
            backToFiles,
            rowGroupsPaged,
            rowGroupPages,
            fileRowGroups,
            phrasePage,
            phraseSearchQ,
            setRowGroupPage,
            setPhraseSearch,
            rowContext,
            focusPhrase,
            tab,
            focusId,
            focusFileFilter,
            editorRef,
            deltaRef,
            tagFilter,
            insertTagToEditor,
            openPreview,
            previewPinRequest,
            csvRequest,
            focusFile,
            editorRowNext,
            editorLoadRowPage,
            onNavigate,
            matches,
            searchQ,
            searchLoading,
            doSearch,
            openSearchResult,
            openConflict,
            onResolveConflict,
            followFiles,
            toggleFollow,
            revealFile,
            onEditorFile,
            onRevealFile,
            ctxMenu,
            runCtx,
            jobLog,
            jobMainOutput,
            onJobScroll,
            run,
            buildPack,
            buildAndDownloadPack,
            runTranslate,
            toggleTranslate,
            selTranslateVisible,
            clearTranslateSel,
            toggleExport,
            clearExportScope,
            runExtract,
            cancelJob,
            geminiStatus,
            sourceStatus,
            sourceLabel,
            showSettings,
            settingsSection,
            openSettings,
            loadSourceStatus,
            loadGeminiStatus,
            aiTitle,
            upd,
            updModal,
            updRestarting,
            updRestartDead,
            updLogBusy,
            updateFailed,
            updLabel,
            updTitle,
            loadUpdateStatus,
            runUpdate,
            copyUpdateLog,
            onSave,
            onConfirm,
            scanSource,
            loadProject,
            toast,
            showToast,
            pack,
            packErrors,
            packManifest,
            packMsg,
            packCompat,
            packLangs,
            loadPack,
            savePack,
            packAddAuthor,
            packDelAuthor,
        };
    },
    template: `
  <Picker v-if="showPicker" v-model="projectId" :default-root="root" @confirm="onConfirm"/>
  <Settings v-if="showSettings" :initial="settingsSection" :forced="!sourceStatus.configured" @close="showSettings=false" @changed="loadSourceStatus();loadGeminiStatus()"/>
  <div class="topbar ide" v-else>
    <div class="tb-group tb-left">
      <div class="top-menu-wrap"><button class="ghost icon-btn" @click="menuFor=menuFor==='burger'?null:'burger'" title="Меню"><svg class="icon" viewBox="0 0 24 24"><path d="M4 6h16M4 12h16M4 18h16"/></svg></button><div v-if="menuFor==='burger'" class="dz-menu top-menu left"><div class="dz-menu-h">Проект</div><div class="dz-menu-i" @click="showPicker=true;menuFor=null">Сменить проект…</div><div class="dz-menu-i" @click="gotoView('summary');menuFor=null">Сводка перевода</div><div class="dz-menu-i" @click="runExtract();menuFor=null">Обновить данные игры</div><div class="dz-menu-i" @click="openSettings();menuFor=null">Настройки…</div><div class="dz-menu-i dim" @click="runExtract(true);menuFor=null">Обновить всё принудительно</div><div class="dz-menu-i dim" @click="resetLayout()">Сбросить раскладку</div></div></div>
      <div class="brand">
        <img class="logo" src="/img/yuki-icon.png" alt="Yuki">
        <span>Harmonia Suite</span>
      </div>
      <button class="proj-pill" @click="showPicker=true" :title="projectId"><span class="proj-name">{{projectName||projectId||'—'}}</span><svg class="icon" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg></button>
    </div>
    <div class="toolbar tb-group tb-right">
      <div class="top-menu-wrap"><button class="ghost icon-btn" @click="menuFor=menuFor==='view'?null:'view'" title="Вид: панели и раскладка"><svg class="icon" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16M15 4v16"/></svg></button><div v-if="menuFor==='view'" class="dz-menu top-menu"><div class="dz-menu-h">Панели</div><div class="dz-menu-i" :class="{off:!railVisible.left}" @click="toggleLeft()">Левая</div><div class="dz-menu-i" :class="{off:!railVisible.right}" @click="toggleRight()">Правая</div><div class="dz-menu-i" :class="{off:!railVisible.bottom}" @click="toggleBottom()">Нижняя</div><div class="dz-menu-i dim" @click="resetLayout()">Сбросить раскладку</div></div></div>
      <button class="ghost icon-btn" @click="openPalette" title="Быстрый переход (Ctrl+K)"><svg class="icon" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg></button>
      <button class="theme-btn icon-btn" @click="toggleTheme" :title="theme==='dark'?'Светлая тема':'Тёмная тема'"><svg class="icon" viewBox="0 0 24 24"><circle cx="12" cy="12" r="4.5"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg></button>
      <button class="ghost icon-btn" @click="showPicker=true" title="Сменить проект"><svg class="icon" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"/></svg></button>
    </div>
  </div>

  <div v-if="job" class="jobbar" :class="job.status">
    <div class="job-row"><span class="job-spin" v-if="job.status==='running'"></span><b>{{ {extract:'Обновление данных',gemini:'Перевод Gemini',openrouter:'Перевод OpenRouter',merge:'Сборка CSV','sync-sources':'Синхронизация источников',update:'Обновление приложения'}[job.action] || job.action }}</b><span class="muted" style="margin-left:8px">{{job.status==='running'?'выполняется…':job.status==='queued'?'в очереди…':job.status==='completed'?'готово':job.status==='cancelled'?'отменено':'ошибка'}}</span><button v-if="job.status==='running'||job.status==='queued'" class="ghost sm" style="margin-left:auto" @click="cancelJob" title="Остановить">Отмена</button><button v-if="job.status!=='running'&&job.status!=='queued'" class="ghost icon-btn sm" style="margin-left:auto" @click="job=null" title="Закрыть"><svg class="icon" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>
    <pre ref="jobLog" class="job-log" @scroll="onJobScroll">{{jobMainOutput}}</pre>
  </div>

  <div class="layout" v-if="!showPicker" :style="layoutStyle">
    <div v-if="job&&job.action==='extract'&&job.status==='running'" class="load-veil"></div>
    <div v-if="projectLoading" class="load-veil"><span class="job-spin"></span><span>Загрузка проекта…</span></div>
    <section class="dock left" v-show="railVisible.left">
      <div class="hsplit right" @mousedown="e=>startResize('left',e)" title="Потяните, чтобы изменить ширину"></div>
      <div class="dz-rail" @contextmenu.prevent="openZoneMenu('left',$event)" @dragover.prevent="e=>onTabDragOver('left',layout.left.length,e)" @drop="e=>onDrop('left',e)" :class="{'drop-end':dropPos&&dropPos.zone==='left'&&dropPos.index===layout.left.length}">
        <button v-for="(v,i) in layout.left" :key="v" class="dz-ribtn" :class="[{active:active.left===v},dropClass('left',i)]" draggable="true" @dragstart="e=>onTabDragStart('left',v,e)" @dragend="onDragEnd" @dragover.prevent="e=>onTabDragOver('left',i,e)" @drop.stop="e=>onDropOnTab('left',i,e)" @click="toggleView('left',v)" @contextmenu.prevent.stop="openZoneMenu('left',$event,v)" :title="VIEWS[v].title"><span class="dz-ic" v-html="VIEWS[v].icon"></span></button>
        <span class="grow"></span>
        <button class="dz-ribtn dz-add" @click="openAddMenu('left',$event)" title="Добавить панель">+</button>
      </div>
      <div class="dz-main" v-show="zoneShown('left')">
      <div class="dz-head"><span>{{activeTitle('left')}}</span><span class="grow"></span><button class="dz-gearbtn" @click="menuFor=menuFor==='head:left'?null:'head:left'" title="Настройки зоны"><svg class="icon" viewBox="0 0 24 24"><path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3"/><path d="M1 14h6M9 8h6M17 16h6"/></svg></button><button class="dz-xbtn" @click="active.left&&closeTab('left',active.left)" title="Убрать панель">×</button><div v-if="menuFor==='head:left'" class="dz-menu head-menu"><div class="dz-menu-i" @click="hideZone('left')">Свернуть панель</div><div class="dz-menu-i" @click="openAddMenu('left',$event)">Добавить панель…</div><div class="dz-menu-i dim" @click="resetLayout()">Сбросить раскладку</div></div></div>
      <div class="dz-body">
        <div v-for="v in layout.left" :key="v" class="dz-host" v-show="active.left===v" :ref="el=>setHost(v,el)"></div>
      </div>
      </div>
    </section>
    <Teleport v-if="hostEl('project')" :to="hostEl('project')"><div style="display:contents">
      <div class="pane-head">
        <button v-if="leftMode==='phrases'" class="ghost icon-btn sm" @click="backToFiles" title="К файлам"><svg class="icon" viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg></button>
        <h3 v-if="leftMode==='files'">Файлы проекта</h3>
        <h3 v-else style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis" :title="focusFileFilter">{{focusFileFilter||'Фразы'}}</h3>
        <span class="grow"></span>
              </div>
      <div v-if="leftMode==='files'" class="proj-stats"><div class="proj-num">{{fmtNum(projDone)}} <span>/ {{fmtNum(projTotal)}}</span><b class="proj-pct">{{pct1(projDone,projTotal)}}</b></div><div class="sum-bar"><i :style="'width:'+(projTotal?Math.max(projDone/projTotal*100,projDone?1.5:0):0)+'%'"></i></div><div class="proj-sub">Осталось {{fmtNum(projTotal-projDone)}} · файлов {{fmtNum(treeFileCount)}}<span v-if="summary&&summary.by_status&&summary.by_status.stale"> · устар. {{fmtNum(summary.by_status.stale)}}</span></div></div>
      <div v-if="leftMode==='files'" class="ft-search"><svg class="icon ic-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg><input class="grow" :value="fileSearchQ" @input="fileSearchQ=$event.target.value" placeholder="Поиск файлов…"><Dropdown :modelValue="fileSort" @update:modelValue="setSort" title="Сортировка" width="148px" :options="[{value:'need',label:'Недопереведённые'},{value:'name',label:'По имени'},{value:'progress',label:'По прогрессу'}]" /><button class="ghost icon-btn sm" @click="toggleFollow" :style="followFiles?'':'opacity:.4'" :title="followFiles?'Не следить за редактором':'Следить за редактором: список сам находит файл из редактора'"><svg class="icon" viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/></svg></button></div>
      <div v-else-if="focusFileFilter" class="ft-search"><svg class="icon ic-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg><input class="grow" :value="phraseSearchQ" @input="setPhraseSearch($event.target.value)" placeholder="Поиск по файлу: текст, строка, колонка, статус…"><button v-if="phraseSearchQ" class="ghost icon-btn sm" @click="setPhraseSearch('')" title="Очистить"><svg class="icon" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>
      <div class="pane-body">
        <div v-if="!doc" class="ft-empty"><svg class="icon" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"/></svg><span>Проект не загружен</span></div>
        <div v-else-if="leftMode==='files'">
          <div v-if="!treeFileCount && !fileTreeLoading" class="ft-empty"><svg class="icon" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1 2 2H5a2 2 0 0 1-2-2V7z"/><path d="M12 11v6M9 14h6"/></svg><span>Проект пуст — строк пока нет.</span><button class="primary" @click="runExtract()" style="margin-top:10px">Обновить данные игры</button></div>
          <div v-else class="filetree">
            <div v-for="r in treeRows" :key="r.key">
            <div v-if="r.type==='dir'" class="ft-item ft-dir" :style="'padding-left:'+(8+r.depth*16)+'px'" @click="toggleDir(r.dir.path)"><span class="tree-chev" :title="r.open?'Свернуть':'Развернуть'"><svg class="icon" viewBox="0 0 24 24" :style="r.open?'transform:rotate(90deg)':''"><path d="M9 6l6 6-6 6"/></svg></span>
              <svg class="icon ft-icon" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1 2 2H5a2 2 0 0 1-2-2V7z"/></svg>
              <div style="flex:1;min-width:0">
                <div class="ft-name">{{r.dir.name}}</div>
                <div class="ft-prog"><i :style="'width:'+dirPct(r.dir)+'%'"></i></div>
              </div>
              <span class="ft-count">{{r.dir.done}}/{{r.dir.total}}</span><span v-if="r.dir.total-r.dir.done" class="ft-un">{{r.dir.total-r.dir.done}} неперев.</span>
            </div>
            <div v-else class="ft-group"><div class="ft-item" :data-fp="r.file.path" data-ctx="file" :data-path="r.file.path" :style="'padding-left:'+(8+r.depth*16)+'px'" :class="{active:focusFileFilter===r.file.path||revealFile===r.file.path,reveal:revealFile===r.file.path}" @click="openFile(r.file.path)"><span class="tree-chev" @click.stop="toggleExpand(r.file.path)" :title="expanded(r.file.path)?'Свернуть строки':'Показать строки'"><svg class="icon" viewBox="0 0 24 24" :style="expanded(r.file.path)?'transform:rotate(90deg)':''"><path d="M9 6l6 6-6 6"/></svg></span>
              <svg class="icon ft-icon" viewBox="0 0 24 24"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5z"/><path d="M14 3v5h5"/></svg>
              <div style="flex:1;min-width:0">
                <div class="ft-name" :title="r.file.path">{{baseName(r.file.path)}}</div>
                <div class="ft-prog"><i :style="'width:'+progPct(r.file)+'%'"></i></div>
              </div>
              <span class="ft-count">{{r.file.translated}}/{{r.file.total}}</span><span v-if="fileUn(r.file)" class="ft-un">{{fileUn(r.file)}} неперев.</span>
            </div>
            <div v-if="expanded(r.file.path)" class="tree-phrases"><div v-for="g in filePhraseGroups(r.file.path)" :key="g.row" class="tree-ph-group"><div class="muted tree-ph-rowkey">{{g.key||('row '+(g.row+1))}}<span v-if="g.un"> · {{g.un}} неперев.</span></div><div v-for="c in g.cells" :key="c.id" class="tree-phrase" data-ctx="phrase" :data-id="c.id" :class="{active:focusId===c.id}" @click="focusPhrase(c.id)"><span :class="'ed-status-pill status-'+c.status">{{c.status}}</span><div class="tree-ph-text"><div class="tree-ph-src">{{c.source}}</div><div v-if="c.column_name" class="muted">{{c.column_name}}</div></div></div></div><button v-if="filePhrases(r.file.path).total>100" class="ghost tree-more" @click="openFile(r.file.path)">Все {{filePhrases(r.file.path).total}} фраз →</button></div>
            </div>
            </div>
          </div>
          <button v-if="treeReadyCount" class="ghost done-toggle" @click="toggleHideReady">{{fileHideReady?'Показать готовые ('+treeReadyCount+')':'Скрыть готовые ('+treeReadyCount+')'}}</button>
          <button v-if="treeEmptyCount" class="ghost done-toggle" @click="toggleHideEmpty">{{hideEmpty?'Показать пустые ('+treeEmptyCount+')':'Скрыть пустые ('+treeEmptyCount+')'}}</button>
        </div>
        <div v-else>
          <div v-if="!focusFileFilter" class="ft-empty"><svg class="icon" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h3l2 2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2-2V7z"/></svg><span>Выберите файл в списке</span></div>
          <div v-else-if="!fileRows.totalGroups" class="ft-empty"><svg class="icon" viewBox="0 0 24 24"><path d="M4 6h16M4 12h16M4 18h10"/></svg><span>{{phraseSearchQ?'Ничего не найдено':'В файле нет строк'}}</span></div>
          <template v-else>
          <template v-for="(g,gi) in rowGroupsPaged" :key="g.row">
          <div v-if="gi===0||g.section!==rowGroupsPaged[gi-1].section" class="ft-section">Строки {{g.section*100+1}}–{{Math.min((g.section+1)*100,fileRows.totalGroups)}}</div>
          <div class="ft-rowcard" :class="{active:g.cells.some(c=>c.id===focusId),done:!g.un}">
            <div class="ft-rowhead" @click="focusPhrase(g.cells[0].id)" :title="'rowIndex '+g.row"><span class="ft-rowkey">{{g.row_key||('row '+(g.row+1))}}</span><span v-if="g.cells.length>1" class="muted">{{g.cells.length}} кол.</span><span style="flex:1"></span><span v-if="g.un" class="ft-un">{{g.un}} неперев.</span><span v-else class="ft-ok">✓</span></div>
            <div v-for="c in g.cells" :key="c.id" class="ft-cell" data-ctx="phrase" :data-id="c.id" :class="{active:focusId===c.id}" @click="focusPhrase(c.id)" title="Редактировать">
              <span :class="'ed-status-pill status-'+c.status">{{c.status}}</span>
              <div style="flex:1;min-width:0">
                <div class="ft-cell-src">{{c.source}}</div>
                <div v-if="c.translation" class="ft-cell-tr">{{c.translation}}</div>
                <div v-if="c.column_name" class="muted ft-cell-col">{{c.column_name}}</div>
              </div>
            </div>
          </div>
          </template>
          <div v-if="rowGroupPages>1" class="ft-actions" style="border:none;padding-top:6px"><button class="ghost icon-btn" @click="setRowGroupPage(phrasePage-1)" :disabled="phrasePage===0" title="Назад"><svg class="icon" viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg></button><span class="muted" style="font-size:11px">{{phrasePage+1}}/{{rowGroupPages}}</span><button class="ghost icon-btn" @click="setRowGroupPage(phrasePage+1)" :disabled="phrasePage>=rowGroupPages-1" title="Вперёд"><svg class="icon" viewBox="0 0 24 24"><path d="M9 6l6 6 6-6"/></svg></button></div>
          </template>
        </div>
      </div>
    </div></Teleport>
    <main class="main-pane">
      <Editor ref="editorRef" :entries="doc?doc.entries:[]" :focusId="focusId" :store-key="projectId" :csv-open="csvRequest" :csv-root="root" :pin-request="previewPinRequest" :row-context="rowContext" :row-next="editorRowNext" :load-row-page="editorLoadRowPage" @save="onSave" @navigate="onNavigate" @need-entry="ensureEntry" @resolve="onResolveConflict" @file="onEditorFile" @reveal="onRevealFile"/>
    </main>
    <section class="dock right" v-show="railVisible.right">
      <div class="hsplit left" @mousedown="e=>startResize('right',e)" title="Потяните, чтобы изменить ширину"></div>
      <div class="dz-rail right-edge" @contextmenu.prevent="openZoneMenu('right',$event)" @dragover.prevent="e=>onTabDragOver('right',layout.right.length,e)" @drop="e=>onDrop('right',e)" :class="{'drop-end':dropPos&&dropPos.zone==='right'&&dropPos.index===layout.right.length}">
        <button v-for="(v,i) in layout.right" :key="v" class="dz-ribtn" :class="[{active:active.right===v},dropClass('right',i)]" draggable="true" @dragstart="e=>onTabDragStart('right',v,e)" @dragend="onDragEnd" @dragover.prevent="e=>onTabDragOver('right',i,e)" @drop.stop="e=>onDropOnTab('right',i,e)" @click="toggleView('right',v)" @contextmenu.prevent.stop="openZoneMenu('right',$event,v)" :title="VIEWS[v].title"><span class="dz-ic" v-html="VIEWS[v].icon"></span></button>
        <span class="grow"></span>
        <button class="dz-ribtn dz-add" @click="openAddMenu('right',$event)" title="Добавить панель">+</button>
      </div>
      <div class="dz-main" v-show="zoneShown('right')">
      <div class="dz-head"><span>{{activeTitle('right')}}</span><span class="grow"></span><button class="dz-gearbtn" @click="menuFor=menuFor==='head:right'?null:'head:right'" title="Настройки зоны"><svg class="icon" viewBox="0 0 24 24"><path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3"/><path d="M1 14h6M9 8h6M17 16h6"/></svg></button><button class="dz-xbtn" @click="active.right&&closeTab('right',active.right)" title="Убрать панель">×</button><div v-if="menuFor==='head:right'" class="dz-menu head-menu"><div class="dz-menu-i" @click="hideZone('right')">Свернуть панель</div><div class="dz-menu-i" @click="openAddMenu('right',$event)">Добавить панель…</div><div class="dz-menu-i dim" @click="resetLayout()">Сбросить раскладку</div></div></div>
      <div class="dz-body">
        <div v-for="v in layout.right" :key="v" class="dz-host" v-show="active.right===v" :ref="el=>setHost(v,el)"></div>
      </div>
      </div>
    </section>
    <section class="dock bottom" v-show="railVisible.bottom" :style="zoneStyle('bottom')">
      <div v-if="!narrow" class="hsplit top" @mousedown="startResizeY" title="Потяните, чтобы изменить высоту"></div>
      <div class="dz-rail" @contextmenu.prevent="openZoneMenu('bottom',$event)" @dragover.prevent="e=>onTabDragOver('bottom',layout.bottom.length,e)" @drop="e=>onDrop('bottom',e)" :class="{'drop-end':dropPos&&dropPos.zone==='bottom'&&dropPos.index===layout.bottom.length}">
        <button v-for="(v,i) in layout.bottom" :key="v" class="dz-ribtn" :class="[{active:active.bottom===v},dropClass('bottom',i)]" draggable="true" @dragstart="e=>onTabDragStart('bottom',v,e)" @dragend="onDragEnd" @dragover.prevent="e=>onTabDragOver('bottom',i,e)" @drop.stop="e=>onDropOnTab('bottom',i,e)" @click="toggleView('bottom',v)" @contextmenu.prevent.stop="openZoneMenu('bottom',$event,v)" :title="VIEWS[v].title"><span class="dz-ic" v-html="VIEWS[v].icon"></span></button>
        <span class="grow"></span>
        <button class="dz-ribtn dz-add" @click="openAddMenu('bottom',$event)" title="Добавить панель">+</button>
      </div>
      <div class="dz-main" v-show="zoneShown('bottom')">
      <div class="dz-head"><span>{{activeTitle('bottom')}}</span><span class="grow"></span><button class="dz-gearbtn" @click="menuFor=menuFor==='head:bottom'?null:'head:bottom'" title="Настройки зоны"><svg class="icon" viewBox="0 0 24 24"><path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3"/><path d="M1 14h6M9 8h6M17 16h6"/></svg></button><button class="dz-xbtn" @click="active.bottom&&closeTab('bottom',active.bottom)" title="Убрать панель">×</button><div v-if="menuFor==='head:bottom'" class="dz-menu head-menu"><div class="dz-menu-i" @click="hideZone('bottom')">Свернуть панель</div><div class="dz-menu-i" @click="openAddMenu('bottom',$event)">Добавить панель…</div><div class="dz-menu-i dim" @click="resetLayout()">Сбросить раскладку</div></div></div>
      <div class="dz-body">
        <div v-for="v in layout.bottom" :key="v" class="dz-host" v-show="active.bottom===v" :ref="el=>setHost(v,el)"></div>
      </div>
      </div>
    </section>
    <Teleport v-if="hostEl('log')" :to="hostEl('log')">
      <LogView :log="logText" data-ctx="log"/>
    </Teleport>
    <Teleport v-if="hostEl('translate')" :to="hostEl('translate')">
      <TranslateView :files="sourceFiles" :selected="selTranslateSet" :tr-count="selTranslate.length" :estimate="trEstimate" :pending-map="trPendingMap" :map-ready="trPendingReady" :loading="sourceLoading" :error="sourceError" :root="root" :gemini="geminiStatus" :job="job" @toggle="toggleTranslate" @sel-visible="selTranslateVisible" @clear-sel="clearTranslateSel" @refresh="scanSource" @preview="openPreview" @translate="runTranslate" @cancel="cancelJob"/>
    </Teleport>
      <Teleport v-if="hostEl('search')" :to="hostEl('search')">
      <SearchView :q="searchQ" @update:q="searchQ=$event" :matches="matches" :loading="searchLoading" @search="doSearch" @open="openSearchResult"/>
      </Teleport>
      <Teleport v-if="hostEl('tags')" :to="hostEl('tags')">
      <TagsView :filter="tagFilter" @update:filter="tagFilter=$event" @insert="insertTagToEditor"/>
      </Teleport>
      <Teleport v-if="hostEl('summary')" :to="hostEl('summary')">
      <SummaryView :summary="summary"/>
      </Teleport>
      <Teleport v-if="hostEl('pack')" :to="hostEl('pack')">
      <PackView :project-id="projectId" :pack="pack" :errors="packErrors" :manifest="packManifest" :msg="packMsg" :compat="packCompat" :langs="packLangs" @update:compat="packCompat=$event" @update:langs="packLangs=$event" @save="savePack" @add-author="packAddAuthor" @del-author="packDelAuthor" @toast="showToast"/>
      </Teleport>
      <Teleport v-if="hostEl('export')" :to="hostEl('export')">
      <ExportView :project-id="projectId" :job="job" :scope="selExportSet" :data-rev="dataRev" @build="buildPack" @build-download="buildAndDownloadPack" @toast="showToast" @toggle-scope="toggleExport" @clear-scope="clearExportScope"/>
    </Teleport>
      <Teleport v-if="hostEl('delta')" :to="hostEl('delta')">
      <DeltaView ref="deltaRef" :project-id="projectId" @toast="showToast" @refresh="loadProject" @open-conflict="openConflict"/>
    </Teleport>
  </div>

  <footer class="statusbar" v-if="!showPicker">
    <span class="sb-item sb-proj" :title="projectId">{{projectName||projectId||'—'}}</span>
    <span class="sb-item sb-badge" @click="gotoView('summary')" title="Сводка">{{badge}}</span>
    <span v-if="job" class="sb-item">{{ {extract:'Обновление',gemini:'Gemini',openrouter:'OpenRouter',merge:'Сборка','sync-sources':'Синхронизация',update:'Апдейт'}[job.action]||job.action }}: {{job.status==='running'?'…':job.status==='queued'?'в очереди':job.status }}</span>
    <span class="sb-item" @click="openSettings('sources')" :title="(sourceStatus.activeRoot||'')+' — настроить источники'"><span class="status-dot" :class="sourceStatus.ready?'on':'off'"></span>{{sourceLabel}}</span>
    <span class="grow"></span>
    <span class="sb-item sb-ai" @click="openSettings('ai')" :title="aiTitle"><span class="status-dot" :class="geminiStatus.configured?'on':'off'"></span>Gemini<span class="sb-sep">·</span><span class="status-dot" :class="geminiStatus.openrouterConfigured?'on':'off'"></span>OpenRouter</span>
    <span v-if="upd.supported||upd.version" class="sb-item sb-upd" :class="{'sb-warn':upd.updateAvailable}" @click="upd.updateAvailable||upd.needsToolchain?updModal=true:loadUpdateStatus()" :title="updTitle">{{updLabel}}</span>
  </footer>

  <div v-if="paletteOpen" class="overlay" @click.self="paletteOpen=false">
    <div class="modal palette">
      <div class="search-box" style="border:none;padding:0 0 8px"><svg class="icon ic-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg><input ref="paletteInput" class="grow" :value="paletteQ" @input="paletteQ=$event.target.value;paletteIdx=0" placeholder="Файл, фраза или команда…" @keydown="onPaletteKey"></div>
      <div class="palette-list">
        <div v-for="(it,ri) in paletteResults" :key="it.key" class="palette-item" :class="{active:ri===paletteIdx}" @mouseenter="paletteIdx=ri" @click="runPalette(it)"><span class="palette-hint">{{it.hint}}</span><span class="palette-text">{{it.t}}</span></div>
        <div v-if="!paletteResults.length" class="ft-empty"><span>Ничего не найдено</span></div>
      </div>
    </div>
  </div>

  <div v-if="ctxZone" style="position:fixed;inset:0;z-index:300" @click="ctxZone=null" @contextmenu.prevent="ctxZone=null"></div>
  <div v-if="ctxZone" class="dz-menu" style="position:fixed;z-index:301;bottom:auto" :style="{left:ctxZone.x+'px',top:ctxZone.y+'px'}"><div v-if="ctxZone.view" class="dz-menu-i" @click="hideWidget()">Скрыть виджет</div><div v-else class="dz-menu-i" @click="hidePanel(ctxZone.zone)">Скрыть панель</div></div>
  <div v-if="addMenu" class="dz-menu" style="position:fixed;z-index:301;bottom:auto" :style="{left:addMenu.x+'px',top:addMenu.y+'px'}"><div class="dz-menu-h">Добавить панель</div><div v-if="!hiddenViews.length" class="muted" style="padding:4px 10px;font-size:12px">Все панели размещены</div><div v-for="v in hiddenViews" :key="v" class="dz-menu-i" @click="addView(addMenu.zone,v);addMenu=null"><span class="dz-ic" v-html="VIEWS[v].icon"></span>{{VIEWS[v].title}}</div><div class="dz-menu-i dim" @click="resetLayout();addMenu=null">Сбросить раскладку</div></div>
  <div v-if="ctxMenu" class="dz-menu" style="position:fixed;z-index:302;bottom:auto" :style="{left:ctxMenu.x+'px',top:ctxMenu.y+'px'}"><div v-for="(it,i) in ctxMenu.items" :key="i" class="dz-menu-i" :class="{sel:it.sel}" @click="runCtx(it)">{{it.t}}</div></div>
  <div v-if="updModal" class="overlay" @click.self="updModal=false">
    <div class="modal upd-modal">
      <div class="upd-title">{{updateFailed?'Обновление не выполнено':'Доступно новое обновление'}}</div>
      <template v-if="updateFailed">
        <div class="muted">Причина записана в журнал приложения. Скопируйте хвост журнала для диагностики.</div>
        <div class="set-actions"><button class="primary" @click="copyUpdateLog" :disabled="updLogBusy">{{updLogBusy?'Копирование…':'Скопировать журнал'}}</button><button class="ghost" @click="updModal=false">Закрыть</button></div>
      </template>
      <template v-else>
        <div v-if="upd.needsToolchain" class="muted">Для обновления докачается тулчейн (JDK + git, ~250 МБ, один раз)</div>
        <div v-if="!upd.needsToolchain" class="muted">v{{upd.version}} · {{(upd.currentSha||'').slice(0,7)}} → {{(upd.latestSha||'').slice(0,7)}} · коммитов: {{upd.behindBy}}</div>
        <template v-if="!upd.needsToolchain">
        <div class="upd-sec">IN THIS UPDATE</div>
        <ul class="upd-list"><li v-for="(s,i) in (upd.subjects||[])" :key="i">{{s}}</li></ul>
        <div v-if="upd.behindBy>(upd.subjects||[]).length" class="muted">+ ещё {{upd.behindBy-(upd.subjects||[]).length}} изменений</div>
        </template>
        <div class="set-actions"><button class="primary" @click="runUpdate">Обновить сейчас</button><button class="ghost" @click="updModal=false">Возможно позже</button></div>
      </template>
    </div>
  </div>
  <div v-if="updRestarting" class="overlay"><div class="modal upd-modal"><div class="upd-title">Перезапуск…</div><div class="muted">Новая версия поднимается — страница обновится сама</div><div v-if="updRestartDead" class="muted">Не поднялось за 2 минуты — запусти приложение вручную</div></div></div>
  <div v-if="toast" class="toast">{{toast}}</div>`
};
const app = createApp(App);
app.config.errorHandler = (err, instance, info) => {
    console.error('[vue]', info, err);
    try {
        const box = document.createElement('div');
        box.className = 'toast';
        box.textContent = 'Ошибка интерфейса (' + (info || 'render') + '): ' + (err && err.message ? err.message : err);
        document.body.appendChild(box);
        setTimeout(() => box.remove(), 8000);
    } catch (e) {}
};
window.addEventListener('unhandledrejection', e => {
    console.error('[promise]', e.reason);
});
app.mount('#app');
