# Анализ GoLand 2026.2.3: меню, настройки, подсветка, completion, помощь при наборе

Снято UI-роботом 2026-10-05 с эталонного GoLand **2026.2.3** (`GO-262.10968.67`). GoLand запускался изолированно:
`tools/ui-robot/goland/start-goland.ps1`, копия настроек в `%USERPROFILE%\goland-robot`, порт робота 8595. Проект — `build/ui-robot/goland-playground`,
копия `playground/` без `.idea`. В копию временно клали пробные файлы `internal/probe/analysis.go`, `internal/probe/analysis_test.go`
(`go build`, `go vet`, `go test` — без ошибок) и `internal/probeerr/broken.go` (ошибки нарочно); после съёмки их удалили, копия сверена с эталоном.

Условия съёмки:
- Раскладка клавиатуры — по умолчанию (`$default`, Windows): Ctrl+B — объявление, Alt+Insert — Generate, Ctrl+Alt+Shift+T — Refactor This.
- Тема — Islands Dark, новый UI. Схема редактора — `_@user_Islands Dark`.
- Лицензия — личная, из конфига пользователя (в статус-баре значок «Trial»).
- Go 1.27.1 (`C:\Program Files\Go`); в списке SDK ещё toolchain go1.25.11. golangci-lint 2.13.2 из `~/go/bin`.
- Плагины — комплект GoLand (88 дескрипторов, выключенных нет), сторонний — только Robot server 0.11.23.
- Окно IDE — 1012×1000: шире экран не дал.

**Сверка с плагином (раздел 5 задания) не делалась**: колонка «У нас сейчас» в разделе 6 взята из инвентаря кода и документов
(`COMPARE_GOLAND.md`, `docs/IDE-FEATURES.md`, дескрипторы `go-psi-ide-*.xml`), а не снята теми же пробами на песочнице.

**Как повторить.** Скрипты лежат в `tools/ui-robot/goland/`:
- `start-goland.ps1 [-Fresh]` — запуск, печатает PID;
- `analysis/gj.sh` — помощники `gj`, `comp`, `pop`, `activate`, `escape` (`export GOLAND_PID=<pid>; . tools/ui-robot/goland/analysis/gj.sh`);
- драйверы `completions.sh` (`FROM=<проба>` — продолжить с пробы), `completions2.sh` (второй проход: текст вставляется закрытым,
  каретка — на маркере `@@`), `typing.sh`, `popups.sh`, `settings.sh`;
- JS для Rhino:
  - перенесены из Rider: `go_complete.js` (бывший `complete2.js`, плюс `__HEAD__`), `typing.js` (плюс `{SAVE}` `{FMT}` `{OPT}`),
    `popup.js`, `poll.js`, `menu.js`, `actions_tree.js`, `settings_tree.js`, `settings_page.js`, `code_style_sections.js`, `highlights.js`,
    `codevision.js` (плюс code vision в конце строки), `colors.js`, `keys.js`, `focus.js`, `tidy.js`, `unkeep.js`, `showtw.js`, `frame_size.js`;
  - новые: `wait_indexing.js`, `conditions.js`, `open.js`, `lang_data.js` (инспекции, live templates, postfix, intentions, file templates, inlay —
    через API платформы, а не со страниц);
- пробные файлы — `tools/ui-robot/goland/probe/` (`analysis.go`, `analysis_test.go`, `probeerr/broken.go`).

Порядок: `powershell -File tools/ui-robot/goland/start-goland.ps1`, скопировать пробные файлы в `build/ui-robot/goland-playground/internal/`,
`export GOLAND_PID=…; . tools/ui-robot/goland/analysis/gj.sh; python tools/ui-robot/robot.py wait; gj $A/wait_indexing.js "s|__MAX__|180|"`,
затем драйверы. Скрипты запускаются только через `robot.py js`: `robot.py script` подклеивает `prelude.js`, которому нужен наш плагин.

Подводные камни. Подтвердились с Rider:
1. Попапы и completion пустые, пока окно GoLand не на переднем плане: перед каждой пробой `activate` (`AppActivate(pid)`).
2. Попапы закрывать только Escape (`SendKeys`). Зависаний EDT в GoLand не было.
3. Попап открывает `ActionManager.tryToExecute(...)`, каретка ставится отдельным вызовом за 2 с до действия.
4. Файл восстанавливать точечной правкой (`replaceString`).

Новое в GoLand:
5. `robot.py` не пускал робота ни в какую IDE, кроме песочницы плагина (`check_sandbox`): `wait` молча ждал до таймаута. Добавлена
   переменная `ROBOT_CONFIG` — подстрока пути конфигурации допустимой IDE (`goland-robot/config`); `gj.sh` её выставляет. `robot.py openfile`
   в GoLand падает (HTTP 500) — вместо него `open.js`.
6. Settings в 2026.2 — немодальное окно `NonModalWindowWrapper$FloatDialog`, а не `MyDialog`: XPath снимка `contains(@class,'FloatDialog')`.
   Разделы Code Style — `JBTabs`, а не `JTabbedPane`.
7. XPath робота находит и уже скрытое окно `HeavyWeightWindow` прошлого попапа: снимок навигационного попапа оказался снимком Refactor This.
   Списки навигации — `JBList` (не `MyList`), File Structure — дерево; их снимали по одному, проверяя окно. В выводе `popup.js` текущий
   попап — последний блок `list MyList`.
8. Если completion сам вставил единственный вариант и документ оставлен (`KEEP=yes`), GoLand успевает сохранить файл, и `reloadFromDisk`
   уже ничего не возвращает: проба испортила якорь для следующих. Теперь `__HEAD__` печатает начало документа до штатного восстановления.
