# План реализации

Составлен 2026-09-29 по итогам `COMPARE.md` (сравнение с GoLand 2026.2). Что сделано и проверено — в `ROADMAP.md`; здесь — что делать дальше,
от самого дешёвого и полезного к самому долгому. Сделанный пункт отмечать здесь и переносить в `ROADMAP.md`.

Оценки — чистое время работы одного человека без учёта проверки роботом: **часы** (до половины дня), **день**, **дни** (2–4), **неделя+**.
У каждого пункта: зачем, как (файлы и API), как проверить. Порядок внутри уровня — по пользе для ежедневной работы.

## Порядок

1. **Уровень 0** — проверить вживую то, что написано, но не проверено: полдня-день роботом, снимает риск, что «готовое» не работает.
2. **Уровень 1 (часы)** — раздражители первой недели: инструменты разом, go.mod, статусы в gutter, линтер до сохранения, стек паники.
3. **Уровень 2 (день)** — подтесты и табличные кейсы, покрытие, fuzz, рендереры отладчика, монитор для отладки.
4. **Уровень 3 (дни)** — бенчмарки, горутины, зависимости, remote debug, ошибки сборки без gopls, endpoints.
5. **Уровень 4 (неделя+)** — профили в IDE, Go templates, свой парсер (сделан 2026-10-02, `MIGRATION.md`).
6. **Паритет с GoLand (G1–G9)** — все различия, снятые с живого GoLand 2026.2.3; это то, что значит «доведи до уровня GoLand» (см. `CLAUDE.md`).

---

## Уровень 0. Проверить написанное (полдня–день, робот)

Всё из `playground/README.md` с «нет» в колонке «Робот», в порядке риска:
- [x] Ctrl+наведение на идентификатор: подчёркивание и переход (`GoplsGotoDeclarationHandler`) — 3.7; роботом не проверяется (AWT Robot в RDP-сессии не даёт подсветки), смотреть вживую
- [x] Клики по gutter I↓/I↑ — робот (2026-09-29); [x] клики по code vision — 3.5, 3.6
- [x] Попап «Refactorings and actions of gopls…» — робот (2026-09-29)
- [x] Диалог Struct Tags — робот (2026-09-29); [x] Implement Interface, Test — 2а.6–2а.7
- [x] Apply на странице gopls с перезапуском сервера; Build flags / Env словами — 9.1б, 9.1в
- [x] Attach to Process — робот (2026-09-29); [x] completion в Evaluate, значение при наведении — 8.15, 8.6
- [x] Узел Dependencies с раскрытием (нужен проект с зависимостью: добавить в playground) — 5.2
- [x] New Project «Go», New Go Module…, Go | Build с переходом к ошибке — 5.5, 6.1–6.4
- [x] Add Import…, Browse Documentation/Assembly, Toggle Optimization Details, Open Debug Pages — 3.24–3.27
- [x] Disable Plugins Not Needed for Go, уведомление «go не найден» (временно убрать go из PATH)

Пройдено 2026-09-30: всё из списка и всё с пометкой `[~]` в `ROADMAP.md` проверено пользователем вживую; то, что не сработало, исправлено по ходу.

## Уровень 1. Часы

Сделано 2026-09-29 всё, кроме отмеченного; подробности и статус проверки — в `ROADMAP.md` (проверено пользователем вживую 2026-09-30).

- [x] **Установка инструментов разом.** Одно уведомление при открытии Go-проекта: «Не хватает: dlv, goimports, golangci-lint — Install All / Configure».
  Как: в `GoToolchainCheckActivity` пройти `GoTool.entries`, собрать отсутствующие, `GoTool.install` последовательно в одной `Task.Backgroundable`.
  Проверка: робот, песочница с пустым GOBIN.
- [x] **delve новее toolchain — честное сообщение.** Сейчас глушится `--check-go-version=false`. При отказе `launch` с текстом «Go version … is too old»
  (или при старте, сравнив `dlv version` и `go version`) — уведомление «delve X требует Go ≥ Y, у вас Z» с кнопками Update Go (go.dev/dl) и Install matching delve
  (`go install …/dlv@vN`). Как: `GoDebugProcess` (ветка отказа), `GoTool.DELVE.install` с версией. Проверка: юнит-тест на разбор версий.
- [x] **Analyze Go Stack Trace.** Меню Go | Analyze Stack Trace…: диалог с текстом → консоль с `GoConsoleFilters` (ссылки уже кликабельны), заголовок
  `goroutine N [state]` и `panic:` жирным. Как: `AnalyzeStacktraceUtil.addConsole` платформы + свой `Filter`. Проверка: робот вставляет панику из playground.
- [x] **Folding блоков в go.mod** (`require (…)`, `replace`, `exclude`, `retract`, `use`, `tool`). Как: `FoldingBuilder` по токенам `LPAREN`/`RPAREN` в `GoModLanguage`
  (парсер плоский, скобки в лексере есть). Час.
