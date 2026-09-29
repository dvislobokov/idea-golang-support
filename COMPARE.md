# Go Project Support — полный анализ плагина и сравнение с GoLand

Составлено 2026-09-29 (обновлено после уровней 1–2 плана) по исходникам плагина (версия 0.1.0, целевая платформа IntelliJ IDEA 2026.1.4), `ROADMAP.md`, `PLAN.md`,
`playground/README.md` и материалам JetBrains о GoLand 2025.3 / 2026.1 / 2026.2. Документ — справочник на каждый день: что плагин умеет,
чем отличается от GoLand, чего не хватает и где что искать.

Обозначения статусов:

| Знак | Значение |
|---|---|
| ✅ | есть и проверено вживую (UI-робот в песочнице или пользователь) |
| 🟡 | есть в коде, вживую не проверено, или сделано частично |
| ❌ | нет |
| ➕ | есть в плагине в виде, которого в GoLand нет |

---

## 1. Что это и как устроено

Плагин `io.github.golangsupport` («Go Project Support») даёт поддержку Go в IDE на платформе IntelliJ, где её нет: IntelliJ IDEA, PyCharm,
WebStorm, Rider, форки (GIGA IDE). С GoLand и плагином JetBrains Go (`org.jetbrains.plugins.go`) объявлена несовместимость.

Принцип: **своего анализатора Go нет**. Смысл кода даёт `gopls` через LSP-клиент платформы; отладку — `delve` (`dlv dap`) через **собственный**
DAP-клиент на XDebugger API; сборку, запуск, тесты, модули — команда `go`; форматирование — `gofmt` / `goimports`; линтинг — `golangci-lint`.
Без инструментов работает только то, что построено на своём лексере и сканере объявлений верхнего уровня: подсветка, Structure view, folding,
Go to Symbol, gutter-иконки запуска, генераторы кода по тексту, шаблоны.

| Слой | Чем реализован | Что даёт |
|---|---|---|
| Лексер + сканер объявлений (`lang`) | свой код, ~11 000 строк Kotlin на весь плагин | подсветка, PSI объявлений, структура, folding, навигация по именам, генераторы, идиомы |
| gopls (content-модуль `lsp`) | `LspIntegrationProvider` платформы + свои запросы к серверу | ошибки, completion, hover, rename, code actions, inlay hints, semantic tokens, usages, implementations, code lens, команды `gopls.*` |
| delve (`debugger`) | свой DAP-клиент по TCP | точки останова, шаги, стек, переменные, evaluate, attach |
| `go` (`build`, `run`, `testing`, `mod`) | процессы `go build/vet/run/test/mod/generate/env/version -m` | Build window, run configurations, дерево тестов, зависимости |
| инструменты (`format`, `lint`, `monitor`) | gofmt, goimports, golangci-lint, `go tool pprof/trace`, `GODEBUG` | форматирование, предупреждения линтера, профили, монитор |

Где что лежит: `src/main/kotlin/io/github/golangsupport/<пакет>`, регистрация — `resources/META-INF/plugin.xml`, LSP-часть —
`resources/io.github.golangsupport.lsp.xml`. Тесты: 13 классов, ~89 тестов чистой логики (без запуска go/gopls/dlv).

---

## 2. Полный инвентарь плагина

### 2.1 Язык без инструментов (пакет `lang`)

**Лексер и подсветка**
- Токены: ключевые слова (25), идентификаторы, строки `"…"`, руны `'…'`, raw-строки `` `…` ``, числа (hex/octal/binary/imaginary/`_` — принимаются без валидации), комментарии `//` и `/* */`, директивы `//go:build`, `//go:generate`, `//line` (отдельный цвет), скобки, операторы (по одному символу), bad character.
- Аннотатор без резолва красит: встроенные типы (`int`, `error`, `any`…), константы (`true`, `nil`, `iota`), встроенные функции перед `(`, имена объявлений (функции, типы, поля, константы), вызовы функций, имена пакетов перед точкой (по импортам, включая `gopkg.in/yaml.v3` → `yaml`, `chi/v5` → `chi`). Затенённый `len` всё равно красится как встроенный.
- Semantic tokens gopls → свои ключи: пакет, ссылка на тип, объявление типа, поле, параметр, локальная переменная, переменная пакета, константа, метка. Запрашиваются модификаторы `struct`, `interface`, `pointer`, `slice`… (без этого gopls их не шлёт).
- Цвета «как в GoLand» для 8 ключей в `GoDefault.xml` и `GoDarcula.xml`; страница Settings | Editor | Color Scheme | Go с демо-текстом. Не сделано: отдельный цвет получателя метода.
- `*_test.go` — scope «Tests» платформы (зелёный фон в дереве и вкладках).

**Сканер объявлений и PSI**
- Распознаёт на верхнем уровне: `package`, `import` (одиночные и группы, алиасы, `.`, `_`), `func` (в т.ч. с generics, без тела), методы (получатель сводится к имени типа, `*Server[T]` → `Server`), `type` (struct с полями, interface с методами, прочие типы и алиасы; группы), `const`/`var` (одиночные и группы, по объявлению на имя, `_` пропускается). Поля: именованные (по одному на имя), встроенные (`*pkg.T`, `T[int]`), теги.
- Не распознаёт: содержимое тел функций (локальные типы, замыкания), анонимные структуры (для тегов есть отдельная эвристика), встроенные интерфейсы и объединения типов как члены интерфейса.
- Structure view (Alt+7, Ctrl+F12): методы — на верхнем уровне, не под типом; дети только у struct/interface; сортировка по алфавиту; иконки public/private из AllIcons.
- Breadcrumbs: `(Server) Start()`; sticky lines следуют из них.
- Folding: тела функций/методов/struct/interface `{...}`, группы import/const/var/type `(...)`, блочные комментарии, серии из 2+ строчных комментариев. Блоки `if`/`for` внутри функций не сворачиваются.
- Go to Class (Ctrl+N): только типы. Go to Symbol (Ctrl+Alt+Shift+N): типы, функции, методы, поля, константы, переменные; с gopls платформа добавляет `workspace/symbol`. Индекс `golang.declarations` v1.
- Commenter (`//`, `/* */`), скобки `{}` `()` `[]`, автозакрытие кавычек `"` `` ` `` `'`.
- Rename с PSI-объявления запрещён («needs gopls») — переименование делает gopls через платформу (Shift+F6).

**Отступы при наборе (`GoIndentEngine`)** — как у gofmt, без настроек: уровень на строку с открытыми скобками; закрывающие в начале строки снимают уровень; `case`/`default` на уровне `switch`/`select`; продолжение выражения после `.` или оператора; raw-строки и блочные комментарии не трогаются. Enter между `{}`, `()`, `[]` даёт три строки. Code Style | Go — только вкладка отступов (табы, 4).