9. Python-правка JS через heredoc в Git Bash превратила `\n` в настоящий перевод строки (как и предупреждает `CLAUDE.md`): правки JS — только Edit.
10. Текст пробы вставляется в документ, а не набирается, поэтому закрывающих `}`, `"`, `` ` `` нет. На незакрытом `Holder{`,
    `select {`, `` `json:" `` GoLand даёт список «как для выражения» или пустой; живой набор так не выглядит. Такие пробы повторены вторым
    проходом (`completions2.sh`, раздел «SECOND PASS» в `completion.txt`) с закрытым текстом и кареткой на `@@`.
11. Первый запуск GoLand закрылся через ~5 минут без участия робота (робот был заблокирован, п. 5) — окно закрыли вручную; второй запуск отработал до конца.

## Файлы

| Файл | Что внутри |
|---|---|
| `dumps/main-menu.txt` | Всё дерево `MainMenu` из `ActionManager`: текст, id, сочетание, `[hidden]` / `[disabled]` в контексте редактора `.go` (592 действия) |
| `dumps/toolbar.txt` | `MainToolbarNewUI`, `MainToolbarLeft/Center/Right`, `RunToolbarMainActionGroup`, `NavBarToolBar` (144 действия) |
| `dumps/editor-popup.txt`, `dumps/project-view-popup.txt` | ПКМ редактора, вкладки, гаттера, группы Generate / Refactor / Analyze (868 действий); ПКМ дерева проекта (262) |
| `dumps/alt-enter-generate-refactor-navigate.txt` | 28 живых попапов: Alt+Enter в 20 местах, Generate в 3, Refactor This, Implementations, Super Method, Related, File Structure |
| `dumps/settings-tree.txt` | Всё дерево Settings: имя, id, класс (258 страниц) |
| `dumps/settings-pages.txt` | Опции 20 страниц: Go и 7 подстраниц, Inlay Hints, Code Completion, Auto Import, Smart Keys, Postfix, Gutter Icons, Folding, Intentions, Actions on Save, Data Views Go, Go Profiler, Color Scheme Go |
| `dumps/settings-code-style-go.txt` | Code Style \| Go: 4 раздела |
| `dumps/inspections-go.txt` | 129 инспекций Go / Go modules / Go Template: группа, имя, id, уровень, включена ли |
| `dumps/intentions-go.txt` | 59 intentions Go с категориями |
| `dumps/live-templates-go.txt` | 29 шаблонов (Go, Go Struct Tags, Go Template): ключ, описание, контексты, текст |
| `dumps/postfix-go.txt` | 50 postfix-шаблонов Go (38 разных ключей): ключ, описание, пример |
| `dumps/file-templates-go.txt` | шаблоны файлов Go |
| `dumps/inlay-code-vision-go.txt` | провайдеры inlay hints и code vision |
| `dumps/color-keys-go.txt` | Страница Color Scheme \| Go: имя ↔ `TextAttributesKey`, fallback, цвет Islands Dark, demo-текст |
| `dumps/highlight-*.txt` | Подсветка по диапазонам (ключ, слой, подсказка, гаттер), inlay, сводка ключей: `analysis.go`, `broken.go`, `analysis_test.go`, `store/order.go`, `cmd/check/main.go` |
| `dumps/code-vision-gutter.txt` | Code vision блоком и в конце строки, иконки гаттера с подсказками: `analysis.go`, `analysis_test.go`, `store/order.go` |
| `dumps/completion.txt` | 112 проб completion |
| `dumps/typing-assists.txt` | 26 проб набора |
| `img/*.png` | 27 картинок, только компоненты IDE |

## 1. Меню, тулбар, контекстные меню

Главное меню в новом UI: **File, Edit, View, Navigate, Code, Refactor, Build, Run, Tools, VCS, Window, Help**. Отдельного меню «Go» нет:
Go-действия разложены по стандартным меню. Меню Build у Go-проекта пустое.

| Меню | Что в нём для Go (видимые пункты) |
|---|---|
| Code | **Override Methods** (Ctrl+O), **Implement Methods** (Ctrl+I), Generate (Alt+Insert), Basic / **Type-Matching** (Ctrl+Shift+Space) completion, Complete Current Statement (Ctrl+Shift+Enter), Inspect Code, Code Cleanup, Analyze Data Flow to / from Here, Locate Duplicates, Live Templates, Surround With (Ctrl+Alt+T), Unwrap/Remove, Folding, Reformat Code (Ctrl+Alt+L), Optimize Imports (Ctrl+Alt+O), Move Statement / Element |
| Refactor | Refactor This (Ctrl+Alt+Shift+T), Rename (Shift+F6), Change Signature (Ctrl+F6), Introduce Variable / Constant / Field / Parameter / Parameter Object, Extract Method, Extract Interface, **Introduce Type**, **Add Method** (в интерфейс и все реализации), Inline (Ctrl+Alt+N), Move (F6), Copy (F5), Safe Delete, Pull Members Up / Push Down, Invert Boolean, **Update Syntax…** (`go fix` с предпросмотром) |
| Run | Run / Debug / **Coverage** / **Run with Profiler** (CPU, Memory, Blocking, Mutex, GoLand Profiler), Attach to Process (Ctrl+Alt+F5), **Open Core Dump**, **Debug Saved Trace** (rr), **Dump Goroutines**, **Rewind** (rr), Record and Debug (скрыт: нет rr), Manage / Generate Coverage Report, Import Profiler Results, Open pprof profile |
| Tools \| Go Tools | Go Fmt File (Ctrl+Alt+Shift+F), Go Fmt Project (Ctrl+Alt+Shift+P), Goimports File, **Go Generate File** (Ctrl+Alt+G), Go Vet File, **Share in Playground** (Ctrl+Alt+Shift+S) |
| ПКМ редактора | Go To ▸ (Declaration or Usages, Implementation(s) Ctrl+Alt+B, Type Declaration Ctrl+Shift+B, Super Method Ctrl+U, Related Symbol, Test Ctrl+Shift+T), Refactor ▸, Generate, Run / Debug / Coverage / Profile файла, **Run in Playground**, **Go Mod Tidy**, **Go Mod Vendor**, **Sync Go Module**, Go Tools ▸ |
| ПКМ дерева проекта | New, Refactor, те же Go Mod Tidy / Vendor / Sync, **Detach Directory**, **Open Directory as Project**, **Add Directory to Current Project** (GOPATH), Go Tools ▸, Mark Directory as |

**Тулбар** (`img/02-main-toolbar.png`): Run Widget с конфигурацией (`go build …/check`), Run, Debug, «⋮» (Coverage, Profiler),
а в группе CodeWithMe — **Go Settings…** и **Actions on Save…** (`GoEditSettingsAction`, `GoEditActionsOnSaveAction`).

Tool windows: Project, Structure, Problems View, **Go Optimization**, Services, Coverage, Terminal, Database, Endpoints, TODO, Bookmarks, VCS.

**Живые попапы** (`alt-enter-generate-refactor-navigate.txt`, картинки `img/20…29`):

| Действие | Где | Пункты |
|---|---|---|
| Alt+Enter | `unused := 42` | Delete variable 'unused', Rename to _, Convert to var declaration |
| Alt+Enter | `os.Remove("tmp")` (ошибка не проверена) | **Handle error**, **Ignore explicitly**, **Do not report this method/function anymore**, Introduce local variable; у Handle error превью `err := …; if err != nil { return 0 }` |
| Alt+Enter | `fmt.Printf("%d items\n", "many")` | Add format string argument, Convert to raw string, Exclude string formatting function, Do not show hints for current method, Inject language, Put arguments on separate lines; у самой инспекции quick fix нет |
| Alt+Enter | `defer f.Close()` в цикле | только настройки инспекции |
| Alt+Enter | `missing(items)` | **Create function / variable / global variable / parameter 'missing'**, Introduce local variable; превью `func missing(items []string) {}` |
| Alt+Enter | тег `` `json:name` `` | **Add key to tags**, Add parens to declaration, Convert to double-quoted, Generate constructor, **Generate struct fields from JSON**, Inject language |
| Alt+Enter | тег `` `json:"radius,omitempty"` `` | Add key to tags, **Change field name style in tags**, **Update key value in tags**, Generate constructor, … |
| Alt+Enter | неиспользуемый импорт | Import for side-effects, Optimize imports, Add import alias, Add dot import alias |
| Alt+Enter | `if get() == nil`, `if value != ""` | Replace 'if' with 'switch' |
| Alt+Enter | `type Circle struct` | Generate method, **Implement interface…** (Ctrl+I), Add parens to declaration, Generate constructor, Go to Interfaces (Ctrl+U) |
| Alt+Enter | `type Shape interface` | **Add method to interface and all its implementations**, Go to Implementations |
| Alt+Enter | метод `func (c Circle) Area` | Add comment, Go to Method Specifications |
| Alt+Enter | литерал `[]Shape{Circle{…}, …}` | Convert to var declaration, Put elements on separate lines |
| Alt+Enter | `fmt.Sprintf("%d %s …")` | Add format string argument, Exclude string formatting function, Do not show hints for current method, Put arguments on separate lines |
| Alt+Enter | затеняющее `value, err := load(…)` | **Navigate to shadowed declaration**, Rename variable, Convert to var declaration |
| Alt+Enter | `x = x`, `return p` (nil в интерфейсе), `for _, s := range` | новый список не появился |
| Generate | на `type Holder struct` | Constructor, Method, Implement Methods… (Ctrl+I), **Tests for file**, **Tests for package**, Copyright |
| Generate | на `func Factorial` | Implement Methods…, **Test for function**, Tests for file, Tests for package, Copyright |
| Generate | в `_test.go` | Implement Methods…, Tests for package, Copyright |
| Refactor This | `func load(` | Rename, Change Signature, Introduce Variable, Introduce Constant, Inline Function/Method, Move, Copy File |
| Implementation(s) | `type Shape interface` | список «Choose Implementation»: Circle, Square (с пакетом и файлом) |
| Super Method, Related Symbol | `func (c Circle) Area` | попапа нет (единственная цель — переход сразу) |
| File Structure | `analysis.go` | дерево: константы и переменные (с типом), типы с полями и методами, значки видимости; флажок «Show package structure (Ctrl+F12)» |

Статическая группа Generate (`GenerateGroup`) для Go: Constructor, Getter and Setter, Getter, Setter, **Type from JSON**, Method,
**Struct Fields from JSON**, группа тестов.

## 2. Настройки

Дерево целиком — `dumps/settings-tree.txt` (258 страниц), опции — `dumps/settings-pages.txt`, `dumps/settings-code-style-go.txt`,
картинки `img/40…49`. Корень **Go** — отдельная страница верхнего уровня, не под Tools.

| Страница | Главное (значения по умолчанию) |
|---|---|
| Go (корень) | Suggest parameters name in completion ✓; **Suggest variants that require additional imports as you type** ✓; Indent on Enter in raw strings; Show documentation in parameter info; Detect go packages from clipboard; Ask before sharing in Go Playground ✓; поведение при переименовании **каталога ↔ пакета**, **файла ↔ теста**, **тега** (Show options / делать / не делать); **When JSON is pasted**: показать выбор / конвертировать в тип Go / вставить как есть |
| Go \| GOROOT | выбор SDK (Go 1.27.1) |
| Go \| GOPATH | Global / Project / Module GOPATH; Use GOPATH from environment ✓; Index entire GOPATH |
| Go \| Go Modules | Enable Go modules integration ✓; Environment (GOPROXY, GOPRIVATE…); Enable vendoring support automatically ✓; Download Go module dependencies: для всех проектов / ни для каких / только для текущего |
| Go \| Build Tags | OS, Arch (по умолчанию хост), **Cgo support**, **Compiler** (Any / gc / gccgo), Custom tags, **Experiments** (`GOEXPERIMENT`) |
| Go \| Formatting Functions | список исключённых printf-подобных функций |
| Go \| Imports | Show import popup ✓, **Add unambiguous imports on the fly** ✓, **Optimize imports on the fly** ✓, исключения из импорта и completion |
| Go \| Linters | Execute `golangci-lint run` ✓ и `golangci-lint fmt` ✓ (v2), исполняемый файл, Concurrency, Configure severity, Only fast linters ✓, Use config (`.golangci.yml`), таблица 114 линтеров с описанием (включены errcheck, govet, ineffassign, staticcheck, unused), вкладка Formatters |
| Editor \| Code Style \| Go | Tabs and Indents (табы, 4); **Wrapping and Braces** (аргументы вызова, литералы, параметры и результаты функций: «Do not wrap», перенос после `(` / `{`); **Imports**: backquotes, скобки для одного импорта, удалять лишние алиасы, сортировка GOIMPORTS / GOFMT / NONE, один блок, группировать SDK, **группа «текущий проект» или префиксы как `goimports -local`**; Other: пробел в начале комментария, ширина Fill paragraph 80, gofmt при Reformat Code ✓ |
| Editor \| Inlay Hints | группы Parameters, Types, Values, Method chains, URL path, Other + Code Vision; провайдеры Go — `GoInlayParameterHintsProvider` (опции **Show unnamed fields in structure values** ✓, **Show return parameters** ✓), `GoInlayConstantDefinitionProvider` (значения `iota`) |
| Editor \| Code Vision (внутри Inlay Hints) | провайдеры Go: **Usages** (`go.references`), **Inheritors** (`go.inheritors`), **Implement interface**, **Add method to interface and all its implementations**, **Batch syntax update** (`go.syntax.update`), What's New (modernizer); у go.mod — Update all / direct dependencies |
| Editor \| General \| Code Folding | для Go: **One-line 'if' blocks for error handling** ✓, **One-line functions with a single 'return'** ✓, **One-line case clauses** ✓, **Empty functions** ✓, **Empty struct or interface type definitions** ✓, Formatted strings ✗; общие: заголовок файла, импорты |
| Editor \| General \| Smart Keys | общие: пары скобок и кавычек, **Reformat block on typing '}'** ✓, Surround selection on quote/brace, Jump outside closing bracket with Tab; Enter: smart indent, пара `}`, doc-заготовка; Reformat on paste. Отдельной Go-подстраницы нет |
| Editor \| General \| Postfix Completion | 50 шаблонов Go (см. `postfix-go.txt`), «Expand templates with» |
| Editor \| Intentions | 59 Go-intentions (`intentions-go.txt`) |
| Tools \| Actions on Save | **Reformat code** ✓ (Go files; «Applies both GoLand and golangci-lint»), **Optimize imports** ✓ (сортировка goimports), Rearrange, Code cleanup, Copyright, File Watcher, Upload |
| Debugger \| Data Views \| Go | формат целых (Decimal), Show types ✓, Show pointer addresses ✓, Enable String() view ✗, Show unreadable variables ✓ |
| Profilers \| Go Profiler: Applications / Tests | каталог профилей, частоты memory / block / mutex |
| Editor \| Color Scheme \| Go | 10 групп, 64 ключа — раздел 3 |

**Инспекции** (`inspections-go.txt`): 129 — Probable bugs 32, **Go fix 26** (уровень `SYNTAX_UPDATE`: `interface{}` → `any`, `strings.Cut`,
`slices.Sort`, `min`/`max`, range over int, `maps`, `//go:fix inline`, `errors.AsType` …), Declaration redundancy 20, Code style 15,
General 14, Data flow analysis 6 (**Interprocedural potential nil dereference**, Potential resource leak, Error may be not nil), Control flow 4,
Security 2 (Vulnerable API usage), Go modules 7 (обновления, deprecated, retracted, unused dependency, миграция на go.work), Go Template 2,
Code Coverage 1. Выключены по умолчанию: Nilness analyzer, Struct field alignment, Unnecessarily exported identifier, Usage of context.TODO().

**Live templates** (`live-templates-go.txt`): Go — `map p fori imports consts vars types iota forr printf err main init meth test bench fuzz :`
(18); Go Struct Tags — `xml json`; Go Template — 9. **Postfix**: `! & * aappend append appendAssign as cap close complex copy d delete
dereference else for forr if imag is len nil nn not notnil p panic par parseFloat parseInt pointer print println real remove reterr return rr
sort (5 вариантов по типу) var varCheckError`.

## 3. Подсветка в редакторе

GoLand красит Go **своими семантическими ключами `GO_*`**: на странице Color Scheme \| Go — 64 ключа в 10 группах (`dumps/color-keys-go.txt`,
картинка `img/48`). Картинки редактора — `img/01`, `img/30…33`.

| Вид | Ключи | Замечено на площадке |
|---|---|---|
| Объявления функций | `GO_EXPORTED_FUNCTION`, `GO_LOCAL_FUNCTION`, `GO_BUILTIN_FUNCTION` | да |
| Вызовы | `GO_EXPORTED_FUNCTION_CALL`, `GO_LOCAL_FUNCTION_CALL`, `GO_BUILTIN_FUNCTION_CALL` (`len`, `make` — оранжевым), вызов переменной-функции: `GO_LOCAL_VARIABLE_CALL`, `GO_PACKAGE_*_VARIABLE_CALL`, `GO_STRUCT_*_MEMBER_CALL` | да |
| Типы: объявление | `GO_TYPE_SPECIFICATION`, `GO_PACKAGE_EXPORTED_/LOCAL_STRUCT`, `…_INTERFACE` | да |
| Типы: ссылка | `GO_TYPE_REFERENCE`, `GO_EXPORTED_/LOCAL_STRUCT_REFERENCE`, `…_INTERFACE_REFERENCE`, `GO_BUILTIN_TYPE_REFERENCE` (53 места) | да |
| Переменные | `GO_LOCAL_VARIABLE`, **`GO_SCOPE_VARIABLE`** (объявленные в `for` / `if` / `switch`), `GO_PACKAGE_EXPORTED_/LOCAL_VARIABLE`, **`GO_SHADOWING_VARIABLE`** (бирюзовый) + предупреждение «Declaration of 'value' shadows declaration at …», **`GO_REASSIGNMENT_IN_SHORT_VAR_DECLARATION`**, `GO_BUILTIN_VARIABLE` (`nil`) | да, кроме переприсваивания в `:=` |
| Константы | `GO_PACKAGE_EXPORTED_/LOCAL_CONSTANT`, `GO_LOCAL_CONSTANT` — фиолетовые курсивом; `GO_BUILTIN_CONSTANT` | да |
| Поля, параметры, получатель | `GO_STRUCT_EXPORTED_/LOCAL_MEMBER`, `GO_FUNCTION_PARAMETER`, **`GO_METHOD_RECEIVER`** (голубой) | да |
| Пакет, метка | `GO_PACKAGE` (зелёно-жёлтый), `GO_LABEL` | да |
| Теги структур | **`GO_TAG_KEY`, `GO_TAG_COLON`, `GO_TAG_VALUE`, `GO_TAG_TEXT`** | да |
| Строки | `GO_VALID_STRING_ESCAPE` — **и для глаголов `%d %s %v %q %w`** в `Printf`-подобных; `GO_INVALID_STRING_ESCAPE` | да |
| Комментарии | `GO_COMMENT_KEYWORD` (`go:generate`, `go:embed`), **`GO_COMMENT_REFERENCE`** (имя в doc-комментарии — ссылка, 24 места), `GO_BUILD_TAG` / `_PAREN` / `_OPERATOR` | да |
| Инъекции | `//go:generate stringer …` — **Bash** (`BASH.EXTERNAL_COMMAND`); `regexp.MustCompile` — RegExp (`REGEXP.META`); `time.Format("2006-01-02 …")` — фрагмент раскладки | да |
| Синтаксис устарел | `GO_SYNTAX_UPDATE`: «for loop can be modernized using range over int» | да |
| Неиспользуемое | `NOT_USED_ELEMENT_ATTRIBUTES`: константы, функции (**и экспортируемые** — `Analysis`, `Probes`), параметры | 15 мест |

**Inlay hints** (`highlight-*.txt`, раздел inline inlays):
- значения `iota`: `Debug Level = iota` `= 0`, `Info` `= 1`, `Warn` `= 2`;
- имена параметров только у литералов и неясных аргументов: `format:` у `Sprintf` / `Errorf` / `Printf`, `layout:` у `Format`, `str:` у
  `MustCompile`, `n:` у `produce(3)` / `Factorial(3)`, `target:` у `find(…, 1)`, `text:` у `errors.New`, `args...:` у `t.Fatal`;
- **безымянные поля литерала**: строки табличного теста `{"empty", nil, 0}` получают `name:` `in:` `want:`.

**Code vision** (`code-vision-gutter.txt`): в **конце строки объявления** (а не над ней) — «N usages» / «no usages» (`go.references`) и
«N implementations» (`go.inheritors`) у интерфейса и его методов; над каждым типом-структурой — блок **«Implement interface»**.

**Гаттер:**
- `Go to Implementations` (Ctrl+Alt+B) на интерфейсе и его методах; `Go to Interfaces` / `Go to Method Specifications` (Ctrl+U) на типах и
  методах — **неявная реализация видна в обе стороны**;
- **Recursive call** у `Factorial(n-1)`;
- **Run go generate on comment** у `//go:generate`;
- в `_test.go` Run Test у `package`, `TestX`, `BenchmarkX`, `ExampleX`, `FuzzX` и **у каждой строки табличного теста** (`{"empty", nil, 0}`);
- однострочное сворачивание: `if err != nil { return "", err }`, `case n := <-produce(3): msg += …`, короткие функции.

**Проверки, сработавшие на `broken.go`** (подсказки — `highlight-internal-probeerr-broken.txt`):
- error: Unused import, Unused variable, Unresolved reference 'missing', Missing 'return' at the end of the function;
- warning: **Unhandled error** (`os.Remove`, `f.Close`), Unreachable code, «Expected an opening double quote character after ':'» (тег);
- weak / info: «Placeholder argument '"many"' has the wrong type 'string' (%d)», «Value of 'x' is assigned to itself», «Possible resource leak,
  'defer' is called in the 'for' loop», **«Unused result»** у каждого вызова, результат которого отброшен; Unused function 'Broken';
- **не сработали**: сравнение интерфейса, содержащего nil-указатель, с `nil`; повторный ключ `json` в одном теге.

## 4. Completion (112 проб, `dumps/completion.txt`)

Обозначения: AUTO — автопопап после набора символа; BASIC — Ctrl+Space; SMART — Type-Matching (Ctrl+Shift+Space). Справа у каждого пункта
тип; у полей и методов — **«→ Владелец»** (`created → Base`, `Area() → *Square`, `Error() → interface {...}`), так видно продвижение
через встроенную структуру и набор методов указателя. Пункты чужих пакетов — с путём пакета (`json.Marshal(v any) encoding/json`).
Пробы второго прохода помечены C.

| Где / что набрано | Вызов | Что дал GoLand | Замечания |
|---|---|---|---|
| `c.` (`Circle` со встроенным `Base`) | AUTO | поля и методы с владельцем: `created → Base`, `ID → Base`, `Label`, `Radius`, `Area()`, `Describe() → Base`, `Name()`, `Base`; затем **postfix в том же списке** (`p`, `panic`, `par`, `pointer`…) | промоутированные — вперемешку по алфавиту |
| `sq.` (`*Square`), `shapes[0].`, `err.` | AUTO | методы указателя `→ *Square`; методы интерфейса `→ interface {...}`; у `error` — `Error()` и postfix `as`, `is`, `nil`, `nn`, `notnil` | postfix подбираются по типу |
| пустая строка | BASIC | первыми — **`return err`**, `return`, затем пакеты, `err`, типы | готовый `return` с переменной ошибки в области |
| пустая строка | SMART | пусто | — |
| `cl` | AUTO | `classify`, `clear`, `close`, затем **символы неимпортированных пакетов** (`strings.Clone`, `ast.CompositeLit`, `os.Clearenv`) | автопопап на буквах есть |
| `strings.` | AUTO | экспорт пакета по алфавиту, с сигнатурой и результатом | — |
| `json.` (не импортирован) | AUTO | `json.Marshal … encoding/json`, `json.Marshal … encoding/json/v2`, `json.Unmarshal`… | оба `json` (v1 и v2) |
| `json.Mar` → выбор | BASIC | `json.Marshal(<caret>)` и **сразу `"encoding/json"` в блоке импорта** на своём месте | импорт без задержки |
| `Marsh`, `ToUpp` | BASIC | `json.Marshal`, `xml.Marshal`, `asn1.Marshal`…; `strings.ToUpper`, `bytes.ToUpper`, `unicode.ToUpper` | голое имя чужого пакета |
| `NewReplac` | BASIC | единственный вариант **вставлен сразу**: `strings.NewReplacer(<caret>)` | — |
| `Factorial(` / `total = ` | SMART | `Factorial`, `MaxItems = 10`, `minItems = 1`, `total`, затем `strings.Compare/Count/Index…` (int) | **значение константы в пункте** |
| `var s Shape = ` | SMART | `&Square`, `c`, `Circle`, `sq`, `Shape`, `nil` | **`&Square` — указатель, потому что методы у `*Square`** |
| `err = ` / `errors.Is(err, ` | SMART | `err`, `ErrNotFound`, `Probes(…)`, `fmt.Errorf`, `errors.ErrUnsupported`, `errors.Join`, `errors.New` | — |
| `lvl == ` / `switch lvl { case ` | SMART / BASIC | `Debug = iota`, `Info = iota`, `lvl`, `Warn = iota` первыми | константы типа `Level` |
| C `Holder{@@}` | BASIC | **`Fill all fields…`, `Fill selected fields…`** (действия в списке), затем `Count → Holder`, `Enabled`, `Name`, значения | картинка `img/34` |
| C `Holder{Name: "x", @@}` | BASIC | 4 пункта: Fill…, Fill selected…, `Count`, `Enabled` | уже заданные поля убираются |
| C выбор `Fill all fields…` | BASIC | литерал развёрнут по строкам: `Name:    ""`, `Count: 0`… с выравниванием | — |
| C `Holder{Name: @@}` | SMART | только `string`: `classify`, `selfSource`, `fmt.Sprint*`, `strings.Clone`… | — |
| `Cir` → выбор | BASIC | `Circle{<caret>}` — **литерал со скобками** | — |
| `c.Ar` | BASIC | единственный вариант вставлен как вызов `c.Area()` | method value не снят |
| `le` / `app` → выбор | BASIC | `len(<caret>)`, `append(<caret>)` | — |
| `go`, `def`, `sel`, `for … ra`, C `fallth` | BASIC | ключевое слово первым или вторым (после совпавшего имени) | `fallthrough` — только внутри `case` |
| `ch <- ` | SMART | значения `int` | — |
| `v := <-` | BASIC | **`make(chan interface{})`**, `nil`, … | — |
| C `select { case @@ }`, C `switch err.(type) { case @@ }` | BASIC | общий список значений / типов; заготовок `case v := <-ch:` нет | — |
| C `fmt.Printf("%@@")`, C `"` + набор `%` | BASIC / AUTO | **пусто** | completion глаголов нет |
| C `time.Now().Format("@@")` | BASIC | **`YY`, `YYYY`, `MM`, `DD`, `mm`, `ss`, `year...`, `month...`** с описанием | раскладка времени |
| `` regexp.MustCompile(`\ `` | BASIC | 187 конструкций RE2 с описанием (`\d digits (== [0-9])`, `\A at beginning of text`…) | инъекция RegExp |
| `names.fo` | BASIC | postfix `for`, `forr` | — |
| `.forr` / `.for` на `names` | BASIC | `for i, name := range names {}` / `for i := range names {}` — **имя `name` выведено из `names`** | — |
| `err.nn`, `err.nil` | BASIC | `if err != nil {}` / `if err == nil {}` | — |
| `load("x").rr` | BASIC | `if _, err := load("x"); err != nil { return err }` | число результатов учтено (`_, err`) |
| `load("x").varCheckError` | BASIC | `err, err := load("x"); if err != nil { return err }` | имя первой переменной — шаблонное поле |
| `c.Area().var` | BASIC | **`area := c.Area()`** — имя выведено | — |
| `err.return`, `(total > 0).if`, `total.print` | BASIC | `return err`; `if (total > 0) {}` (скобки не убраны); `print(total)` | — |
| `names.sort` | BASIC | один вариант по типу: `sort.Strings()` | из 5 `sort` выбран по `[]string` |
| `forr`, `fori` | BASIC | `for i, i2 := range collection {}`; `for i := 0; i < ; i++ {}` | шаблоны без вывода имён |
| `err` (live template) | BASIC | в списке и переменная `err`, и шаблон «If error» | выбор первого вставил переменную |
| `printf` | BASIC | `fmt.Printf("", <caret>)` | — |
| `meth` / `main` / `test` / `bench` (верхний уровень) | BASIC | `func (b Base) name() {}` (тип получателя — первый тип файла); `func main() {}`; `func TestName(t *testing.T) {}`; `func BenchmarkName(b *testing.B) { for i := 0; i < b.N; i++ {} }` | — |
| `fun` (верхний уровень) | BASIC | `func`, **`func (*T) : Method`**, **`func : Implement Interface...`** | Implement Interface — пункт completion |
| `func (h *Hol` | BASIC | `Holder` | — |
| C `func (h *Holder) @@()`, C `Str@@` | BASIC | пусто | имён методов интерфейсов нет |
| `func g(` | BASIC | **имена параметров по типу**: `err error`, `string2 string`, `base Base`… | — |
| C `var @@ Circle`, C `for @@ := range` | BASIC | пусто | имён переменных нет |
| `return ` в `(*Circle, int, error)` | SMART / BASIC | `&Circle`, `nil` / `nil` и всё | **готовых нулевых значений `nil, 0, nil` нет** |
| `` Age int ` `` | AUTO | **`Add tag key to all fields…`**, `asn1`, `bson`, `json`, `xml`, `yaml` | — |
| C `` FullName string `json:"@@"` `` | BASIC | **`full-name`, `full_name`, `FullName`, `fullName`** | четыре стиля имени |
| `"encoding/` / `"github.com/` в импорте | BASIC | пакеты stdlib; модуль из `require` | — |
| `t.` в тесте | AUTO | 39 методов `*testing.T` с владельцем (`*common`, `*T`) | — |
| `assert` (testify нет) | BASIC | пусто | — |
| go.mod: пустая строка | BASIC | `exclude go godebug ignore module replace require retract …` | — |
| go.mod: `require github.com/google/uuid ` | BASIC | пакеты (`cmd`, `std`, …) вместо версий | версий нет |
| C `if @@ {}` | SMART | только `bool`: `errors.As`, `errors.Is`, `strings.Contains*`, `HasPrefix`… | — |

## 5. Помощь при наборе (`dumps/typing-assists.txt`, 26 проб)

| Что набрано | Что получилось |
|---|---|
| `(` после `load` | `load(<caret>)` — пара |
| `"ab"`, `` `ab` ``, `'x'`, `[]int{1}` | пары `""`, обратных кавычек, `''`, `[]`, `{}`; закрывающий символ перешагивается |
| `{` + Enter после `if …` / заголовка функции | блок на три строки, каретка с отступом |
| Enter в конце `// first part` | новая строка **без** `//` |
| Ctrl+Shift+Enter в `if total > 0`, `for i := 0; i < 3; i++`, `func other(a int) error` | `{` `}` дописаны, каретка внутри |
| Ctrl+Shift+Enter на `load("x"` | `)` дописана, каретка на новой строке |
| Enter после `case 1:` | отступ на уровень глубже |
| `}` после `if total>0 {   total=1   ` | **не переформатировано** (настройка «Reformat block on typing '}'» включена; робот не воспроизвёл — проверить вживую) |
| `(` + Backspace | пара удалена целиком |
| `:=1` после имени | без помощи |
| `lo` | автопопап (123 пункта) |
| `//` + Enter над функцией | заготовки doc-комментария нет |
| `.` после `c.Name` | `()` не вставляется, открыт список (2 пункта) |
| Enter в блоке импорта | обычная новая строка с отступом |
| сохранение `total=total+1` (`saveAllDocuments`) | **не отформатировано** за 4 с (Actions on Save: Reformat включён — проверить вживую) |
| Reformat Code | `total = total + 1` |
| Optimize Imports в `broken.go` | неиспользуемый `"strings"` удалён |
| `err` + Tab | Tab вставил табуляцию: шаблон не развернулся (действие `EditorTab`, а не раскрытие шаблона — проба не годится) |
| Enter внутри строки, Smart Enter в литерале | пробы составлены неудачно (строка оказалась незакрытой; литерал без `}`) — не снято |

## 6. Чего не хватает плагину (приоритеты)

«Как сделать» — через слои плагина: **синтаксис** — PSI / стабы go-psi (`go-psi-core`), токены `lang.GoTokens`; **семантика** — resolve и
типы `go-psi-semantic`, индексы `GoTypesIndex` / `GoExportsIndex`; **каталог** — `catalogue`; **IDE-фича** — `go-psi-ide` / пакет `lang`;
**gopls** — только где NATIVE-пути нет; **инструмент** — `go`, golangci-lint; **платформа** — API IntelliJ без знаний о Go.
Колонка «У нас сейчас» — по инвентарю кода и документов, **не замер** (раздел 5 задания не делался). Приоритет — по частоте в ежедневной
работе, затем по цене.

| # | Есть в GoLand | У нас сейчас | Чего нет | Как сделать | Цена (дни) |
|---|---|---|---|---|---|
| 1 | Сворачивание однострочных `if err != nil { return … }`, функций с одним `return`, `case`-веток, пустых функций и типов — включено по умолчанию, настройки в Code Folding | `GoFoldingBuilder`: блоки целиком, плейсхолдер `...` | однострочный показ свёрнутого блока и эти 5 настроек | **синтаксис**: регионы с плейсхолдером `{ return "", err }` из текста тела, `isCollapsedByDefault` по настройке; `CodeFoldingOptionsProvider` для Go | 1 |
| 2 | 64 цветовых ключа: экспортируемое / локальное у функций, типов, констант, переменных, полей; получатель; затенение; переменная области (`for`/`if`); переприсваивание в `:=`; вызов встроенной функции | ~28 ключей Go, аннотатор не отличает экспортируемое, получатель, затенение | ключи и страница цветов с этими именами | **семантика**: аннотатор уже знает вид объявления (`GoDeclarationKind`); экспорт — по первой букве; получатель — отдельно от параметра; затенение — resolve областей. Новые ключи с fallback на текущие, чтобы схемы не ломались | 2 |
| 3 | Затенение: цвет + предупреждение «Declaration of 'x' shadows declaration at …» + Alt+Enter **Navigate to shadowed declaration** | `GoShadowedError` (только `err`) | для всех переменных, переход к затенённому | **семантика**: resolve во внешней области; intention с навигацией | 1 |
| 4 | Code vision: «N usages» и «N implementations» **в конце строки** объявления; «Implement interface» блоком над каждой структурой | usages и implementations блоком над объявлением | линза Implement interface, позиция «в конце строки» | **IDE-фича**: провайдер-линза, вызывающая наш Implement Interface (Ctrl+I); позиция — `CodeVisionAnchorKind` по настройке | 0,5 |
| 5 | В completion литерала: **Fill all fields… / Fill selected fields…**; выбор разворачивает литерал по строкам с выравниванием | intentions FillStructFields / FillRequiredFields; completion полей — оставшиеся | пункты-действия в lookup, «Fill selected» с выбором полей | **IDE-фича**: `LookupElement` с `handleInsert` → наш intention; диалог выбора полей | 1 |
| 6 | Глаголы `%d %s %v %w` подсвечены в `Printf`-подобных; inlay `format:` у строки формата | inspection `GoPrintf`, completion глаголов после `%` | подсветка глаголов внутри строки | **синтаксис + семантика**: аннотатор по аргументу формата известных функций (тот же список, что у `GoPrintf`), ключ `GO_FORMAT_VERB` с fallback на escape | 0,5 |
| 7 | Теги: подсветка ключ / `:` / значение; Alt+Enter **Add key to tags**, **Change field name style in tags**, **Update key value in tags**; completion `Add tag key to all fields…`; имя в 4 стилях (`full-name`, `full_name`, `FullName`, `fullName`) | 27 ключей, правила validator / gorm / env, `GoStructTag`, typed handler | подсветка тега, массовые действия, стили имени | **синтаксис**: разбор тега уже есть в inspection — отдать токены аннотатору; intentions по всем полям структуры | 1,5 |
| 8 | Postfix: 38 ключей, подбор по типу (у `error` — `as`, `is`, `nn`; `.sort` выбирает `sort.Strings` / `Ints` / `Slice` по типу), **вывод имён**: `.var` → `area := c.Area()`, `.forr` → `name` из `names`; postfix в общем списке после `.` | 28 ключей | имена из выражения, `.sort` по типу, `& * d p pointer dereference cap close copy delete is as`, `reterr`, `varCheckError`, `parseInt/Float` | **синтаксис** (имя — последний идентификатор, единственное число — отрезать `s`), тип — **семантика** | 1 |
| 9 | Inspection group **Go fix** (26 правил modernize: `interface{}`→`any`, `strings.Cut`, `slices.Sort`, `min`/`max`, range over int, `maps`, `//go:fix inline`…) + меню **Refactor \| Update Syntax…** (пакетно, с предпросмотром) + линза «Batch syntax update» | только анализаторы modernize gopls (gopls по умолчанию выключен) | нативные правила и пакетное применение | **семантика**: 8–10 самых частых правил как inspections с quick fix; пакетно — платформенный `RunInspection` по области; или **инструмент** `go fix ./...` с диффом | 3–5 |
| 10 | Unhandled error: **Do not report this method/function anymore** (список исключений), **Ignore explicitly**; info «Unused result» у каждого отброшенного результата | `GoUncheckedError`, Handle error / Ignore explicitly | список исключений из Alt+Enter, «Unused result» | **IDE-фича**: настройка-список в inspection + quick fix «добавить в исключения» | 0,5 |
| 11 | Гаттер **Recursive call** | нет | иконка у рекурсивного вызова | **семантика**: resolve вызова == своя функция; `LineMarkerProvider` | 0,5 |
| 12 | Refactor: **Add Method** (в интерфейс и все реализации, и как intention / линза), **Extract Interface**, **Introduce Type**, Introduce Parameter | Rename, Introduce Variable/Constant, Extract Function, Inline, Change Signature, Move, Safe Delete | эти четыре | **семантика**: реализации — `GoImplementationSearch`; Extract Interface — методы типа из стабов | 3 |
| 13 | Инъекция **Bash в `//go:generate`**; inlay `layout:` / `str:`; completion раскладки `YYYY MM DD` в `time.Format("…")` | references в `go:generate`, RegExp, JSON, SQL-инъекции, inlay `go.time.layout`, completion раскладки (по документам) | Bash в директиве; сверить completion раскладки | **платформа**: `MultiHostInjector` языка Shell Script, если он есть в IDE | 0,5 |
| 14 | Generate: **Tests for file**, **Tests for package**, Test for function; Struct Fields from JSON; Copyright | Test для функции и файла, Type from JSON | тесты для пакета, поля из JSON в существующую структуру | **IDE-фича**: `GoGenerators` поверх уже есть генератора теста | 1 |
| 15 | Корень Settings \| Go: переименование **каталога ↔ пакета**, **файла ↔ теста**, **тега** при переименовании поля; **When JSON is pasted → Go type** | Rename с пакетом; Type from JSON — в Generate | связанные переименования, вставка JSON как тип | **IDE-фича**: `RenamePsiElementProcessor` для файла / тега; `CopyPastePreProcessor` для JSON | 2 |
| 16 | Code Style \| Go \| Imports: сортировка GOIMPORTS / GOFMT / NONE, группа «проект» или префиксы как `goimports -local`, один блок, удалять лишние алиасы; Imports: **Add unambiguous imports on the fly**, **Optimize imports on the fly** | `GoImportOptimizer` (группы goimports), вставка из completion | эти настройки и импорт «на лету» | **синтаксис + каталог**: настройки в `GoSettings`, однозначный импорт по `GoSymbolIndex` | 1,5 |
| 17 | Completion: значение константы в пункте (`MaxItems = 10`), `&Square` по набору методов, `return err` первым на пустой строке, `func : Implement Interface...` и `func (*T) : Method` на верхнем уровне, имена параметров по типу (`err error`, `base Base`) | smart по типу, `GoReturnCompletionContributor`, имён по типу нет | эти пункты | **семантика** (набор методов указателя), **синтаксис** (имена) | 1,5 |
| 18 | Inlay **Show return parameters** (параметры-результаты) | имена параметров, поля литерала, `iota`, типы, размер структуры | имена результатов | **семантика**, провайдер `go.parameter.names` | 0,5 |

**Где плагин уже не хуже или лучше** (по инвентарю, вживую в этой разведке не сравнивалось):
- live templates: у нас 41, у GoLand 18 для Go (+2 тега, +9 Go Template);
- inspections потока: незакрытые блокировки, отправка в закрытый канал, `WaitGroup.Add` в горутине, потерянный `cancel` — у GoLand
  соответствия нашлись не для всех; inlay размера и выравнивания структуры (у GoLand «Struct field alignment» выключен);
- intentions Fill Switch / Select, Merge / Split if, Wrap error `%w` — у GoLand в Alt+Enter на тех же местах их не было;
- набор в русской раскладке, серый текст по правилам без модели, completion глаголов `Printf` (у GoLand пусто);
- иконки запуска у строк табличного теста, значения `iota`, безымянные поля литерала — паритет.

**Расхождения с `COMPARE_GOLAND.md`** (там «по справке», здесь «снято»):
- инспекций у GoLand **129 в 12 группах**, а не «123 в 9»; новая группа Go fix (26) и Go modules (7);
- живых шаблонов у GoLand **29** (18 для Go), а не «около 40»;
- postfix — 38 разных ключей;
- иконка рекурсии и вставки (RegExp, раскладка времени, Bash в `go:generate`) подтверждены;
- «Группировка использований по чтению и записи — нет» у нас устарело: в коде есть `GoReadWriteAccessDetector`.

## Предложение для ROADMAP / PLAN

Пункты 1–8 таблицы — «дешёвый паритет в ежедневной работе», около 9 дней; 9–18 — следующим этапом, около 16 дней. Порядок по частоте:
сворачивание (1), цвета (2), затенение (3), Fill в completion (5), глаголы `Printf` (6), линза Implement interface (4), теги (7), postfix (8).
Внесено 2026-10-05 в `PLAN.md`, раздел «Паритет с GoLand», блоки G1–G9: туда попали все различия, включая каталоги (инспекции, intentions, postfix, шаблоны), сверенные с кодом плагина. Таблица выше — сводка; актуальный список и статусы — в PLAN.

## Что проверить вживую и почему

- **Формат при сохранении и переформатирование блока на `}`**: в GoLand обе настройки включены, но под роботом не сработали
  (сохранение через `saveAllDocuments`, `}` через `TypedAction`). Возможно, нужен реальный ввод с клавиатуры или больше времени.
- Раскрытие live template по Tab (`err` + Tab): робот зовёт `EditorTab`, а не раскрытие шаблона.
- Enter внутри строкового литерала и Smart Enter в литерале структуры — пробы составлены неудачно.
- Подсказки при наведении (quick doc, тексты инспекций в балуне), предпросмотр Alt+Enter (Ctrl+Q) — картинками не снимаются.
- Попапы Alt+Enter на `x = x`, `return p` и `for … range` не открылись: либо действий нет, либо попап не успел. Super Method и Related Symbol
  на методе ушли сразу в переход.
- Пустые ответы completion: глаголы `Printf`, имена переменных, имена методов на получателе, путь модуля в `require` go.mod — у GoLand
  этого, похоже, нет, но автопопап внутри строки мог не вызваться.
- Inlay «Show return parameters» включён, но на площадке не встретился.