- [x] **Баннер после правки go.mod.** `EditorNotificationProvider` для go.mod: после сохранения с изменённым набором `require` — «Run go mod tidy | Download | Dismiss».
  Как: сравнивать `GoModFile.parse` до/после через `GoModulesService` (кэш по modificationStamp уже есть), действия — существующие `GoModTidyAction`/`GoModDownloadAction`.
- [x] **Статусы прогонов в gutter ▶.** Иконка `TestState.Green/Red` вместо `Run` у `Test*`, если есть статус в `GoTestStatuses` (уже копит любой прогон).
  Как: `GoRunLineMarkerContributor` спрашивает `GoTestStatuses`. Час.
- [x] **Предупреждения линтера не пропадают до сохранения.** Сейчас аннотатор возвращает пусто для изменённого документа. Хранить последний результат в
  `RangeMarker`-ах и показывать со сдвигом, пока файл не сохранён и не перелинтован. Как: `GoLintAnnotator.collectInformation` → кэш `List<RangeMarker + issue>`. Часы.
- [x] **Ссылки в консоли тестов для неоднозначных имён.** В тестовой консоли пакет известен (`gotest://<pkgDir>|…`), `order_test.go:39` резолвить относительно
  каталога пакета текущего узла. Как: `GoTestConsole` ставит свой `Filter` с приоритетом над `GoConsoleFilterProvider`. Часы.
- [x] **Правила идиом серым текстом:** `if !ok` после comma-ok (`v, ok := m[k]`, `x, ok := y.(T)`), `defer wg.Done()` первой строкой в `go func()` после `wg.Add`,
  `defer close(ch)` (сделано 2026-09-29 вместе с остальным по серому тексту, см. ROADMAP), `for rows.Next()` после `Query`. Как: `GoIdioms` — новые регулярки, юнит-тесты в `GoIdiomsTest`. По часу на правило.
- [x] **Постфиксы `.sort`, `.rrv`** (`sort.Slice(x, func(i, j int) bool {…})`, `return x, nil` с переменной). `GoPostfixTemplates`, полчаса.
- [x] **Structure view: методы под типом.** Группировать `METHOD` под объявление типа с тем же именем получателя (в этом файле); прочие — как сейчас.
  Как: `GoStructureViewFactory`, дети у STRUCT/INTERFACE/TYPE. Часы. Проверка: робот, Alt+7.
- [x] **Run configuration: галочки Race и `-count=1`** (частые флаги отдельными полями, всё равно уходят в `goArguments`). `GoSettingsEditor`, `GoRunConfigurationOptions`. Часы.
- [x] **golangci-lint fmt как форматтер** (v2): вариант в «Reformat Code with» — `golangci-lint fmt --stdin`? Сначала проверить, есть ли stdin-режим (`golangci-lint fmt --help`);
  если нет — по файлу с временной копией. `GoFormattingService`. Часы.
- [x] **Платформенный тест загрузки plugin.xml** и состава меню Go (`BasePlatformTestCase`, `ActionManager`): ловит битые регистрации до песочницы. Часы.

## Уровень 2. День каждый

Сделано 2026-09-29; первый шаг покрытия (свой gutter, без `CoverageEngine`), рендереры без `time.Time` (delve форматирует сам). Проверено пользователем вживую 2026-09-30, см. `ROADMAP.md`.

- [x] **Запуск подтеста и кейса табличного теста из gutter.** Самый частый способ писать тесты в Go. Иконка ▶ у `t.Run("name", …)` и у строки `{name: "x", …}`
  внутри `tests := []struct{…}{…}` в функции `TestX` → паттерн `^TestX$/^name$` (имя экранировать, пробелы → `_` как делает `go test`).
  Как: `GoRunTargets.of` — сканер тела тестовой функции по токенам: строковый литерал первым аргументом `t.Run(` или значение поля `name`/`Name`/`desc`/`testName` в composite literal;
  `GoRunLineMarkerContributor` на этих строках; `GoTestLocator` — переход к `t.Run`/кейсу по имени подтеста. Проверка: робот на `TestTotal/empty`.
- [x] **Покрытие тестов.** `-coverprofile=<tmp>/cover.out` в run configuration (галочка Coverage или отдельный executor «Run with Coverage»); разбор формата
  `file:startLine.startCol,endLine.endCol stmts count` — тривиален; показ: подсветка строк в gutter (`RangeHighlighter` с `EditorColors` coverage-ключами) и процент у пакета
  и функции в окне Go Tests. Полная интеграция с `CoverageEngine` платформы (окно Coverage, отчёт) — ещё 2–3 дня, делать вторым шагом.
  Как: `GoCoverage.parse` (чистая функция + тест), `GoTestRunState` — уведомление «Coverage is ready» и подсветка открытых файлов.
