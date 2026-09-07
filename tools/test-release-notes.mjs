// Release-notes generator tests (mirror of the grouped-changes contract).
// Zero deps, node built-in runner.
// Usage: node --test tools/test-release-notes.mjs   (run from the repo root)
import {describe, it} from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';

const require = createRequire(import.meta.url);
const {
  parseArgs, parseConventional, displayOf, isReleaseCommit,
  groupCommits, parseShortstat, formatDateEn, renderNotes,
} = require('./generate-release-notes.cjs');

const REPO = 'example-org/example-repo';
const sha = seed => `${seed}`.padEnd(40, '0');
const entry = (subject, seed = 'a1b2c3d') => ({sha: sha(seed), subject});
const base = {tag: 'v1.2.0', prev: 'v1.1.0', date: 'September 8, 2026', repo: REPO};

describe('parseConventional', () => {
  it('разбирает тип со скоупом', () => {
    assert.deepEqual(parseConventional('feat(pack): add export manifest'),
      {type: 'feat', scope: 'pack', breaking: false, subject: 'add export manifest'});
  });
  it('разбирает тип без скоупа и нормализует регистр', () => {
    assert.deepEqual(parseConventional('Fix: align slider fill'),
      {type: 'fix', scope: '', breaking: false, subject: 'align slider fill'});
  });
  it('видит breaking-маркер', () => {
    assert.equal(parseConventional('refactor(api)!: drop legacy field').breaking, true);
  });
  it('не-conventional возвращает null', () => {
    assert.equal(parseConventional('Make it exist first'), null);
  });
});

describe('displayOf/isReleaseCommit', () => {
  it('дисплей держит скоуп без префикса типа', () => {
    assert.equal(displayOf(parseConventional('fix(ui): align slider'), 'fix(ui): align slider'), 'ui: align slider');
  });
  it('сырой текст для не-conventional', () => {
    assert.equal(displayOf(null, 'Make it exist first'), 'Make it exist first');
  });
  it('релизный коммит только chore(release)', () => {
    assert.equal(isReleaseCommit(parseConventional('chore(release): 1.2.0')), true);
    assert.equal(isReleaseCommit(parseConventional('chore(deps): bump lib')), false);
    assert.equal(isReleaseCommit(null), false);
  });
});

describe('groupCommits', () => {
  it('сортирует по секциям, а не по порядку входа', () => {
    const groups = groupCommits([entry('fix(ui): align slider', 'b'), entry('feat(pack): add manifest', 'a')]);
    assert.deepEqual(groups.map(g => g.key), ['feat', 'fix']);
  });
  it('выкидывает релизные коммиты, неизвестное кладёт в Other', () => {
    const groups = groupCommits([entry('chore(release): 1.2.0'), entry('Make it exist first', 'c')]);
    assert.deepEqual(groups.map(g => g.key), ['other']);
  });
  it('пустой вход — пустые группы', () => {
    assert.deepEqual(groupCommits([]), []);
  });
});

describe('parseShortstat/formatDateEn', () => {
  it('полный shortstat', () => {
    assert.deepEqual(parseShortstat(' 5 files changed, 12 insertions(+), 3 deletions(-)'),
      {files: 5, insertions: 12, deletions: 3});
  });
  it('только файлы и пустой ввод', () => {
    assert.deepEqual(parseShortstat(' 1 file changed'), {files: 1, insertions: 0, deletions: 0});
    assert.deepEqual(parseShortstat(''), {files: 0, insertions: 0, deletions: 0});
  });
  it('дата строкой и Date', () => {
    assert.equal(formatDateEn('2026-09-08'), 'September 8, 2026');
    assert.equal(formatDateEn(new Date(Date.UTC(2026, 0, 5))), 'January 5, 2026');
  });
  it('parseArgs требует значение флага', () => {
    assert.throws(() => parseArgs(['--tag']), /missing value/);
    assert.throws(() => parseArgs(['--nope', 'x']), /unknown flag/);
  });
});

describe('renderNotes', () => {
  const stat = {commits: 3, files: 5, insertions: 12, deletions: 3};
  it('окно с фичами: заголовок, статистика, highlights, changes, compare', () => {
    const notes = renderNotes({...base, groups: groupCommits(
      [entry('feat(pack): add manifest', 'a'), entry('fix(ui): align slider', 'b')]), stat, contributors: 1});
    assert.match(notes, /# Harmonia Suite v1\.2\.0 \(v1\.2\.0\)/);
    assert.match(notes, /\*\*Release Date:\*\* September 8, 2026/);
    assert.match(notes, /\*\*Since v1\.1\.0:\*\* 3 commits · 5 files changed · \+12 \/ -3 · 1 contributor/);
    assert.match(notes, /## ✨ Highlights/);
    assert.match(notes, /## 📦 Changes/);
    assert.match(notes, /### ✨ Features/);
    assert.match(notes, new RegExp(`\\[${'v1\\.1\\.0\\.\\.\\.v1\\.2\\.0'}\\]`));
    assert.match(notes, new RegExp(`https://github.com/${REPO}/compare/v1.1.0\\.\\.\\.v1.2.0`));
    assert.match(notes, new RegExp(`https://github.com/${REPO}/commit/a0+`));
  });
  it('только bookkeeping — честная заглушка без highlights', () => {
    const notes = renderNotes({...base, groups: [], stat: {commits: 1, files: 1, insertions: 1, deletions: 1}, contributors: 1});
    assert.match(notes, /release bookkeeping only/);
    assert.doesNotMatch(notes, /## ✨ Highlights/);
  });
  it('первый релиз — без compare-ссылки', () => {
    const notes = renderNotes({...base, prev: null, groups: [], stat, contributors: 2});
    assert.match(notes, /\*\*Initial release\*\* — 3 commits · 5 files changed · \+12 \/ -3 · 2 contributors/);
    assert.doesNotMatch(notes, /Full Changelog/);
  });
});