**Идиомы серым текстом (inline completion, Tab принимает; настройка «Suggest the idiomatic next line»)**
- После присваивания ошибки (`x, err := …`, имя может быть `readErr`, `errX`) — `if err != nil { return <нули под сигнатуру>, err }`; в `main` — `log.Fatal(err)`; в тесте (`t *testing.T`, `b`) — `t.Fatal(err)`; без результатов — `return`; именованные результаты — по именам; нулевые значения: `""`, `false`, `0`, `nil` (указатели, срезы, map, chan, func, интерфейсы, известные интерфейсы `context.Context`, `io.Reader`… и имена на `Reader/Writer/Closer/Handler/…`), `*new(T)` для типового параметра, `T{}` для прочего.
- После `ctx, cancel := context.With*` — `defer cancel()`; после `mu.Lock()`/`RLock()` — `defer mu.Unlock()`/`RUnlock()`; после `time.NewTicker/NewTimer` — `defer x.Stop()`.
- После закрытия `if err != nil {…}` над `f, err := os.Open/Create/OpenFile/CreateTemp` — `defer f.Close()`; `net.Dial/Listen`, `sql.Open`, `grpc.Dial/NewClient`, `tls.Dial`, `zip.OpenReader`, `gzip.NewReader` — `Close()`; `http.Get/Post/Head/PostForm`, `x.Do/Get/Post` — `defer resp.Body.Close()`; `x.Query*/Prepare*` — `Close()`; `x.Begin/BeginTx` — `defer tx.Rollback()`.
- Условия: остаток строки пуст, набранное — префикс подсказки, следующая строка не содержит уже её.

**Intentions (Alt+Enter, категория Go)**
1. Handle error — на одиночном вызове; число результатов спрашивается у gopls (без него считается 1).
2. Add if err != nil check — после `err := …` (имя должно начинаться с `err`).
3. Add missing return — вставляет `return <нули>` перед `}`.
4. Create function 'name' — из вызова: параметры по аргументам (типы угадываются: string/bool/int/float64/any), результаты по левой части (`err` → `error`, прочее → `any`), метод при `s.name()` внутри метода; в конец файла с `panic("not implemented")`.
5. Add struct tags… (диалог), 6. Implement interface… (попап), 7. Generate test.
8. Refactorings and actions of gopls… (из модуля `lsp`) — попап со всеми code actions сервера для каретки/выделения (Extract variable/function/method, Inline call, Fill struct, Invert if, Add test…), кроме «браузерных».
9. Исправления golangci-lint (см. 2.7).

**Generate (Alt+Insert)**: Constructor… (`NewT(...) *T`, выбор полей), Getters…, Setters…, Getters and Setters… (акронимы `ID`, `URL`, `HTTP`…), String() Method… (`fmt.Sprintf`, импорт `fmt` не добавляется), Struct Tags… (json/yaml/xml/db/mapstructure/toml; snake/camel/as is/lowercase; omitempty; существующие теги дополняются; неэкспортируемые поля без json/yaml/xml), Implement Interface… (интерфейсы проекта, не stdlib; только недостающие методы; `panic("not implemented")`), Test (табличный тест в `<file>_test.go`, `TestName` / `TestRecv_Name`).

**Постфиксные шаблоны (23)**: `.if .else .nil .notnil .err .errv .return .rr .var .for .fori .forr .range .len .print .printf .panic .go .defer .append .not .switch .wrap`. Выражение слева от точки берётся по тексту (цепочки, вызовы, индексы, литералы). Импорты (`fmt`) не добавляются.

**Live templates (41, группа Go, не разворачиваются в строках и комментариях)**: `err errw errn ife errt erras sentinel forr fori main init fn meth str inter enum opt ctx tctx test ttest bench fuzz example tmain tr helper gof deff sel sw tsw pf lf json hf mu wg mk ch ticker`. `meth` подставляет `*Тип` последнего объявленного выше типа. `bench` использует `b.Loop()` (Go 1.24).

**Complete Statement (Ctrl+Shift+Enter)**: закрытие `(`/`[`, `{}` после `if/for/switch/select/func/else/type … struct|interface/go func/defer func`, каретка внутрь.

**Док-комментарий**: `//` на пустой строке над `func/type/var/const` → `// Name ` (настройка).

**Completion в struct-тегах**: ключи `json yaml xml toml db bson mapstructure koanf msgpack csv validate binding form query param uri header url env envDefault envPrefix default gorm description example`; имена полей в snake/camel/lower/as is/kebab (`env` — UPPER_SNAKE); опции (`omitempty`, `omitzero`, `string`, `inline`, `squash`, `attr`…); ~80 правил go-playground/validator для `validate`/`binding`; настройки gorm через `;`. Работает и в анонимных структурах.

### 2.2 gopls (модуль `lsp`, грузится только где есть LSP-клиент платформы)

- Запуск при открытии `.go`, `go.mod`, `go.work` (project-wide клиент, рабочий каталог — корень проекта, к PATH добавляется каталог `go`). Нет gopls — уведомление с Install / About / Configure (раз за сессию).
- Аргументы: `serve`, `-rpc.trace` (настройка), `-debug=localhost:0` (настройка; адрес парсится из stderr).
- Что платформа даёт через LSP: диагностика, completion с автоимпортом (`additionalTextEdits`), hover/Quick Documentation, Parameter Info, rename, code actions при диагностике, inlay hints (parameterNames, assignVariableTypes, rangeVariableTypes, constantValues, compositeLiteralFields, functionTypeParameters), semantic tokens, code lens, Optimize Imports, `workspace/symbol` в Go to Symbol.
- Что платформенный клиент **не** умеет и закрыто своими запросами: Go to Declaration с PSI-целями (ссылка под Ctrl+мышь; go-to-definition платформы выключен, чтобы цели не двоились), Find Usages **с объявления**, Go to Implementation **с объявления** (interface, его методы, struct, type, method), code vision «N usages» / «N implementations» (до 300 объявлений на файл; `main`, `init`, тесты не считаются), gutter-иконки I↓/I↑ с переходом.
- Ограничение: Find Usages / Go to Implementation **с места использования** зависят от того, разрешит ли платформа токен в объявление; подсветка ссылки при Ctrl+наведении вживую не проверена.
- Умолчания, которые плагин задаёт gopls (`GoplsDefaults`): `staticcheck=true`, `gofumpt=false`, `buildFlags=[-tags=…]`, `semanticTokens=true`, линзы `generate regenerate_cgo tidy upgrade_dependency vendor vulncheck` (линза `test` выключена — есть gutter), `hints` все включены. `usePlaceholders` намеренно не задан.
- Команды `gopls.*` по клику на линзу идут запросом с прогрессом и ошибкой (платформа шлёт уведомление без ответа): `run_tests` → свой раннер тестов, `generate` → Build window, остальное — `workspace/executeCommand`; результаты `workspace_stats`, `mem_stats`, `views`, `modules`, `packages`, `list_known_packages` — в окно лога.
- Меню **Go | gopls**: Add Import… (список известных пакетов), Browse Documentation / Assembly / Free Symbols of Selection (веб-страницы gopls), Toggle Compiler Optimization Details (inlining, escape, bounds checks как диагностика), Check for Dependency Upgrades, Upgrade All Dependencies (`go get -u -t ./...`), Run govulncheck, Reset go.mod Diagnostics, Show Statistics, Show Log, Open Debug Pages, Settings…, Restart.
- Tool window **gopls**: stderr сервера (с `-rpc.trace` — весь протокол), `logMessage`/`showMessage`, старт/инициализация с версией/остановка, отправленные команды и ошибки; кнопки Restart / Open Debug Pages / Settings. Виджет Language Services в статус-баре с иконкой gopls и Show Log.
- Страница **Settings | Tools | Go | gopls**: генерируется из `gopls api-json` установленной версии (группы Build, Formatting, General, Completion, Diagnostics, Documentation, Inlay Hints, Navigation, Other; экспериментальные — в свёрнутой группе), контрол по типу опции (галочка, список, `[]string` словами, `NAME=value`, галочки ключей словарей, диалог с поиском для ~244 анализаторов), поиск, Reset all, хранится только отличие от действующего значения, Apply перезапускает сервер.

