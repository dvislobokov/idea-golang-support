# Промпт: разведка GoLand как эталонной IDE (по образцу разведки Rider в `../idea-dotnet-support`)

Этот файл — задание для сессии Claude Code в `C:\Users\dvislobokov\idea-golang-support`. Выполнять сверху вниз, отмечая чек-лист в конце.
Цель (слова пользователя): «хочу и тот плагин довести до лучшего состояния». Результат — снятая UI-роботом картина того, что GoLand показывает
пользователю (меню, настройки, подсветка, completion, помощь при наборе), и список разрывов с нашим плагином с приоритетами и способом закрытия.

## 0. Что прочитать до начала (не пропускать)

В этом репозитории:
- `CLAUDE.md` — правила репозитория (робот, порты, `--offline`, Rhino-оговорки, «вживую не проверено»). Они действуют поверх этого файла.
- `MIGRATION.md` (шапка и статус шагов), `ROADMAP.md`, `PLAN.md` — что у плагина есть и куда он идёт. Важно: смысл кода сейчас даёт **свой PSI go-psi**
  (`GoFeatures`, настройка «Language features: gopls / Built-in», по умолчанию Built-in, gopls выключен с 0.2.82); gopls — запасной путь.
- `COMPARE_GOLAND.md` — уже есть сравнение с GoLand, но **по справке и блогу**, не по живой IDE (раздел 2, «Откуда сведения»). Эта разведка его
  проверяет и уточняет; его разделы 5.1–5.4 — главный ориентир для сверки. `COMPARE.md` — инвентарь плагина.
- `docs/IDE-FEATURES.md`, `docs/FEATURES.md` (что go-psi умеет / не умеет), `docs/SEMANTIC.md`; `playground/README.md` (чек-лист живой проверки).
- `tools/ui-robot/robot.py` (команды `wait`, `js`, `shot`, `windows`, `find`, `action`), `tools/ui-robot/scripts/` (особенно `prelude.js`,
  `highlights.js`, `codevision.js`, `colors.js`, `complete_at.js`, `popups.js`, `settings.js`, `show_settings_by_id.js`, `session.sh`).
- `src/main/resources/colorSchemes/GoDefault.xml` — наши ключи `GO_*` (сейчас их 8), `src/main/resources/META-INF/plugin.xml`.

В `C:\Users\dvislobokov\idea-dotnet-support` (образец; **ничего там не менять**):
- `docs/rider-analysis/README.md` — итоговый документ, его структуру (шапка с условиями съёмки, «Как повторить», «Подводные камни», таблица
  файлов, разделы 1–6, таблица разрывов «Есть в Rider / У нас сейчас / Чего нет / Как сделать», «что проверить вживую») повторить один в один.
- `docs/rider-analysis/dumps/*.txt` — формат дампов; `docs/RIDER_REFERENCE.md` — более ранний точечный документ проб.
- `tools/ui-robot/rider/start-rider.ps1` — изолированный запуск эталонной IDE.
- `tools/ui-robot/rider/analysis/` — `rj.sh` (помощники `rj`, `comp`, `pop`, `activate`, `escape`), `esc.sh`, драйверы `completions.sh`,
  `typing.sh`, `popups.sh`, `settings.sh`; Rhino-скрипты `complete2.js`, `typing.js`, `popup.js`, `poll.js`, `menu.js`, `actions_tree.js`,
  `settings_tree.js`, `settings_page.js`, `code_style_sections.js`, `highlights.js`, `codevision.js`, `colors.js`, `keys.js`, `focus.js`,
  `frame_size.js`, `showtw.js`, `tidy.js`, `unkeep.js`; пробный файл `RiderAnalysis.cs`. Ещё `tools/ui-robot/scripts/rider_complete.js` и
  `tools/ui-robot/rider/probes.sh`, раздел про Rider и Rhino в `tools/ui-robot/README.md`.

## 1. Ограничения

- Не коммитить (репозиторий к тому же не инициализирован). Не трогать настоящий конфиг GoLand (`%APPDATA%\JetBrains\GoLand2026.2`) — только читать
  и копировать из него. Не трогать `playground/` — работать на копии в `build/`.
