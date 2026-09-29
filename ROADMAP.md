# Go Project Support — дорожная карта

Отметки: `[x]` сделано, `[~]` сделано, но не проверено вживую, `[ ]` не начато. «Робот» — проверено UI-роботом в песочнице IDEA 2026.1.4.

## Язык (без инструментов)
- [x] Лексер, подсветка, директивы `//go:`, страница цветов — робот
- [x] Аннотатор: встроенные типы / константы / функции, имена объявлений, вызовы — робот
- [x] Сканер объявлений → PSI, Structure view, breadcrumbs — робот
- [~] Folding (тела, группы, комментарии), Go to Class / Symbol, commenter, скобки, кавычки
- [~] Live templates (`err`, `errw`, `forr`, `fori`, `main`, `meth`, `test`, `ttest`, `bench`, `gof`, `deff`, `sel`, `pf`, `json`)
- [x] Отступы при наборе как у gofmt (`GoIndentEngine`: уровень на строку с открытыми скобками, `case` на уровне `switch`, продолжение выражения), Enter между скобками, Code Style | Go (табы) — робот
- [x] Идиомы серым текстом с принятием по Tab: `if err != nil` с `return` под сигнатуру функции, `defer` для отмены контекста, мьютекса, файла, тела ответа, строк и транзакции (настройка) — робот (`if err`), остальное юнит-тесты;
  [~] `if !ok` после comma-ok (map → `fmt.Errorf("unknown %v", key)`, type assertion → `unexpected type %T`, канал → нули), `defer wg.Done()` первой строкой `go func()` под `wg.Add`, `for rows.Next() {}` после `defer rows.Close()` (тело пустое, каретка после блока) — юнит-тесты
- [x] Structure view: методы под своим типом, когда тип объявлен в том же файле (`GoStructureViewFactory.Element`) — робот
- [~] Постфиксы `.errn` (`return nil, err`) и `.sort` (`sort.Slice`) — не проверено
- [x] Цвета как в GoLand: semantic tokens gopls (пакеты, ссылки на типы, поля, константы, параметры, встроенные) → свои ключи (`GoSemanticColors`), цвета в `colorSchemes/GoDarcula.xml` и `GoDefault.xml`; без gopls аннотатор сам красит имена пакетов перед точкой — проверено пользователем в установленном плагине. Не сделано: отдельный цвет получателя метода (gopls отдаёт его как обычный параметр)
- [x] `*_test.go` — scope «Tests» платформы: зелёный фон в дереве и вкладках — робот
- [x] New → Go File (пустой, программа, тест; пакет — из соседних файлов или имени каталога), New Go Module — шаблон теста проверен роботом, диалог нет
- [~] New Project: категория «Go» в модульном мастере (IDEA-семейство и форки, где `directoryProjectGenerator` не показывается, — GIGA IDE), в духе GoLand: поля GOROOT (автоопределение) и module path под именем/расположением, Finish делает `go mod init` и (по галочке) пишет `main.go`. `GoNewProjectWizard` на EP `newProjectWizard.generator`. Вживую не проверено

## gopls (content-модуль `lsp`)
- [x] Запуск на проект, go.mod / go.work тоже; inlay hints, диагностика — робот
- [x] Настройки: staticcheck, gofumpt, inlay hints, build tags; перезапуск при Apply и из меню
- [x] Страница Settings | Tools | Go | gopls: форма, сгенерированная из каталога установленного gopls (`gopls api-json`): группы документации (Build, Formatting, Completion, Diagnostics…), галочка / список / поле по типу опции, списки и `NAME=value` без JSON, галочки для ключей словарей (линзы, подсказки), диалог с поиском для 244 анализаторов, экспериментальные опции в сворачиваемых подгруппах, поиск, Reset all. Контролы показывают действующее значение (умолчание gopls или то, что задаёт плагин — `GoplsDefaults`), хранится только отличие — юнит-тесты (`GoplsValuesTest`, `GoplsCatalogueTest`), страница и диалог анализаторов просмотрены роботом; Apply с перезапуском сервера вживую не проверен
- [x] Usages / implementations над объявлениями, Find Usages и Go to Implementation с объявления, Go to Declaration через PSI-цели (чего нет в LSP-клиенте платформы) — робот; подсветка под Ctrl + мышь вживую не проверена
- [~] С места использования: `GoplsTargetElementEvaluator` (`targetElementEvaluator` для Go) резолвит имя в объявление через `definition` gopls — Find Usages, Go to Implementation
  и подсветка использований стартуют с любого места: объявление верхнего уровня (`NewOrder(`, `.Total()`) или локальная переменная / параметр (цель — токен её объявления,
  `GoFindUsagesProvider.isLocalName`) — робот (6 usages `order` из main.go); имя пакета — нет. Go to Type Declaration (`typeDefinition`) — робот (`order` → `struct Order`);
  Type Info Ctrl+Shift+P (первая строка hover) — не проверено
