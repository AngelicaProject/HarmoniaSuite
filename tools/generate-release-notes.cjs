// Generates GitHub Release notes from Conventional Commits.
// Range is <prev>..(<tag> when it exists, else HEAD), so an upcoming tag can
// be previewed before it is cut. Release bookkeeping (chore(release)) counts
// toward stats but stays out of the lists.
//
// Usage: node tools/generate-release-notes.cjs --tag v1.0.9 [--prev v1.0.8]
//        [--end HEAD] [--repo AngelicaProject/HarmoniaSuite] [--date 2026-09-08]
//        [--out release-notes.md]   (run from the repo root)
'use strict';

const {execFileSync} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const DEFAULT_REPO = 'AngelicaProject/HarmoniaSuite';
const EMPTY_TREE = '4b825dc642cb6eb9a060e54bf8d69288fbee4904';
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December'];

const SECTIONS = [
  ['feat', '✨ Features'],
  ['fix', '🐛 Fixes'],
  ['perf', '⚡ Performance'],
  ['refactor', '🔧 Refactoring'],
  ['docs', '📚 Docs'],
  ['test', '🧪 Tests'],
  ['build', '🔨 Build'],
  ['ci', '🤖 CI'],
  ['chore', '🧹 Maintenance'],
  ['style', '🎨 Style'],
  ['other', '📝 Other'],
];
const KNOWN = new Set(SECTIONS.map(([key]) => key));
const HIGHLIGHT_KEYS = new Set(['feat', 'fix', 'perf']);
const HIGHLIGHT_CAP = 10;

function runGit(args) {
  return execFileSync('git', args, {cwd: ROOT, encoding: 'utf8'});
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i];
    if (!flag.startsWith('--')) throw new Error(`unexpected arg ${flag}`);
    const key = flag.slice(2);
    if (!['tag', 'prev', 'end', 'repo', 'date', 'out'].includes(key)) throw new Error(`unknown flag ${flag}`);
    const value = argv[i + 1];
    if (!value || value.startsWith('--')) throw new Error(`missing value for ${flag}`);
    out[key] = value;
    i++;
  }
  return out;
}

function parseConventional(subject) {
  const m = /^([A-Za-z]+)(?:\(([^)]*)\))?(!)?:\s*(.+?)\s*$/.exec(String(subject));
  if (!m) return null;
  return {type: m[1].toLowerCase(), scope: m[2] || '', breaking: m[3] === '!', subject: m[4]};
}

function displayOf(parsed, raw) {
  if (!parsed) return String(raw).trim();
  return parsed.scope ? `${parsed.scope}: ${parsed.subject}` : parsed.subject;
}

function isReleaseCommit(parsed) {
  return !!parsed && parsed.type === 'chore' && parsed.scope === 'release';
}

function groupCommits(entries) {
  const byKey = new Map();
  for (const entry of entries) {
    const parsed = parseConventional(entry.subject);
    if (isReleaseCommit(parsed)) continue;
    const key = parsed && KNOWN.has(parsed.type) ? parsed.type : 'other';
    if (!byKey.has(key)) byKey.set(key, []);
    byKey.get(key).push({sha: entry.sha, text: displayOf(parsed, entry.subject)});
  }
  return SECTIONS.filter(([key]) => byKey.has(key))
    .map(([key, title]) => ({key, title, items: byKey.get(key)}));
}

function parseShortstat(text) {
  const m = /(\d+)\s+files?\s+changed(?:,\s+(\d+)\s+insertions?\(\+\))?(?:,\s+(\d+)\s+deletions?\(-\))?/
    .exec(String(text || ''));
  if (!m) return {files: 0, insertions: 0, deletions: 0};
  return {files: Number(m[1]), insertions: Number(m[2] || 0), deletions: Number(m[3] || 0)};
}

function formatDateEn(date) {
  let year, month, day;
  if (typeof date === 'string') {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
    if (!m) throw new Error(`bad --date ${date}, want YYYY-MM-DD`);
    year = Number(m[1]);
    month = Number(m[2]);
    day = Number(m[3]);
  } else {
    year = date.getUTCFullYear();
    month = date.getUTCMonth() + 1;
    day = date.getUTCDate();
  }
  return `${MONTHS[month - 1]} ${day}, ${year}`;
}

function plural(count, one, many) {
  return `${count} ${count === 1 ? one : many}`;
}

function statsLine(stat, contributors) {
  return `${plural(stat.commits, 'commit', 'commits')} · ` +
    `${plural(stat.files, 'file', 'files')} changed · ` +
    `+${stat.insertions} / -${stat.deletions} · ` +
    plural(contributors, 'contributor', 'contributors');
}