- [x] **Fuzz как режим.** Галочка Fuzz в конфигурации → `go test -run ^$ -fuzz ^FuzzX$ -fuzztime=…`; gutter у `FuzzX` — пункт «Run fuzzing»; падение → `testdata/fuzz/FuzzX/<id>`
  кликабельно. Как: `GoRunConfigurationOptions.fuzz`, `GoTestEvents` (вывод fuzz идёт не в `-json`-событиях теста, а как `output` пакета — показывать в консоли).
- [x] **Рендереры значений в отладчике.** `[]byte` как строка (с переключателем), `error` — текст `Error()` через `call` по требованию, `time.Time` — `call t.String()`
  лениво (вызов в остановленной программе; если delve откажет — сырой вид), срезы/map — `len/cap` в заголовке (delve уже отдаёт `[]int len: 3, cap: 3`, оставить как есть),
  длинные строки — `XFullValueEvaluator` (попап «View»). Как: `GoValue.computePresentation`, `GoDebugValues`. Проверка: робот, Variables с `order`, `time.Now()`, `[]byte("x")`.
- [x] **Монитор для сессии отладчика.** Событие DAP `process` несёт `systemProcessId` → зарегистрировать в `RunningGoProcesses` как «Debug: name (pid)»; CPU/память от ОС
  сразу, телеметрия — если в `launch.env` добавить `GODEBUG` (галочка та же, строки рантайма приходят как `output` категории stderr — фильтровать в `GoDebugProcess`).
  Как: `GoDebugProcess` (событие `process`), `GoMonitorSession`.
- [x] **Ошибки сборки в редакторе без gopls.** Аннотатор по последнему `go build`/`vet` (как `BuildProblemsAnnotator` в dotnet-плагине): `GoBuildOutputParser` уже
  разбирает строки; хранить по файлу и показывать, пока файл не изменён. Страховка для IDE без LSP-модуля. Как: `build/GoBuildProblems` (project service) + `Annotator`.
- [x] **Type Info (Ctrl+Shift+P) и Go to Type Declaration.** Из hover gopls: тип выражения — первая строка сигнатуры; переход к типу — `textDocument/typeDefinition`
  (gopls умеет, платформа не показывает). Как: `GoplsNavigation` + `TypeDeclarationProvider` и `ExpressionTypeProvider` EP. День.
- [x] **Find Usages / Go to Implementation с места использования.** После проверки уровня 0: если платформа не резолвит наш токен, в `GoplsUsageSearcher` и
  `GoplsImplementationSearch` принимать любой `IDENTIFIER`-лист, спрашивать gopls `definition`, затем `references`/`implementation` от найденной позиции.
- [x] **Autotest в окне Go Tests** и автообновление дерева при правке `_test.go` (`VirtualFileListener` → `GoTestExplorer.refresh` с debounce). День.

## Уровень 3. Дни

- [x] (2026-09-29, робот) **Бенчмарки таблицей и сравнение.** Разбор строк `BenchmarkX-8  N  ns/op  B/op  allocs/op` из вывода (событие `bench` + `output`) → вкладка с таблицей в Run window
  (`JBTable`: имя, итерации, ns/op, B/op, allocs/op); хранить последний прогон по пакету в workspace, показывать дельту в % (упрощённый `benchstat`). Галочка `-benchmem` в конфигурации.
  Как: `testing/GoBenchmarks` (чистый парсер + тест), `GoTestConsole` — дополнительная вкладка консоли. 2 дня.
- [x] (2026-09-29, робот; без «где создана» и меток) **Горутины в отладчике.** Отдельная вкладка «Goroutines» в Debug window: группировка по функции верхнего фрейма и состоянию (`Running`, `waiting`, `chan receive`…),
  фильтр пользовательских (не `runtime.*`), поиск, «где создана» (delve отдаёт в имени `[Go N] fn (state)`; для created-by нужен `stackTrace` каждой — лениво),
  двойной клик — переключить стек. Как: `GoDebugFrames` + `XDebugSessionTab` extra content. 2–3 дня.
- [x] (2026-09-29, робот) **Окно зависимостей.** `go list -m -u -json all` → таблица модуль / версия / доступное обновление / прямая-косвенная; кнопки Upgrade (`go get path@ver`), Tidy,
  govulncheck (gopls `vulncheck` уже есть — результат показывать в таблице пометкой). Как: `mod/GoModuleUpdates` (парсер + тест), tool window или вкладка в узле Dependencies. 2–3 дня.
- [x] (2026-09-29, робот) **Completion в go.mod.** Пути модулей — из `GOMODCACHE/cache/download` и `go list -m all`; версии — `GOPROXY/<path>/@v/list` (сеть, кэш на сессию); версии `go`
  — по установленному toolchain. Как: `CompletionContributor` для `GoModLanguage` (лексер даёт DIRECTIVE/WORD/VERSION). 2 дня.
