# Playground: ручная проверка плагина

К сборке плагина не относится. Запуск IDE с этим проектом (из корня репозитория, Git Bash):

```sh
export JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\jbr"
./gradlew.bat runIde --args="C:/Users/dvislobokov/idea-golang-support/playground"
```

Отметки в колонке «Робот»: **да** — уже проверено UI-роботом, **API** — проверено вызовом API IDE без кликов, **нет** — роботом не проверялось.
Пункты с «нет» пользователь проверил вживую 2026-09-30; колонка говорит только о роботе. `TestFailing` в `store/order_test.go` падает нарочно, `TestSkipped` пропускается нарочно.

## 1. Редактор и язык

| # | Что сделать | Что должно быть | Робот |
|---|---|---|---|
| 1.1 | Открыть `store/order.go` | Подсветка: ключевые слова, строки, теги структур, встроенные типы (`int`, `string`), имена объявлений; директив `//go:` в этом файле нет | да |
| 1.2 | Structure (Alt+7) | `ErrEmpty`, `Item` с полями, `Priced` с `Total() int`, `Order`, методы как `(Order) Add(item Item)`; у полей и функций нет пустых стрелок раскрытия | да, стрелки — нет |
| 1.3 | Breadcrumbs внизу редактора, каретка внутри `Total` | `(Order) Total()` | да |
| 1.4 | Свернуть тело функции, блок `type ( … )`, несколько строк `//` подряд | Сворачивается: `{...}`, `(...)`, `//...` | нет |
| 1.5 | Ctrl+N → `Order`; Ctrl+Alt+Shift+N → `NewOrder`, `Validate` | Находит, переходит | нет |
| 1.6 | Ctrl+/ на строке, Ctrl+Shift+/ на выделении | `//` и `/* */` | нет |
| 1.7 | Набрать `err` + Tab внутри функции; `test` + Tab в `_test.go`; `meth` + Tab под структурой | Live templates; у `meth` получатель — `*Тип`, объявленный выше | нет |
| 1.8 | Settings → Editor → Color Scheme → Go | Группы атрибутов, демо-текст раскрашен | да |

## 2. Набор текста (блок 1 плана)

| # | Что сделать | Что должно быть | Робот |
|---|---|---|---|
| 2.1 | Enter в конце строки `for _, item := range o.items {` | Новая строка с отступом на уровень глубже (табом) | да |
| 2.2 | Набрать `if x {}` и Enter между скобками | Три строки, каретка на средней с отступом; то же для `()` и `[]` | да (`{}`) |
| 2.3 | Внутри `switch` набрать `case 1:` и Enter | Следующая строка на уровень глубже `case`; сам `case` на уровне `switch` | нет (юнит-тест) |
| 2.4 | Набрать `total :=` и Enter | Строка-продолжение на уровень глубже | нет (юнит-тест) |
| 2.4а | В `store` набрать `func Load(name string) (*Order, error) {`, внутри `f, err := os.Open(name)` и Enter | Серым: `if err != nil {` / `return nil, err` / `}`; **Tab** вставляет; любой другой символ убирает | да |
| 2.4б | После вставленного блока нажать Enter | Серым: `defer f.Close()` | нет (юнит-тест) |
| 2.4в | `ctx, cancel := context.WithTimeout(ctx, time.Second)` + Enter; `mu.Lock()` + Enter | `defer cancel()`; `defer mu.Unlock()` | нет (юнит-тест) |
| 2.4г | В `func main` после `err := run()` + Enter; в тесте | `log.Fatal(err)`; `t.Fatal(err)` | нет (юнит-тест) |
| 2.4д | Начать набирать `if` на строке с подсказкой; набрать что-то другое | Подсказка остаётся и сокращается; исчезает | нет |
| 2.5 | Испортить пробелы (`func      NewOrder`), Ctrl+S | Через мгновение строка отформатирована, файл сохранён | да |
| 2.6 | В настройках выбрать goimports, удалить используемый импорт, Ctrl+S | Импорт вернулся | нет |
| 2.7 | Сделать синтаксическую ошибку, Ctrl+S | Файл сохранён как есть, без сообщений об ошибке форматтера | нет |
| 2.8 | ПКМ по `store` → New → Go File → Test, имя `order_service` | `order_service_test.go`: `package store`, `func TestOrderService(t *testing.T)` | шаблон — да, диалог — нет |
| 2.9 | New → Go File → Program в `cmd/tool` | `package main`, `func main()` | нет |
| 2.10 | Ctrl+Alt+L (Reformat Code) | gofmt, без ошибок | нет |