### 2.3 Модули (`mod`, `view`)

- `go.mod` / `go.work`: тип файла, подсветка (директивы, версии, строки, `=>`, комментарии), commenter. Разбор: `module go toolchain require replace exclude tool use`; `retract godebug ignore` только подсвечиваются. PSI плоский: **нет** completion, навигации, folding, инспекций в go.mod.
- Узел **Dependencies** в Project view у корня модуля: прямые зависимости, подузел Indirect, замены `=> path`, раскрытие в исходники из `GOMODCACHE` (пусто, пока не скачано), двойной клик — строка в go.mod.
- Иконки файлов: go.mod, go.work, go.sum, `_test.go`, сгенерированный код (`zz_generated*`, `.pb.go`, `_gen.go`, `_string.go`…), шаблоны (`.tmpl .gotmpl .gohtml .gotxt`), конфиги (`.golangci.*`, `.goreleaser.*`, `.air.toml`, `sqlc.*`, `buf*.yaml`, `.mockery.*`), бинарники (`__debug_bin*`, `*.test`), каталог `vendor`.
- Меню Go | Modules: Tidy, Download, Vendor (Build window). New Go Module… (`go mod init`). Линзы gopls в go.mod: tidy, vendor, vulncheck, check upgrades / upgrade.
- `GoModulesService`: модуль файла, все модули проекта (без `vendor/`), go.work корня.

### 2.4 Сборка, запуск, тесты (`build`, `run`, `testing`)

- Меню Go: Build (`go build ./...`), Vet (`go vet ./...`; находки — warning), Generate (`go generate ./...`) во всех модулях проекта или в модуле выбранного файла (ПКМ в дереве) → Build tool window с кликабельными `file.go:12:3: msg`; окно активируется только при ошибке.
- Run configuration **Go**: Command (`go run` / `go test`), Package (каталог или файл), Go tool arguments (`-race`, `-count=1`, `-ldflags`…), Program arguments, Working directory (только run), Environment, Test pattern (`-run`), Recursive (`./...`), Benchmark (`-run ^$ -bench`), Collect runtime telemetry (run), Profile (cpu/mem/block/mutex/trace; test). Глобально: Build tags (для build/run/test/vet/gopls/lint/delve) и Test arguments (добавляются ко всем `go test`). Нет отдельных полей `-v`, `-count`, `-timeout`, `-race`, `-cover`, `-fuzz`.
- Producer и gutter ▶: `func main`, `Test*`, `Benchmark*`, `Fuzz*`, `Example*` (fuzz и example — через `-run`, режима `-fuzz` нет), файл теста → пакет, каталог → `./...`. Подтесты `t.Run` и кейсы табличных тестов — **не** запускаются отдельно из gutter. Иконки не отражают статус последнего прогона.
- Автогенерация конфигураций `go run <dir>` для каталогов с `func main` при открытии проекта (до 30, глубина 6; удалённая не возвращается).
- Дерево тестов из `go test -json`: пакеты → тесты → подтесты (по `/`), pass/fail/skip (skip — ignored, не ошибка), длительность, вывод теста, панки и падения сборки — узел «(package)» с выводом. Rerun Failed (по именам верхних функций), Toggle auto-test (перезапуск при изменениях), переход к функции по клику (к `t.Run` подтеста — нет). Бенчмарки — результат строкой в выводе, таблицы нет.
- Консольные фильтры во всех консолях: `file.go:12[:3]` компилятора, `t.Errorf`, стеков паник, `log.Lshortfile`; голое имя файла — если единственное в проекте.
- Tool window **Go Tests**: пакеты и функции проекта (по сканеру, без компиляции), иконки Test/Benchmark/Fuzz/Example, статусы последних прогонов (из любого запуска), Run/Debug Selected, Run All (`./...` на модуль), Refresh, Expand/Collapse, speed search, двойной клик — переход. Нет: подтестов в дереве, автообновления при правке файла, фильтра по статусу, длительностей, контекстного меню.
- Профили тестов: `-cpuprofile/-memprofile/-blockprofile/-mutexprofile/-trace` в temp; уведомление «… profile of the tests is ready» с «Open in pprof» (`go tool pprof -http` на выбранном порту, браузер) / «Open in go tool trace» / «Show in Explorer». Просмотра профилей внутри IDE нет.

### 2.5 Отладчик (`debugger`, собственный DAP-клиент к `dlv dap`)

- Запуск: `dlv dap --listen=127.0.0.1:0 [--check-go-version=false] [--log --log-output=dap,debugger]`; `launch` mode `debug`/`test`, `outputMode: remote`, бинарь во временном каталоге `__debug_bin<uuid>` (удаляется по завершении), `-test.v`, `-test.run`, `-test.bench`, `buildFlags` (теги + Go tool arguments), `cwd`, `env`. Настройки: Show global variables (off), Hide system goroutines (on), Stack trace depth (50), Debug any Go version (on), Write delve log (on).
- Отказ сборки → уведомление «Debug has not started» с выводом компилятора, сессия закрыта.
- Точки: строчные (только на строках кода в телах функций / инициализаторах var), условие, hit count (`==,!=,>,>=,<,<=,% N`), log message (`{expr}` интерполирует delve, без остановки), точка на паники (Unrecovered panics, Fatal throws; включена по умолчанию) с `exceptionInfo` в консоль. Нет: точек на функцию, watchpoints, точек на recovered panic, точек на горутину.
- Управление: Resume, Step Over/Into/Out, Pause, Run to Cursor (временная точка), Stop (`terminate`/`disconnect`), для attach — Detach по умолчанию. Нет: Smart Step Into, Set Next Statement, Drop Frame, Restart, обратной отладки.
- Горутины: каждая — стек в списке потоков (остановленная первой), плоский список без группировки/статуса/меток; фреймы страницами по 50 (до 2000), серые для runtime.
- Значения: как отдаёт delve (тип + строка), дети лениво по 100, указатель — одна дочерняя переменная; `evaluateName` для Add to Watches / Copy; inline values в редакторе для простых имён; значение при наведении (`a.b.c`, не вызовы/индексы) — не проверено вживую. Set Value (F2) — для скаляров, строк, указателей в контейнерах (не для структур/срезов/результатов evaluate). Нет: своих рендереров (`time.Time`, `error`, `[]byte` как строка), просмотра длинных строк, «jump to type source».
- Evaluate / watches: вызовы функций без префикса `call` (кроме встроенных и конверсий); точка внутри вызываемой функции прерывает вызов (свойство delve); completion локальных и полей после `x.` (по данным остановленной программы, 3 с).
- Attach to Process (Run | Attach to Process…): группа «Go», все локальные процессы (какие из них Go — из списка не видно), `attach {mode: local}`. Нет: remote (`dlv --headless` в контейнере/по SSH), `exec` бинарника, core dump.
- Логи: `<log dir>/delve/dlv-*.log` на сессию (последние 20), трасса протокола `delve/protocol/*.log` по действию Go | Debugger | Trace Debugger Protocol; Show Debugger Logs открывает каталог.