- [x] Gutter-иконки I↓ / I↑: реализации интерфейса и его методов, интерфейсы типа и его методов (один запрос `implementation` в обе стороны) — робот, клик по I↓ у `Priced` даёт попап «Implementations of Priced»: Order, Discounted
- [x] Code lens gopls и команды `gopls.*`: линзы показывает платформа (go.mod: tidy, vendor, vulncheck, check for upgrades / upgrade; `//go:generate`), но клик она отправляла уведомлением без ответа — теперь клик идёт через `GoplsCommands`: `run_tests` запускает наш раннер тестов, `generate` — `go generate` в Build window, остальное — запрос с прогрессом в статус-баре, ошибка — в балун и в лог. Подменю **Go | gopls**: Add Import… (список известных пакетов), Browse Documentation / Assembly / Free Symbols (веб-страницы gopls), Toggle Compiler Optimization Details, Check for Dependency Upgrades, Upgrade All, Run govulncheck, Reset go.mod Diagnostics, Show Statistics, Show Log, Open Debug Pages, Settings, Restart. Проверено роботом: tidy, ошибка upgrade несуществующего модуля (в логе), `run test` → конфигурация TestTotal в раннере, generate, Check for Upgrades, Statistics; линза `test` у gopls по умолчанию выключена (у нас есть gutter) — включается на странице gopls; действия Browse*/Add Import/Toggle вживую не проверены
- [x] Виджет статуса и окно с логом сервера: элемент виджета Language Services с иконкой gopls, страницей настроек Go и действием Show Log; tool window **gopls** — stderr сервера (с `-rpc.trace` по настройке — весь протокол), `window/logMessage` и `showMessage`, старт / инициализация с версией / остановка («Stopped unexpectedly» с подсказкой), отправленные команды и их ошибки; кнопки Restart, Open Debug Pages (`-debug=localhost:0` по настройке, адрес из лога), Settings. Роботом просмотрено окно и лог падения gopls (паника при пустом URI — воспроизводилась только скриптом робота); попап виджета робот не открыл