## 2а. Генераторы, шаблоны, интеншены

| # | Что сделать | Что должно быть | Робот |
|---|---|---|---|
| 2а.1 | В функции набрать `err.nil` + Tab; `os.Remove(p).err` + Tab; `items.for` + Tab; `load().var` + Tab | `if err == nil {}`; `if err := os.Remove(p); err != nil { return err }`; `for _, v := range items {}`; `v := load()` | да |
| 2а.2 | Набрать `strings.ToUp`, выбрать `ToUpper` | Добавился `import "strings"` | да |
| 2а.3 | `if x > 0` → Ctrl+Shift+Enter | `if x > 0 {` и каретка на пустой строке внутри | да |
| 2а.4 | Каретка в `type Order struct` → Alt+Insert | Constructor…, Getters…, Setters…, Getters and Setters…, String() Method…, Struct Tags…, Implement Interface…, Test | список — да; диалоги — **нет** |
| 2а.5 | Alt+Insert → Struct Tags… → json + db, snake_case, omitempty | Теги у экспортируемых полей; у неэкспортируемых только db; существующие теги дополняются | **нет** |
| 2а.6 | Alt+Insert → Implement Interface… → `Priced` на новом типе; Ctrl+I → галочка Non-project → `http.Handler` | Попап «Choose interface to implement:» с поиском; методы с `panic("not implemented")`, только недостающие; для `Handler` — `ServeHTTP(w http.ResponseWriter, r *http.Request)` и импорт `net/http` | робот |
| 2а.7 | В функции `Total` → Alt+Enter → Generate test | `order_test.go` открылся, в конце `TestOrder_Total` с таблицей | **нет** |
| 2а.8 | `os.Remove(p)` отдельной строкой → Alt+Enter → Handle error | `if err := os.Remove(p); err != nil { return err }` | да |
| 2а.9 | `data, err := os.ReadFile(n)` → Alt+Enter → Add if err != nil check | Проверка с `return nil, err` | да |
| 2а.10 | Функция с результатом без `return` → Alt+Enter → Add missing return | `return 0, nil` перед `}` | да |
| 2а.11 | `sum := add(a, 2)` без `add` → Alt+Enter → Create function 'add' | В конце файла `func add(a any, arg2 int) any { panic(...) }` | да (без результата — до правки) |
| 2а.11а | Курсор в `Flags` (`order.go`) → Alt+Enter → Reorder fields for a smaller struct (24 → 16 bytes) | `Timeout` первым, `Debug`, `Verbose` следом; после сохранения с `fieldalignment` в `.golangci.yml` та же правка — исправление к находке govet | **нет** |
| 2а.12 | Пустая строка над `func Total` → набрать `//` | `// Total ` | да |
| 2а.13 | Settings → Tools → Go → Editor: снять «Start a doc comment with the name…» → снова `//` | Комментарий остаётся `//` | **нет** |
| 2а.15 | В поле `Name string` набрать `` ` `` (IDE закроет кавычку), затем `js` | Список ключей; выбор `json` даёт `json:""` и список имён `name`…; Tab внутри кавычек **не** даёт двойных кавычек | да |
| 2а.16 | В теге `validate:"required,` → Ctrl+Space; `gorm:"primaryKey;` → Ctrl+Space; `json:"name,` → Ctrl+Space | Правила validator (email, min=, oneof=…); настройки gorm (column:, index…); опции json (omitempty…) | да |
| 2а.14 | Go → Help Page | Вкладка «Go Help»: шапка с градиентом, таблицы клавиш, подсказки; тема как у IDE | да |

## 3. gopls

