# Harmonia Suite — Agent Guide

This file documents stable engineering constraints for Harmonia Suite. Keep it concise. Do not turn it into an API catalog or a snapshot of every implementation detail.

## Architecture

Harmonia Suite is one product and one repository with two source trees:

```text
backend:   Spring Boot / Java
frontend:  Vue / TypeScript / Vite
```

The backend owns canonical persistence, filesystem access, source ingestion, exports and runtime infrastructure.

The frontend is a web client of the backend REST API. It must remain usable both from a normal browser and from a future desktop shell. Do not couple frontend business logic to Electron or another desktop runtime.

Production packaging produces one Spring Boot application that serves both:

```text
/        frontend assets
/api/**  REST API
```

The legacy application runtime has been removed. Files under `db/migration/**` are compatibility-only historical migrations and are not the canonical runtime architecture. Do not model new features after the historical legacy schema or migrations.

Canonical backend code is developed by bounded context. Do not introduce new top-level global architectural buckets such as `controller/`, `service/`, `repository/`, `dto/`, `mapping/` or `domain/`. New canonical modules use package-by-feature with explicit `api/application/domain/infrastructure` boundaries. MapStruct is the standard DTO mapper. Controllers remain thin, SQL belongs in repository or infrastructure adapters, and legacy data compatibility must not become a dependency of canonical domain code.

## Repository layout

```text
frontend/                 Vue application source
src/main/java/            backend source
src/main/resources/       backend resources and DB migrations
src/test/java/            backend tests
tools/                    repository/release tooling
target/                   generated Maven output
frontend/dist/            generated Vite output
```

Frontend source does not belong in `src/main/resources/static`.

Generated build output must not be committed.

## Backend stack

* Java 21
* Spring Boot 3.4.3
* Maven wrapper
* Spring JDBC
* Flyway
* SQLite by default
* PostgreSQL profile for server deployments
* MapStruct for DTO mapping
* JUnit 5

Do not introduce an ORM unless a separate architectural decision explicitly requires one.

Controllers stay thin. Controllers call services; controllers do not access repositories directly.

Repositories own SQL and database access.

Use bind parameters for dynamic values. Never interpolate user/data values into SQL.

## Frontend stack

* Vue 3
* Vite
* TypeScript
* npm
* Vitest
* Vue Test Utils when component mounting is useful
* ESLint
* Prettier

Do not add Nuxt, Pinia, Tailwind, Axios, TanStack Query, SSR or a UI component framework without a concrete need.

Pinia is not the default place for state. Prefer component-local state and composables first.

Vue Router is optional and should only be introduced when URL-based navigation is intentionally adopted.

## Frontend structure

Prefer the following boundaries:

```text
src/api/          HTTP transport and typed backend DTO boundary
src/domain/       pure client-side domain logic
src/composables/  reusable stateful UI behavior
src/components/   reusable UI components
src/views/        screen-level composition when useful
```

Vue components must not construct backend URLs or perform ad-hoc raw HTTP calls.

Wire-format transformations belong at the API boundary.

Use TypeScript for production frontend code. Avoid `any`; use it only when the boundary is genuinely unknown and document why.

Prefer Vue SFCs. New components should normally use:

```text
<script setup lang="ts">
```

Do not move all application state into `App.vue`.

## API boundary

The frontend talks to the backend through relative `/api/**` URLs.

Do not hardcode a backend host in components.

Development uses the Vite proxy:

```text
/api/** → http://127.0.0.1:8765
```

Do not change the backend REST contract as part of unrelated frontend work.

Backend project and entry identities are UUIDs. Preserve server-defined identity semantics.

## Development

Backend:

```bash
./mvnw spring-boot:run
```

On Windows use `mvnw.cmd`.

Frontend:

```bash
cd frontend
npm ci
npm run dev
```

Vite is the normal frontend development server. Spring does not need to serve frontend source during development.

## Verification

Backend tests:

```bash
./mvnw test
```

Frontend verification:

```bash
cd frontend
npm run check
```

`npm run check` must cover:

```text
TypeScript typecheck
ESLint
format check
Vitest
Vite production build
```

Full production package:

```bash
./mvnw clean package
```

Do not claim a change is complete when the relevant verification command is failing.

For cross-layer changes, run both backend and frontend verification plus the full package.

## Frontend tests

Use Vitest for frontend application tests.

Keep pure logic tests close to the code where practical:

```text
tags.ts
tags.test.ts
```

Prioritize tests for meaningful logic and regressions over snapshot volume.

Use Vue Test Utils for component behavior when mounting the component is useful.

Do not create custom frontend test runners.

Repository tooling that is not part of the frontend may use the Node built-in test runner where appropriate.

