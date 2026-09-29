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
5. **Уровень 4 (неделя+)** — профили в IDE, Go templates, свой парсер.

---

## Уровень 0. Проверить написанное (полдня–день, робот)

Всё из `playground/README.md` с «нет» в колонке «Робот», в порядке риска:
- [ ] Ctrl+наведение на идентификатор: подчёркивание и переход (`GoplsGotoDeclarationHandler`) — 3.7; роботом не проверяется (AWT Robot в RDP-сессии не даёт подсветки), смотреть вживую
- [x] Клики по gutter I↓/I↑ — робот (2026-09-29); [ ] клики по code vision — 3.5, 3.6
- [x] Попап «Refactorings and actions of gopls…» — робот (2026-09-29)
- [x] Диалог Struct Tags — робот (2026-09-29); [ ] Implement Interface, Test — 2а.6–2а.7
- [ ] Apply на странице gopls с перезапуском сервера; Build flags / Env словами — 9.1б, 9.1в
- [x] Attach to Process — робот (2026-09-29); [ ] completion в Evaluate, значение при наведении — 8.15, 8.6
- [ ] Узел Dependencies с раскрытием (нужен проект с зависимостью: добавить в playground) — 5.2
- [ ] New Project «Go», New Go Module…, Go | Build с переходом к ошибке — 5.5, 6.1–6.4
- [ ] Add Import…, Browse Documentation/Assembly, Toggle Optimization Details, Open Debug Pages — 3.24–3.27
- [ ] Disable Plugins Not Needed for Go, уведомление «go не найден» (временно убрать go из PATH)

Что не сработает — чинить сразу, это дешевле любой новой фичи.

## Уровень 1. Часы

Сделано 2026-09-29 всё, кроме отмеченного; подробности и статус проверки — в `ROADMAP.md` (пометка `[~]`: вживую не проверено, только компиляция и юнит-тесты).

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
  `defer close(ch)`, `for rows.Next()` после `Query`. Как: `GoIdioms` — новые регулярки, юнит-тесты в `GoIdiomsTest`. По часу на правило.
- [x] **Постфиксы `.sort`, `.rrv`** (`sort.Slice(x, func(i, j int) bool {…})`, `return x, nil` с переменной). `GoPostfixTemplates`, полчаса.
- [x] **Structure view: методы под типом.** Группировать `METHOD` под объявление типа с тем же именем получателя (в этом файле); прочие — как сейчас.
  Как: `GoStructureViewFactory`, дети у STRUCT/INTERFACE/TYPE. Часы. Проверка: робот, Alt+7.
- [x] **Run configuration: галочки Race и `-count=1`** (частые флаги отдельными полями, всё равно уходят в `goArguments`). `GoSettingsEditor`, `GoRunConfigurationOptions`. Часы.
- [x] **golangci-lint fmt как форматтер** (v2): вариант в «Reformat Code with» — `golangci-lint fmt --stdin`? Сначала проверить, есть ли stdin-режим (`golangci-lint fmt --help`);
  если нет — по файлу с временной копией. `GoFormattingService`. Часы.
- [x] **Платформенный тест загрузки plugin.xml** и состава меню Go (`BasePlatformTestCase`, `ActionManager`): ловит битые регистрации до песочницы. Часы.

## Уровень 2. День каждый

Сделано 2026-09-29; первый шаг покрытия (свой gutter, без `CoverageEngine`), рендереры без `time.Time` (delve форматирует сам). Вживую не проверено, см. `ROADMAP.md`.

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
- [ ] **Точки на функцию и watchpoints.** `setFunctionBreakpoints` (тип точки без строки, диалог «имя функции»), `setDataBreakpoints` через `dataBreakpointInfo` на переменной
  в Variables (delve поддерживает с 1.21). Как: новые `XBreakpointType` в `GoDebugBreakpoints`. 1–2 дня.
- [ ] **Endpoints.** Сканер по токенам: `http.HandleFunc("/path"`, `mux.Handle`, `r.Get/Post/…("/path"` (chi, gin, echo, fiber, gorilla) → tool window Endpoints
  (метод, путь, обработчик, переход) и Search Everywhere по URL. Без резолва: по строковым литералам и имени метода. 2–3 дня.