| # | Что сделать | Что должно быть | Робот |
|---|---|---|---|
| 3.1 | Открыть любой `.go` | В статус-баре виджет языкового сервиса с gopls; inlay-подсказки (`text:`, типы переменных) | да |
| 3.2 | Набрать `order.` в `main.go` | Completion с методами и полями | нет |
| 3.3 | Сделать ошибку типа (`var x int = "a"`) | Красное подчёркивание от gopls | нет |
| 3.4 | Над объявлениями в `order.go` | «N usages», у `Priced` и его `Total()` — «2 implementations»; у `main` и тестов счётчиков нет | да |
| 3.5 | Клик по «4 usages» у `NewOrder` | Попап с 4 местами: `main.go` и три в `order_test.go` | нет |
| 3.6 | Клик по «2 implementations» у `Priced` | `Order` и `Discounted` | нет |
| 3.6а | Gutter в `order.go` | `I↓` у `Priced` и у его `Total()`; `I↑` у `Order`, `Discounted` и у их методов `Total()`; у `Item`, `NewOrder`, `Add` иконок нет | да |
| 3.6б | Клик по `I↓` у `Priced`; по `I↑` у `Order`; по `I↑` у `(o *Order) Total()` | Попап с `Order` и `Discounted`; переход к `Priced`; переход к `Total()` интерфейса. Подсказка при наведении: «Is implemented by 2 types» / «Implements 1 interface» | **нет** |
| 3.7 | **Ctrl + навести мышь** на `NewOrder` в `main.go` | Имя подчёркивается как ссылка, курсор — рука, всплывает `func NewOrder(...)`; клик — переход | **нет** |
| 3.8 | Ctrl+B на `store.NewOrder` в `main.go`; на `uuid.NewString` | Переход в `order.go`; переход в исходник из module cache | API / нет |
| 3.9 | Alt+F7 на имени `NewOrder` в объявлении | Окно Find с 4 usages | API |
| 3.10 | Ctrl+Alt+B на имени `Priced` в объявлении | `Order`, `Discounted` | API |
| 3.11 | Alt+F7 / Ctrl+Alt+B на **использовании** (не объявлении) | Известное ограничение: может не сработать. Записать, что происходит | нет |
| 3.12 | Shift+F6 на `NewOrder` | Rename через gopls, правятся все файлы | нет |
| 3.13 | Наведение без Ctrl, Ctrl+Q | Документация от gopls | нет |
| 3.14 | Alt+Enter на структуре, на вызове с ошибкой | Code actions gopls (fill struct, add tags…) | нет |
| 3.15 | Settings → Tools → Go: включить Staticcheck, Apply | gopls перезапустился (виджет), предупреждений стало больше | нет |
| 3.16 | Меню Go → gopls → Restart | Перезапуск без ошибок; в окне gopls строки «Stopped», «Starting: …», «Initialized: gopls v0.23.0» | лог — да |
| 3.17 | Клик по виджету языковых сервисов в статус-баре | Строка gopls с иконкой Go; рядом Restart / Stop и **Show Log**; шестерёнка открывает Settings → Tools → Go | **нет** |
| 3.18 | Меню Go → gopls → Show Log | Окно **gopls** внизу: лог сервера (Created View, go/packages.Load…), команды, кнопки Restart / Open Debug Pages / Settings | да |
| 3.19 | `go.mod`: линза «Run go mod tidy»; «Check for upgrades» над `require` | Прогресс в статус-баре; в логе строка команды; после check — подсказка о новой версии uuid (если есть) | через API — да |
| 3.20 | `go.mod`: «Upgrade direct dependencies» | Ошибка сети / успех — в балуне и в логе, IDE не зависает | ошибка — да |
| 3.21 | Settings → gopls → Codelenses → включить `test`, Apply; в `order_test.go` линза «run test» | Запускается конфигурация `TestTotal` в раннере тестов, не в gopls | через API — да |
| 3.22 | Над `//go:generate` в `zz_generated.consts.go` линза «run go generate» | `go generate` в Build window | через API — да |
| 3.23 | Go → gopls → Check for Dependency Upgrades; Show Statistics | Диагностика в go.mod; в окне gopls JSON со статистикой (Files, Packages, HeapAlloc) | да |
| 3.24 | Go → gopls → Add Import… в `main.go` | Попап со списком пакетов с поиском; выбор добавляет import | **нет** |
| 3.25 | Каретка на `Total` → Go → gopls → Browse Documentation; Browse Assembly; выделить строки → Browse Free Symbols | Открывается браузер со страницей gopls | **нет** |
| 3.26 | Go → gopls → Toggle Compiler Optimization Details | В файле появляются подсказки об inlining / escape; повтор — убирает | **нет** |
| 3.27 | Settings → Tools → Go → «Serve the debug pages», «Log every message», Apply → Go → gopls → Open Debug Pages | Браузер с http://localhost:NNNNN; в окне gopls — весь протокол | **нет** |