### 2.6 Форматирование (`format`)

- Reformat Code (Ctrl+Alt+L): gofmt (рядом с `go` или в `GOROOT/bin`) / goimports / None; весь файл через stdin, 30 с; синтаксическая ошибка — сообщение с первой строкой stderr.
- Format on save (по умолчанию включено): файл сохраняется, форматтер в фоне (10 с), минимальный diff (каретки, folding, точки остаются), повторное сохранение. Файл с ошибкой синтаксиса остаётся как есть.
- gofumpt — через опцию gopls (форматирование gopls платформа не использует, только для code actions). Нет: `golangci-lint fmt`, настроек группировки импортов (local prefix — можно задать в gopls `local`).

### 2.7 Линтер (`lint`)

- golangci-lint v1 и v2 (по `golangci-lint version`; JSON `--out-format=json` / `--output.json.path=stdout`), для **сохранённых** файлов, запуск на пакет файла из корня модуля, с `.golangci.yml` репозитория, `--build-tags` из настроек, таймаут 90 с; результаты кэшируются по modificationStamp; severity `error` → ERROR, остальное WARNING; текст `linter: message`. Нет линтера — молчит (без предложения установить).
- Диапазон: слово в колонке; для errcheck (колонка указывает на `(`) — имя вызова.
- Исправления: errcheck → «Handle error» (число результатов из hover gopls) и «Ignore error explicitly» (`_, _ = call`); любое правило (кроме `typecheck`) → «Suppress with //nolint:rule» (сливается с существующим `//nolint:a,b`).
- Нет: линтинг несохранённого текста (предупреждения пропадают до сохранения), запуск по проекту в Build window, автоисправления из `SuggestedFixes`, `golangci-lint fmt`.

### 2.8 Мониторинг и профили (`monitor`)

- Tool window **Go Monitor** (справа): выбор процесса (запущенные из IDE; галочка All Go processes — все Go-программы машины по `go version -m`, с версией Go, модулем, ревизией, race), Refresh, Goroutines, Debug (attach), статус.
- Графики (300 точек, 1/с): CPU (% ядер), Memory (committed / working set / live heap), Heap (before GC / goal), GC pauses (мс/с), GC collections (+ CPU in GC %), Threads (+ idle), Scheduler (runnable goroutines / idle procs).
- CPU и память — от ОС для любого процесса (Windows: JNA `GetProcessMemoryInfo`, Linux: `/proc`, macOS: `ps` RSS; число потоков от ОС только на Linux). Остальное — телеметрия рантайма: галочка «Collect runtime telemetry» в run configuration → `go build -o` + запуск с `GODEBUG=gctrace=1,schedtrace=1000`, строки рантайма уходят в монитор, не в консоль. `inittrace` парсится, но не показывается.
- Goroutines: снимок через `dlv attach` (`stopOnEntry` → `threads` → detach), диалог со сводкой «N × функция», поиском и полным списком; программа продолжает работать.
- Профили тестов — см. 2.4. Нет: монитор для сессии отладчика, `/debug/pprof` живого процесса, профиль обычной программы (не теста), просмотр профилей в IDE (flame graph), сравнение бенчмарков.

### 2.9 Проект, SDK, настройки, помощь (`templates`, `sdk`, `settings`, `help`)

- New → Go File: Empty / Program (`func main`) / Test (`_test.go` с `TestName`; пакет — из соседних файлов, `main` для `cmd/*`, иначе имя каталога).
- New Project → категория **Go** (модульный мастер, для IDEA-семейства и форков): GOROOT (автоопределение), Module path, галочка `main.go`; Finish делает `go mod init`. Вживую не проверено.
- Поиск `go`: настройка → PATH → `GOROOT/bin` → стандартные каталоги (Windows: `%ProgramFiles%\Go`, `C:\Go`, `~\go\go`, `~\sdk\go`; Unix: `/usr/local/go`, `/usr/lib/go`, `/opt/homebrew/bin`, `/snap/bin`, `~/sdk/go`). Нет `go` при наличии go.mod — предупреждение с Download Go / Configure. Загрузки SDK из IDE нет.
- Инструменты (gopls, dlv, golangci-lint, goimports): путь из настроек → PATH → GOBIN/GOPATH/bin; Install/Update кнопкой (`go install …@latest`) на странице настроек или из уведомления. Установка «всех разом» — нет.
- Окно **Go on This Machine**: go, модули проекта, инструменты, `go env` (важные первыми).
- **Disable Plugins Not Needed for Go**: уведомление при старте на Go-проекте и действие в меню; список id (Java, Maven, Python + фреймворки GIGA, Spring, Elements/Endpoints, GitHub, GitLab), диалог с чекбоксами, `PluginEnabler`, перезапуск, Don't ask again.
- Settings | Tools | Go — группы Toolchain (path to go, build tags, create run configurations, test arguments), Language Server (enable, staticcheck, gofumpt, inlay hints, rpc trace, debug pages), Debugger (globals, hide system goroutines, stack depth, any Go version, delve log), Editor (doc comment names, inline idioms), Code Quality (formatter, format on save, golangci-lint), Tools (пути и Install). Подстраница gopls — см. 2.2. Все 22 поля хранятся application-level в `golang-support.xml`.
- **Go | Help Page** (и Help-меню): HTML-вкладка (JCEF) с клавишами из текущей раскладки, разделы Editing / Generate and fix / Navigation / Run, test, debug, monitor / Tools / Tips.

### 2.10 Инфраструктура

- Сборка на локальной IDEA 2026.1.4 (Gradle 9.7.1, Kotlin 2.3), ничего не скачивается; `sinceBuild=261`.
- UI-робот (`tools/ui-robot`, порт 8083), зонды `tools/dlv-dap/probe.py`, `tools/gopls/probe.py`, `semantic_tokens.py`, `completion.py`, генератор иконок `tools/icons/generate.py`.
- Юнит-тесты чистой логики; платформенных тестов (plugin.xml, состав меню) нет.

---

## 3. Сравнение с GoLand

GoLand 2026.2 (июль 2026) — эталон. У GoLand свой полный парсер, type checker и сотни инспекций; плагин намеренно опирается на gopls, поэтому
часть «нет» ниже — это не пробелы реализации, а то, что gopls/LSP-клиент платформы не дают. Такие случаи помечены «(LSP)».

### 3.1 Редактор и язык

