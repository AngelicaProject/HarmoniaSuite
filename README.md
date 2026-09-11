<p align="center">
<img src="frontend/public/img/yuki-banner.jpg" alt="Harmonia Suite" width="100%">
</p>

# Harmonia Suite

[![CI](https://github.com/AngelicaProject/HarmoniaSuite/actions/workflows/ci.yml/badge.svg)](https://github.com/AngelicaProject/HarmoniaSuite/actions/workflows/ci.yml) [![codecov](https://codecov.io/gh/AngelicaProject/HarmoniaSuite/branch/main/graph/badge.svg)](https://codecov.io/gh/AngelicaProject/HarmoniaSuite) [![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0.html)

**A local pipeline for translating Final Fantasy XIV into other languages, packed for the Harmonia Dalamud plugin.** It reads strings out of an installed game client, runs them through machine translation, and hands them to a desktop editor for review by hand. What comes out is a ZIP that Harmonia installs as-is. The Spring Boot service remains the gateway/business layer; Electron owns the desktop window and local gateway lifecycle.

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

Grab the MSI from [Releases](https://github.com/AngelicaProject/HarmoniaSuite/releases) — it installs per user, no admin rights needed. The bundled JDK, Git, Node.js toolchain, and unpacker come with it, so you only point it at the game path. Node.js is used for future source updates and is not needed to run the packaged JAR. State lives in `%APPDATA%/HarmoniaSuite`.

Updates handle themselves: a version chip in the status bar, one button. The app pulls sources and rebuilds locally, rolling back on failure. If your checkout is dirty it refuses the update instead of risking local work.

### From source

You need JDK 21, git, and Node 24 LTS. The frontend runs through Vite while Spring Boot serves the API.

```bash
git clone https://github.com/AngelicaProject/HarmoniaSuite.git
cd HarmoniaSuite
.\mvnw.cmd spring-boot:run
```

In a second terminal:

```bash
cd frontend
npm ci
npm run dev
```

For the browser-independent frontend, keep API calls relative to `/api/**`. The Phase 1 desktop shell
serves the built frontend through `harmonia://app/`, chooses a dynamic loopback port for Spring Boot,
waits for `/api/status`, and proxies API requests without exposing that port to Vue. See
[apps/desktop/README.md](apps/desktop/README.md) for the manual Electron run. Vite's fixed-port proxy
remains only as a standalone browser development fallback.

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

Tag tooling: `node tools/sync-game-data.cjs` rebuilds `frontend/src/domain/uiColors.ts` from `UIColor.csv` and refreshes `tools/tag-inventory.json`. Unknown and payload tags go into `TAG_KINDS` (`frontend/src/domain/tags.ts`). Frontend checks run with `cd frontend && npm run check`.

## Team workflow

Everyone keeps their own database and the work splits by file. Deltas carry edits by `cell_id` with a source fingerprint in the header; a delta built against different sources gets rejected instead of applied blindly.

If two people edit the same cell, it shows up as a conflict. Incoming edits never overwrite yours silently.

## Database backups

Settings → Database: create a full snapshot, download or delete copies, set how many to keep (default 10, range 1–100; shrinking deletes the excess right away). Automatic backups: every N minutes (default 0 = off, range 0 or 5–10080); the check runs once a minute from the newest snapshot, so a manual backup also resets the timer. Snapshots live in `backups/` next to `harmonia.db` (`data/backups/` from source, `%APPDATA%/HarmoniaSuite/backups/` installed). The list shows the estimate for the chosen retention (≈ copies × newest snapshot).
The application log is `logs/harmonia.log` in the source workspace and `%APPDATA%/HarmoniaSuite/logs/harmonia.log` in an installed build.

Restore is manual, with the app stopped:

1. Stop the app. Never replace the file while it runs (one process per `.db`).
2. If the app still starts, create a fresh backup first, so the restore itself can be undone.
3. Delete `harmonia.db` plus `harmonia.db-wal` / `harmonia.db-shm` if present (stale WAL sidecars over a replaced main file corrupt data).
4. Copy the chosen `harmonia-YYYYMMDD-HHmmss.db` to `harmonia.db` and start the app; Flyway replays migrations on boot. A snapshot from a newer app version will not start — pick one from the matching version.
5. Optional integrity check: `sqlite3 <file> "PRAGMA integrity_check;"` → `ok`.

PostgreSQL profile: backups are embedded-SQLite only there (`400`); use `pg_dump`.

## Development

```bash
.\mvnw.cmd -q -DskipTests compile   # build
.\mvnw.cmd test                      # tests (JUnit 5, no Spring context)
cd frontend && npm run check        # typecheck, lint, format, Vitest, Vite build
```

The stack is Spring Boot 3.4.3 and Java 21, SQLite by default with a PostgreSQL profile for real deploys.

The production frontend is built by Vite and copied directly to `target/classes/static` during Maven `prepare-package`; generated `frontend/dist` is not committed. Commits follow Conventional Commits on `main`; fixtures stay invented (`example.com`, `pack-one`).

## Release

Development happens on `*-SNAPSHOT`. To cut a release: `versions:set`, a `vX.Y.Z` tag, `package`. You get `harmonia-suite-X.Y.Z.zip` with `VERSION.txt` inside; CI builds the MSI and the portable zip for each tag.

## License

AGPL-3.0, see [LICENSE](LICENSE).