## 4. Линтер

| # | Что сделать | Что должно быть | Робот |
|---|---|---|---|
| 4.1 | Открыть `store/lint.go`, подождать несколько секунд | Жёлтое подчёркивание `os.Open("x")` целиком, текст `errcheck: Error return value…` | да, диапазон после правки — нет |
| 4.1а | Alt+Enter на `os.Open("x")` в `lint.go` | «Handle error», «Ignore error explicitly», «Suppress with //nolint:errcheck», «Refactorings and actions of gopls...» | да |
| 4.1б | «Handle error» | `_, err := os.Open("x")` и `if err != nil { return err }`, каретка на `err` после `return` | да |
| 4.1в | Отменить (Ctrl+Z), затем «Ignore error explicitly»; затем «Suppress with //nolint» | `_, _ = os.Open("x")`; `//nolint:errcheck` в конце строки | нет (юнит-тест) |
| 4.1г | Выделить выражение (`order.Total()` в `main.go`), Alt+Enter → «Refactorings and actions of gopls...» | Попап: Extract variable, Inline call…; выбор применяет правку | **нет** |
| 4.2 | Исправить (`_, _ = os.Open("x")`), сохранить | Предупреждение исчезло | нет |
| 4.3 | Выключить галочку golangci-lint в настройках | Предупреждений линтера нет | нет |

## 5. Модули и дерево проекта

| # | Что сделать | Что должно быть | Робот |
|---|---|---|---|
| 5.1 | Дерево проекта | Иконки: `go.mod` (квадрат с «go»), `go.sum` (щит), `_test.go` (с галочкой), `zz_generated.consts.go` (оранжевые `<>`), `page.gohtml` (фигурные скобки), `.golangci.yml` (шестерёнка) | часть |
| 5.1а | `order_test.go` в дереве и во вкладке | Зелёный фон, как у тестов в GoLand (scope «Tests») | да (дерево) |
| 5.2 | Узел **Dependencies** под корнем | `github.com/google/uuid  v1.6.0`; раскрывается в исходники из module cache; двойной клик — строка в `go.mod` | узел виден — да, раскрытие — нет |
| 5.3 | Открыть `go.mod` | Подсветка директив и версий, Ctrl+/ комментирует | нет |
| 5.4 | Go → Modules → Tidy / Download | Задача в Build tool window, без ошибок | нет |
| 5.5 | Go → New Go Module… на новом каталоге | Диалог с путём модуля, создаётся `go.mod` | нет |
| 5.6 | Go → Go on This Machine… | Таблица: go, модуль, инструменты, `go env` | да |

## 6. Сборка

| # | Что сделать | Что должно быть | Робот |
|---|---|---|---|
| 6.1 | Go → Build | Build tool window: `go build ./...`, успех | нет |
| 6.2 | Сделать ошибку компиляции, Go → Build | Окно открылось само, ошибка — узел с переходом к строке | нет |
| 6.3 | Go → Vet | Находки — предупреждения, не ошибки | нет |
| 6.4 | ПКМ по каталогу → Build / Vet / Tidy | Работает для модуля выбранного каталога | нет |

## 7. Запуск и тесты

