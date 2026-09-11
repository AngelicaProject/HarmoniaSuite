# Harmonia Suite desktop and installer design

Статус: design baseline для поэтапной миграции из browser-centric запуска. Этот документ фиксирует
границы Phase 1 и решения, которые должен соблюдать будущий Rust installer core.

## Пути и владение данными

На Windows application-managed root — `%LOCALAPPDATA%\HarmoniaSuite\`, а user-data root —
`%APPDATA%\HarmoniaSuite\`. На Linux application-managed root — `$XDG_DATA_HOME/harmonia-suite`
(fallback `~/.local/share/harmonia-suite`), user-data root — `$XDG_DATA_HOME/HarmoniaSuite`
(fallback `~/.local/share/HarmoniaSuite`), state — `$XDG_STATE_HOME/harmonia-suite`
(fallback `~/.local/state/harmonia-suite`), cache — `$XDG_CACHE_HOME/harmonia-suite`
(fallback `~/.cache/harmonia-suite`). Эти корни вычисляются без привязки frontend к ОС.

Application root содержит только управляемые артефакты:

```text
bin/                 launchers and activator
versions/<sha>/      immutable staged versions
toolchain/           side-by-side managed Java/Node/Git
source/              managed bare Git mirror
build/<sha>/         disposable detached worktrees
cache/               downloads and build caches
state/               install-state, lock and diagnostics
```

User-data root содержит `data/harmonia.db`, WAL/SHM sidecars, `backups/`, `projects/`, source
exports, logs and user configuration. Installer никогда не делает version directory источником
этих данных и не удаляет их при обычном uninstall.

## Process model и gateway boundary

`HarmoniaSuite` — Electron main process. Он получает single-instance lock, регистрирует
`harmonia://app`, создаёт окно с `nodeIntegration=false`, `contextIsolation=true`, `sandbox=true`
и `webviewTag=false`, затем выбирает единственный active `GatewayProfile`.

В local mode main process выбирает port `0` на `127.0.0.1`, запускает принадлежащий ему Spring
child с `--server.address=127.0.0.1 --server.port=<port>`, ждёт `GET /api/status` и только после
readiness загружает UI. В remote mode child не запускается; proxy разрешает HTTPS gateway (HTTP
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

`state/install.json` хранит `currentCommit`, `previousCommit`, transaction id и component
metadata. Activation сначала пишет и fsync-ит временный state, затем атомарно заменяет state-файл
(с platform-specific replace semantics). Current всегда разрешается через этот state; никакого
in-place обновления исполняемого current нет. Updater/activator — отдельный process, поэтому
самый старый running executable не заменяет себя.

## Manifest baseline

Manifest versioned и расширяемый; подпись проверяется встроенным Ed25519 public key до чтения
artifact URLs:

```json
{
  "schemaVersion": 1,
  "channel": "rolling-main",
  "targetCommit": "<full sha256 git commit>",
  "productVersion": "1.2.0",
  "components": {"desktop": "1", "gateway": "1", "installer": "1", "extractor": "1"},
  "artifacts": [{"component": "desktop", "platform": "windows", "arch": "x64",
    "url": "https://...", "sha256": "<64 lowercase hex>"}],
  "toolchain": {"java": "21.x", "node": "24.x", "git": "<version>"},
  "minimumInstallerVersion": "1",
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

## Known migration delta

The current Java `UpdateService` still performs `git pull` and `git reset --hard` in a checkout, and
the current release workflow publishes a Windows-only Java/AWT + external .NET unpacker package.
These are legacy paths, not the new contract. They remain untouched in Phase 1 except that backend
desktop lifecycle hooks are disabled; responsibility moves to `apps/installer` in later phases.