## Ежедневная работа с кодом (как в GoLand)
- [x] Автоимпорт при дополнении: `strings.ToUp` → `ToUpper` добавляет `import "strings"` (gopls отдаёт `additionalTextEdits`, платформа применяет) — робот
- [x] Постфиксные шаблоны (`GoPostfixTemplates`): `.if .else .nil .notnil .err .errv .return .rr .var .for .fori .forr .range .len .print .printf .panic .go .defer .append .not .switch .wrap`; выражение слева от точки — по тексту (цепочки, вызовы, индексы, литералы) — робот: `.nil`, `.err`, `.for`, `.var`, `.return`
- [x] Live templates: 41 шаблон (`err errw errn ife fn main init meth str inter enum ctx tctx hf mu wg ch mk sw tsw ticker lf test ttest tr helper bench fuzz example tmain errt erras sentinel opt gof deff sel pf forr fori`)
- [x] Complete Statement (Ctrl+Shift+Enter, `GoSmartEnterProcessor`): `{}` после `if/for/func/switch/select/type … struct`, закрытие скобок вызова, каретка внутрь — робот
- [x] Alt+Insert (`GoGenerateActions`): Constructor, Getters, Setters, Getters and Setters, String() Method (с выбором полей), Struct Tags (json/yaml/xml/db/mapstructure/toml, snake/camel/как есть/lowercase, omitempty), Implement Interface (интерфейсы проекта, только недостающие методы), Test (табличный, в `_test.go` рядом) — робот: пункты в Generate, диалог Struct Tags (OK → `json:"currency"`); попап Implement Interface и остальные диалоги вживую не открывались
- [x] Alt+Enter (`GoIntentions`): Handle error на одиночном вызове (число результатов из hover gopls), Add if err != nil check после присваивания ошибки, Add missing return, Create function из вызова (параметры по аргументам, результаты по левой части `a, err :=`, метод при `s.name()` в методе), Add struct tags, Implement interface, Generate test — робот: первые четыре
- [x] Дополнение в struct-тегах (`GoStructTags`): ключи (json, yaml, xml, toml, db, bson, mapstructure, koanf, msgpack, csv, validate, binding, form, query, param, uri, header, url, env, envDefault, envPrefix, default, gorm, description, example) → `key:""` с кареткой внутри и новым списком; имя поля в snake/camel/lower/как есть/kebab (для env — UPPER_SNAKE); опции после запятой (omitempty, omitzero, string, inline, squash…); правила go-playground/validator для validate/binding (через `,` и `|`); настройки gorm через `;`. Live templates больше не разворачиваются внутри строк и комментариев (`json` + Tab внутри кавычек давал ``json:""`` — найдено пользователем) — робот
- [x] Док-комментарий: `//` на пустой строке над `func/type/var/const` → `// Name ` (настройка Editor, по умолчанию включено) — робот
- [x] Страница помощи Go | Help Page (и Help-меню): вкладка редактора в стиле сайтов JetBrains, светлая/тёмная по теме, клавиши из текущей раскладки, разделы Editing / Generate and fix / Navigation / Run… / Tools / Tips — робот (снимок)

## Модули
- [x] go.mod / go.work: тип файла, подсветка, разбор (`GoModFile`)
- [x] Folding блоков `require (…)`, `replace (…)` и остальных в go.mod / go.work (`GoModFoldingBuilder`) — робот
- [~] Узел Dependencies в Project view с исходниками из module cache (в playground нет зависимостей — не проверено)
- [x] Меню Go → Modules: Tidy, Download, Vendor
- [ ] Окно зависимостей: обновления (`go list -m -u all`), `go get`, уязвимости (`govulncheck`)
- [x] Баннер над go.mod после сохранения с другими require / replace / exclude: «Run go mod tidy | Download | Dismiss» (`GoModSaveListener`
  сравнивает текст с диском до записи, `GoModNotificationProvider`) — робот: баннер после добавления require, tidy из баннера убрал строку и баннер

## Build / Run / Test
- [~] Build, Vet, Generate → Build tool window с переходом к ошибке
- [x] Run configuration «Go» (`go run` / `go test`), gutter-иконки, producer — робот
- [x] Дерево тестов из `go test -json`: подтесты, skip, fail, Rerun Failed, переход к тесту — робот
- [x] Консольные фильтры: `file.go:12` компилятора, тестов и стеков паник кликабельны — робот (консоль тестов). Голое имя файла находится, только если оно одно в проекте;
  [~] в консоли тестов неоднозначное имя ищется под каталогом прогона (`GoTestOutputFilter`) — вживую не проверено
- [x] Окно Go Tests: пакеты и тестовые функции проекта, Run / Debug выбранного, Run All, статусы последних прогонов (`GoTestStatuses`, слушатель проекта) — робот;
  [x] Run with Coverage, процент у пакета, переключатель «Rerun the Tests of a Package on Save» (`GoAutoTest`, пауза 1,5 с после сохранения) — робот; дерево обновляется само при появлении `_test.go` — робот
