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
| 3.16 | Меню Go → Restart Language Server | Перезапуск без ошибок | нет |

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
| 7.11 | Edit Configurations → Go | Поля: Command, Package, Go tool arguments, Program arguments, Working directory, Environment, Test pattern, две галочки | нет |

## 8. Отладчик

| # | Что сделать | Что должно быть | Робот |
|---|---|---|---|
| 8.1 | Точка в `main.go` на `if len(os.Args)`, Debug `go run shop` | Остановка, `[Go 1] main.main`, стек, Locals с `order` | да |
| 8.2 | F8, F7 в `order.Total()`, Shift+F8, F9 | Шаги работают; «F8 ничего не делает» быть не должно | F8 — да |
| 8.3 | Evaluate: `order.Currency`, `len(order.items)`, `order.Total()` | Значения; вызов функции работает без `call` | да |
| 8.4 | F2 на `total` внутри `Total()` → `777` | Значение изменилось | да |
| 8.5 | F2 на строковой переменной (`o.Currency` → `"USD"`) | Значение изменилось | **нет** |
| 8.6 | Навести мышь на переменную во время остановки | Всплывает значение | **нет** |
| 8.7 | ПКМ по точке: Condition `item.Price > 500` в цикле `Total()` | Останавливается только на `cup` | **нет** |
| 8.8 | ПКМ по точке → More: Hit count `2`; Log message `total = {total}` | Остановка на втором проходе; сообщение в консоли без остановки | **нет** |
| 8.9 | Добавить `panic("boom")` в `main`, Debug | Остановка на панике (View Breakpoints → Go Panic Breakpoints включён) | **нет** |
| 8.10 | Debug теста (▶ у `TestTotal` → Debug), точка в `Total()` | Остановка внутри теста | да |
| 8.11 | Сломать компиляцию, Debug | Уведомление «Debug has not started» **с текстом ошибки компилятора**, сессия закрылась | без текста — да |
| 8.12 | Stop во время остановки | Сессия закрывается, в диспетчере задач нет висящих `dlv.exe` и `__debug_bin*.exe` | нет |
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