| Возможность | GoLand | Плагин | Статус |
|---|---|---|---|
| Подсветка синтаксиса, директив, строк, рун | да | свой лексер | ✅ |
| Semantic highlighting (пакеты, типы, поля, параметры, константы) | свой анализ + уникальные цвета локальных переменных | semantic tokens gopls; без gopls — только аннотатор | ✅ (цвет получателя метода — ❌) |
| Ошибки компиляции в редакторе | своя проверка типов, мгновенно | диагностика gopls; без gopls — ошибки последнего Go \| Build / Vet в редакторе (сохранённые файлы) | ✅ |
| Инспекции (сотни: unused, shadowing, resource leak 2025.3, redundant else, unreachable, error handling…) | да | анализаторы gopls + staticcheck (~244, включаются на странице gopls) + golangci-lint | 🟡 меньше и без локальных инспекций плагина |
| Quick-fixes при инспекциях | сотни | code actions gopls при диагностике; свои 4 intention; 3 фикса линтера | 🟡 |
| Go 1.26 modernizers / `go fix` в редакторе (2026.1–2026.2) | да, с Problems window и diff | modernize-анализаторы gopls (по умолчанию в gopls v0.18+) как диагностика | 🟡 без сводного окна и массового применения |
| Completion: символы, smart, ML-ранжирование | да | gopls через платформу, автоимпорт при выборе | ✅ базовое; smart/ML ❌ |
| Completion в struct-тегах | ключи и опции популярных библиотек | 27 ключей, ~80 правил validator, gorm, env | ✅ (➕ шире по библиотекам) |
| Postfix templates | ~25 (`.if .else .nil .notnil .err .var .for .forr .range .return .rr .len .print .panic .go .defer .append .switch .sort .not …`) | 25 (с `.sort`, `.errn`) | ✅ |
| Live templates | ~40 | 41 | ✅ |
| Идиомы серым текстом без ИИ | нет (есть Full Line completion с моделью) | правила `if err != nil`, `if !ok`, `defer …`, `for rows.Next()` | ➕ |
| Complete Statement | да | да | ✅ |
| Отступы при наборе как gofmt | да | да | ✅ |
| Форматирование gofmt / goimports / gofumpt, format on save | встроенный форматтер + gofmt/goimports/gofumpt, `golangci-lint fmt` (2025.3) | gofmt / goimports / `golangci-lint fmt`; gofumpt через gopls | ✅ |
| Настройки code style (пробелы, переносы, пустые строки) | да, поверх gofmt | только отступы | ❌ (по замыслу: gofmt) |
| Optimize Imports, группировка импортов | да | через gopls | 🟡 |
| Folding: тела, группы, комментарии, блоки внутри функций, custom regions | всё | тела, группы, комментарии | 🟡 |
| Structure view с методами под типом, фильтрами | да | методы под типом того же файла; фильтров нет | ✅ |
| Breadcrumbs, sticky lines | да | да | ✅ |
| Parameter Info, Quick Documentation, Type Info (Ctrl+Shift+P), Expression type | да | всё через gopls; Type Info — первая строка hover | 🟡 |
| Exit points highlighting, recursive call gutter, usages highlighting в файле | да | document highlight через LSP (если платформа) ; остальное ❌ | ❌ |
| Rename с превью и по всему модулю | да | gopls rename через платформу | 🟡 (не проверено роботом) |
| Refactorings: Extract variable/function/method, Inline, Change Signature, Move, Safe Delete, Introduce Constant, Extract Interface | все | Extract variable/function/method, Inline call, Fill struct… — code actions gopls через пункт «Refactorings and actions of gopls…» | 🟡 Change Signature, Move, Safe Delete, Extract Interface ❌ (LSP) |
| Generate: constructor, getters/setters, String(), tags, implement interface, test | да (+ Implement methods по Ctrl+O с типами) | да, по тексту без типов; интерфейсы только проекта | ✅ / stdlib-интерфейсы ❌ |
| Generate test (табличный) | да, с gotests-подобным скелетом | да | ✅ |
| Create function/method from usage | да, с типами | да, типы угадываются (`any`) | 🟡 |
| Go templates (`html/template`, `text/template`) | подсветка, completion, навигация | только иконка файла | ❌ |
| cgo, Plan9 assembly | подсветка, навигация | нет | ❌ |
| Generics | полная поддержка | лексер/сканер их пропускают; смысл — gopls | ✅ через gopls |
| Doc comment по `//` | да | да | ✅ |
| Spell checking, TODO в комментариях | да | платформа (комментарии — токены COMMENTS) | ✅ |

### 3.2 Навигация и поиск

| Возможность | GoLand | Плагин | Статус |
|---|---|---|---|
| Go to Declaration (Ctrl+B, Ctrl+клик) | да | gopls, PSI-цели, подсветка ссылки | ✅ (Ctrl+наведение вживую 🟡) |
| Go to Implementation с объявления | да | gopls `implementation` | ✅ |
| Go to Implementation с места использования | да | `targetElementEvaluator` резолвит имя через gopls: объявления и локальные переменные; имена пакетов — нет | ✅ |
| Find Usages с объявления, группировка read/write | да | gopls references, без группировки | ✅ / группировка ❌ |
| Find Usages с места использования | да | `targetElementEvaluator` резолвит имя через gopls: объявления и локальные переменные; имена пакетов — нет | ✅ |
| Code vision usages/implementations | да | да | ✅ |
| Gutter implements / implemented by | да | да | ✅ |
| Go to Type Declaration, Super Method, Related Symbol | да | Go to Type Declaration через `typeDefinition` gopls; остальное нет | ✅ / ❌ |
| Type Hierarchy, Call Hierarchy | да | нет (gopls умеет call hierarchy, платформа не показывает) | ❌ |
| Go to Class / Symbol / File | да | да (по индексу + `workspace/symbol`) | ✅ |
| Recent locations, bookmarks | платформа | платформа | ✅ |
| Навигация внутри go.mod (модуль → исходники, версии) | да | узел Dependencies; в самом go.mod ❌ | 🟡 |
| Переход в исходники зависимостей из module cache | да (External Libraries) | Dependencies в Project view | 🟡 |

### 3.3 Модули и зависимости

| Возможность | GoLand | Плагин | Статус |
|---|---|---|---|
| Подсветка go.mod / go.work, folding блоков (2025.3) | да | подсветка, commenter, folding блоков | ✅ |
| Completion в go.mod (пути модулей, версии из proxy) | да | директивы, пути из module cache, версии из кэша и GOPROXY, версии go | ✅ |
| Quick doc, навигация из go.mod | да | нет | ❌ |
| Инспекции go.mod (неиспользуемые require, обновления) | да | линзы и диагностика gopls: tidy, upgrades, vulncheck | 🟡 |
| Автоматическое `go mod tidy` / подсказка после правки | да (Sync) | баннер над go.mod после сохранения с другими require: Tidy / Download | ✅ |
| Vulnerability checker (Package Checker) | да | govulncheck через gopls в go.mod и кнопкой в окне Go Dependencies (уязвимые модули помечены) | 🟡 |
| Окно зависимостей с обновлениями и upgrade | да (Dependencies tool window) | окно Go Dependencies: `go list -m -u`, Upgrade Selected / All, Tidy, govulncheck | ✅ |
| Dependency diagram | да | нет | ❌ |
| Vendoring, GOPATH-режим | да | vendor (команда); GOPATH-режим ❌ | 🟡 |
| Workspaces (go.work): узел, Add module to workspace | да | разбор `use`, gopls открывает; UI ❌ | 🟡 |
| Установка Go SDK из IDE, несколько SDK | да | нет; путь к `go` в настройках | ❌ |

### 3.4 Сборка, запуск, тесты