- [x] (2026-09-29, робот) **Remote debug и режимы delve.** Конфигурация «Go Remote»: host:port уже запущенного `dlv dap --listen` (пропустить `DelveProcess`, только сокет; `launch`/`attach`
  как сейчас) — день; «Go Exec» (`mode: exec`, путь к бинарнику, без сборки) — полдня; core dump (`mode: core`, `coreFilePath`) — полдня. Как: `GoRunConfigurationOptions.kind`,
  `GoDebugRunner`, `GoLaunchArguments`.
- [ ] **Watchpoints.** Точки на функцию сделаны (2026-09-30, ROADMAP). Осталось `setDataBreakpoints` через `dataBreakpointInfo` на переменной в Variables.
  Проверено 2026-10-04 по исходникам delve v1.27.2 (`service/dap/server.go`, `onInitializeRequest`): `supportsDataBreakpoints` объявляется только на
  linux/darwin amd64/arm64, на Windows — нет. Делать вместе с Run Targets (WSL), где это можно проверить вживую; действие показывать только при
  capability. Как: новый `XBreakpointType` в `GoDebugBreakpoints`. День.
- [ ] **Endpoints.** Сканер по токенам: `http.HandleFunc("/path"`, `mux.Handle`, `r.Get/Post/…("/path"` (chi, gin, echo, fiber, gorilla) → tool window Endpoints
  (метод, путь, обработчик, переход) и Search Everywhere по URL. Без резолва: по строковым литералам и имени метода. 2–3 дня.
- [ ] **Extract Interface** на PSI: интерфейс по выбранным методам типа (Change Signature, Safe Delete и Move уже свои — 0.2.47–0.2.58). День.
- [ ] **go.mod: остаток.** Уже есть (0.2.44–0.2.84): `replace`/`use` в никуда, дубли, неиспользуемый require, версии go/toolchain, новее с Upgrade to и линзами.
  Осталось: уязвимые версии в файле (по кэшу `govulncheck` окна Go Dependencies), intentions Upgrade to version…, Remove requirement (`go get path@none`),
  Make direct / indirect, Add replace, Exclude; `psi.referenceContributor`: путь модуля → каталог в module cache, Find Usages пути в импортах. Правки — только
  через `go get` / `go mod tidy` (иначе разойдётся go.sum). День.
- [ ] (2026-10-02) **Менеджер пакетов через GOPROXY: окно Go Packages.** Расширение окна Go Dependencies. Чистый клиент `mod/GoProxyClient` по протоколу прокси
  (`/@v/list`, `/@latest`, `/@v/<v>.info`, `.mod`, `.zip`; GOPROXY из `go env` с `,`/`|` и `direct`, GOPRIVATE/GONOSUMDB уважать; разбор ответов — без сети, тесты):
  ввод пути модуля с подсказкой из `index.golang.org/index` (кэш ленты, поиск по подстроке локально — у GOPROXY поиска нет) и из GOMODCACHE, кнопка «Open on pkg.go.dev»;
  карточка модуля: версии с датами, зависимости из `.mod`, README и лицензия из `.zip` (один файл, лениво, в памяти), требуемая версия Go; Add dependency… с выбором версии,
  Remove, обновление — через `go get path@version` и `go mod tidy`; уязвимости — `govulncheck` (есть) и `vuln.go.dev` по пути модуля. Fallback на `go list -m -versions`,
  если корпоративный прокси не отдаёт `@latest`/index. Один кэш состояний модулей кормит окно и подсветку go.mod (пункт выше). 2–3 дня.
- [ ] (2026-10-02) **Подсказки имён переменных и эвристический ранкер completion.** Имён нет вовсе: `NameSuggestionProvider` платформы (rename, completion после `:=`,
  параметры и результаты функции, `for … range`) по таблице «тип → имя» (`*os.File` → `file`/`f`, `context.Context` → `ctx`, `error` → `err`, `bytes.Buffer` → `buf`,
  `sync.Mutex` → `mu`, `*testing.T` → `t`, `time.Duration` → `d`/`timeout`) с откатом к последнему слову имени типа и инициалам, множественное число для срезов и map;
  тип — `GoSemanticService.typeOf`. Ранжирование: реализация точки `GoCompletionRanker` go-psi-ide без модели — частота имени в файле и пакете (стабы), недавность
  (последние выбранные элементы из `CompletionStatistician`/своего LRU), совпадение с ожидаемым типом уже учтено weigher'ом; каталог символов (`ope` → `os.Open` + импорт,
  уже работает) подключить к тому же ранжированию (волна 3 §11: unimported members). ML-версия — `docs/ML.md`, только после этого. 1–2 дня.
