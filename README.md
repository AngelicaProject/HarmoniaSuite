<p align="center">
<img src="src/main/resources/static/img/yuki-banner.jpg" alt="Harmonia Suite" width="100%">
</p>

# Harmonia Suite

[![CI](https://github.com/AngelicaProject/HarmoniaSuite/actions/workflows/ci.yml/badge.svg)](https://github.com/AngelicaProject/HarmoniaSuite/actions/workflows/ci.yml) [![codecov](https://codecov.io/gh/AngelicaProject/HarmoniaSuite/branch/main/graph/badge.svg)](https://codecov.io/gh/AngelicaProject/HarmoniaSuite) [![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0.html)

**A local pipeline for translating Final Fantasy XIV into other languages, packed for the Harmonia Dalamud plugin.** It reads strings out of an installed game client, runs them through machine translation, and hands them to a web editor for review by hand. What comes out is a ZIP that Harmonia installs as-is. One Spring Boot service with a web UI and SQLite underneath. No accounts, nothing to operate.

Machine translation runs on either Gemini or OpenRouter. Paste the key in settings and it picks it up without a restart. With no key that step stays unavailable; the rest works the same.

<table>
<tr><td><b>Reads the game client</b></td><td>Set the client path in settings (usually detected on its own); the tool unpacks EXD data to CSV under <code>data/sources/&lt;gameVersion&gt;/en</code>.</td></tr>
<tr><td><b>Machine pass with a stop button</b></td><td>Translation runs in batches with a pause between requests and live progress. You can cancel any time; an interrupted batch leaves the database consistent.</td></tr>
<tr><td><b>One file at a time</b></td><td>Entries open file by file as row cards, with phrase search and Ctrl+K file jump. The full table never loads into the browser.</td></tr>
<tr><td><b>Survives game patches</b></td><td>After a patch, re-extract carries translations forward by stable cell id, then reports what was carried, what went stale, and what is still untranslated.</td></tr>
<tr><td><b>A team with no server</b></td><td>Everyone runs a local instance with a local database and owns a set of files. Work moves between instances as delta files keyed by cell id.</td></tr>
<tr><td><b>A ZIP that installs</b></td><td>Merge writes translated EXD CSVs beside <code>manifest.json</code> and zips them at the archive root, exactly the shape the Harmonia importer accepts.</td></tr>
</table>

---

## Quick install

### Windows, MSI

Grab the MSI from [Releases](https://github.com/AngelicaProject/HarmoniaSuite/releases) — it installs per user, no admin rights needed. JDK, git, and the unpacker come with it, so you only point it at the game path. State lives in `%APPDATA%/HarmoniaSuite`.

Updates handle themselves: a version chip in the status bar, one button. The app pulls sources and rebuilds locally, rolling back on failure. If your checkout is dirty it refuses the update instead of risking local work.

### From source

You need JDK 21 and git.

```bash
git clone https://github.com/AngelicaProject/HarmoniaSuite.git
cd HarmoniaSuite
.\mvnw.cmd spring-boot:run
```

Run it from the repo root, since `data/` and `projects/` resolve against the working directory. The UI is on http://127.0.0.1:8765.

---

## Getting started

```text
game path → extract → machine translation → review → merge → ZIP
```

1. Check the game path in settings; EXD unpacking runs from there too.
2. Create a project and run extract. Every translatable String cell becomes one entry.
3. Machine translation is optional, Gemini or OpenRouter. Paste the keys (`GEMINI_API_KEY`, `OPENROUTER_API_KEY`) into settings; no restart needed.
4. Review in the editor, one file at a time, with phrase search and jump to any row.
5. Fill in language, authors, and game version on the Pack card, then merge assembles the ZIP with `manifest.json` inside.

To check which build is running: `GET /api/version`. A `dev` answer means a non-packaged run from an IDE rather than a release build.

## After a game patch

Patches move strings around. Re-extract matches translations back by cell id and reports carried, stale, and untranslated counts.

Stale entries no longer count as translated and stay out of merge. In the editor they stand apart, so only they need retranslation.

Tag tooling: `node tools/sync-game-data.cjs` rebuilds `js/ui-colors.js` from `UIColor.csv` and refreshes `tools/tag-inventory.json`. Unknown and payload tags go into `TAG_KINDS` (`js/tags.js`). Check: `node --test tools/test-tags.mjs`.

## Team workflow

Everyone keeps their own database and the work splits by file. Deltas carry edits by `cell_id` with a source fingerprint in the header; a delta built against different sources gets rejected instead of applied blindly.

If two people edit the same cell, it shows up as a conflict. Incoming edits never overwrite yours silently.

## Development

```bash
.\mvnw.cmd -q -DskipTests compile   # build
.\mvnw.cmd test                      # tests (JUnit 5, no Spring context)
node --test tools/test-tags.mjs      # FFXIV tag checks
```

The stack is Spring Boot 3.4.3 and Java 21, SQLite by default with a PostgreSQL profile for real deploys.

If you touch `js` or `css`, bump `?v=` in `index.html` and every import, or stale files stick in cache. Commits follow Conventional Commits on `main`; fixtures stay invented (`example.com`, `pack-one`).

## Release

Development happens on `*-SNAPSHOT`. To cut a release: `versions:set`, a `vX.Y.Z` tag, `package`. You get `harmonia-suite-X.Y.Z.zip` with `VERSION.txt` inside; CI builds the MSI and the portable zip for each tag.

## License

AGPL-3.0, see [LICENSE](LICENSE).