function itemLine(repoUrl, item) {
  return `- **${item.text}** ([\`${item.sha.slice(0, 7)}\`](${repoUrl}/commit/${item.sha}))`;
}

function renderNotes({tag, prev, date, repo, groups, stat, contributors}) {
  const repoUrl = `https://github.com/${repo}`;
  const out = [];
  out.push(`# Harmonia Suite ${tag} (${tag})`, '');
  out.push(`**Release Date:** ${date}`);
  out.push(prev
    ? `**Since ${prev}:** ${statsLine(stat, contributors)}`
    : `**Initial release** — ${statsLine(stat, contributors)}`);
  out.push('', '---', '');
  const highlights = groups.filter(group => HIGHLIGHT_KEYS.has(group.key)).flatMap(group => group.items);
  if (highlights.length === 0 && groups.length === 0) {
    out.push(prev ? `No user-facing changes since ${prev} — release bookkeeping only.` : 'Initial release.', '');
  } else {
    if (highlights.length > 0) {
      out.push('## ✨ Highlights', '');
      for (const item of highlights.slice(0, HIGHLIGHT_CAP)) out.push(itemLine(repoUrl, item));
      if (highlights.length > HIGHLIGHT_CAP) out.push(`...and ${highlights.length - HIGHLIGHT_CAP} more, see Changes below.`);
      out.push('');
    }
    if (groups.length > 0) {
      out.push('## 📦 Changes', '');
      for (const group of groups) {
        out.push(`### ${group.title}`, '');
        for (const item of group.items) out.push(itemLine(repoUrl, item));
        out.push('');
      }
    }
  }
  out.push('## Updating', '');
  out.push('- Installed app: version chip → Update (pulls sources and rebuilds locally, rolls back on failure).');
  out.push('- MSI / portable ZIP: see Assets below.');
  out.push('- From source: `git pull --ff-only` + `.\\mvnw.cmd package`.', '');
  if (prev) out.push(`**Full Changelog**: [${prev}...${tag}](${repoUrl}/compare/${prev}...${tag})`, '');
  return out.join('\n');
}

function latestTags() {
  return runGit(['tag', '--list', 'v*', '--sort=-v:refname']).split('\n').map(line => line.trim()).filter(Boolean);
}

function tagExists(tag) {
  try {
    execFileSync('git', ['rev-list', '-n', '1', tag], {cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore']});
    return true;
  } catch {
    return false;
  }
}

function readLog(range) {
  const raw = runGit(['log', range, '--no-merges', '--pretty=format:%H%x00%an%x00%ae%x00%s%x1f%b%x1e']);
  return raw.split('\x1e').map(record => record.trim()).filter(Boolean).map(record => {
    const [head, body] = record.split('\x1f');
    const [sha, author, email, ...subjectParts] = head.split('\x00');
    return {sha, author, email, subject: subjectParts.join('\x00'), body: (body || '').trim()};
  });
}

function main(argv) {
  const opts = parseArgs(argv);
  if (!opts.tag) throw new Error('missing --tag vX.Y.Z');
  let prev = opts.prev || null;
  if (!prev) {
    const tags = latestTags();
    prev = tags.includes(opts.tag) ? (tags[tags.indexOf(opts.tag) + 1] || null) : (tags[0] || null);
  }
  const end = opts.end || (tagExists(opts.tag) ? opts.tag : 'HEAD');
  const range = prev ? `${prev}..${end}` : end;
  const entries = readLog(range);
  const diffBase = prev || EMPTY_TREE;
  const stat = {commits: entries.length, ...parseShortstat(runGit(['diff', '--shortstat', `${diffBase}..${end}`]))};
  const contributors = new Set(entries.map(entry => entry.email)).size;
  const notes = renderNotes({
    tag: opts.tag,
    prev,
    date: formatDateEn(opts.date || new Date()),
    repo: opts.repo || DEFAULT_REPO,
    groups: groupCommits(entries),
    stat,
    contributors,
  });
  if (opts.out) fs.writeFileSync(path.resolve(ROOT, opts.out), `${notes}\n`, 'utf8');
  else process.stdout.write(`${notes}\n`);
}

if (require.main === module) main(process.argv.slice(2));

module.exports = {
  parseArgs, parseConventional, displayOf, isReleaseCommit,
  groupCommits, parseShortstat, formatDateEn, renderNotes, SECTIONS,
};