- [ ] **Go SDK из IDE.** Список версий с go.dev/dl, загрузка и распаковка в `~/sdk/goX`, запись в Path to go; переключение между установленными. 2 дня.
- [~] **Run Targets (Docker / WSL / SSH).** Сделано (0.2.98, 2026-10-04, робот на sshd в WSL): Debug `go run` / `go test` на SSH-хосте — программа и
  встроенный delve кросс-собираются здесь, копируются через `ssh 'cat > file'`, `dlv dap` на 127.0.0.1 там через `ssh -W` (`run/GoSsh`, `debugger/GoSshDebug`).
  0.2.99: «Files to copy» и testdata (`tar | ssh`, права 600, без повтора при неизменном наборе), delve на unix-сокете вместо TCP-порта.
  Осталось: Run (не Debug) на хосте; attach к процессу там; Docker (`docker cp` / `docker exec`) и WSL без sshd (`wsl.exe`: EOF stdin через interop не доходит — seen live).

## Уровень 4. Неделя и больше

- [ ] **Профили в IDE: свой просмотрщик.** Страница `go tool pprof -http` во вкладке редактора уже есть (2026-09-30, ROADMAP), но из неё нет перехода к коду.
  Осталось: разбор профиля (`-proto`) своим кодом, таблица Top и flame graph с переходом к функции, горячие строки в gutter; для обычной программы —
  «Open pprof» по адресу `net/http/pprof` из кода. Неделя.
- [ ] **Go templates** (`html/template`, `text/template`): свой язык для `{{ … }}` внутри HTML/текста (`TemplateLanguage` платформы), подсветка actions, completion
  функций (`if range with template block define end`, builtin funcs), переход к `define`. 1–2 недели.
- [x] (2026-10-02) Свой парсер и PSI — go-psi, подключён шагами 1–10 `MIGRATION.md`.
- [ ] **cgo** — навигация и подсветка вокруг `import "C"`: только если появится запрос (ассемблер `.s` сделан в 0.2.60–0.2.66).

## Паритет с GoLand (по живой разведке 2026-10-05)

Источник — `docs/goland-analysis/README.md` (что снято и как), дампы `docs/goland-analysis/dumps/*.txt` (точные пункты, тексты, списки), сверка
с кодом плагина 2026-10-05 (по исходникам, не роботом; «частично» — что есть, но не как у GoLand). Блоки — в порядке пользы для ежедневной
работы; внутри блока — сверху вниз. Эталон спорного поведения — переснять на GoLand (`tools/ui-robot/goland/start-goland.ps1`), не угадывать.
Готовность блока: юнит/платформенные тесты + те же пробы на песочнице (`TARGET=plugin`, `tools/ui-robot/goland/analysis/*.sh`) совпали с дампом GoLand.
Пересечения с уровнями выше помечены ссылкой — делать один раз.

### G1. Вид кода в редакторе (≈6 дней)
- [x] (2026-10-05) **Сворачивание однострочников**: `if err != nil { return … }`, функция с одним `return`, ветка `case` → в одну строку с текстом тела; пустые функции
  и типы; 5 галочек в Code Folding (`CodeFoldingOptionsProvider`), по умолчанию включены, как в GoLand. `GoFoldingBuilder`. 1 день.
- [x] (2026-10-05) **Цветовые ключи как у GoLand** (64, `color-keys-go.txt`; у нас 28): экспортируемая / локальная функция, вызов, тип (struct / interface, объявление
  и ссылка), константа (пакетная экспорт. / локальная / локальная в функции), переменная пакета; получатель отдельно от параметра; поле экспорт. / нет;
  вызов переменной-функции и поля-функции; встроенная переменная `nil`; переменная области (`for` / `if` / `switch`); переприсваивание в `:=`;
  экранирование valid / invalid; `:`; comment keyword (`go:generate`); build-тег (тег, скобки, операторы). Fallback новых ключей — на текущие, чтобы
  пользовательские схемы не поменялись. `GoColors`, `GoSemanticHighlightingAnnotator`, `GoColorSettingsPage` (демо-текст с примерами). 2 дня.
- [x] (2026-10-05) **Затенение для всех переменных**: ключ `GO_SHADOWING_VARIABLE` + weak warning «Declaration of 'x' shadows declaration at line N» + Alt+Enter
  Navigate to shadowed declaration / Rename. Сейчас только `GoShadowedError` и модификатор gopls. Resolve по областям. 1 день.
- [x] (2026-10-05) **Глаголы `%d %s %v %w`** подсвечены внутри строки формата printf-подобных (тот же список, что у `GoPrintfInspection`); ключ verb с fallback на escape. 0,5 дня.
- [x] (2026-10-05) **Тег структуры**: ключ / `:` / значение / прочий текст — свои ключи; разбор из `GoStructTags`. 0,5 дня.
- [x] (2026-10-05) **Bash в `//go:generate`**: `MultiHostInjector`, если в IDE есть Shell Script (опциональная зависимость). 0,5 дня.
- [x] (2026-10-05) **Гаттер Recursive call** у вызова своей же функции / метода. `LineMarkerProvider` + resolve. 0,5 дня.