- [x] Статус последнего прогона в gutter ▶ теста (зелёный / красный / жёлтый для skip; daemon перезапускается при новом статусе) — робот
- [~] **Подтесты и кейсы табличных тестов**: ▶ у `t.Run("name", …)` и у `{name: "case", …}` (поля `name desc description testName title scenario tc caseName label`),
  паттерн `^TestX$/^case$`, пробелы → `_`, литералы с `%` и `\` пропускаются; переход из дерева тестов к строке подтеста (`GoSubtests`, юнит-тест) — робот: ▶ на строках кейсов,
  запуск `TestTotal/empty` из контекста каретки с паттерном `^TestTotal$/^empty$`, локатор `TestTotal/empty` ведёт на строку кейса
- [~] **Покрытие**: галочка Coverage в конфигурации и Run with Coverage в Go Tests → `-coverprofile` во временный файл, разбор (`GoCoverage`, юнит-тест), полосы
  в gutter открытых файлов (зелёная / красная / жёлтая для строк, попавших в блоки с разным счётчиком; цвета `CodeInsightColors.LINE_*_COVERAGE`), уведомление
  с процентом и Hide Coverage, процент у пакетов в Go Tests (`GoCoverageService`) — робот: 72,7 % по `store`, 20 полос в order.go, уведомление, процент в окне
- [~] Fuzz как режим: галочка Fuzz в конфигурации (`-run ^$ -fuzz <паттерн>`), в gutter у `FuzzX` — Run Fuzzing (`GoFuzzAction`) — не проверено
- [x] Галочки Race (`-race`) и `-count=1` в конфигурации — робот (`go test -json -race -count=1 .` в консоли)
- [x] Автогенерация конфигураций для каталогов с `func main` (настройка; удалённая не возвращается) — робот
- [x] Бенчмарки таблицей: строки `BenchmarkX-N iterations ns/op B/op allocs/op` (и любые другие метрики, `MB/s`) собираются из `go test -json` (`GoBenchmarkCollector`:
  строка приходит двумя событиями `output`, склеивается по `\n` — seen live), по окончании прогона — вкладка **Benchmarks** в окне Go Tests с колонками и Δ % против
  прошлого прогона того же пакета (последние два прогона хранятся в workspace, `GoBenchmarkResults`); галочка `-benchmem` в конфигурации (по умолчанию включена) — робот (два прогона, Δ −3,0 %)
- [x] Go | Analyze Stack Trace…: диалог платформы (`Unscramble`) — робот (диалог открывается; ссылки в его консоли не проверены)
- [x] Ошибки последнего Go | Build / Vet в редакторе (`GoBuildProblems` + внешний аннотатор), только когда LSP-модуля нет или gopls выключен, для сохранённых файлов — робот (gopls остановлен, две ошибки компилятора в lint.go)

## Отладчик (пакет `debugger`, delve)
- [x] **Свой DAP-клиент** на XDebugger API вместо платформенного (`intellij.platform.dap` есть не во всех IDE и форках): перенесён из dotnet-плагина (`DapConnection` — транспорт, `GoDebugProcess`, фреймы, значения, точки), content-модуль `dap` и зависимость `bundledModule("intellij.platform.dap")` убраны. С ним отпали трюки вокруг закрытого клиента: переписывание `setBreakpoints` на потоке, подсматривание `variablesReference` из трафика, сторож неудачного `launch`, правка события `stopped`. Робот (2026-09-22): точка, стек, переменные, шаги (into / over / out), Evaluate с `call`, условие точки, hit count, log message (в консоль без остановки), Set Value строки и вложенного поля через указатель, остановка на панике с `exceptionInfo`, отказ сборки → уведомление и закрытая сессия; после Stop нет `dlv.exe` / `__debug_bin`
- [x] Запуск `dlv dap` по TCP, launch для `go run` и `go test`, остановка на точке, стек, переменные, шаги, evaluate — робот
- [~] Отладочный бинарь собирается во временный каталог (`output` в launch: `$TMPDIR/__debug_bin<uuid>`), а не в каталог пакета, как в GoLand; удаляется по завершении сессии (`deleteOnExit` на случай блокировки в Windows) — юнит-тест на путь, вживую не проверено
- [x] Отказ запуска (не компилируется, версия Go) — уведомление с выводом компилятора, сессия закрывается — робот
- [x] Лог delve на сессию, Show Debugger Logs, Trace Debugger Protocol (свой трассировщик: `delve/protocol/protocol-*.log`, registry `go.debugger.protocol.trace`)
- [x] Hit count, logpoints, условие точки, точки на паники — робот
- [~] Значение при наведении (`GoHoverExpression`) — вживую не проверено; значения в редакторе рядом с кодом (`GoInlineValues`) — юнит-тест
- [x] Attach to Process: Run | Attach to Process, группа «Go» со всеми процессами (какие из них Go, из списка не узнать), `attach` с `processId` — робот (`GoAttachProfile` к запущенному бинарнику, остановка на точке, значения; диалог выбора процесса не открывался). Не сделано: сессия attach не попадает в Go Monitor (delve не шлёт `process`)
- [x] Set Value: `setVariable` с контейнером, который клиент знает сам — робот: строка, число внутри `order.items[0]`
- [x] Вкладка **Goroutines** в окне отладки (`GoGoroutinesTabLayouter`, `createTabLayouter`): горутины остановки по функции верхнего фрейма с числом, состояние из имени
  delve (`* [Go 1] f (Thread N)` — seen live), поиск, галочка «Hide runtime goroutines» (`runtime.*`, `os/signal.*`), раскрытие узла — 8 верхних фреймов по запросу,
  двойной клик — горутина становится текущей (`setCurrentStackFrame`); перестраивается на каждой остановке — робот (`main.worker × 3`, `main.main`, `main.main.func1`);
  двойной клик и раскрытие вживую не проверены. Не сделано: «где создана», метки (labels) горутин
- [x] Вызовы функций в Evaluate без префикса `call` (`GoEvaluate`) — робот: `order.Total()` = 1600. Точка внутри вызываемой функции прерывает вызов («call stopped») — так у delve
- [~] Completion в Evaluate / watches из остановленной программы (`GoExpressionCompletionContributor`: локальные и поля после `value.`) — юнит-тест контекста, вживую не проверено
- [~] Представление значений (`GoValuePresentation`, юнит-тест): `[]uint8` с текстовыми байтами — строка с типом `[]byte len N`, ошибки (`*errors.errorString {s: "…"}`,
  `wrapError {msg: …}`) — их сообщение — робот (`ErrEmpty` = "store: empty order", `[]byte(o.Currency)` = {[]byte len 3} "EUR"); [~] «View» полного значения обрезанных строк (контекст `clipboard`) — не проверено
- [x] Отказ запуска из-за несовместимости delve и Go (`DelveGoVersion`): в уведомлении — что требуется и что установлено, кнопки Download Go / Update delve / Configure — робот (delve 1.27.2 при Go 1.24.7, настройка выключена)
- [x] Процесс сессии отладчика в Go Monitor (событие DAP `process`, `systemProcessId`): CPU и память; телеметрии рантайма нет — робот (`Debug: go run shop (pid)` в списке)

## Качество кода
- [x] Reformat Code: gofmt / goimports; форматирование при сохранении (настройка, по умолчанию включено) — робот; [x] третий вариант `golangci-lint fmt` (`fmt --stdin`, v2) — робот (format on save)
- [x] golangci-lint в редакторе: сохранённые файлы, пакет файла, v1 и v2, настройка — робот; [x] находки не пропадают при наборе: держатся на `RangeMarker` до следующего сохранения — робот (строка вставлена сверху, подчёркивание errcheck ушло на строку ниже)
- [x] Исправления к находкам линтера: «Handle error» / «Ignore error explicitly» для errcheck, «Suppress with //nolint» для любого правила — робот
- [x] Alt+Enter → «Refactorings and actions of gopls...»: code actions сервера для каретки или выделения — робот: попап со списком действий (Add test for main…) открывается
- [ ] golangci-lint по проекту (в Build tool window), автоисправления из отчёта
- [x] Окно «Go on This Machine» (go, модули, инструменты, `go env`) — робот; [~] уведомление, когда в проекте есть go.mod, а `go` не найден
- [~] Недостающие инструменты одним уведомлением при открытии Go-проекта: «Go tools are missing: dlv, goimports — Install All / Configure / Don't Ask Again» (`GoTool.installAll`, одна фоновая задача) — не проверено
- [~] Предложение отключить лишние для Go плагины (`GoPluginAdvisor`): уведомление при старте на Go-проекте и при активации плагина без перезапуска, пункт Go | Disable Plugins Not Needed for Go; диалог с чекбоксами отключает через платформенный `PluginEnabler` и предлагает перезапуск, «Don't ask again». Список по id GIGA IDE (Java, Maven, Python и фреймворки, Spring, Elements/Endpoints, GitHub, GitLab), отсутствующие id пропускаются. Вживую не проверено

## Мониторинг и прочее (по образцу dotnet-плагина)
- [x] **Go Monitor** (tool window справа, как .NET Monitor): процесс из списка (запущенные из IDE, с галочкой «All Go processes» — все Go-программы машины, найденные по build info `go version -m`), графики CPU, память (committed / working set / live heap), Heap (before GC / goal), GC pauses, GC collections + CPU in GC, Threads, Scheduler (runnable goroutines / idle procs). CPU и память — от ОС для любого процесса; остальное — телеметрия рантайма: в run configuration галочка «Collect runtime telemetry» → программа собирается `go build -o` и запускается сама с `GODEBUG=gctrace=1,schedtrace=1000` (через `go run` переменная попала бы и в команду go), строки рантайма уходят в монитор, не в консоль. Кнопки Goroutines (снимок delve: attach с `stopOnEntry`, `threads`, detach; диалог со сводкой по функциям и поиском) и Debug (attach). Робот (2026-09-22): графики с телеметрией, список процессов машины (3 Go-программы Docker/cowork), снимок горутин (36, `time.Sleep` × 20). Найдено вживую: у консольной программы на Windows дочерний `conhost.exe` — сэмплер его исключает; `executableCannonicalPath` платформы пуст на Windows — путь берётся из JVM или командной строки; поломанный вызов go нельзя кэшировать как «не Go»
- [x] Профили тестов: в run configuration поле Profile (CPU / memory / block / mutex / execution trace) → `go test -cpuprofile=…` в temp-каталог; по окончании уведомление «CPU profile of the tests is ready» с «Open in pprof» (`go tool pprof -http=localhost:PORT -no_browser`, порт выбирает плагин: с `:0` pprof порт не называет — seen live) / «Open in go tool trace» и «Show in Explorer». Робот: уведомление с файлом; открытие в браузере не проверено
- [~] Монитор для сессии отладчика (pid из события `process` delve) — см. Отладчик; [ ] `inittrace` как таблица старта, счётчик горутин без снимка (нет в `schedtrace`)
- [ ] Endpoints: маршруты net/http, chi, gin, echo
- [~] Analyze Go Stack Trace — пункт Go | Analyze Stack Trace… поверх диалога платформы (см. Build / Run / Test)
- [ ] New Go Project / модуль

## Инфраструктура
- [x] Сборка на локальной IDEA, UI-робот (`tools/ui-robot`), зонд delve (`tools/dlv-dap`)
- [x] Юнит-тесты чистой логики
- [x] Платформенные тесты: загрузка plugin.xml, состав меню Go, типы файлов, тип конфигурации (`GoPluginTest`)
- [x] Иконки в стиле и по назначениям dotnet-плагина (`tools/icons/generate.py`, таблица соответствий в его шапке): go / _test, go.mod ↔ project, go.work ↔ solution, зависимость ↔ nuget (+ indirect), бинарник ↔ assembly, конфиги инструментов ↔ config, сгенерированный код ↔ msbuild, шаблоны ↔ settingsJson, go.sum, vendor, benchmark / fuzz / example, окна инструментов (4 цвета + 20x20)
- [x] UI-робот на своём порту 8083 с проверкой, что IDE — песочница этого проекта (8082 занят песочницей dotnet-плагина)
- [ ] Иконки узлов Structure view свои, а не из AllIcons
