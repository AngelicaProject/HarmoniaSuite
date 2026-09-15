# Harmonia Suite desktop and installer design

Статус: design baseline для desktop запуска и Rust installer core. Этот документ фиксирует
границы Phase 1 и решения, которые должен соблюдать installer core.

## Пути и владение данными

На Windows application-managed root — `%LOCALAPPDATA%\HarmoniaSuite\`, а user-data root —
`%APPDATA%\HarmoniaSuite\`. На Linux application-managed root — `$XDG_DATA_HOME/harmonia-suite`
(fallback `~/.local/share/harmonia-suite`), user-data root — `$XDG_DATA_HOME/harmonia-suite-data`
(fallback `~/.local/share/harmonia-suite-data`), state — `$XDG_STATE_HOME/harmonia-suite`
(fallback `~/.local/state/harmonia-suite`), cache — `$XDG_CACHE_HOME/harmonia-suite`
(fallback `~/.cache/harmonia-suite`). Эти корни вычисляются без привязки frontend к ОС.

Application root содержит только управляемые артефакты:

```text
bin/                 installer helper and activator
versions/<sha>/      immutable staged versions
current              filesystem pointer to the active immutable version
toolchain/           side-by-side managed Java/Node
source/              managed bare Git mirror
build/<sha>/         disposable detached worktrees
cache/               downloads and build caches
state/               install-state, lock and diagnostics (Windows; Linux uses XDG state_root)
```

User-data root содержит canonical database `data/core/harmonia.db`, its WAL/SHM sidecars, source
exports, logs and user configuration. The historical `data/harmonia.db` path is a compatibility
boundary only: the runtime leaves an existing file untouched. Installer никогда не делает version
directory источником этих данных и не удаляет их при обычном uninstall.

## Process model и gateway boundary

`HarmoniaSuite` — Electron main process. Он получает single-instance lock, регистрирует
`harmonia://app`, создаёт окно с `nodeIntegration=false`, `contextIsolation=true`, `sandbox=true`
и `webviewTag=false`, затем выбирает единственный active `GatewayProfile`.

В local mode main process запускает принадлежащий ему Spring child с
`--server.address=127.0.0.1 --server.port=0 --harmonia.gateway-instance=<token>`, принимает
от него после bind точный порт через identity-bound readiness marker, затем ждёт `GET /api/version`
и только после readiness загружает UI. В remote mode child не запускается; proxy разрешает HTTPS gateway (HTTP
только для loopback или явного development override). Renderer видит только относительные
`/api/**`: `harmonia://app/api/**` проксируется main process, остальные пути custom protocol
отдают Vue `dist` и fallback на `index.html`.

Закрытие последнего окна останавливает owned gateway и завершает Electron. Backend не открывает
браузер, не создаёт Java AWT tray и не управляет desktop shutdown.

## Update state machine

Installer и updater используют один core и persistent transition log:

```text
Idle -> ResolvingTarget -> PreparingToolchain -> FetchingSource
     -> PreparingWorktree -> BuildingFrontend -> BuildingBackend
     -> BuildingDesktop -> Verifying -> Staging -> WaitingForShutdown
     -> Activating -> HealthChecking -> Completed
```

Любой recoverable failure переходит в `Failed`; ошибка после activation проходит через
`RollingBack` к предыдущему verified version. При старте installer читает последнюю запись
transaction: незавершённый staging удаляется только если он создан этим installer, а после
сомнительной activation выбирается current/previous по валидированному state и health check.

## Current/previous/staging layout и activation

Каждая версия собирается в `versions/<full-sha>.staging/`, проверяется, затем переименовывается в
`versions/<full-sha>/`. Внутри находятся `desktop/`, `frontend/`, `gateway/`, `extractor/` и
`metadata.json`; immutable version directories не изменяются после activation.

`state_root/install.json` хранит `currentCommit`, `previousCommit`, transaction id и component
metadata. Activation сначала публикует crash-safe filesystem pointer `current` на полностью
проверенную версию, затем сохраняет state (с platform-specific replace semantics). Pointer и
transaction journal используются вместе: rollback сначала возвращает pointer на snapshot current,
затем восстанавливает installation state. Current не является копией версии, а только symlink
(Linux) или managed directory junction (Windows); immutable version directories не изменяются
после activation. Updater/activator — отдельный process, поэтому самый старый running executable
не заменяет себя.

Поддерживаемая установка запускает настоящий Electron runtime напрямую из
`current/desktop/HarmoniaSuite.exe` на Windows или `current/desktop/harmonia-suite` на Linux.
`HarmoniaSetup.exe`/`harmonia-setup` остаётся installer/updater/repair/uninstall helper. В payload
создаётся только canonical executable; signed manifest с `minimumInstallerVersion` выше текущего
engine отклоняется через `UpdaterUpgradeRequired` до staging.

Installed Electron получает runtime context из canonicalized `process.resourcesPath` и
`versions/<sha>/metadata.json`: backend, managed Java, installer и `state` проверяются внутри
управляемого application root. Launcher-specific `HARMONIA_*` environment variables не являются
частью installed-runtime contract; для update handoff допускаются только operation-scoped launch
acknowledgement variables.

## Manifest baseline

Manifest versioned и расширяемый; подпись проверяется встроенным Ed25519 public key до чтения
artifact URLs:

```json
{
  "schemaVersion": 1,
  "channel": "rolling",
  "targetCommit": "<full Git object id / commit SHA>",
  "productVersion": "1.2.0",
  "components": {"desktop": "1", "gateway": "1", "installer": "1", "extractor": "1"},
  "artifacts": [{"component": "desktop", "platform": "windows", "arch": "x64",
    "url": "https://...", "sha256": "<64 lowercase hex>"}],
  "toolchain": {"java": "21.x", "node": "24.x", "git": "<version>"},
  "minimumInstallerVersion": "0.1.3",
  "signature": {"algorithm": "ed25519", "keyId": "<id>", "value": "<base64>"}
}
```

Target is resolved once as exact `origin/main` SHA and all build inputs are derived from that SHA.
Archives are downloaded to `.partial`, hashed before extraction/execution, and retained in cache.

## DB migration and rollback strategy

DB, workspace and backups belong to user-data root. Before first launch of a target whose backend
may migrate the schema, desktop/backend is stopped and a consistent SQLite backup is created using
the existing `VACUUM INTO`/SQLite backup mechanism; raw-copy of a live WAL database is forbidden.
If the backup cannot be created, activation is blocked. The backup records target SHA and schema
version in diagnostics.

Flyway migrations remain forward-only and are kept semantically aligned for SQLite/PostgreSQL.
Rollback first switches the immutable application version back to `previousCommit`; it must not
pretend that an already-applied DB downgrade is safe. If the previous binary cannot read the new
schema, recovery uses the pre-update DB backup through an explicit, diagnosed restore path while
preserving the failed target and current data. No migration may delete/overwrite translations
without a deliberate migration and backup coverage.

## Known non-production update path

Spring no longer owns source updates or checkout mutation. Update responsibility belongs to the
transactional `apps/installer` flow, while the desktop shell owns launch and gateway orchestration.
Release platform and artifact boundaries are documented in
[`release-operations.md`](release-operations.md).