### G2. Code vision и inlay (≈2 дня)
- [x] (2026-10-05) Линза **Implement interface** над каждой структурой → наш Implement Methods (Ctrl+I). 0,5 дня.
- [x] (2026-10-05) Линза **Add method to interface and all its implementations** над интерфейсом (вместе с G6 «Add Method»). 0,5 дня.
- [x] (2026-10-05) Usages / implementations **в конце строки** объявления, как в GoLand (`CodeVisionAnchorKind` по настройке платформы, проверить, что наша позиция настраивается). 0,5 дня.
- [x] (2026-10-05) Inlay **имена результатов** (`Show return parameters`) у вызова с именованными результатами; inlay параметров — только у литералов и неясных аргументов, как в GoLand (сверить с `GoParameterNameHintsProvider`). 0,5 дня.
- Линзы «Batch syntax update» и «What's New» (modernizer) — в G5. Линзы Change signature / Rename refactoring и Code author — платформенные, проверить, что работают с нашим PSI.

### G3. Completion (≈5 дней)
- [x] (2026-10-05) **Fill all fields… / Fill selected fields…** пунктами в lookup литерала `T{}` (первыми); выбор — литерал по строкам с выравниванием; Fill selected — диалог выбора полей. Сейчас только intention. 1 день.
- [x] (2026-10-05) **Значение константы в пункте**: `MaxItems = 10 : untyped int`, `Debug = iota : Level`. 0,5 часа.
- [x] (2026-10-05) Верхний уровень: пункты **`func (*T) : Method`** и **`func : Implement Interface...`**. Часы.
- [x] (2026-10-05) **Имена параметров по типу** в `func g(`: `err error`, `base Base`, `string2 string`. Вместе с подсказкой имён переменных (уровень 3, «Подсказки имён переменных»). 1 день вместе.
- [x] (2026-10-05) Тег: в completion ключа — пункт **Add tag key to all fields…**; имя в `json:"…"` — **4 стиля** (`full-name`, `full_name`, `FullName`, `fullName`), сверить с `GoStructTagCompletionContributor`. Часы.
- [x] (2026-10-05) **Postfix в общем списке после `.`** (как у GoLand: `c.` → поля, методы, затем `p`, `panic`, `par`…), подбор по типу (`error` → `as is nn nil notnil`). 0,5 дня.
- [x] (2026-10-05) **Postfix — недостающие ключи** (`postfix-go.txt`): `! & * d p pointer dereference aappend appendAssign cap copy close delete complex imag real println remove as is parseInt parseFloat`;
  `.sort` — вариант по типу (`sort.Strings` / `Ints` / `Float64s` / `sort.Sort`); `.print` как builtin `print()` — у нас `fmt.Println`, оставить свой и добавить `println`;
  **вывод имён**: `.var` → `area := c.Area()`, `.forr` → `name` из `names` (единственное число). `GoPostfixTemplates`. 1 день.
- [x] (2026-10-05) **Live templates — недостающие** (`live-templates-go.txt`): `map p imports consts vars types iota :`, тег `xml`; 9 шаблонов Go Template — вместе с Go templates (уровень 4). Часы.
- [ ] (G3, осталось; 2026-10-05 роботом сверены `c.`, `err.`, `Holder{`, `fmt.`, Alt+Enter в 12 точках и подсветка `analysis.go` — см. ROADMAP) Сверить роботом пробы, где у нас «есть» по коду: `return err` первым на пустой строке, `&Square` по набору методов, импорт при выборе `json.Marshal`, json/v2, раскладка времени `YYYY MM DD`.

### G4. Alt+Enter — недостающие intentions (≈3 дня; список — `intentions-go.txt` и `alt-enter-…txt`)
- [x] (2026-10-05) Импорты: **Import for side-effects** (`_`), **Add import alias**, **Add / Remove dot import alias**.
- [x] (2026-10-05) Строки и вызовы: **Put arguments / elements on separate lines** (и обратно), **Join concatenated string literals**, Convert to raw string (сверить с `GoChangeQuote`).
- [x] (2026-10-05) Формат: **Add format string argument**, **Exclude string formatting function** (+ список printf-подобных в настройках, G8).
- [x] (2026-10-05) Ошибки: **Do not report this method/function anymore** у Unhandled error (список исключений в инспекции).
- [x] (2026-10-05) Теги: **Change field name style in tags**, **Update key value in tags**.
- [x] (2026-10-05) Выражения: **Flip binary operator**, **Negate expression** (4 вида), **Specify type explicitly**, **Specify dot type** (не Go: фича Go templates).
- [x] (2026-10-05) Литералы: **Remove keys from struct literal**, **Fill all fields recursively** (не сделано, отдельный заход), **Move field assignment to struct initialization**.
- [x] (2026-10-05) Сигнатуры: **Expand / Reuse signature types** (`a, b int` ↔ `a int, b int`).
- [x] (2026-10-05) Объявления: **Export** (переименовать в экспортируемое), **Migrate function parameter to method receiver**, Merge declaration up / via comma, Split declarations (сверить с `GoSplitDeclaration`).
- [x] (2026-10-05) Unresolved: Create **global variable** / **parameter** (функция, метод, поле, тип, переменная есть).
- [x] (2026-10-05) Навигация из Alt+Enter: Go to Implementations / Interfaces / Method Specifications, **Navigate to shadowed declaration** (G1); **Run go generate** на комментарии / файле / пакете.
- [x] (2026-10-05) go.mod: Merge a group of directives / all directives / directive up, Update dependencies….

