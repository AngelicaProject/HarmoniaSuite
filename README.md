# Harmonia Suite

[![CI](https://github.com/AngelicaProject/HarmoniaSuite/actions/workflows/ci.yml/badge.svg)](https://github.com/AngelicaProject/HarmoniaSuite/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/AngelicaProject/HarmoniaSuite/branch/main/graph/badge.svg)](https://codecov.io/gh/AngelicaProject/HarmoniaSuite)

Локальный пайплайн локализации FFXIV (EN → RU). Генерирует паки переводов для импорта в Harmonia.

## Требования

- JDK 21+
- API-ключи (опционально): `GEMINI_API_KEY`, `OPENROUTER_API_KEY`
- Фронтенд работает без интернета (Vue завендорен, шрифты системные); сеть нужна только провайдерам перевода

## Запуск в IntelliJ IDEA

1. Open the repo root as Maven project
2. Project SDK: **Java 21**
3. Run `com.harmoniasuite.HarmoniaSuiteApplication`
4. Working directory: repo root
5. UI: http://127.0.0.1:8765

## CLI

```bash
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
node --test tools/test-tags.mjs
```

## Данные

- `data/sources/<версия>/en/` — кэш исходных CSV из игры
- `projects/<имя>/exported_csv/` — результат `merge`

## После патча игры

1. Синхронизируй источники («Источники данных» → «Синхронизировать»).
2. Запусти `node tools/sync-game-data.cjs` — пересоберёт `js/ui-colors.js`
   из `UIColor.csv` и обновит `tools/tag-inventory.json`.
3. Если скрипт сообщил о новых именах тегов или `payload`-тегах — добавь им
   категорию в `TAG_KINDS` (`js/tags.js`), misc оставь только неведомому.

## Релиз фронта

Статика версионируется вручную: при любом изменении `js/`/`css` подними
`?v=` в `index.html` и во всех `import ... from '...?v='`.

## Релиз

Разработка идёт на `*-SNAPSHOT`. Релиз: `versions:set -DnewVersion=X.Y.Z`,
тег `vX.Y.Z`, `./mvnw.cmd package` → `harmonia-suite-X.Y.Z.zip` (внутри `VERSION.txt`).
Какая сборка запущена — `GET /api/version` (`dev` = запуск не из сборки).