| Возможность | GoLand | Плагин | Статус |
|---|---|---|---|
| Run configurations: Application, Package, File, Test, Build, Remote | все | одна конфигурация Go (run/test, каталог или файл) | 🟡 |
| Run Targets (Docker, SSH, WSL) | да | нет | ❌ |
| Автоконфигурации для `main` пакетов (2026.2) | да | да | ✅ |
| Gutter ▶ на main / Test / Benchmark / Fuzz / Example | да | да | ✅ |
| Запуск подтеста `t.Run` и кейса табличного теста из gutter | да | ▶ у `t.Run("…")` и у `{name: "…"}` | ✅ |
| Фаззинг как режим (`-fuzz`) | да | галочка Fuzz, Run Fuzzing в gutter | 🟡 |
| Дерево тестов с подтестами, статусами, длительностью | да | да | ✅ |
| Rerun Failed, auto-test | да | да (Rerun — по верхним функциям); auto-test и в окне Go Tests | ✅ |
| Переход к подтесту `t.Run` | да | к строке `t.Run` / кейса | 🟡 |
| Покрытие (`-cover`), подсветка в gutter, проценты, отчёт | да | полосы в gutter, процент у пакетов в Go Tests; отчёта и окна Coverage нет | ✅ (частично) |
| Бенчмарки: таблица ns/op, B/op, allocs/op, сравнение | да | вкладка Benchmarks в Go Tests с Δ % против прошлого прогона, `-benchmem` | ✅ |
| Профили CPU/memory/block/mutex, flame graph в IDE (2026.2 — и для приложений, без тестов) | да | профили тестов → внешние pprof/trace в браузере | 🟡 |
| testify / gocheck / поддержка фреймворков (навигация к assert) | да | нет | ❌ |
| Окно с тестами проекта | Structure/Run | Go Tests (➕ отдельное окно со статусами) | ✅ |
| Build tool window с ошибками, vet | да | да | ✅ |
| `go generate` | да | да (меню, линза) | ✅ |
| Консольные ссылки на файлы, стек паник | да | да | ✅ (неоднозначные имена 🟡) |
| Analyze Go Stack Trace (вставить панику) | да | Go \| Analyze Stack Trace… (диалог платформы + наши фильтры) | ✅ |
| Pre-commit запуск тестов, `go fix` в pre-commit | да | нет | ❌ |

### 3.5 Отладчик

| Возможность | GoLand | Плагин | Статус |
|---|---|---|---|
| Точки: строчные, условие, hit count, log | да | да | ✅ |
| Точки на паники (unrecovered, fatal throw) | да | да | ✅ |
| Точки на функцию, watchpoints (data breakpoints) | да | нет | ❌ |
| Step Into/Over/Out, Run to Cursor | да | да | ✅ |
| Smart Step Into, Set Next Statement, Drop Frame | да | нет | ❌ |
| Горутины: список, группировка, метки (profiler labels), фильтр системных | да | вкладка Goroutines: группы по функции, поиск, фильтр runtime, фреймы по раскрытию; меток нет | ✅ (без меток) |
| Стек, фреймы runtime серым | да | да | ✅ |
| Переменные, lazy children, указатели | да, с рендерерами (`time.Time`, `error`, строки, срезы с len/cap) | `[]byte` как текст, ошибки — сообщением, `time.Time` форматирует delve | ✅ (частично) |
| Просмотр длинных строк, «View as» | да | «View» для обрезанных delve строк и срезов (контекст `clipboard`) | 🟡 |
| Evaluate с вызовами функций, watches | да | да | ✅ |
| Completion в Evaluate | да, по типам | по данным остановки (локальные, поля) | 🟡 |
| Inline values, значение при наведении | да | да / не проверено | 🟡 |
| Set Value | да | скаляры, строки, указатели | 🟡 |
| Attach to local process | да | да (без пометки, какие процессы Go) | 🟡 |
| Remote debug (`dlv --headless`, контейнер, SSH) | да | вид Remote dlv dap: сокет к `dlv dap --listen`, attach к pid или exec бинарника там, substitutePath; `--headless` нет | ✅ (частично) |
| Debug бинарника (`exec`), core dump | да | виды Binary и Core dump конфигурации Go | ✅ |
| Обратная отладка (rr) | да | нет | ❌ |
| Отказ сборки с выводом компилятора | да | да | ✅ |
| Логи отладчика, трасса протокола | скрыто | Show Debugger Logs, Trace Debugger Protocol | ➕ |
| Работает в IDE без модуля `intellij.platform.dap` | н/п | да, свой клиент | ➕ |

### 3.6 Качество кода и инструменты

| Возможность | GoLand | Плагин | Статус |
|---|---|---|---|
| golangci-lint в редакторе (v1/v2), `.golangci.yml` | да, по умолчанию с 2025.3; `golangci-lint fmt` | да, для сохранённых файлов, находки держатся при наборе; fmt как форматтер | ✅ |
| Фиксы к находкам линтера | quick-fixes | Handle error / Ignore / `//nolint` | 🟡 (SuggestedFixes ❌) |
| go vet | инспекция | меню Go | Vet | 🟡 |
| staticcheck | инспекции | через gopls, по умолчанию включён | ✅ |
| govulncheck | Package Checker | gopls | 🟡 |
| Escape analysis / optimization details (2026.2) | визуализация | Toggle Compiler Optimization Details (диагностика gopls) | 🟡 |
| Struct field reordering для экономии памяти (2026.2) | да | нет | ❌ |
| Browse assembly / free symbols / документация в вебе | нет | через gopls | ➕ |
| Настройки gopls из `api-json` | н/п | да | ➕ |
| Лог и статистика языкового сервера | н/п | окно gopls, Show Statistics, Debug Pages | ➕ |
| HTTP Client, Database tools, Docker, Kubernetes, Terraform, Web (JS/TS), SQL в строках | да | зависит от IDE-хоста (в Ultimate есть, в Community/форках — нет) | — |
| Endpoints (net/http, chi, gin, echo) | да | нет | ❌ |
| Protobuf / gRPC | плагин | нет (общие плагины платформы) | — |
| AI: Junie, AI Assistant, Claude Agent, ACP-агенты | да | зависит от IDE-хоста | — |

### 3.7 Наблюдаемость

| Возможность | GoLand | Плагин | Статус |
|---|---|---|---|
| Live CPU/memory графики (2026.2) | да | Go Monitor: CPU, memory, heap, GC, threads, scheduler | ✅ (➕ GC/scheduler) |
| Профилирование приложения без тестов, flame graph, gutter с горячими строками (2026.2) | да | нет | ❌ |
| Goroutine leak profile (Go 1.27) | да | снимок горутин через delve со сводкой | 🟡 |
| Список всех Go-процессов машины с build info | нет | да | ➕ |
| Монитор сессии отладчика | н/п | CPU и память процесса из события `process` delve | ✅ |

### 3.8 Проект и IDE

| Возможность | GoLand | Плагин | Статус |
|---|---|---|---|
| New Project с загрузкой SDK, шаблонами (web, CLI) | да | категория Go: GOROOT, module path, main.go | 🟡 |
| Single-file editing без проекта (2025.3) | да | платформа открывает, gopls стартует на каталог | 🟡 |
| New Go File с шаблонами | да | да | ✅ |
| Отключение лишних плагинов для Go | н/п | да | ➕ |
| Страница помощи с клавишами | документация | Help Page в IDE | ➕ |
| Git worktrees, Islands UI, Wayland | платформа | платформа | — |

---

## 4. Сводка: готово / частично / нет