### G5. Go fix (modernize) нативно (≈4 дня)
- [x] (2026-10-05, 0.2.131–0.2.135) Группа инспекций **Go fix** (уровень `SYNTAX_UPDATE`, с учётом версии `go` в go.mod), простые: `any` вместо `interface{}`, `min`/`max`, range over int, переменная цикла (go 1.22),
  `slices.Contains` / `Sort` / `Backward`, `strings.Cut` / `CutPrefix`, `maps` вместо цикла, `new(expr)`, `omitzero`, `//go:build` вместо `// +build`, embed-литерал,
  `net.JoinHostPort`, `WaitGroup.Go`, `t.Context()`, `reflect.TypeFor`, `errors.AsType`. Каждое — инспекция + quick fix + тест. 2,5 дня.
- [x] (2026-10-05, 0.2.136) С типами: `strings.SplitSeq`, `strings.Builder` в цикле, `atomic.Int64` и т. п., `unsafe.*`. Не сделано: `//go:fix inline` (нужен inline),
  итераторы stdlib сверх `slices.Backward` / `maps.Keys`, поля структур в `atomic.Int64` (нужна правка нескольких файлов).
- [x] (2026-10-05, 0.2.137) **Refactor | Update Syntax…**: все Go fix по области (платформенный прогон инспекций группы, результаты и Fix all в Inspection Results;
  своего диалога предпросмотра, как у GoLand, нет) + линзы «Update syntax (N places)» и «What's New» в файле.

### G6. Рефакторинги и Generate (≈6 дней)
- [x] (2026-10-05, 0.2.138) **Override Methods** (Ctrl+O): методы встроенных типов для переопределения.
- [x] (2026-10-05, 0.2.139) **Extract Interface** — уровень 3, пункт уже есть. **Introduce Type** (тип из выражения / литерала).
- [x] (2026-10-05, 0.2.140) **Add Method** во интерфейс **и все реализации** (было с G4, `GoAddInterfaceMethodIntention`) + **Remove method from interface and all its implementations**.
- [x] (2026-10-05, 0.2.142–0.2.144) **Introduce Parameter**, **Introduce Field**, **Introduce Parameter Object**.
- [x] (2026-10-05, 0.2.145–0.2.146) **Invert Boolean**, **Copy** (файл — платформа, объявление — своё Copy Declaration…).
- [x] (2026-10-05, 0.2.141) Generate: **Tests for package**, **Method** (диалог), **Copyright**.

### G7. Инспекции — недостающие (≈6 дней; `inspections-go.txt`)
- [x] (2026-10-05, 0.2.147–0.2.149; 28 инспекций, `for true` / `Replace(…, 0)` / пробел в директиве уже были правилами S1006 / SA1018 / SA9009) Дёшево (синтаксис / локально), по часу–два: Code style — пробел после `//`, комментарий экспортируемого начинается не с имени (сверить с `GoDocComment`),
  текст ошибки с заглавной / точкой, `var A, B int` у экспортируемых, имя начинается с имени пакета, имя получателя (`this`/`self`, разные имена), лишний `else`
  после `return`, `for true`, тип-параметр в нижнем регистре, `timeoutSeconds time.Duration`, несортированные импорты, snake_case, литерал без имён полей;
  Redundancy — пустое объявление, `[]T{}` → nil slice, лишние запятая / `;` / скобки / алиас импорта / тип в составном литерале / тип у `var`/`const`,
  неиспользуемый тип-параметр; Probable bugs — `defer recover()`, имя = имя импорта, предобъявленное имя, `strings.Replace(..., 0)`, неправильный `iota`, пробел в директиве,
  кривой build-тег (сверить с `GoBuildConstraint`), смешанные получатели, `err.(*T)` на обёрнутой ошибке; Control flow — присваивание получателю. 3 дня.
- [x] (2026-10-05, 0.2.150) Data flow: **Constant condition**, **деление на ноль**, лишнее приведение, экспортируемая функция с неэкспортируемым типом (Error may be not nil и
  разыменование nil уже были). Не сделано: межпроцедурная nil-ность (`GoMaybeNil`) — нужна сводка по функциям.
- [x] (2026-10-05, 0.2.151) Неиспользуемые **функции, глобальные переменные, константы, типы** (не только экспортируемое) — поиск ссылок по индексу; экспортируемые — как GoLand
  (internal / приложение; типы только в `main`).
