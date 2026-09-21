# Go Project Support — дорожная карта

Отметки: `[x]` сделано, `[~]` сделано, но не проверено вживую, `[ ]` не начато. «Робот» — проверено UI-роботом в песочнице IDEA 2026.1.4.

## Язык (без инструментов)
- [x] Лексер, подсветка, директивы `//go:`, страница цветов — робот
- [x] Аннотатор: встроенные типы / константы / функции, имена объявлений, вызовы — робот
- [x] Сканер объявлений → PSI, Structure view, breadcrumbs — робот
- [~] Folding (тела, группы, комментарии), Go to Class / Symbol, commenter, скобки, кавычки
- [~] Live templates (`err`, `errw`, `forr`, `fori`, `main`, `meth`, `test`, `ttest`, `bench`, `gof`, `deff`, `sel`, `pf`, `json`)
- [ ] Отступы при наборе (Enter после `{`, `case`), как `csharpIndent` в dotnet-плагине
- [ ] Шаблоны файлов: New → Go File (пустой, main, test)

## gopls (content-модуль `lsp`)
- [x] Запуск на проект, go.mod / go.work тоже; inlay hints, диагностика — робот
- [x] Настройки: staticcheck, gofumpt, inlay hints, build tags; перезапуск при Apply и из меню
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
- [ ] Консольные фильтры: `file.go:12` и стеки паник кликабельны
- [ ] Test Explorer (окно со всеми тестами проекта), покрытие (`-coverprofile`) с подсветкой в редакторе
- [ ] Автогенерация конфигураций для `cmd/*`
- [ ] Бенчмарки: таблица результатов, сравнение прогонов

## Отладчик (content-модуль `dap`, delve)
- [x] Запуск `dlv dap` по TCP, launch для `go run` и `go test`, остановка на точке, стек, переменные, шаги, evaluate — робот
- [x] Отказ запуска (не компилируется, версия Go) — уведомление с выводом компилятора, сессия закрывается — робот
- [x] Лог delve на сессию, Show Debugger Logs, Trace Debugger Protocol
- [~] Hit count и logpoints (переписывание `setBreakpoints`), условие точки, точки на паники
- [~] Значение при наведении (`GoHoverExpression`)
- [ ] Attach to Process
- [ ] Set Value (у delve только `setVariable`: нужен variablesReference контейнера)
- [ ] Вызовы функций в Evaluate без префикса `call`
- [ ] Completion в Evaluate / watches из остановленной программы

## Качество кода
- [x] Reformat Code: gofmt / goimports
- [ ] golangci-lint в редакторе (внешний аннотатор) и по проекту
- [ ] Проверка toolchain при открытии проекта, окно «Go on This Machine» (`go version`, `go env`, GOTOOLCHAIN)

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
- [ ] Больше иконок: пакет, интерфейс / структура в дереве, go.work, vendor, окна инструментов