| # | Что сделать | Что должно быть | Робот |
|---|---|---|---|
| 7.1 | Список конфигураций после открытия проекта | Есть `go run shop` (создана сама) | да |
| 7.2 | ▶ у `func main`, Run | Консоль: `order <uuid>, total: 1600 EUR`, `with a discount: 1440 EUR` | да (старая версия `main`) |
| 7.3 | ▶ у `func TestTotal` | Дерево: `TestTotal` с подтестами `empty`, `two_items` | да |
| 7.4 | ПКМ по `store` → Run | 1 failed, 4 passed, 1 ignored; `TestSkipped` — серый, не красный | да |
| 7.5 | Клик по `TestFailing` | В консоли `order_test.go:NN` — ссылки, клик ведёт в файл | ссылки — да, клик — нет |
| 7.6 | Двойной клик по тесту в дереве | Переход к функции | нет |
| 7.7 | Rerun Failed Tests | Запускается только `TestFailing` | нет |
| 7.8 | ▶ у `BenchmarkTotal` | Запуск с `-bench`, результат в консоли | нет |
| 7.9 | Окно **Go Tests** (колба внизу) | Пакет, 5 функций, у бенчмарка иконка секундомера; после прогона — статусы (пропущенный — «ignored», не ошибка) | да, статус skip после правки — нет |
| 7.10 | Go Tests: выделить два теста → Run; Run All; Debug | Запускается выбранное | нет |
| 7.10а | Edit Configurations → `go run shop` → галочка **Collect runtime telemetry**, Run; окно **Go Monitor** справа | В консоли нет строк `gc N @…` и `SCHED`; в мониторе процесс `go run shop (pid)`, графики Memory/Heap/GC/Threads/Scheduler заполняются; статус «N GCs, GOMAXPROCS 12» | да (робот, `cmd/alloc`) |
| 7.10б | Go Monitor → **Goroutines** во время работы программы | Диалог со сводкой `N × функция` и списком `[Go N] …`; программа продолжает работать | да (робот) |
| 7.10в | Go Monitor → галочка **All Go processes** → Refresh | В списке Go-программы машины (gopls, docker…) с версией Go и ревизией; для них CPU и память | да (робот, список) |
| 7.10г | Go Monitor → **Debug** | Отладчик подключается к процессу | **нет** |
| 7.10д | `go test store` → Profile: **CPU profile**, Run | После прогона уведомление «CPU profile of the tests is ready»; «Open in pprof» открывает браузер с pprof; «Execution trace» → `go tool trace` | уведомление — да; браузер — **нет** |
| 7.11 | Edit Configurations → Go | Поля: Command, Package, Go tool arguments, Program arguments, Working directory, Environment, Test pattern, две галочки | нет |

## 8. Отладчик

| # | Что сделать | Что должно быть | Робот |
|---|---|---|---|
| 8.1 | Точка в `main.go` на `if len(os.Args)`, Debug `go run shop` | Остановка, `[Go 1] main.main`, стек, Locals с `order` | да |
| 8.2 | F8, F7 в `order.Total()`, Shift+F8, F9 | Шаги работают; «F8 ничего не делает» быть не должно | F8 — да |
| 8.3 | Evaluate: `order.Currency`, `len(order.items)`, `order.Total()` | Значения; вызов функции работает без `call` | да |
| 8.4 | F2 на `total` внутри `Total()` → `777` | Значение изменилось | да |
| 8.5 | F2 на строковой переменной (`o.Currency` → `"USD"`) | Значение изменилось | да (робот, через API) |
| 8.6 | Навести мышь на переменную во время остановки | Всплывает значение | **нет** |
| 8.7 | ПКМ по точке: Condition `item.Price > 500` в цикле `Total()` | Останавливается только на `cup` | да (робот, `item.Name == "cup"`) |
| 8.8 | ПКМ по точке → More: Hit count `2`; Log message `total = {total}` | Остановка на втором проходе; сообщение `> [Go 1]: total = 1600` в консоли без остановки | да (робот) — сам диалог свойств не проверен |
| 8.9 | Добавить `panic("boom")` в `main`, Debug | Остановка в `runtime.fatalpanic`, в стеке `main.main main.go:21`; в консоли `panic: "boom"` и стек | остановка — да (робот); текст в консоли — **нет** |
| 8.10 | Debug теста (▶ у `TestTotal` → Debug), точка в `Total()` | Остановка внутри теста | да |
| 8.11 | Сломать компиляцию, Debug | В консоли сессии строки `> dlv dap …`, `> go build -gcflags=…`, вывод компилятора; в окне Build задача «Debug: имя» с кликабельными ошибками; балун в одну строку с кнопкой Show Build Window | робот |
| 8.11б | Temp без права запуска (робот подменяет `java.io.tmpdir` на каталог с `icacls /deny …:(X)`), Debug | Уведомление «Debug binaries are built in the package directory now» с Keep Temp Directory / Configure, настройка на Package directory, сессия перезапущена и программа отработала; в Temp бинаря не осталось | робот |
| 8.12 | Stop во время остановки | Сессия закрывается, в диспетчере задач нет висящих `dlv.exe` и `__debug_bin*.exe` | да (робот) |
| 8.14 | Run → Attach to Process… | Группа «Go» со списком процессов; выбрать программу, собранную `go build` и запущенную вручную; точка срабатывает; Stop отсоединяет, программа живёт | **нет** |
| 8.15 | В Evaluate набрать `ord` → Ctrl+Space; `order.` → Ctrl+Space | Подсказки: локальные переменные; поля `Currency`, `items` | **нет** |
| 8.13 | Go → Debugger → Show Debugger Logs | Открывается папка с `dlv-*.log` | нет |