- [ ] go.mod: [x] (2026-10-05, 0.2.152) **deprecated** модуль, **retracted** версия (данные `go list -m -u -json`); [ ] миграция replace → go.work, неразрешённый путь в `ignore`, слияние `require`.
- [ ] **Vulnerable API usage** в коде (результат govulncheck — подсветка вызова) и импорт уязвимого пакета. 1 день.

### G8. Настройки и помощь при наборе (≈4 дня)
- [ ] Imports: **Add unambiguous imports on the fly**, **Optimize imports on the fly**, **Show import popup**, исключения из импорта / completion. 1 день.
- [ ] Code Style \| Go \| Imports: сортировка goimports / gofmt / нет, группа «проект» или local prefixes (`goimports -local`), один блок, удалять лишние алиасы; Wrapping (аргументы, литералы, параметры). 1 день.
- [ ] Список **printf-подобных функций** (Settings + Alt+Enter Exclude). Часы.
- [ ] Переименование: **файл ↔ `_test`-файл**, **тег** при переименовании поля, каталог ↔ пакет (есть) — с выбором Show options / делать / не делать. 0,5 дня.
- [ ] **Вставка JSON → тип Go** при Ctrl+V (спросить / конвертировать / как есть; `CopyPastePreProcessor`). 0,5 дня.
- [x] (2026-10-05, 0.2.153–0.2.154) Actions on Save: **Optimize imports** отдельной галочкой (Reformat есть); Go Modules: vendoring, загрузка зависимостей (четыре варианта GoLand),
  Environment для команд go. Не сделано: загрузка зависимостей при открытии проекта (только после сохранения go.mod).
- [x] (2026-10-05, 0.2.155) Debugger Data Views \| Go: формат целых (dec / hex / bin / оба), адреса указателей (только скрытие: delve адрес пройденного указателя не шлёт), String() view.
- [x] (2026-10-05, 0.2.156) Набор: **Reformat block on typing `}`** (платформенный `indentBrace` со встроенным форматтером, тесты закрепляют; вживую — проверить); заготовка
  doc-комментария по Enter после `//` над объявлением (`GoDocCommentEnterHandler`).

### G9. Запуск, инструменты, меню (≈5 дней)
- [ ] **Coverage через платформенный `CoverageEngine`**: окно Coverage, отчёт, Run with Coverage как executor (сейчас свой gutter). 2 дня (это «второй шаг» из уровня 2).
- [ ] **Run with Profiler** executor (CPU / Memory / Block / Mutex) поверх поля Profile + наш просмотрщик (уровень 4). 0,5 дня без просмотрщика.
- [ ] **Dump Goroutines** работающего процесса (SIGQUIT / `debug.SetTraceback`, на Windows — через delve attach, `GoSnapshot`). 0,5 дня.
- [ ] Tools \| Go Tools: **Go Fmt Project**, **Go Vet File**, **Goimports File**; **Share in Playground** / Run in Playground (с подтверждением, как у GoLand). 0,5 дня.
- [ ] Project view: **Sync Go Module**, GOPATH — Add Directory to Current Project / Detach. 0,5 дня.
- [ ] Окно **Go Optimization** нативно: `go build -gcflags=-m=2` → инлайнинг, escape, bounds checks в дереве и в редакторе (сейчас только переключатель gopls). 1 день.
- [ ] Тулбар: Go Settings… и Actions on Save… кнопками. Часы.
- [ ] Analyze Data Flow to / from Here на `GoDataflow`; Locate Duplicates и Code Cleanup для Go — проверить, что платформа работает с нашим PSI и quick fix-ами. 1 день.

Не повторяем: rr (Record / Rewind / Debug Saved Trace) — только Linux, уйдёт вместе с Run Targets; Code author — платформа (VCS).

## Не делать (по замыслу плагина)

- Настройки code style, которые спорят с gofmt. Исключение — то, что gofmt не решает и есть в GoLand (G8): группировка импортов как у `goimports -local`, перенос длинных строк.
- Свои списки опций gopls — страница генерируется из `gopls api-json`.
- Платформенный DAP-клиент — свой клиент работает в любой IDE.
- HTTP Client, Database, Docker, Kubernetes, Terraform, AI — это плагины IDE-хоста, не Go-плагина.

---

## Каталог символов зависимостей: что осталось

Сделано (2026-09-29, пакет `catalogue`, см. ROADMAP): stdlib и прямые зависимости, свой кэш на `module@version` вместо библиотечных корней платформы —
с корнями платформа строила бы по зависимостям все свои индексы, а нужен один список экспортируемых имён на пакет.

- [x] (2026-09-29) Пакеты самого проекта: отдельный индекс платформы `GoExportsIndex`, см. ROADMAP.
- [ ] Косвенные зависимости: по настройке, их сотни; сначала замерить время и размер на большом go.mod.
- [ ] Smart completion: функции каталога, возвращающие ожидаемый тип (`GoExpectedTypes`); типы каталога сейчас — текст сканера.
- [ ] Методы типов каталога: сейчас только объявления верхнего уровня без получателя.