### Готово и проверено (можно пользоваться каждый день)
- Подсветка, структура, folding, навигация по именам, отступы, Complete Statement, док-комментарии, live/postfix-шаблоны, идиомы `if err != nil` / `defer`.
- gopls: ошибки, completion с автоимпортом, hover, inlay hints, semantic colours, rename, code actions, usages/implementations с объявлений, code vision, gutter I↓/I↑, линзы go.mod и `//go:generate`, меню gopls, лог, страница настроек.
- Generate (Alt+Insert) и intentions (Alt+Enter): constructor, accessors, String, tags, implement interface, test, handle error, add check, missing return, create function.
- Run/Test: конфигурации, gutter, автоконфигурации, дерево тестов с подтестами, Rerun Failed, консольные ссылки, окно Go Tests, профили тестов.
- Отладчик: точки с условиями/hit count/log, паники, шаги, стек, переменные, evaluate с вызовами, Set Value, отказ сборки.
- gofmt/goimports на Reformat и при сохранении; golangci-lint с фиксами; Build/Vet/Generate; Modules Tidy/Download/Vendor; Go Monitor с телеметрией и снимком горутин; Go on This Machine.

### Есть, но не проверено вживую (🟡, см. `playground/README.md`, колонка «Робот» = нет)
- Диалоги Generate (Struct Tags, Implement Interface, Test), Ctrl+наведение, клики по gutter I↓/I↑, попап «Refactorings and actions of gopls», Add Import / Browse* / Toggle Optimization, Apply настроек gopls с перезапуском, узел Dependencies с раскрытием, New Project, New Go Module, Attach to Process, completion в Evaluate, значение при наведении, отладочный бинарь в temp, Open in pprof в браузере, Debug из монитора, Disable Plugins, уведомление «go не найден», Build/Vet с переходом к ошибке.

### Сделано 2026-09-29 по плану (уровни 1 и 2); проверено роботом, кроме: установка инструментов разом, ссылки в консоли тестов при дублях имён, fuzz, Race/-count=1, идиомы и постфиксы (юнит-тесты), Type Info, «View» длинных строк, автообновление дерева Go Tests
- Установка недостающих инструментов одним уведомлением; честное сообщение о несовместимости delve и Go; Go | Analyze Stack Trace.
- go.mod: folding блоков, баннер «Run go mod tidy» после правки require.
- Тесты: подтесты и кейсы табличных тестов из gutter и в переходах, покрытие (gutter + проценты в Go Tests), fuzz как режим, галочки Race / `-count=1`,
  статусы прогонов в gutter, auto-test и автообновление в окне Go Tests, точные ссылки на файлы в консоли тестов.
- Редактор: предупреждения линтера не пропадают при наборе, ошибки Build / Vet в редакторе без gopls, `golangci-lint fmt`, методы под типом в Structure,
  идиомы `if !ok` / `defer wg.Done()` / `for rows.Next()`, постфиксы `.sort` / `.errn`.
- gopls: Find Usages / Go to Implementation с места использования, Go to Type Declaration, Type Info.
- Отладчик: `[]byte` и ошибки текстом, полное значение обрезанных строк, процесс сессии в Go Monitor.

### Нет (упорядочено по тому, насколько мешает каждый день)
1. **Бенчмарки таблицей** и сравнение (benchstat).
2. **Горутины**: группировка, метки; рендерер `time.Time` проверить вживую (delve форматирует сам).
3. **Remote debug / exec / core dump**, точки на функции и watchpoints, Smart Step Into.
4. **Change Signature, Move, Safe Delete, Extract Interface** (нужен свой парсер или доработка платформы).
5. **Completion и навигация в go.mod**, окно зависимостей с обновлениями.
6. **Профили в IDE** (flame graph) и профилирование приложения, а не тестов.
7. **Endpoints, Go templates, cgo/assembly.**
8. Type/Call Hierarchy, exit points, recursive call gutter, dependency diagram, SDK из IDE, Run Targets, testify-навигация, pre-commit проверки, окно Coverage платформы.

---

## 5. Ежедневный справочник

### 5.1 Клавиши (по умолчанию Windows; фактические берутся из keymap на Go | Help Page)

| Действие | Клавиши | Чем сделано |
|---|---|---|
| Go to Declaration | Ctrl+B / Ctrl+клик | gopls |
| Go to Implementation (с объявления) | Ctrl+Alt+B, gutter I↓/I↑ | gopls |
| Find Usages / Show Usages (с объявления) | Alt+F7 / Ctrl+Alt+F7, code vision | gopls |
| Rename | Shift+F6 | gopls |
| Quick Documentation / Parameter Info | Ctrl+Q / Ctrl+P | gopls |
| Go to Class / Symbol | Ctrl+N / Ctrl+Alt+Shift+N | индекс плагина + gopls |
| File Structure / Structure | Ctrl+F12 / Alt+7 | сканер |
| Complete Statement | Ctrl+Shift+Enter | плагин |
| Принять идиому серым текстом | Tab | плагин |
| Generate | Alt+Insert | плагин |
| Intentions / code actions | Alt+Enter | плагин + gopls + линтер |
| Reformat / Optimize Imports | Ctrl+Alt+L / Ctrl+Alt+O | gofmt-goimports / gopls |
| Run / Debug контекст | Ctrl+Shift+F10 / gutter ▶ | go / delve |
| Evaluate | Alt+F8 | delve (`call` дописывается сам) |
| Set Value | F2 в Variables | delve |
| Attach to Process | Run \| Attach to Process… | delve |

### 5.2 Live templates (Tab)

| Группа | Аббревиатуры |
|---|---|
| Ошибки | `err` `errw` (`%w`) `errn` (`nil, err`) `ife` (`if v, err := …`) `errt` (`errors.Is`) `erras` (`errors.As`) `sentinel` |
| Объявления | `fn` `meth` (`*Тип` выше) `str` `inter` `enum` (iota) `opt` (functional option) `main` `init` |
| Контекст и конкурентность | `ctx` `tctx` (timeout + cancel) `gof` `deff` `sel` `mu` `wg` `ch` `mk` `ticker` |
| Управление | `forr` (range) `fori` `sw` `tsw` (type switch) |
| Тесты | `test` `ttest` (табличный) `tr` (`t.Run`) `helper` `bench` (`b.Loop`) `fuzz` `example` `tmain` |
| Вывод и прочее | `pf` `lf` `json` (тег) `hf` (http handler) |

### 5.3 Постфиксные шаблоны (после выражения + `.` + Tab)

`.if` `.else` (`if !x`) `.nil` `.notnil` `.err` (`if err := x; err != nil`) `.errv` (`v, err := x`) `.return` `.rr` (`return x, nil`) `.var` `.for` (range k, v) `.fori` `.forr` (обратный) `.range` `.len` `.print` `.printf` `.panic` `.go` `.defer` `.append` `.not` `.switch` `.wrap` (`fmt.Errorf("…: %w", x)`)

### 5.4 Что предложит Alt+Enter

