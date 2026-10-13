# tools/compare — сравнение плагина с GoLand и gopls

Отчёты — `docs/compare/<проект>-{broken,completion,refactorings,typing}-<дата>.md`, сырые данные — рядом, в `*-dumps/`.
Проект сравнения — клон в `build/compare/<проект>` на зафиксированном коммите (prometheus v3.15.0: `git clone --depth 1 --branch v3.15.0
https://github.com/prometheus/prometheus build/compare/prometheus && cd build/compare/prometheus && go mod download`).

## Три стороны и чем они сняты

| Сторона | Чем | Где |
|---|---|---|
| Плагин, быстро | стенд на JUnit (`BasePlatformTestCase`): клон — content root light-проекта, локальный toolchain (host GOOS/GOARCH, GOROOT, GOMODCACHE), gopls выключен, включены Go-инспекции по умолчанию и шаблоны в completion. Включается только `GO_HARNESS=1`, в обычном `test` молчит; с ним heap тестовой JVM — 8 ГБ | `src/test/kotlin/io/github/golangsupport/compare/*HarnessTest.kt` |
| Плагин, вживую | те же JS-драйверы, что для GoLand, в песочнице IDEA с плагином: `./gradlew.bat runIdeForUiTests --no-configuration-cache`, открыть копию клона, `TARGET=plugin` (порт 8083) | `tools/ui-robot/goland/analysis/*.js` |
| GoLand | `tools/ui-robot/goland/start-goland.ps1 -Project <копия клона>` (порт 8595), `export GOLAND_PID=<pid>` | те же драйверы |
| gopls | скрипты LSP-клиента | `tools/compare/*.py`, `tools/compare/refactor/gopls_refactor.py` |

GoLand и песочница плагина работают на **копии** клона (`git clone --no-hardlinks build/compare/prometheus build/compare/prometheus-goland`):
IDE создаёт `.idea`, а скрипты рефакторингов пишут в файлы и откатывают их через git. Одна IDE с роботом за раз.

## Часть 1: ошибки на сломанном коде

- `oracle/` — оракул на go/packages + go/types: по каждой ошибке файл, строка, колонка, **код** (`UndeclaredName`, `WrongArgCount`, … — имена
  из `$GOROOT/src/internal/types/errors/codes.go`) и текст, одна JSON-строка на ошибку. В отличие от `go build`, не обрезает вывод после
  десяти ошибок. Запуск: `cd tools/compare/oracle && go run . -dir <каталог модуля> [-tests] ./pkg/...` (или `go build -o oracle.exe .`).
- `broken_oracle.py` — эталон по файлу поломок `путь|было|стало`: по одной кладёт на диск, запускает `go build -gcflags=-e`, `go vet` и
  оракул на пакете и возвращает файл байт в байт.
- `GoBrokenHarnessTest` — плагин: `testClean` (фон чистого клона, `GO_HARNESS_PACKAGES`) и `testBroken` (поломки только в документе).
- `broken_compare.py` — сверка по (файл, строка, колонка, текст), вердикты, таблица.

## Часть 2: completion

- `sites/` — выбор мест для «восстанови удалённое» по go/types с видом места (`go run . -dir <клон> -per-kind 60 <файлы…>`).
- `GoCompletionHarnessTest` — плагин (кусками: `GO_HARNESS_FROM`, `GO_HARNESS_LIMIT`, `GO_HARNESS_NAME`); `gopls_completion.py` — gopls;
  `tools/ui-robot/goland/analysis/restore_complete.js` — GoLand / живая IDEA (пачками; пример цикла — в отчёте части 2).
- `completion/scenarios.json` + `completion/probe/zz_compare_probe.go` — 40 ручных сценариев: `complete_batch.js` (IDE),
  `GoCompletionScenariosHarnessTest` (стенд). Пробный файл кладётся в `rules/` клона на время прогона.
- `completion_stats.py`, `completion_causes.py` — таблицы top-1 / top-5 / латентность и разбор причин.

## Часть 3: рефакторинги

`refactor/scenarios.json` (36), `GoRefactorHarnessTest` (стенд, на своей копии клона), `refactor/gopls_refactor.py`,
`refactor/apply_and_build.py` (сборка результатов на отдельной копии), `refactor/compare.py`; IDE — `refactor/goland_run.sh` +
`tools/ui-robot/goland/analysis/refactor_probe.js` (Alt+Enter, Refactor This; Rename / Safe Delete / intentions выполняются, после каждого —
diff, `go build`, откат копии); ручные шаги для диалоговых рефакторингов — `refactor/goland-steps.json`.

## Часть 4: клавиши

`typing/scenarios.txt` (80) → `typing/to_json.py` → `GoTypingHarnessTest` (стенд) и `tools/ui-robot/goland/analysis/typing_batch.sh`
(`PG_DIR` — открытый проект; `TARGET=plugin` — песочница); `typing/compare.py` — таблица и diff. Проверка глазами —
`playground/compare/typing_manual.go`, `playground/compare/completion_manual.go` (метки `MARK:` и `EXPECT:`).

## Коды ошибок go/types

`codes/extract.py` — примеры из `$GOROOT/src/internal/types/errors/codes.go` по правилам `codes_test.go`, по пакету на пример
(`build/compare/codes`); эталон — `oracle` на этом модуле; плагин — `GoErrorCodesHarnessTest` (`GO_HARNESS_ROOT=build/compare/codes`);
`codes/compare.py` — вердикт по каждому коду. Весь `internal/types/testdata` — корпусный прогон `GorootTypesTestdataCorpusTest`
(`:go-psi-semantic:corpusTest`). Отчёт — `docs/compare/go-error-codes-<дата>.md`.