## 9. Настройки

| # | Что сделать | Что должно быть | Робот |
|---|---|---|---|
| 9.1 | Settings → Tools → Go | Группы Toolchain, Language Server, Debugger, Code Quality, Tools; версия Go и пути найденных инструментов; **нет выключенных опций-заглушек** | да (до последних опций) |
| 9.1а | Settings → Tools → Go → **gopls** | Строка «gopls v0.23.0, 43 settings», форма по группам (Build, Formatting, General, Completion, Diagnostics, Documentation, Inlay Hints, Navigation); поле поиска остаётся наверху при прокрутке и фильтрует по имени, тексту и ключам; «Experimental and advanced» свёрнуты; у Codelenses отмечены линзы плагина, `test` снят | да (робот: вид страницы) |
| 9.1б | Hover kind → `SingleLine`, Apply; навести на функцию | gopls перезапустился, подсказка в одну строку; после повторного открытия страницы значение сохранено | **нет** |
| 9.1в | Build flags: ввести `-tags=x -mod=mod`; Env: ввести `GOOS` (без `=`), Apply | Флаги принимаются словами, без JSON; для Env — ошибка «is not a value of Env (map[string]string)» | **нет** |
| 9.1г | Analyses → Configure...: найти `QF1001`, включить, OK, Apply; затем Reset all to defaults, Apply | Рядом с кнопкой «… enabled, 1 changed here»; после Reset переопределений нет | частично (робот: диалог открывается, список с галочками) |
| 9.2 | Кнопка Update у любого инструмента | Строка «Running: go install…», затем путь | нет |
| 9.3 | Settings → Editor → Code Style → Go | Только вкладка отступов, Use tab character включён | нет |
| 9.4 | Settings → Editor → Inlay Hints → Code vision → Usages | Выключение убирает «N usages» у Go | нет |

## 10. Подсказки: каталог, типы, раскладка

Всё набирается в `cmd/check/main.go`: у каждой проверки там своя функция с номером в комментарии. После проверки вернуть файл назад (Ctrl+Z), чтобы
следующая начиналась с чистого места. Пакеты для примера — `report` (обычный) и `internal/money` (внутренний).

**Перед началом.** Три условия, без которых пункты ниже ничего не покажут:

1. IDE перезапущена после установки плагина (подмена раскладки подключается только при старте).
2. gopls запущен: в статус-баре справа иконка gopls с цифрами вида `312 MB · 2%`.
3. Каталог прочитан: в `idea.log` есть строка `Go catalogue: … symbols of … packages (3 of the project) …`. Три пакета проекта — `store`, `report` и
   `internal/money`; программы (`cmd/shop`, `cmd/check`) в каталог не входят. Пока строки нет, пункты 10.1–10.6 пусты.

Список открывается сам при наборе; если закрылся — Ctrl+Space. «Tab» в таблице — выбор пункта клавишей Tab.

