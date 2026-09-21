# Go Project Support — дорожная карта

Отметки: `[x]` сделано, `[~]` сделано, но не проверено вживую, `[ ]` не начато. «Робот» — проверено UI-роботом в песочнице IDEA 2026.1.4.

## Язык (без инструментов)
- [x] Лексер, подсветка, директивы `//go:`, страница цветов — робот
- [x] Аннотатор: встроенные типы / константы / функции, имена объявлений, вызовы — робот
- [x] Сканер объявлений → PSI, Structure view, breadcrumbs — робот
- [~] Folding (тела, группы, комментарии), Go to Class / Symbol, commenter, скобки, кавычки
- [~] Live templates (`err`, `errw`, `forr`, `fori`, `main`, `meth`, `test`, `ttest`, `bench`, `gof`, `deff`, `sel`, `pf`, `json`)
- [x] Отступы при наборе как у gofmt (`GoIndentEngine`: уровень на строку с открытыми скобками, `case` на уровне `switch`, продолжение выражения), Enter между скобками, Code Style | Go (табы) — робот
- [x] Идиомы серым текстом с принятием по Tab: `if err != nil` с `return` под сигнатуру функции, `defer` для отмены контекста, мьютекса, файла, тела ответа, строк и транзакции (настройка) — робот (`if err`), остальное юнит-тесты
- [x] Цвета как в GoLand: semantic tokens gopls (пакеты, ссылки на типы, поля, константы, параметры, встроенные) → свои ключи (`GoSemanticColors`), цвета в `colorSchemes/GoDarcula.xml` и `GoDefault.xml`; без gopls аннотатор сам красит имена пакетов перед точкой — проверено пользователем в установленном плагине. Не сделано: отдельный цвет получателя метода (gopls отдаёт его как обычный параметр)
- [x] `*_test.go` — scope «Tests» платформы: зелёный фон в дереве и вкладках — робот
- [x] New → Go File (пустой, программа, тест; пакет — из соседних файлов или имени каталога), New Go Module — шаблон теста проверен роботом, диалог нет

## gopls (content-модуль `lsp`)
- [x] Запуск на проект, go.mod / go.work тоже; inlay hints, диагностика — робот
- [x] Настройки: staticcheck, gofumpt, inlay hints, build tags; перезапуск при Apply и из меню
- [x] Страница Settings | Tools | Go | gopls: форма, сгенерированная из каталога установленного gopls (`gopls api-json`): группы документации (Build, Formatting, Completion, Diagnostics…), галочка / список / поле по типу опции, списки и `NAME=value` без JSON, галочки для ключей словарей (линзы, подсказки), диалог с поиском для 244 анализаторов, экспериментальные опции в сворачиваемых подгруппах, поиск, Reset all. Контролы показывают действующее значение (умолчание gopls или то, что задаёт плагин — `GoplsDefaults`), хранится только отличие — юнит-тесты (`GoplsValuesTest`, `GoplsCatalogueTest`), страница и диалог анализаторов просмотрены роботом; Apply с перезапуском сервера вживую не проверен
- [x] Usages / implementations над объявлениями, Find Usages и Go to Implementation с объявления, Go to Declaration через PSI-цели (чего нет в LSP-клиенте платформы) — робот; подсветка под Ctrl + мышь вживую не проверена
- [x] Gutter-иконки I↓ / I↑: реализации интерфейса и его методов, интерфейсы типа и его методов (один запрос `implementation` в обе стороны) — робот, клик не проверен
- [ ] Code lens gopls (run test, tidy, upgrade dependency), команды `gopls.*`
- [ ] Виджет статуса, окно с логом сервера

## Модули
- [x] go.mod / go.work: тип файла, подсветка, разбор (`GoModFile`)
- [~] Узел Dependencies в Project view с исходниками из module cache (в playground нет зависимостей — не проверено)
- [x] Меню Go → Modules: Tidy, Download, Vendor
- [ ] Окно зависимостей: обновления (`go list -m -u all`), `go get`, уязвимости (`govulncheck`)
- [ ] Автоматический `go mod tidy` / предложение после правки go.mod