- [ ] **Change Signature / Extract Interface / Safe Delete на gopls.** Полного Change Signature у gopls нет; есть code actions `refactor.rewrite.removeUnusedParam`
  и `moveParamLeft` / `moveParamRight` (v0.17+) — они уже попадают в попап «Refactorings and actions of gopls…», нужно лишь проверить и упомянуть на Help Page.
  Extract Interface — свой генератор по методам типа (сканер знает сигнатуры) — день; Safe Delete — `references` перед удалением объявления с диалогом — день.
- [ ] **Go SDK из IDE.** Список версий с go.dev/dl, загрузка и распаковка в `~/sdk/goX`, запись в Path to go; переключение между установленными. 2 дня.
- [ ] **Run Targets (Docker / WSL / SSH)** — зависит от IDE-хоста; сначала WSL: путь `\\wsl$`, `go` из дистрибутива, delve там же по TCP. 2–3 дня.

## Уровень 4. Неделя и больше

- [ ] **Профили в IDE.** Первый шаг (день): `go tool pprof -top -nodecount=200` → таблица Top в tool window, клик — к функции. Второй (неделя): flame graph
  (`go tool pprof -raw` или `-proto` + разбор protobuf профиля своим кодом, `TimeSeriesChart`-подобный компонент), горячие строки в gutter. Третий: профиль обычной программы
  через `runtime/pprof` требует кода в программе — предлагать `net/http/pprof` по импорту («Open pprof» по адресу из кода), как в PLAN 5.
- [ ] **Go templates** (`html/template`, `text/template`): свой язык для `{{ … }}` внутри HTML/текста (`TemplateLanguage` платформы), подсветка actions, completion
  функций (`if range with template block define end`, builtin funcs), переход к `define`. 1–2 недели.
- [ ] **Свой парсер и PSI Go** (недели): локальные инспекции, surround/unwrap, Find Usages и Go to Implementation с места использования без gopls, Change Signature,
  Move, Type/Call Hierarchy. Кандидат — грамматика заброшенного go-lang-idea-plugin (Apache 2.0; проверить лицензию, generics и модулей там нет). Отдельное решение:
  не начинать, пока gopls-путь не упрётся в потолок.
- [ ] **cgo и Plan9 assembly** — подсветка `.s` файлов и `import "C"`: только если появится запрос.

## Не делать (по замыслу плагина)

- Настройки code style сверх отступов — стиль задаёт gofmt.
- Свои списки опций gopls — страница генерируется из `gopls api-json`.
- Платформенный DAP-клиент — свой клиент работает в любой IDE.
- HTTP Client, Database, Docker, Kubernetes, Terraform, AI — это плагины IDE-хоста, не Go-плагина.

---

## Приложение. Кэширование ответов gopls

Кэшировать completion смысла нет: ответ LSP привязан к версии документа и позиции, любое нажатие клавиши меняет версию, и кэш попадал бы только при повторном
вызове в том же месте. gopls и так держит у себя типы и пакеты — это основная стоимость запроса.

Что реально можно ускорить на стороне плагина:
- [ ] **Документация к пунктам списка** (`completionItem/resolve`): дока символа меняется редко, ключ «символ + пакет». Самый заметный эффект при листании списка.
- [ ] **Hover** по ключу «файл + версия + позиция»: повторное наведение и быстрая дока без обращения к серверу.
- [ ] **Completion с `isIncomplete=false`**: клиент фильтрует первый ответ локально, без новых запросов. Сначала посмотреть, что gopls отдаёт сейчас.
- [ ] Настройки самого gopls: `completionBudget`, отключение ненужных анализаторов.

Запросы шлёт платформа, а не плагин, поэтому кэш придётся встраивать прокси-слоем вокруг процесса gopls — тем же приёмом с потоками, что в отладчике
(`DapMessageRewritingStream` / `DapMessageWatchingStream`).

- [ ] **Сначала замер:** включить трассу LSP (`-rpc.trace`, окно gopls) и посмотреть, какие запросы медленные и как часто повторяются, чтобы не кэшировать то, что и так
  отвечает за миллисекунды.