- Одна IDE за раз: эталонный GoLand и песочница плагина (`runIdeForUiTests`, порт 8083) одновременно не держать (память машины, фокус окна).
  Gradle не нужен для шагов 2–7; он нужен только для необязательного шага 8.
- Робот в GoLand выводит окно вперёд и шлёт Escape: **до запуска предупредить пользователя**, что минут на 30–60 окно GoLand будет перехватывать фокус.
- Снимать только компоненты IDE (`robot.py shot файл "<xpath>"`), никогда весь рабочий стол. Картинок — не больше 60.
- Не декомпилировать и не копировать код GoLand (правило go-psi из `CLAUDE.md`): снимаем только то, что видно пользователю и что отдаёт публичный API
  платформы (ActionManager, Configurable, MarkupModel, Lookup, Inlay).
- Честно писать, что снять не удалось (наведение, автопопапы, которые робот не вызвал, асинхронные меню) — отдельным списком «проверить вживую».
- Python- и JS-скрипты с обратными слэшами создавать инструментом Write, не heredoc в Git Bash (слэши теряются).

## 2. Подготовка (порядок важен)

1. Проверить установку: `C:\Program Files\JetBrains\GoLand 2026.2.3\bin\goland64.exe`, `product-info.json` (build `262.10968.67`,
   `envVarBaseName = GOLAND`, `dataDirectoryName = GoLand2026.2`). Если пути другие — поправить и записать в шапку отчёта. Старый GoLand 2025.1.3
   не использовать (только если 2026.2.3 не стартует).
2. Убедиться, что есть robot-server-plugin: `.intellijPlatform/sandbox/idea-golang-support/IU-*/plugins_runIdeForUiTests/robot-server-plugin`
   (есть на 2026-10-04). Если нет — один раз `./gradlew.bat runIdeForUiTests --no-configuration-cache` (в фоне), дождаться `robot.py wait`, закрыть.
