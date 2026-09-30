---
marp: true
title: Go Project Support
description: Go в IntelliJ IDEA, PyCharm, WebStorm и Rider. Редактор на gopls, отладчик на delve, тесты, модули, Go Monitor.
lang: ru
size: 16:9
paginate: true
header: Go Project Support
style: |
  :root {
    --bg: #19191c; --panel: #1e1f22; --line: #3a3c41;
    --text: #ffffff; --text-2: #c3c5cc; --text-3: #8b8e97;
    --cyan: #00add8; --violet: #6b7cff; --magenta: #ce3262;
    --grad: linear-gradient(100deg, var(--cyan), var(--violet) 52%, var(--magenta));
  }
  section {
    background: var(--bg); color: var(--text-2);
    font-family: "Inter", "Segoe UI Variable Display", "Segoe UI", system-ui, sans-serif;
    font-size: 25px; line-height: 1.45; padding: 96px 72px 56px; place-content: start stretch;
  }
  section::before { content: ""; position: absolute; left: 72px; top: 60px; width: 56px; height: 4px; border-radius: 2px; background: var(--grad); }
  section::after { color: var(--text-3); font-size: 16px; }
  header { color: var(--text-3); font-size: 16px; left: 144px; top: 51px; }
  h1 { color: var(--text); font-size: 1.75em; line-height: 1.1; letter-spacing: -.03em; margin: 0 0 .55em; }
  h2 { color: var(--text); font-size: 1.05em; margin: .7em 0 .25em; }
  p { margin: .35em 0; }
  strong { color: var(--text); font-weight: 600; }
  a { color: var(--cyan); }
  ul, ol { margin: .2em 0; padding-left: 1.1em; }
  li { margin: .28em 0; }
  li::marker { color: var(--violet); }
  code { font-family: "JetBrains Mono", "Cascadia Code", Consolas, monospace; font-size: .86em; color: var(--text); background: rgba(255, 255, 255, .09); border-radius: 5px; padding: .08em .36em; }
  pre { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 18px 22px; font-size: .74em; line-height: 1.6; margin: .4em 0; }
  pre code { background: none; padding: 0; color: var(--text-2); font-size: 1em; }
  .hljs-keyword, .hljs-built_in, .hljs-literal, .hljs-type { color: #cf8e6d; }
  .hljs-string { color: #6aab73; }
  .hljs-number { color: #2aacb8; }
  .hljs-comment { color: #7a7e85; font-style: italic; }
  .hljs-title, .hljs-title.function_ { color: #56a8f5; }
  .hljs-params, .hljs-variable, .hljs-attr { color: var(--text-2); }
  section table { display: table; font-size: .76em; border-collapse: collapse; width: 100%; margin: .3em 0 .5em; }
  table, thead, tbody, tr, th, td { background: transparent !important; }
  section table th { color: var(--text-3); font-weight: 500; text-align: left; border: 0; border-bottom: 1px solid var(--line); padding: 8px 14px 8px 0; }
  section table td { color: var(--text-2); border: 0; border-bottom: 1px solid var(--line); padding: 8px 14px 8px 0; vertical-align: top; }
  section table td:first-child { color: var(--text); font-weight: 600; white-space: nowrap; }
  blockquote { border-left: 4px solid var(--violet); margin: .6em 0 0; padding: .1em 0 .1em 18px; color: var(--text-3); font-size: .88em; }
  section.cols { display: grid; grid-template-columns: 1fr 1fr; grid-auto-rows: min-content; column-gap: 44px; align-content: start; }
  section.cols > h1, section.cols > blockquote { grid-column: 1 / -1; }
  section.cols > pre, section.cols > table { align-self: start; }
  section.lead {
    place-content: center stretch; padding: 72px 96px;
    background-color: var(--bg);
    background-image: radial-gradient(circle at 92% 8%, rgba(206, 50, 98, .38), transparent 42%), radial-gradient(circle at 70% 0%, rgba(107, 124, 255, .42), transparent 46%), radial-gradient(circle at 100% 45%, rgba(0, 173, 216, .30), transparent 40%);
  }
  section.lead::before { position: static; margin-bottom: 28px; width: 72px; height: 5px; }
  section.lead h1 { font-size: 3.3em; line-height: 1.02; letter-spacing: -.035em; margin-bottom: .3em; }
  section.lead p { font-size: 1.15em; max-width: 30em; }
  section.lead header { display: none; }
  section.small { font-size: 22px; }
---

<!-- _class: lead -->
<!-- _paginate: false -->

# Go не только в GoLand

Редактор на gopls, отладчик на delve, тесты, модули и мониторинг в той IDE, которая у вас уже открыта.

**Go Project Support**, версия 0.1.0, платформа IntelliJ 2026.1 и новее

---

# Зачем это нужно

- **Одна среда на все языки команды.** Сервис на Go лежит рядом с фронтендом, Python и C#. Переключаться между IDE ради одного каталога неудобно.
- **Go вне GoLand.** Поддержка Go от JetBrains есть в GoLand и в IDEA Ultimate. В PyCharm, WebStorm, Rider и форках платформы её нет.
- **Инструменты сообщества вместо своего анализатора.** gopls, delve, golangci-lint и команда `go` уже знают о языке всё. Плагин даёт им интерфейс IDE.
- **Привычные окна.** Debug, Run, дерево тестов, Structure и Alt+Enter ведут себя так же, как для других языков.

> Мы даём людям возможность писать на Go, не привязываясь к одному GoLand.

---

# Где работает

| IDE | Что получаете |
|---|---|
| IntelliJ IDEA | Go рядом с Java и Kotlin в одном проекте |
| PyCharm | Сервис на Go рядом с кодом на Python |
| WebStorm | Бэкенд на Go рядом с фронтендом |
| Rider | Go рядом с C# и .NET |
| Форки платформы | Отладчик и всё остальное, даже если в сборке нет модулей LSP и DAP |

- Если в IDE нет клиента LSP, отключается только часть с gopls. Остальной плагин загружается.
- В GoLand и рядом с плагином Go от JetBrains плагин не ставится: там поддержка уже есть.

---

# Как устроено

| Слой | Чем сделан | Что даёт |
|---|---|---|
| Язык | свой лексер и сканер объявлений | подсветка, Structure, folding, Go to Symbol, генераторы, идиомы |
| gopls | клиент LSP платформы и свои запросы | ошибки, completion, rename, навигация, действия Alt+Enter |
| delve | свой клиент протокола отладки | точки останова, шаги, стек, переменные, Evaluate, attach |
| Команда `go` | процессы `go build`, `test`, `mod`, `run` | сборка, запуск, дерево тестов, зависимости |
| Инструменты | gofmt, goimports, golangci-lint, pprof | форматирование, предупреждения, профили |

> Своего парсера Go нет. Это решение, а не недоделка: смысл кода отдаёт тот же сервер, что работает в VS Code.

---

# Работает без единого инструмента

Пока gopls не установлен или ещё грузит проект, редактор уже полезен.

- **Подсветка** синтаксиса, директив `//go:`, тегов структур, встроенных типов и функций.
- **Structure и breadcrumbs.** Типы, поля, функции, методы под своим типом.
- **Folding** тел функций и блоков внутри них, групп `import`, `const`, `var`, комментариев.
- **Go to Class и Go to Symbol** по индексу объявлений.
- **Отступы как у gofmt** при наборе, Enter между скобками, Complete Statement.
- **Шаблоны и генераторы.** Live templates, postfix, Generate, серый текст.
- **Запуск.** Значки у `func main` и тестов, конфигурации, дерево тестов.

---

<!-- _class: cols -->

# Серый текст без модели

```go
func Load(name string) (*Order, error) {
    f, err := os.Open(name)
    // серым, Tab принимает:
    if err != nil {
        return nil, err
    }
    // следующая подсказка:
    defer f.Close()
}
```

- **`if err != nil`** с `return` под сигнатуру функции. В `main` будет `log.Fatal`, в тесте `t.Fatal`.
- **`defer`** для файла, тела ответа HTTP, строк запроса, мьютекса, отмены контекста, таймера.
- **`if !ok`** после обращения к map, приведения типа и чтения из канала.
- **`defer wg.Done()`** первой строкой `go func()`.
- Это правила, а не ИИ. Подсказка одинакова у всех и не уходит в сеть.

---

# Completion, Alt+Enter, Generate

| Клавиши | Что предложит |
|---|---|
| Completion | символы gopls с автоимпортом, ключи и опции в тегах структур |
| Alt+Enter на коде | Handle error, Add if err != nil check, Add missing return, Create function с типами от gopls |
| Alt+Enter с gopls | Extract variable, function и method, Inline call, Fill struct, Invert if |
| Alt+Enter на предупреждении | исправления gopls и staticcheck, `//nolint` для линтера |
| Alt+Insert, Ctrl+I | Constructor, Getters, Setters, String(), Struct Tags, Implement Interface, Test |
| Ctrl+Shift+Enter | закрывает скобки и ставит `{}` после `if`, `for`, `func` |

- **Implement Interface по Ctrl+I**, как в GoLand: поиск по интерфейсам проекта, stdlib и зависимостей, типы чужого пакета квалифицируются, импорт добавляется.
- Теги структур: `json`, `yaml`, `xml`, `db`, `validate`, `gorm`, `env` и ещё два десятка ключей.
- `//` над объявлением превращается в `// Name` для док-комментария.

---

<!-- _class: small -->

# Шаблоны: 41 live template и 25 postfix

| Группа | Аббревиатуры |
|---|---|
| Ошибки | `err` `errw` `errn` `ife` `errt` `erras` `sentinel` |
| Объявления | `fn` `meth` `str` `inter` `enum` `opt` `main` `init` |
| Конкурентность | `ctx` `tctx` `gof` `deff` `sel` `mu` `wg` `ch` `mk` `ticker` |
| Управление | `forr` `fori` `sw` `tsw` |
| Тесты | `test` `ttest` `tr` `helper` `bench` `fuzz` `example` `tmain` |
| Вывод и прочее | `pf` `lf` `json` `hf` |

**Postfix после выражения:** `.if` `.else` `.nil` `.notnil` `.err` `.errv` `.errn` `.return` `.rr` `.var` `.for` `.fori` `.forr` `.range` `.len` `.print` `.printf` `.panic` `.go` `.defer` `.append` `.not` `.switch` `.wrap` `.sort`

```go
os.Remove(path).err   // if err := os.Remove(path); err != nil { return err }
items.for             // for _, v := range items { }
```

---

<!-- _class: small -->

# Навигация по коду

| Действие | Клавиши |
|---|---|
| Go to Declaration | Ctrl+B, Ctrl+клик |
| Go to Implementation, Super Method | Ctrl+Alt+B, Ctrl+U, значки I↓ и I↑ в gutter |
| Find Usages и Show Usages | Alt+F7, Ctrl+Alt+F7, счётчик над объявлением |
| Go to Type Declaration | Ctrl+Shift+B |
| Type Info | Ctrl+Shift+P |
| Rename | Shift+F6 |
| Quick Documentation, Parameter Info | Ctrl+Q, Ctrl+P |
| Go to Class, Go to Symbol | Ctrl+N, Ctrl+Alt+Shift+N |
| Тест и обратно | Ctrl+Shift+T: `x.go` ↔ `x_test.go`, `Total` ↔ `TestTotal` |
| Call Hierarchy, Type Hierarchy | Ctrl+Alt+H, Ctrl+H |

- Реализаций, usages с объявления и ссылки под Ctrl+мышь в клиенте LSP платформы нет: плагин спрашивает gopls сам.

---

# gopls под контролем

- **Страница настроек строится по установленной версии.** Список опций берётся из `gopls api-json`, поэтому новые настройки сервера появляются без обновления плагина.
- **Окно gopls.** Лог сервера, его сообщения, отправленные команды и ошибки. По настройке пишется весь протокол.
- **Виджет в статус-баре.** Состояние сервера, перезапуск и переход к настройкам.
- **Линзы.** В `go.mod` это tidy, vendor, проверка обновлений и govulncheck. В коде это `//go:generate`.
- **Меню Go, gopls.** Add Import, документация и ассемблер функции в браузере, решения компилятора об inlining и escape.
- **Go, Reanalyze Project.** Индексы Go-файлов, каталог пакетов, gopls и подсветка заново, без перезапуска IDE.
- **Цвета как в GoLand.** Пакеты, типы, поля, параметры и константы красятся по semantic tokens.

> По умолчанию включены staticcheck, inlay hints и semantic tokens.

---

<!-- _class: cols small -->

# Отладчик на delve

```go
func (o *Order) Total() int {
    total := 0
●   for _, item := range o.items {
        total += item.Price * item.Quantity
    }
    return total
}

// Evaluate: order.Total()  →  1600
// o.Currency = "EUR"
// o.items    len: 2, cap: 2
```

- **Точки останова.** Условие, счётчик попаданий, сообщение в лог без остановки, остановка на панике.
- **Выполнение.** Step Over, Into, Out, Run to Cursor, Pause.
- **Данные.** Переменные, Watches, Evaluate с вызовом функций, Set Value.
- **Читаемые значения.** `[]byte` текстом, ошибка своим сообщением.
- **Консоль как в GoLand.** Команда delve, `go build`, что запускается.
- **Честный отказ.** Ошибки сборки в консоли и в окне Build ссылками; бинарь не запустился из Temp — сборка в каталог пакета и перезапуск.

---

# Отладчик: куда подключиться

| Режим | Что происходит |
|---|---|
| Debug конфигурации | delve сам собирает программу или тесты и запускает их |
| Attach to Process | подключение к работающему процессу на этой машине |
| Binary | отладка готового бинарника без сборки |
| Core dump | разбор дампа вместе с бинарником, на Windows это minidump |
| Remote dlv dap | сокет к `dlv dap --listen` на другой машине, подстановка путей исходников |

- **Вкладка Goroutines.** Группы по функции верхнего фрейма, поиск, фильтр системных горутин.
- **Свой клиент протокола.** Он построен на стандартном окне Debug и не зависит от модуля DAP платформы.
- **Диагностика.** Лог delve на каждую сессию и трасса протокола по запросу.

---

<!-- _class: cols -->

# Тесты: от пакета до одной строки таблицы

```go
func TestTotal(t *testing.T) {
    tests := []struct {
        name string
        want int
    }{
▶       {name: "empty", want: 0},
▶       {name: "two items", want: 250},
    }
    for _, tt := range tests {
▶       t.Run(tt.name, func(t *testing.T) {
            // ...
        })
    }
}
```

- **Значок запуска** у `Test`, `Benchmark`, `Fuzz`, `Example`, у `t.Run` и у каждой строки таблицы.
- **Дерево растёт во время прогона.** Подтесты стоят под своим тестом, пропущенные не считаются ошибкой.
- **Окно Go Tests.** Все тесты проекта со статусом последнего прогона.
- **Цикл.** Rerun Failed и перезапуск тестов пакета при сохранении.
- **Ссылки.** `order_test.go:40` в консоли ведёт в код.

---

# Покрытие, бенчмарки, профили

| Что | Как выглядит |
|---|---|
| Покрытие | полосы в редакторе и процент у пакета после Run with Coverage |
| Бенчмарки | таблица ns/op, B/op, allocs/op и разница в процентах против прошлого прогона |
| Профили тестов | CPU, память, блокировки, мьютексы, трасса. Открываются в pprof |
| Fuzz | галочка в конфигурации и пункт Run Fuzzing у `FuzzXxx` |
| Флаги | галочки Race, `-count=1`, `-short`, `-failfast`, поле `-timeout` |

- Статус последнего прогона виден прямо в gutter: зелёный, красный или жёлтый для пропущенного теста.
- Отладка теста запускается той же кнопкой, что и отладка программы.

---

# Модули и зависимости

- **go.mod и go.work.** Подсветка, сворачивание блоков, completion директив, путей модулей и версий.
- **Узел Dependencies в дереве проекта.** Прямые и косвенные зависимости, замены, исходники из module cache.
- **Окно Go Dependencies.** Текущая и новая версия каждого модуля, Upgrade Selected, Upgrade All, Tidy.
- **Уязвимости.** Проверка govulncheck кнопкой, затронутые модули помечаются в таблице.
- **Баннер после правки.** Изменили `require` и сохранили файл, сверху появляется Run go mod tidy.
- **Меню Go, Modules.** Tidy, Download, Vendor.

> Версии для completion берутся из локального кэша и с GOPROXY.

---

# Сборка и запуск

- **Build, Vet, Generate.** Результат приходит в окно Build, по ошибке можно перейти в код.
- **Одна конфигурация Go.** Виды `go run`, `go test`, готовый бинарник, core dump и удалённый delve.
- **Конфигурации появляются сами** для каталогов с `func main`.
- **Поля на каждый день.** Аргументы программы, переменные окружения, теги сборки, шаблон `-run`.
- **Ссылки в любой консоли.** `file.go:12` компилятора, тестов и стека паники.
- **Analyze Stack Trace.** Вставили панику из лога, фреймы стали ссылками.
- **Ошибки сборки в редакторе.** Там, где нет gopls, подчёркиваются ошибки последнего Build.

---

# Качество кода

| Инструмент | Что делает |
|---|---|
| gofmt, goimports | Reformat Code и форматирование при сохранении |
| `golangci-lint fmt` | третий вариант форматтера, по правилам `.golangci.yml` |
| golangci-lint | предупреждения в редакторе, версии 1 и 2, конфигурация репозитория |
| staticcheck | анализаторы внутри gopls, включены по умолчанию |
| govulncheck | известные уязвимости зависимостей |

- **Исправления линтера.** Handle error и Ignore error explicitly для errcheck, `//nolint` для любого правила.
- **Предупреждения не пропадают при наборе.** Они держатся на своих местах до следующего сохранения.
- **Установка инструментов.** Плагин сам предлагает поставить недостающее через `go install`.

---

# Go Monitor

- **Графики.** CPU, память, куча до сборки и её цель, паузы GC, число сборок, потоки, очередь планировщика.
- **Любая Go-программа машины.** В списке есть и запущенные из IDE, и все остальные, с версией Go и модулем.
- **Телеметрия рантайма.** Галочка в конфигурации включает `gctrace` и `schedtrace`. Их строки идут в графики, а не в консоль.
- **Снимок горутин.** Сводка вида «N × функция» с поиском, программа продолжает работать.
- **Сессия отладчика** тоже видна в мониторе.
- **Attach из монитора.** Выбрали процесс, нажали Debug.

> В GoLand есть графики CPU и памяти. Графиков GC и планировщика и списка всех Go-процессов там нет.

---

<!-- _class: small -->

# Сравнение с GoLand

| Область | GoLand | Go Project Support | Итог |
|---|---|---|---|
| Модель кода | свой парсер и проверка типов | gopls и сканер по токенам | частично |
| Ошибки | сотни инспекций | gopls, staticcheck, golangci-lint | частично |
| Рефакторинги | Change Signature, Move, Safe Delete | Rename, Extract, Inline, Fill struct | частично |
| Навигация | полная, с иерархиями | объявления, реализации, usages, иерархии, тест ↔ код | есть |
| Отладчик | delve, remote, обратная отладка | запуск, attach, бинарник, core dump, remote | частично |
| Тесты | дерево, покрытие, бенчмарки | то же, с разницей между прогонами | есть |
| Модули | окно зависимостей, Package Checker | completion, окно зависимостей, govulncheck | есть |
| Мониторинг | CPU и память | ещё GC, планировщик, все процессы | есть |
| Профили | flame graph в IDE | pprof в браузере, только тесты | нет |
| Где работает | GoLand и IDEA Ultimate | IDEA, PyCharm, WebStorm, Rider, форки | есть |

---

<!-- _class: cols -->

# Чего нет и что есть только здесь

- **Нет.** Change Signature, Move, Safe Delete, Extract Interface.
- **Нет.** Flame graph и профилирование приложения, а не тестов.
- **Нет.** Endpoints, Go templates, cgo и ассемблер.
- **Нет.** Watchpoints, Smart Step Into.
- **Нет.** Загрузка Go SDK из IDE, запуск в Docker, WSL и по SSH.

* **Только здесь.** Идиомы серым текстом по правилам, без модели.
* **Только здесь.** Все Go-процессы машины с версией и модулем.
* **Только здесь.** Графики GC и планировщика.
* **Только здесь.** Настройки gopls по каталогу сервера, его лог и статистика.
* **Только здесь.** Страница помощи с клавишами из вашей раскладки.
* **Только здесь.** Optimize IDE for Go: отключение плагинов, не нужных для Go.
* **Только здесь.** Отладка не ломается о запрет запуска из Temp.

---

# С чего начать

1. **Поставьте Go.** Путь к `go` плагин находит сам. Вручную он задаётся в Settings, Tools, Go.
2. **Откройте папку с `go.mod`.** Подойдёт и каталог с `go.work`.
3. **Согласитесь на установку инструментов.** Это gopls, dlv, goimports и golangci-lint. Они ставятся через `go install`.
4. **Дождитесь gopls.** Сервер виден в виджете Language Services. До этого уже работают подсветка, структура и серый текст.
5. **Откройте Go, Help Page.** Там клавиши из вашей раскладки и приёмы на каждый день.

| Если не работает | Куда смотреть |
|---|---|
| Нет completion и ошибок | Go, gopls, Show Log и пункт Restart |
| Отладка не стартует | консоль сессии и окно Build, затем Go, Debugger, Show Debugger Logs |
| Всё устарело после `go get` или смены ветки | Go, Reanalyze Project |
| Нет предупреждений линтера | файл сохранён, golangci-lint установлен |

---

<!-- _class: small -->

# Сценарий демо на 10 минут

1. **Открыть `playground`.** Structure с методами под типом, узел Dependencies, окно Go on This Machine.
2. **Набрать код.** `f, err := os.Open(name)` и Tab, затем `strings.ToUp` с автоимпортом и `items.for`.
3. **Alt+Enter.** Handle error, Create function, Refactorings and actions of gopls, затем Shift+F6.
4. **Навигация.** Ctrl+B на `NewOrder`, значок реализаций у `Priced`, счётчики usages.
5. **Запуск.** `cmd/shop` из gutter, графики в Go Monitor.
6. **Отладка.** Точка в `Total`, Evaluate `order.Total()`, вкладка Goroutines.
7. **Тесты с покрытием.** Go Tests, Run with Coverage, кейс `TestTotal/empty`, Rerun Failed.
8. **Бенчмарк.** `BenchmarkTotal` два раза, разница во вкладке Benchmarks.
9. **Модули.** Completion версии в `go.mod`, баннер tidy, окно Go Dependencies.

> В `playground` тест `TestFailing` падает нарочно: дереву нужен красный узел.

---

# Новое за последние дни

| Что | Как |
|---|---|
| Implement Interface по Ctrl+I | поиск по проекту, stdlib и зависимостям; чужие типы квалифицируются, импорт добавляется |
| Create function | типы параметров и результатов от gopls, свой вариант только без сервера |
| Консоль отладки | команда delve и `go build`, ошибки сборки в окне Build ссылками, балун в одну строку |
| Бинарь отладки | не запустился из Temp — сборка в каталог пакета и перезапуск сессии |
| Go, Reanalyze Project | индексы, каталог пакетов, gopls и подсветка заново без перезапуска |
| Навигация | Ctrl+Shift+T к тесту и обратно, Ctrl+U к методу интерфейса, свёртки блоков внутри функций |
| Тесты | галочки `-short`, `-failfast`, поле `-timeout` |
| Go, Optimize IDE for Go… | отключение плагинов, не нужных для Go; уведомление ждёт конца индексации |

---

# Что дальше

| Срок | Планы |
|---|---|
| Ближайшее | Handle error на любом вызове, недостающий импорт по Alt+Enter, Add test case для табличных тестов |
| Ближайшее | разница got и want в консоли тестов, Open on pkg.go.dev, String() для констант с `iota` |
| Дни | watchpoints, Endpoints, Extract Interface, Safe Delete, исправления к ST1003 (Rename to HTTPPort) |
| Дни | загрузка Go SDK из IDE, запуск в WSL, Docker и по SSH |
| Недели | профили и flame graph в IDE, Go templates |

- Свой парсер Go не начинаем, пока путь через gopls не упёрся в потолок.
- Порядок работ ведётся в `PLAN.md`, статус каждой функции в `ROADMAP.md`.

---

<!-- _class: lead -->
<!-- _paginate: false -->

# Go там, где вы уже работаете

IntelliJ IDEA, PyCharm, WebStorm, Rider и форки платформы.

**Go Project Support**, `io.github.golangsupport`
Подробности: `docs/demo.html`, `COMPARE.md`, `ROADMAP.md`
