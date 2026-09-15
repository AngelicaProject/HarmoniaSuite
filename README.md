<p align="center">
<img src="frontend/public/img/yuki-banner.png" alt="Harmonia Suite" width="100%">
</p>

# Harmonia Suite

[![CI](https://github.com/AngelicaProject/HarmoniaSuite/actions/workflows/ci.yml/badge.svg)](https://github.com/AngelicaProject/HarmoniaSuite/actions/workflows/ci.yml) [![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0.html)

Harmonia backend v1 is under reconstruction. The canonical source/HXS pipeline is available through the Atlas boundary and managed source upload/artifact lifecycle. The legacy project, translation-editor, extraction, AI, backup, and pack/export runtime has been removed.

The workspace and translation model is forthcoming. The current frontend is a minimal transitional shell; it does not expose future source, Atlas, workspace, or translation controls.

## Development

The backend is Spring Boot 3.4.3 on Java 21. SQLite is the default local database and PostgreSQL is supported through the `postgres` profile. Canonical runtime data lives at `data/core/harmonia.db`; the historical `data/harmonia.db` file is preserved only as a future offline import source and is never opened by the application.

The desktop Electron shell, local gateway, updater, and packaging remain part of the product. The gateway waits for `/api/version` and proxies the backend without exposing its loopback port to the frontend.

```bash
./mvnw test
cd frontend
npm ci
npm run check
```

The production package builds the frontend with Vite and serves it from the Spring Boot artifact. See [apps/desktop/README.md](apps/desktop/README.md) for desktop development and [docs/release-operations.md](docs/release-operations.md) for release procedures.

## Compatibility boundary

Files under `src/main/resources/db/migration/` are unchanged historical legacy migrations. They document the old database for a future importer; they are not the canonical runtime migration chain. Runtime migrations are under `src/main/resources/db/core/migration/` and are applied explicitly by `CoreDatabaseConfig`.

## License

AGPL-3.0, see [LICENSE](LICENSE).