## Packaging

Frontend source is built by Vite:

```text
frontend/src
→ frontend/dist
```

Maven production packaging copies the generated Vite output directly into Maven build output:

```text
frontend/dist
→ target/classes/static
→ Spring Boot JAR
```

Never copy generated frontend files back into tracked source directories.

`./mvnw package` must produce a complete runnable artifact without requiring a separate manual frontend build first.

A packaged JAR does not require Node at runtime.

## Node toolchain

Node is a build/update dependency, not an application runtime dependency.

The canonical required Node version is stored in:

```text
frontend/.node-version
```

Use the Node 24 LTS line unless the project intentionally upgrades it.

The Harmonia Windows distribution owns its build toolchain:

```text
toolchain/
  jdk/
  git/
  node/
```

Do not install bundled tools globally and do not modify the user's global PATH.

An installed application should prefer Harmonia-managed toolchain binaries over arbitrary system binaries.

## Desktop updater

The desktop Electron shell owns update orchestration, managed toolchains, installation and relaunch. Keep `apps/desktop/**` updater and packaging infrastructure independent from the Spring backend. Do not reintroduce a backend `UpdateController`, job-based source updater, or legacy Spring self-update workflow.

When desktop source updates build the backend, toolchain resolution should prefer:

```text
bundled
→ Harmonia cache
→ compatible system tool
→ managed download
```

Downloaded Node versions should be cacheable side by side. A failed desktop update must preserve the existing rollback behavior. Do not weaken dirty-worktree or history-divergence protections while changing updater code.

## Distribution

Windows releases include the toolchain needed for future source builds:

```text
JDK
MinGit
Node
```

Use portable distributions inside the application installation. Do not invoke system-wide installers for these dependencies.

Current release artifacts target Windows x64 and Linux x64 setup binaries and rolling manifests;
the installed application launches the real Electron executable through the managed `current`
pointer for supported installations. MSI, portable ZIP, and macOS releases are outside
the release contract.

The supported installer baseline is 0.1.3. Rolling manifests that require this floor must fail
closed through the generic `UpdaterUpgradeRequired` mechanism on older engines. Installed launches
use the canonical Electron executable under the managed `current` pointer.

A release must not depend on the end user having Java, Git or Node installed globally.

## Data and persistence

SQLite is the default local database. PostgreSQL is an alternate deployment profile. `data/core/harmonia.db` is the only runtime database path. The historical `data/harmonia.db` file is compatibility-only: startup must not create, open, migrate, modify, back up, rename or delete it. Do not implement a legacy importer until the canonical workspace/translation model is complete.

Database schema changes go through Flyway migrations.

When changing schema behavior, keep SQLite and PostgreSQL migrations semantically aligned.

Be particularly careful with operations that can overwrite or delete translations. Data preservation is more important than silently recovering from malformed source input.

## Jobs and concurrency

The legacy project/job subsystem is removed. Canonical source ingestion uses its bounded upload queue and worker. Long-running canonical work remains cancellable where practical, and mutable per-run state must not be introduced into singleton Spring services.

## Filesystem

Treat user/project paths as untrusted input at filesystem boundaries.

Normalize and validate paths before filesystem access.

Do not introduce arbitrary client-controlled file writes outside intended workspace/export locations.

## Secrets

API keys and credentials must never be committed.

Use environment variables, ignored local configuration, or the existing application settings mechanisms.

Do not log secrets.

Tests and committed examples must use neutral synthetic values.

## Git and commits

Default branch:

```text
main
```

Use Conventional Commit style:

```text
feat(scope): ...
fix(scope): ...
refactor(scope): ...
test(scope): ...
build(scope): ...
docs(scope): ...
chore(scope): ...
```

Keep commits focused and reviewable.

Do not commit:

```text
target/
frontend/node_modules/
frontend/dist/
IDE metadata
local databases
workspace data
credentials
machine-specific absolute paths
```

Do not rewrite unrelated code while implementing a focused task.

## CI

CI is expected to verify:

```text
backend tests
frontend check
production package
```

Release CI additionally verifies distribution packaging.

When changing build or toolchain behavior, update CI in the same change so local and CI workflows remain equivalent.

## Documentation discipline

`AGENTS.md` contains stable engineering rules, not exhaustive implementation documentation.

Do not add:

* complete REST endpoint inventories;
* lists of every DTO;
* descriptions of every SQL query;
* CSS widget details;
* current LLM model catalogs;
* temporary implementation notes;
* historical migration notes.

If a subsystem requires detailed documentation, put it under `docs/` or in a focused README near that subsystem.

When architecture changes, update this file only where the stable rules actually changed.