3. Создать `tools/ui-robot/goland/` и перенести туда, адаптируя, из `../idea-dotnet-support/tools/ui-robot/rider/`:
   - `start-goland.ps1` из `start-rider.ps1`: параметры `-GoLand "C:\Program Files\JetBrains\GoLand 2026.2.3"`, `-Config GoLand2026.2`,
     `-Root "$env:USERPROFILE\goland-robot"`, `-Port 8595`, `-Project <repo>\build\ui-robot\goland-playground`; копия конфига без `c.kdbx`, `c.pwd`,
     `plugins` (лицензия `goland.key` и `options/go.sdk.xml` с GOROOT копируются); свои `system`, `plugins` (только robot-server-plugin), `log`;
     файлы `goland.properties` / `goland.vmoptions` из `bin\goland64.exe.vmoptions` + `-Drobot-server.port=8595`,
     `-Djb.privacy.policy.text=<!--999.999-->`, `-Djb.consents.confirmation.enabled=false`, `-Dide.show.tips.on.startup.default.value=false`,
     `-Didea.trust.all.projects=true`; переменные `GOLAND_PROPERTIES`, `GOLAND_VM_OPTIONS`; `Start-Process ... -PassThru` и **печатать PID**
     (он нужен для `AppActivate`). Ключ `-Fresh` — удалить `$Root` и скопировать конфиг заново.
   - `analysis/gj.sh` из `rj.sh`: `ROBOT_PORT=8595`, `NO_PROXY=127.0.0.1` (в shell заданы `HTTP_PROXY`), `GOLAND_PID`, функции `gj` (= `rj`),
     `comp`, `pop`, `activate`, `escape`, `P` = `build/ui-robot/goland-playground`; картинки — в `docs/goland-analysis/img/`.
     **Скрипты запускать через `robot.py js`, не `robot.py script`**: `script` подклеивает `prelude.js`, который ищет плагин
     `io.github.golangsupport` — в GoLand его нет, скрипт упадёт.
   - Rhino-скрипты из `rider/analysis/*.js` — скопировать и заменить специфичное для Rider (`ReSharperNavigateTo`, `RiderModalPopupCookie`,
     `SolutionExplorerPopupMenu*`, C#-страницы настроек) на Go-аналоги; `esc.sh`, `poll.js`, `focus.js`, `keys.js`, `tidy.js`, `unkeep.js` — как есть.
   - Новый `analysis/wait_indexing.js`: опрос `DumbService.isDumb(project)` и `ProgressManager`/`StatusBar` до тишины, печатает время.
4. Копия площадки: `build/ui-robot/goland-playground` ← `playground/` без `.idea`; сохранить эталон рядом (`goland-playground.orig`) для сверки в конце.
   В копию добавить пробные файлы (удалить после съёмки):
   - `internal/probe/analysis.go` (компилируется, `go build ./...` — 0 ошибок): интерфейс + структура, неявно его реализующая (и через указатель);
     встроенная структура; generic-функция и тип с ограничением (`~int | ~float64`, `comparable`); методы на значении и указателе; рекурсия;
     замыкание; горутина, канал, `select`, `defer`; `switch v := x.(type)`; метка + `break L`; затенение (`err :=` во внутреннем блоке),
     переприсваиваемая переменная; `fmt.Printf("%d %s %v %w")`, `regexp.MustCompile(`...`)`, `time.Format("2006-01-02")`, теги
     `json:"name,omitempty"`; `//go:generate`, `//go:embed`; экспортируемые и неэкспортируемые функции, константы, `iota`, встроенные `len`, `make`;
     пустые строки-якоря с комментариями `// PROBE:<имя>` для проб completion и набора (как `RiderAnalysis.cs`).
   - `internal/probe/analysis_test.go`: `TestX`, табличный тест с `t.Run`, `BenchmarkX`, `ExampleX`, `FuzzX`.
   - `internal/probeerr/broken.go` (отдельный пакет, ошибки нарочно): неиспользуемые переменная и импорт, `err` без проверки, недостижимый код,
     `Printf` с неверным глаголом, самоприсваивание, `defer` в цикле, сравнение с `nil` интерфейса — чтобы сработали инспекции.
5. Запуск: `powershell -File tools/ui-robot/goland/start-goland.ps1`, затем `ROBOT_PORT=8595 python tools/ui-robot/robot.py wait`,
   `gj analysis/wait_indexing.js`. Первый снимок — окно IDE (`img/01-window.png`). Если всплыл диалог лицензии, импорта настроек или GOROOT —
   снять его компонент, закрыть Escape/кнопкой; если без пользователя не обойти (лицензия) — остановиться и спросить.
6. Записать условия съёмки: версия и build, keymap (из `options/keymap.xml` копии; если файла нет — по умолчанию), тема, лицензия, версия Go
   (`go version`), включённые плагины (`bundled_plugins.txt`/`disabled_plugins.txt`).

## 3. Подводные камни (найдены на Rider, в GoLand ждать того же)

1. Popup и completion приходят **пустыми**, пока окно IDE не на переднем плане; `focusProjectWindow` Windows блокирует → перед каждой пробой
   `activate` = `WScript.Shell.AppActivate(<pid>)`.
2. Попапы Alt+Enter / Generate / Refactor This / Navigate закрывать **только Escape** (`SendKeys('{ESC}')` после `AppActivate`). Закрытие сменой
   каретки или фокуса один раз навсегда повесило EDT, IDE пришлось убить.
3. Попапы открывать `ActionManager.getInstance().tryToExecute(action, inputEvent, component, place, true)`; `ActionUtil.invokeAction` попап
   не открывает. Каретку ставить отдельным вызовом за ~2 с до действия.
4. Проба completion — не дольше ~10 с; файл восстанавливать точечной правкой (`replaceString`), не `setText` всего документа (иначе IDE заново
   анализирует файл после каждой пробы).
5. Rhino: в замыканиях внутри цикла не использовать блочные `const`/`let` (держат первое значение) — `var`; строка, начинающаяся с `(` после `}))`,
   парсится как вызов — ставить `;`. Результат скрипта предварять `@@@` (так его вырезает `gj`).
6. Цвета semantic-подсветки приходят позже разметки: подсветку снимать после `wait_indexing.js` и паузы (`DaemonCodeAnalyzer` закончил), не сразу
   после открытия файла.
7. Часть меню IDE строит асинхронно — собранная вручную копия меню бывает пустой; состав брать из дерева `ActionManager`.

## 4. Что снять (шесть разделов, как у Rider)

**4.1 Меню, тулбар, контекстные меню** (`actions_tree.js`, `menu.js`, `popup.js`/`pop`).
- `dumps/main-menu.txt` — всё дерево `MainMenu`: текст, id, сочетание, `[hidden]`/`[disabled]` в контексте редактора `.go`;
  `dumps/main-menu-visible.txt` — видимые меню верхнего уровня; `dumps/toolbar.txt` (`MainToolbarNewUI`, `RunToolbarMainActionGroup` и т. п.);
  `dumps/editor-popup.txt`, `dumps/project-view-popup.txt` (ПКМ на `.go`, `go.mod`, папке пакета, `_test.go`), ПКМ на вкладке и гаттере.
- Отдельно выписать Go-специфичное: Go-инструменты (`go generate`, `go mod tidy`/`download`/`why`, `go work`), Build/Run/Test-конфигурации
  (Go Build, Go Test, gocheck/testify, Go Remote), Coverage, Profiler (CPU/Memory/Blocking/Mutex), Run with race, Show Go Assembly, Tools | Go Tools,
  Go Modules / Dependencies. Id находить грепом по дампу (`Go`, `Gopls`, `golang`), не угадывать.
- Живые попапы (`dumps/alt-enter-generate-refactor-navigate.txt`): Alt+Enter (`ShowIntentionActions`) в 12–15 местах `analysis.go`/`broken.go`
  (на имени структуры, на `err` без проверки, на неиспользуемой переменной, на строке `Printf`, на `if`, на `for range`, на литерале структуры,
  на вызове несуществующей функции, на теге, на импорте, на интерфейсе, на методе с получателем); Generate (`Generate`, Alt+Insert) на структуре и в
  `_test.go` (конструктор, геттеры/сеттеры, Implement Methods, Test for function, Table test); Refactor This (`Refactorings.QuickListPopupAction`);
  навигация: Implementation(s), Super Method, Type Declaration, Related Symbol, File Structure. По картинке на 6–8 главных попапов.

**4.2 Настройки** (`settings_tree.js`, `settings_page.js`, `code_style_sections.js`, драйвер `settings.sh`).
- `dumps/settings-tree.txt` — всё дерево Settings (имя, id, класс).
- `dumps/settings-pages.txt` — опции всех страниц Go: Go (GOROOT, GOPATH, Go Modules, Build Tags, Vendoring, Imports, On Save / Actions on Save,
  Go Linter, Fuzzing и что найдётся — имена сверить по дереву), Editor | Code Style | Go (все вкладки → `dumps/settings-code-style-go.txt`),
  Inlay Hints | Go, Code Vision, Inspections | Go (все инспекции с уровнем и включённостью → `dumps/inspections-go.txt`),
  Postfix Completion | Go, Live Templates | Go (имя, описание, текст шаблона, контексты → `dumps/live-templates-go.txt`), File and Code Templates Go,
  Color Scheme | Go. Картинки — 6–8 главных страниц (`//div[@class='MyDialog']`).

**4.3 Подсветка редактора** (`highlights.js`, `codevision.js`, `colors.js`).
- `dumps/color-keys-go.txt` — все ключи страницы Color Scheme | Go: имя ↔ `TextAttributesKey` (`GO_*`), цвет, fallback, demo-текст.
- `dumps/highlight-<файл>.txt` для `analysis.go`, `broken.go`, `analysis_test.go` и 2 файлов площадки: диапазон, текст, ключ, слой, подсказка;
  сводка по ключам. Отметить: локальные/пакетные/экспортируемые функции и вызовы, методы, поля, параметры, получатели, локальные переменные,
  затенение, переприсваивание, встроенные функции и типы, параметры типа, метки, константы, глаголы в `Printf`, regexp-инъекция, раскладка
  `time.Format`, теги структур (ключи и значения), директивы `//go:`.
- Inlay hints (имена параметров, типы, прочее) и code vision (usages, implementations, «N implementations» у интерфейса) — `dumps/code-vision-gutter.txt`;
  гаттер: implements / is implemented (неявные интерфейсы Go!), recursion, run/test/bench/fuzz-иконки, goroutine, embed — иконка + подсказка.
- Инспекции, сработавшие в `broken.go`: id, уровень, текст, quick fixes.

**4.4 Completion** (`complete2.js` → `go_complete.js`, драйвер `completions.sh`; ~70–80 проб, `dumps/completion.txt`). На каждую пробу: набрано,
вид (BASIC / SMART / AUTO), первые 15 пунктов с типом/tail/иконкой, вставленный текст после выбора. Минимальный список:
после `.` (поля, методы, методы указателя, встроенная структура); пакет не импортирован (`strings.`, `json.Mar`) + проверка добавленного импорта;
голое имя символа чужого пакета; smart по ожидаемому типу (аргумент, `return`, присваивание, `:=`); postfix `.if .nn .nil .err .var .rr .for .forr
.return .print .sort` и прочие из дампа 4.2; live templates `forr fori meth err func main test bench`; поля в литерале структуры (оставшиеся,
`Field: ` с Tab); сигнатура метода для реализации интерфейса; `return` с нулевыми значениями; блок `if err != nil`; теги `json:"…"` (имя по полю);
глаголы `Printf`; импорт в `import ( "…` (пути пакетов и модулей); generics (ограничения, инстанцирование); method value; ключевые слова
по контексту (`go`, `defer`, `select`, `fallthrough`, `range`); операции с каналом; `case` в `select` и type switch; имена переменных по типу;
`go.mod` (директивы, версии модулей); `_test.go` (`t.`, `assert.` если есть testify). Что не вызвалось — в «проверить вживую».

**4.5 Помощь при наборе** (`typing.js`, драйвер `typing.sh`, `dumps/typing-assists.txt`, ~20 проб, строка после каждого символа):
пары скобок и кавычек (включая бэктик), Enter между `{}`, Enter в `//`-комментарии и в строке, `if err` + Tab, auto-import при выборе пункта,
`:=`/`=` подсказки, gofmt при сохранении и Reformat (`saveAllDocuments`), Complete Statement (Ctrl+Shift+Enter) в `if`, `for`, `func`,
вставка/удаление импорта при правке, отступ после `case`, `}` выравнивание, Smart Enter в литерале структуры.

**4.6 Разрывы** — см. шаг 6.

## 5. Сверка с плагином (необязательно, после закрытия GoLand)

Если хватает времени: закрыть GoLand, поднять песочницу плагина (`./gradlew.bat runIdeForUiTests --no-configuration-cache`, порт 8083, проект —
копия площадки с теми же пробными файлами) и прогнать **те же** пробы 4.3–4.5 существующими скриптами (`highlights.js`, `complete_at.js`,
`editop.js`) → `dumps/ours-*.txt`. Тогда колонка «У нас сейчас» — замер, а не пересказ ROADMAP. Песочницу после закрыть, в её `idea.log` —
0 строк `Plugin to blame: Go`. Если не делали — так и написать в шапке.

## 6. Таблица разрывов (раздел 6 отчёта)

Колонки: `# | Есть в GoLand | У нас сейчас | Чего нет | Как сделать | Цена (дни)`. Приоритет — по частоте в ежедневной работе, затем по цене.
«Как сделать» — через слои этого плагина:
- **синтаксис** — PSI/стабы go-psi (`go-psi-core`), токены `lang.GoTokens`;
- **семантика** — resolve/типы `go-psi-semantic` (`semantic.*`), индексы (`GoTypesIndex`, `GoExportsIndex`);
- **каталог** — `catalogue` (stdlib и модули из `require`);
- **IDE-фича** — `go-psi-ide` / пакет `lang` (генераторы `GoGenerators`, `GoIntentions`, `GoPostfixTemplates`, live templates, line markers, инспекции);
- **gopls** — только если в нём есть, а NATIVE-пути ещё нет (помнить: по умолчанию gopls выключен; это временный путь по `MIGRATION.md`);
- **инструмент** — `go`, golangci-lint, delve; **платформа** — то, что даёт IntelliJ без Go-знаний.
Отдельно — пункты, где плагин **уже не хуже или лучше** GoLand, и где расходится с `COMPARE_GOLAND.md` (там «по справке» → здесь «снято»).

## 7. Что положить и куда

- `docs/goland-analysis/README.md` — по структуре `../idea-dotnet-support/docs/rider-analysis/README.md`: шапка (дата, версия/build, условия
  съёмки, как запускался), «Как повторить», «Подводные камни» (что подтвердилось и что нового в GoLand), таблица файлов, разделы 1–6,
  «Что проверить вживую и почему».
- `docs/goland-analysis/dumps/*.txt`, `docs/goland-analysis/img/NN-*.png` (≤ 60, только компоненты IDE).
- `tools/ui-robot/goland/` — `start-goland.ps1`, `analysis/*.sh|*.js`, копии пробных файлов (`analysis.go`, `analysis_test.go`, `broken.go`),
  короткий раздел «GoLand как эталон» в `tools/ui-robot/README.md` (если файла нет — в шапке `start-goland.ps1`).
- `COMPARE_GOLAND.md` — **не переписывать**; добавить в раздел 2 «Откуда сведения» строку «GoLand вживую — `docs/goland-analysis`» и в конце
  короткий раздел «Сверка с живым GoLand (2026-…)»: что подтвердилось, что оказалось иначе.
- `ROADMAP.md` / `PLAN.md` — **не перестраивать**. Предложенные пункты положить в README отчёта разделом «Предложение для ROADMAP/PLAN»
  и спросить пользователя, вносить ли их и куда.

## 8. Завершение

1. Удалить пробные файлы из копии, сверить `build/ui-robot/goland-playground` с `goland-playground.orig` (`diff -r`), убрать `.orig`.
2. Закрыть GoLand (`robot.py action Exit` с `ROBOT_PORT=8595`, подтвердить; если завис — `Stop-Process -Id <pid>`), убедиться, что порт 8595 закрыт
   (`curl --noproxy '*' http://127.0.0.1:8595` не отвечает) и процесса нет. Порт робота даёт выполнять код в IDE — открытым не оставлять.
3. `%USERPROFILE%\goland-robot` оставить (повторный запуск быстрее), но сказать пользователю, что там копия лицензии и его можно удалить.
4. Ответ пользователю по-русски: что снято (числа: действия, страницы, ключи цветов, пробы completion/набора, картинки), 10 главных разрывов,
   что не удалось снять и что проверить вживую, где лежат файлы.

## Чек-лист

- [ ] Прочитаны `CLAUDE.md`, `MIGRATION.md`, `ROADMAP.md`, `COMPARE_GOLAND.md`, образец `rider-analysis/README.md`
- [ ] Пользователь предупреждён о перехвате фокуса
- [ ] `tools/ui-robot/goland/` создан, скрипты адаптированы, запуск через `robot.py js`
- [ ] Копия площадки + пробные файлы; `go build ./...` по `internal/probe` — 0 ошибок
- [ ] GoLand поднят изолированно (порт 8595, PID записан), индексация закончена, условия съёмки записаны
- [ ] 4.1 меню/тулбар/ПКМ/попапы · 4.2 настройки · 4.3 подсветка/inlay/code vision/гаттер/инспекции · 4.4 completion · 4.5 набор
- [ ] (необязательно) те же пробы на песочнице плагина, GoLand к этому моменту закрыт
- [ ] Таблица разрывов с «Как сделать» по слоям плагина и ценой
- [ ] `docs/goland-analysis/README.md`, `dumps/`, `img/` (≤ 60); строка и раздел сверки в `COMPARE_GOLAND.md`
- [ ] Предложение для ROADMAP/PLAN — в отчёте, вопрос пользователю задан
- [ ] Пробные файлы удалены, копия сверена; GoLand и песочница закрыты, порты 8595/8083 свободны
- [ ] Ответ пользователю: числа, топ-10 разрывов, «вживую не проверено»