| # | Что сделать | Что должно быть | Робот |
|---|---|---|---|
| 10.1 | В `byName` набрать `Printl` | В списке `fmt.Println` и `log.Println`, справа путь пакета. Tab на первом: `fmt.Println()`, каретка в скобках, сверху параметры, в файле появился `import "fmt"` | нет |
| 10.2 | В `byName` набрать `Summar` | `report.Summary` **выше** пунктов стандартной библиотеки. Tab: `report.Summary()` и `import "example.com/playground/report"` отдельной группой под стандартными | нет |
| 10.3 | В `byName` набрать `Forma` | Есть `money.Format` (внутренний пакет своего модуля виден). Есть и `fmt.…`/`time.…` с тем же началом, ниже | нет |
| 10.4 | В `byName` набрать `htt` | Пункт `http` с путём `net/http` и пометкой `import`. Tab: `http` и `import "net/http"`; после точки — список членов пакета | нет |
| 10.5 | В `literal` набрать `c := client`, выбрать `http.Client`, Tab | `c := http.Client{}`, каретка между скобками, импорт `net/http`. Имя найдено при наборе строчными | нет |
| 10.6 | Не уходя из скобок: Alt+Enter → Fill all fields | Поля `Transport`, `CheckRedirect`, `Jar`, `Timeout` с нулевыми значениями. То же на `http.Request{}`: добавились импорты `net/url`, `crypto/tls`, `mime/multipart`, `io` | нет |
| 10.6а | То же с пробелом: `http.Client {}` | Пункт Fill all fields есть | нет |
| 10.7 | В `fits` стереть `title` в `describe(title, lines)`, Ctrl+Space; затем Ctrl+Shift+Space | В обычном списке `title` жирным и выше остальных; `lines`, `width`, `verbose` — обычным. В умном списке остались только значения типа `string` | нет |
| 10.7а | Там же набрать `, ` после первого аргумента | Список открылся сам, жирным `lines` и `width` (тип `int`) | нет |
| 10.8 | В `load` стереть `nil, err` после `return`, набрать `ni` | Первый пункт — `nil, err` жирным; `nil` выше `net.…`, если такие есть | нет |
| 10.9 | В `sorted` стереть функцию в `sort.Slice(names, …)`, Ctrl+Space, выбрать `func(...) {}` | `func(i, j int) bool {}` — без `\` и без переноса строки перед `}` | нет |
| 10.10 | Русская раскладка. В `layout` на пустой строке быстро набрать `аьеюЗкштедт` | В коде `fmt.Println`, ни один символ не пропал, кириллица не мелькает; после точки открылся список | нет |
| 10.11 | Русская раскладка. Набрать слово в конце комментария над `layout` и между кавычками в `return ""` | Русский текст остался русским в обоих местах | нет |
| 10.12 | В `byName` набрать `NR` заглавными, затем отдельно `newreq` строчными | В обоих случаях есть `http.NewRequest` | нет |

Проверки вне файла:

| # | Что сделать | Что должно быть | Робот |
|---|---|---|---|
| 10.13 | В `report/report.go` добавить `func Footer() string { return "" }`, сохранить; в `cmd/check` набрать `Foot` | `report.Footer` в списке. Может появиться не с первого открытия списка, а со второго: каталог пересобирается в фоне | нет |
| 10.14 | Клик по иконке gopls в статус-баре | Окно: `gopls v…` словами (не JSON), Built with, CPU, Memory, Process; ссылки Restart, Log, Statistics, Settings работают | нет |
| 10.15 | Go → gopls → Restart | Иконка исчезла и вернулась, цифры снова обновляются | нет |
| 10.16 | Settings → Tools → Go, группа Completion: снять «Offer functions and types of packages by their names», повторить 10.1 | `fmt.Println` в списке нет; вернуть галочку — снова есть | нет |
| 10.17 | File → New → Project | Пункт «Go»; подсказки под полями помещаются в окно, горизонтальной прокрутки нет | пункт — пользователь, остальное нет |
| 10.18 | В любом `.go` Alt+Enter → Refactorings and actions of gopls… → выбрать действие (например, на выделенном выражении Extract variable) | Правка применилась; раньше не применялось ничего | нет |
| 10.19 | В `cmd/check/main.go` добавить в импорты `"strings"`, не используя; курсор на этой строке, Alt+Enter | Пункт **Optimize imports** вверху списка; после выбора строка `"strings"` удалена, остальные импорты на месте. Курсор в теле функции — пункта нет | нет |
| 10.20 | В `store/order.go` выделить выражение (например, `item.Price * item.Quantity`), Alt+Enter; затем курсор внутри любой функции без выделения, Alt+Enter | С выделением: пункты **Extract variable**, **Extract function** прямо в списке. Без выделения: **Add test for …** и другие действия gopls; внизу остался «Refactorings and actions of gopls...». Выбранный пункт применяется | нет |

Если пункт не сработал, полезно знать, какая часть молчит:

- нет пунктов с путём пакета справа (10.1–10.3) — каталог: смотреть строку `Go catalogue` в логе, Go → Read Packages Again;
- нет жирных пунктов и умного списка (10.7) — ожидаемый тип: нужен работающий gopls;
- 10.4 пусто — список пакетов gopls (`gopls.list_known_packages`), смотреть окно лога gopls;
- 10.10 теряет символы — в логе искать `IllegalStateException` за момент набора.

## 11. Серый текст

Подсказка следующей строки серым цветом: **Tab** принимает, любой другой символ убирает. Правила, а не модель: появляется мгновенно и только там,
где продолжение одно. Настройка — Settings → Tools → Go, группа Editor, «Suggest the idiomatic next line as grey text».

Примеры — файлы `idioms.go`, `wrapped.go`, `handler.go`, `service.go` и `fields.go` в `cmd/check`. Пакеты `internal/status` и `internal/codes` — заглушки вместо gRPC, которого в зависимостях playground нет. В каждой функции строка помечена номером проверки: курсор в конец этой строки
(после комментария), **Enter**. После проверки вернуть файл (Ctrl+Z).

| # | Где | Что должно появиться серым | Робот |
|---|---|---|---|
| 11.1 | `idiomOpen`, строка `// 11.1` | `if err != nil {` / `return nil, err` / `}` | да (раньше) |
| 11.2 | `idiomByHand`: на пустой строке набрать `if err != nil {`, Enter | Внутри блока: `return nil, err`. Если в блоке уже есть строки — ничего | нет |
| 11.3 | `idiomScanner`, строка `// 11.3` | `for scanner.Scan() {` / пустая строка / `}`. Принять (Tab), курсор после `}` цикла, Enter: `if err := scanner.Err(); err != nil {` / `return 0, err` / `}` | нет |
| 11.4 | `idiomChannel`, строка `// 11.4` | `defer close(results)` | нет |
| 11.5 | `idiomSignals`, строка `// 11.5` | `defer stop()` | нет |
| 11.6 | `idiomSpan`, строка `// 11.6` | `defer loadSpan.End()` | нет |
| 11.7 | `idiomDirectory`, строка `// 11.7` (после `}` проверки ошибки) | `defer os.RemoveAll(dir)` | нет |
| 11.8 | `wrapped.go`, `loadSettings`, строка `// 11.8` | `if err != nil {` / `return settings{}, fmt.Errorf("read file: %w", err)` / `}` — с обёрткой, потому что файл оборачивает ошибки | нет |
| 11.9 | В `wrapped.go` заменить оба `fmt.Errorf(...)` в `saveSettings` на `err`, повторить 11.8 | `return settings{}, err` — без обёртки: привычки оборачивать в файле больше нет | нет |
| 11.10 | В любой проверке начать набирать начало подсказки (`de` для `defer …`, `if` для `if err …`) | Подсказка остаётся и сокращается на набранное; другой символ её убирает | нет |
| 11.11 | При открытом списке completion нажать Tab | Выбирается пункт списка, а не серый текст | нет |
| 11.12 | `handler.go`, `handleUpload`, строка `// 11.12` | `if err != nil {` / `http.Error(w, err.Error(), http.StatusInternalServerError)` / `return` / `}` — обработчик отвечает, а не возвращает | нет |
| 11.13 | `handler.go`, `handleCreate`, строка `// 11.13` | То же с `http.StatusBadRequest`: не разобрано то, что прислал клиент | нет |
| 11.14 | `service.go`, `list`, строка `// 11.14` | `if err != nil {` / `return nil, status.Errorf(codes.Internal, "read dir: %v", err)` / `}` — файл отвечает статусами gRPC | нет |
| 11.15 | `service.go`, последняя строка `// 11.15`: Enter дважды, набрать `func (` | `s *service) ` — получатель как у методов выше. Если редактор сам закрыл скобку, серым только `s *service` | нет |
| 11.16 | `fields.go`, строка `// 11.16`: Enter, набрать `LastName string` и пробел | `` `json:"last_name,omitempty" db:"last_name"` `` — ключи и стиль имён как у полей выше; `validate` не повторяется | нет |
| 11.17 | `fields.go`, строка `// 11.17`: Enter, набрать `OrderID int64` и пробел | `` `json:"orderID"` `` — в этой структуре имена в camelCase | нет |

Ожидаемый текст пунктов 11.1–11.8 и 11.12–11.17 получен прогоном правил по этим же файлам, а не выписан по памяти; сам показ серым в редакторе — нет.

## Что записывать

Для каждого несработавшего пункта: номер, что произошло вместо ожидаемого, и — если есть — ошибка из Help → Show Log (`idea.log`, искать `golangsupport`
и `Plugin to blame`). Лог песочницы: `.intellijPlatform/sandbox/idea-golang-support/IU-*/log_runIde/idea.log`.