- На одиночном вызове: **Handle error**. После `x, err := …`: **Add if err != nil check**. В функции без `return`: **Add missing return**. На вызове несуществующей функции: **Create function**. В struct: **Add struct tags…**. В типе: **Implement interface…**. В функции: **Generate test**.
- Везде с gopls: **Refactorings and actions of gopls…** (Extract variable/function/method, Inline call, Fill struct, Invert if, Add test, Remove unused parameter, modernize…), плюс фиксы диагностик gopls/staticcheck прямо в списке.
- На подчёркивании линтера: **Handle error**, **Ignore error explicitly** (errcheck), **Suppress with //nolint:rule**.

### 5.5 Меню Go

Build · Vet · Generate · Modules (Tidy, Download, Vendor) · New Go Module… · Go on This Machine… · Disable Plugins Not Needed for Go… · Monitor Go Process · Help Page · Debugger (Show Debugger Logs, Trace Debugger Protocol) · gopls (Add Import…, Browse Documentation / Assembly / Free Symbols, Toggle Compiler Optimization Details, Check for Dependency Upgrades, Upgrade All Dependencies, Run govulncheck, Reset go.mod Diagnostics, Show Statistics, Show Log, Open Debug Pages, Settings…, Restart).
ПКМ в дереве проекта: Build, Vet, Tidy для модуля выбранного файла; New → Go File.

### 5.6 Окна инструментов

| Окно | Где | Что |
|---|---|---|
| Go Tests | внизу | пакеты и тесты проекта, статусы, Run/Debug Selected, Run All |
| Go Monitor | справа | процесс, CPU/память/heap/GC/threads/scheduler, Goroutines, Debug, All Go processes |
| gopls | внизу | лог сервера, команды, Restart / Debug Pages / Settings |
| Build | внизу | Build/Vet/Generate/Modules с переходом к ошибкам |
| Run / Debug | внизу | консоль, дерево тестов, отладчик |

### 5.7 Run configuration «Go» — полезные сочетания

- Гонки и без кэша: Go tool arguments `-race -count=1` (или глобально Settings | Tools | Go | Test arguments).
- Теги сборки: Settings | Tools | Go | Build tags (применяются к build, run, test, vet, gopls, линтеру, delve).
- Один тест: Test pattern `^TestName$`; подтест: `^TestName$/^case$`.
- Бенчмарк: галочка Benchmark + Test pattern (или пусто = все); `-benchmem` — в Go tool arguments.
- Профиль: Profile = CPU/Memory/Block/Mutex/Trace → уведомление с «Open in pprof».
- Графики рантайма: галочка Collect runtime telemetry (программа собирается `go build -o`, вывод рантайма уходит в Go Monitor).
- Отладка: Debug той же конфигурации; delve собирает сам, `--check-go-version=false` включён по умолчанию.

### 5.8 Настройки (Settings | Tools | Go)

Toolchain: Path to go, Build tags, Create run configurations, Test arguments · Language Server: Use gopls, Staticcheck, gofumpt, Inlay hints, Log every message, Serve debug pages · Debugger: Show global variables, Hide system goroutines, Stack trace depth, Debug any Go version, Write delve log · Editor: Doc comment names, Idiomatic next line · Code Quality: Reformat with (gofmt/goimports/None), Format on save, golangci-lint · Tools: пути и Install/Update для gopls, dlv, golangci-lint, goimports · Подстраница **gopls**: все опции установленной версии (analyses, codelenses, hints, hoverKind, local, buildFlags, env, directoryFilters…).

### 5.9 Где искать, если что-то не работает

| Симптом | Куда смотреть |
|---|---|
| Нет completion / ошибок | статус-бар → виджет Language Services; Go \| gopls \| Show Log («Stopped unexpectedly»); Settings \| Tools \| Go → Use gopls; Restart |
| Нет предупреждений линтера | файл сохранён? golangci-lint установлен (Settings \| Tools \| Go \| Tools)? 90 с таймаут на большой пакет |
| Отладка не стартует | балун «Debug has not started» с выводом компилятора; Go \| Debugger \| Show Debugger Logs (`delve/dlv-*.log`); Trace Debugger Protocol для полной трассы |
| «Go version … is too old» от delve | Settings \| Tools \| Go \| Debug programs of a Go version this delve does not support (по умолчанию включено) |
| Нет `go` | Settings \| Tools \| Go \| Path to go; Go \| Go on This Machine… |
| Форматирование не применилось | синтаксическая ошибка в файле (форматтер молчит); Settings → Reformat with |
| Медленно / много лишних плагинов | Go \| Disable Plugins Not Needed for Go… |
| Ошибки платформы | idea.log, искать `Plugin to blame: Go` |

### 5.10 Инструменты, которые ставит плагин

| Инструмент | Модуль | Зачем |
|---|---|---|
| gopls | `golang.org/x/tools/gopls` | ошибки, completion, навигация, рефакторинги |
| dlv | `github.com/go-delve/delve/cmd/dlv` | отладчик, снимок горутин |
| golangci-lint | `github.com/golangci/golangci-lint/v2/cmd/golangci-lint` | предупреждения в редакторе |
| goimports | `golang.org/x/tools/cmd/goimports` | Reformat Code с импортами |

Ставятся кнопкой Install/Update на странице настроек или из уведомления (`go install …@latest`); ищутся в настройках → PATH → GOBIN → GOPATH/bin.

---

## 6. Кому чего не хватает (по ролям)

| Разработчик | Что плагин закрывает | Чего не хватает в первую очередь |
|---|---|---|
| Бэкенд-сервисы (HTTP/gRPC) | gopls, тесты, отладка, линтер, шаблоны handler/ctx/errors, теги validate/gorm/env | Endpoints, remote debug в контейнере, Run Targets (Docker), coverage, HTTP client зависит от IDE |
| CLI / инструменты | автоконфигурации `cmd/*`, run с аргументами, профили тестов, монитор | профилирование приложения без тестов, benchmark-таблицы |
| Библиотеки / open source | Implement interface, Generate test, govulncheck, upgrades, `//nolint`, gofumpt | coverage, `-fuzz`, запуск кейса табличного теста, Change Signature |
| Инфраструктура / DevOps на Go | go.work, vendor, build tags, All Go processes в мониторе | completion в go.mod, Terraform/K8s (от IDE-хоста), core dump |
| Перформанс / рантайм | Go Monitor (GC, scheduler, heap goal), снимок горутин, pprof/trace одной кнопкой, optimization details | flame graph в IDE, группировка горутин, монитор сессии отладчика, struct reordering |
| Новичок в Go | идиомы серым текстом, Help Page, шаблоны, установка инструментов кнопкой | установка всех инструментов разом, SDK из IDE, подсказка после правки go.mod |

---

## 7. Источники

- Исходники плагина (`src/main/kotlin`, `resources/META-INF/plugin.xml`, `resources/io.github.golangsupport.lsp.xml`), `ROADMAP.md`, `PLAN.md`, `playground/README.md`.
- GoLand: [What's New 2026.2](https://blog.jetbrains.com/go/2026/07/16/goland-2026-2-is-now-available/), [2026.1](https://blog.jetbrains.com/go/2026/03/26/goland-2026-1-is-released/), [2025.3](https://blog.jetbrains.com/go/2025/12/08/goland-2025-3-is-out/), [Features](https://www.jetbrains.com/go/features/), [Refactorings](https://www.jetbrains.com/help/go/refactoring-source-code.html), [Testing](https://www.jetbrains.com/help/go/performing-tests.html), [Debugging](https://www.jetbrains.com/help/go/debugging-code.html).
