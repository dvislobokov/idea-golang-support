# Playground: ручная проверка плагина

К сборке плагина не относится. Запуск IDE с этим проектом (из корня репозитория, Git Bash):

```sh
export JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\jbr"
./gradlew.bat runIde --args="C:/Users/dvislobokov/idea-golang-support/playground"
```

Отметки в колонке «Робот»: **да** — уже проверено UI-роботом, **API** — проверено вызовом API IDE без кликов, **нет** — не проверялось вообще, смотреть
в первую очередь. `TestFailing` в `store/order_test.go` падает нарочно, `TestSkipped` пропускается нарочно.

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
| 2а.6 | Alt+Insert → Implement Interface… → `Priced` на новом типе | Методы интерфейса с `panic("not implemented")`, только недостающие | **нет** |
| 2а.7 | В функции `Total` → Alt+Enter → Generate test | `order_test.go` открылся, в конце `TestOrder_Total` с таблицей | **нет** |
| 2а.8 | `os.Remove(p)` отдельной строкой → Alt+Enter → Handle error | `if err := os.Remove(p); err != nil { return err }` | да |
| 2а.9 | `data, err := os.ReadFile(n)` → Alt+Enter → Add if err != nil check | Проверка с `return nil, err` | да |
| 2а.10 | Функция с результатом без `return` → Alt+Enter → Add missing return | `return 0, nil` перед `}` | да |
| 2а.11 | `sum := add(a, 2)` без `add` → Alt+Enter → Create function 'add' | В конце файла `func add(a any, arg2 int) any { panic(...) }` | да (без результата — до правки) |
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
| 7.10д | `go test store` → Profile: **CPU profile**, Run | После прогона уведомление «CPU profile of the tests is ready»; «Open» открывает вкладку профиля в Go Monitor; «Execution trace» → `go tool trace` | уведомление — да; браузер — **нет** |
| 7.10е | ▶ у `func TestTotal` → Run with Coverage (или ПКМ по `store` → Go → Run Tests with Coverage) | Окно Cover с деревом тестов; в `order.go` зелёные полосы у выполненных строк и красная у `return nil` в `Validate`; в дереве проекта `store 80% statements`, `lint.go 0%`; уведомление «Coverage: 80%…» с Hide coverage | да |
| 7.10ж | Go → Hide Coverage | Полосы и проценты исчезают | **нет** |
| 7.10з | Structure (Alt+7) на `order.go` | Под `Order`: поля, затем `Add(item Item)`, `Total() int`, `Validate() error` | да |
| 7.10и | Run `cmd/pprofdemo` (▶ у `func main`), Go Monitor → выбрать его | Через ≤5 с строка «pprof: http://127.0.0.1:6060/debug/pprof» и кнопки CPU 30s, Heap, Allocs, Goroutines, Mutex, Block, Trace 5s | **нет** |
| 7.10к | Goroutines (строка pprof) | Вкладка «Goroutines …» в Go Monitor: `8 × main.main.func1 — in time.Sleep` и др., стек снизу (двойной клик — к исходнику); программа не останавливается | **нет** |
| 7.10л | Heap; CPU 30s (идёт прогресс, можно отменить); Trace 5s | Вкладка профиля: Flame Graph (клик — зум, двойной клик — исходник) и Top; у Heap выбор inuse/alloc; «Open in Browser» — flame graph pprof без Graphviz; для trace — `go tool trace` | **нет** |
| 7.10м | Go Monitor → `go run shop` (без pprof) | Через ~10 с подсказка «pprof is not served: import _ "net/http/pprof"…» | **нет** |
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
| 8.11 | Сломать компиляцию, Debug | Уведомление «Debug has not started» **с текстом ошибки компилятора**, сессия закрылась | без текста — да |
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

## Что записывать

Для каждого несработавшего пункта: номер, что произошло вместо ожидаемого, и — если есть — ошибка из Help → Show Log (`idea.log`, искать `golangsupport`
и `Plugin to blame`). Лог песочницы: `.intellijPlatform/sandbox/idea-golang-support/IU-*/log_runIde/idea.log`.
