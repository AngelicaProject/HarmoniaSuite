// Syncs game-derived frontend data after a game patch + sources refresh:
//  1. Regenerates frontend/src/domain/uiColors.ts from
//     data/sources/<version>/en/UIColor.csv (Dark theme column, UInt32 RGBA).
//  2. Scans data/sources/<version>/en/*.csv with the real tag parser and refreshes
//     tools/tag-inventory.json (tag name -> uses, kind, sample).
//     Names that resolve to kind "misc" or appear for the first time need
//     a human decision in TAG_KINDS (frontend/src/domain/tags.ts).
//
// Usage: node tools/sync-game-data.cjs   (run from the repo root)
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');

function latestSourceEn() {
  const sources = path.join(ROOT, 'data', 'sources');
  const versions = fs.readdirSync(sources).filter(v => {
    try {
      return fs.statSync(path.join(sources, v)).isDirectory()
        && fs.statSync(path.join(sources, v, 'en')).isDirectory();
    } catch (e) {
      return false;
    }
  }).sort();
  if (!versions.length) throw new Error('no sources: run sync-sources first');
  const version = versions[versions.length - 1];
  return {version, dir: path.join(sources, version, 'en')};
}

const LATEST = latestSourceEn();
const RAW = LATEST.dir;
const COLORS_TS = path.join(ROOT, 'frontend', 'src', 'domain', 'uiColors.ts');
const TAGS_TS = path.join(ROOT, 'frontend', 'src', 'domain', 'tags.ts');
const INVENTORY = path.join(__dirname, 'tag-inventory.json');

function cssOf(uint32) {
  const r = (uint32 >>> 24) & 255, g = (uint32 >>> 16) & 255, b = (uint32 >>> 8) & 255, a = uint32 & 255;
  return a === 255 ? '#' + [r, g, b].map(v => v.toString(16).padStart(2, '0')).join('')
    : `rgba(${r},${g},${b},${(a / 255).toFixed(3)})`;
}

// Minimal CSV reader (quotes, doubled quotes, embedded newlines).
function* csvFields(text) {
  let field = '', inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inQuotes) {
      if (c === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; }
        else inQuotes = false;
      } else field += c;
    } else if (c === '"') inQuotes = true;
    else if (c === ',' || c === '\n') {
      if (field.includes('<')) yield field;
      field = '';
    } else if (c !== '\r') field += c;
  }
  if (field.includes('<')) yield field;
}

async function main() {
  // 1. Colors.
  const ui = fs.readFileSync(path.join(RAW, 'UIColor.csv'), 'utf8').replace(/^\uFEFF/, '').split('\n');
  const map = {};
  for (let i = 4; i < ui.length; i++) {
    const cells = ui[i].trim().split(',');
    const id = Number(cells[0]), dark = Number(cells[1]);
    if (Number.isInteger(id) && Number.isInteger(dark)) map[id] = cssOf(dark);
  }
  const js = '// Generated from data/sources/' + LATEST.version + '/en/UIColor.csv (Dark theme column, UInt32 RGBA).\n'
    + '// Regenerate on game patch: node tools/sync-game-data.cjs\n'
    + 'export const UI_COLORS = ' + JSON.stringify(map) + ';\n';
  const before = fs.existsSync(COLORS_TS) ? fs.readFileSync(COLORS_TS, 'utf8') : null;
  fs.writeFileSync(COLORS_TS, js);
  console.log(`uiColors.ts: ${Object.keys(map).length} rows${before === js ? ' (unchanged)' : ' (UPDATED)'}`);

  // 2. Tag inventory with the real parser.
  // tags.ts is intentionally also valid JavaScript, but Node's native loader does not
  // resolve Vite's extensionless TypeScript imports. Inline the generated color table
  // into a data module so this repository tool remains dependency-free.
  const tagSource = fs.readFileSync(TAGS_TS, 'utf8')
    .replace(/^import[^\n]+\n/, 'const UI_COLORS = ' + JSON.stringify(map) + ';\n');
  const tags = await import('data:text/javascript,' + encodeURIComponent(tagSource));
  const files = fs.readdirSync(RAW).filter(f => f.toLowerCase().endsWith('.csv'));
  const stats = new Map();
  let payloads = 0;
  for (const f of files) {
    const text = fs.readFileSync(path.join(RAW, f), 'utf8');
    for (const field of csvFields(text)) {
      for (const t of tags.parseDeep(field)) {
        const name = t.text.startsWith('<payload:') ? 'payload' : tags.tagNameOf(t.text);
        const e = stats.get(name) || {count: 0, sample: null};
        e.count++;
        if (!e.sample && t.text.length <= 90) e.sample = t.text;
        stats.set(name, e);
        if (name === 'payload') payloads++;
      }
    }
  }
  const prev = fs.existsSync(INVENTORY) ? JSON.parse(fs.readFileSync(INVENTORY, 'utf8')) : {tags: {}};
  const out = {generated: new Date().toISOString(), files: files.length, tags: {}};
  const sorted = [...stats.entries()].sort((a, b) => b[1].count - a[1].count);
  for (const [name, e] of sorted) {
    out.tags[name] = {count: e.count, kind: tags.tagKindOf(name === 'payload' ? '<payload: 0>' : `<${name}>`), sample: e.sample};
  }
  fs.writeFileSync(INVENTORY, JSON.stringify(out, null, 1) + '\n');

  const fresh = sorted.filter(([n]) => !prev.tags[n]).map(([n]) => n);
  const misc = sorted.filter(([n]) => out.tags[n].kind === 'misc').map(([n]) => `${n}(${stats.get(n).count})`);
  console.log(`scanned ${files.length} files, ${sorted.length} tag names`);
  console.log(fresh.length ? `NEW since last run: ${fresh.join(', ')}` : 'no new tag names since last run');
  console.log(`payload tags: ${payloads}`);
  console.log(`misc kind (check TAG_KINDS): ${misc.join(', ') || '—'}`);
}

main().catch(e => { console.error(e); process.exit(1); });