## Build / Run / Test
- [~] Build, Vet, Generate → Build tool window с переходом к ошибке
- [x] Run configuration «Go» (`go run` / `go test`), gutter-иконки, producer — робот
- [x] Дерево тестов из `go test -json`: подтесты, skip, fail, Rerun Failed, переход к тесту — робот
- [x] Консольные фильтры: `file.go:12` компилятора, тестов и стеков паник кликабельны — робот (консоль тестов). Голое имя файла находится, только если оно одно в проекте
- [x] Окно Go Tests: пакеты и тестовые функции проекта, Run / Debug выбранного, Run All, статусы последних прогонов (`GoTestStatuses`, слушатель проекта) — робот
- [ ] Покрытие (`-coverprofile`) с подсветкой в редакторе
- [x] Автогенерация конфигураций для каталогов с `func main` (настройка; удалённая не возвращается) — робот
- [ ] Бенчмарки: таблица результатов, сравнение прогонов

## Отладчик (content-модуль `dap`, delve)
- [x] Запуск `dlv dap` по TCP, launch для `go run` и `go test`, остановка на точке, стек, переменные, шаги, evaluate — робот
- [x] Отказ запуска (не компилируется, версия Go) — уведомление с выводом компилятора, сессия закрывается — робот
- [x] Лог delve на сессию, Show Debugger Logs, Trace Debugger Protocol
- [~] Hit count и logpoints (переписывание `setBreakpoints`), условие точки, точки на паники
- [~] Значение при наведении (`GoHoverExpression`)
- [ ] Attach to Process
- [x] Set Value: `setVariable`, контейнер переменной узнаётся из трафика (`DapVariableContainers`) — робот (int); строки и указатели не проверены
- [x] Вызовы функций в Evaluate без префикса `call` (`GoEvaluate`) — робот: до delve доходит `call f()`. Метод, который нигде не вызывается, линкер выбрасывает, и delve его не знает
- [ ] Completion в Evaluate / watches из остановленной программы

## Качество кода
- [x] Reformat Code: gofmt / goimports; форматирование при сохранении (настройка, по умолчанию включено) — робот
- [x] golangci-lint в редакторе: сохранённые файлы, пакет файла, v1 и v2, настройка — робот
- [x] Исправления к находкам линтера: «Handle error» / «Ignore error explicitly» для errcheck, «Suppress with //nolint» для любого правила — робот
- [x] Alt+Enter → «Refactorings and actions of gopls...»: code actions сервера для каретки или выделения — пункт виден роботу, попап не проверен
- [ ] golangci-lint по проекту (в Build tool window), автоисправления из отчёта
- [x] Окно «Go on This Machine» (go, модули, инструменты, `go env`) — робот; [~] уведомление, когда в проекте есть go.mod, а `go` не найден

## Мониторинг и прочее (по образцу dotnet-плагина)
- [ ] Монитор процесса: `runtime/metrics` / pprof-эндпоинт, горутины, heap, GC
- [ ] Профилирование: `go tool pprof` (CPU, heap) из run configuration
- [ ] Endpoints: маршруты net/http, chi, gin, echo
- [ ] Analyze Go Stack Trace (вставить панику — получить переходы)
- [ ] New Go Project / модуль

## Инфраструктура
- [x] Сборка на локальной IDEA, UI-робот (`tools/ui-robot`), зонд delve (`tools/dlv-dap`)
- [x] Юнит-тесты чистой логики
- [ ] Платформенные тесты: загрузка plugin.xml, состав меню и страницы настроек (без выключенных заглушек)
- [x] Иконки в стиле и по назначениям dotnet-плагина (`tools/icons/generate.py`, таблица соответствий в его шапке): go / _test, go.mod ↔ project, go.work ↔ solution, зависимость ↔ nuget (+ indirect), бинарник ↔ assembly, конфиги инструментов ↔ config, сгенерированный код ↔ msbuild, шаблоны ↔ settingsJson, go.sum, vendor, benchmark / fuzz / example, окна инструментов (4 цвета + 20x20)
- [x] UI-робот на своём порту 8083 с проверкой, что IDE — песочница этого проекта (8082 занят песочницей dotnet-плагина)
- [ ] Иконки узлов Structure view свои, а не из AllIcons
