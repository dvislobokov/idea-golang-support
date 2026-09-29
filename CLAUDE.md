# idea-golang-support

Плагин «Go Project Support» (`io.github.golangsupport`) для IDE на платформе IntelliJ, в которых нет поддержки Go (IntelliJ IDEA, PyCharm, WebStorm, Rider…).
Сделан по образцу `../idea-dotnet-support`, но устроен иначе: смысл кода даёт **gopls** (платформенный LSP-клиент), отладку — **delve** (`dlv dap`,
**свой** DAP-клиент на XDebugger API, как в dotnet-плагине: модуль `intellij.platform.dap` есть не во всех IDE и форках), остальное — команда `go` и инструменты экосистемы. Своего парсера Go нет: лексер + сканер верхнего уровня
(`lang/GoDeclarations`), по которому строятся PSI-узлы `GoDeclaration` (Structure view, breadcrumbs, folding, Go to Symbol, gutter-иконки, строки для
точек останова) — это работает и без gopls. Меняешь, что сканер считает объявлением, — подними `VERSION` у `GoDeclarationIndex`.

С плагином JetBrains (`org.jetbrains.plugins.go`, GoLand) объявлена несовместимость: тот же тип файлов, и добавить там нечего.

Статус фич — `ROADMAP.md`, что делать дальше и в каком порядке — `PLAN.md` (оба ведутся по-русски; сделанный пункт плана отмечать и переносить в ROADMAP). `playground/` — Go-модуль для живой проверки (один тест в нём падает нарочно), к сборке не относится.
`tools/dlv-dap/probe.py` — зонд `dlv dap` (как объявляет порт, capabilities). `tools/gopls/probe.py` — зонд gopls без IDE: диагностики файла и code actions в заданных местах (так отличают «сервер не предлагает» от «платформа не показывает»). `tools/icons/generate.py` — все SVG плагина (править фигуры там, потом запускать). Анализ платформенных API LSP / DAP — в соседнем репозитории:
`../idea-dotnet-support/docs/platform-lsp-dap.html`, `tools/platform-api/api.json`, журнал находок по DAP-клиенту — `PLATFORM_DAP_PLAN.md` там же.

## Сборка и проверка

Системных JDK и Gradle нет. Wrapper запускать с JBR целевой IDE (Git Bash):

```sh
export JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\jbr"
./gradlew.bat test buildPlugin -q --offline   # основная проверка перед тем, как сказать «готово»
./gradlew.bat compileKotlin -q                # быстрая проверка компиляции
./gradlew.bat test --tests "io.github.golangsupport.GoToolingTest" -q --offline
./gradlew.bat runIde --args="C:/Users/dvislobokov/idea-golang-support/playground"   # песочница для пользователя
```

- **`--offline` для test / buildPlugin**: без него Gradle пытается разрешить `java-compiler-ant-tasks` для инструментирования тестов, через прокси этой машины
  это не проходит, и падение выглядит как ошибка сериализации configuration cache. `runIdeForUiTests` запускать с `--no-configuration-cache` (задача
  RunIde не сериализуется). В shell заданы `HTTP_PROXY`/`HTTPS_PROXY`: `robot.py` прокси игнорирует сам, для curl к роботу нужен `--noproxy '*'`
  (`session.sh` экспортирует `NO_PROXY`).

- Целевая платформа — локальная IntelliJ IDEA 2026.1.4 (`localIdePath` в `gradle.properties`), ничего не скачивается. `sinceBuild = 261`: с 2026.1 API
  LSP-клиента называется `LspIntegrationProvider` / `LspClientDescriptor` (старые `LspServer*` — Deprecated) и есть модуль DAP.
- Gradle 9.7.1, Kotlin 2.3.21, `apiVersion`/`languageVersion` = **2.3** (stdlib берётся из платформы) — не использовать API Kotlin новее.
- Упавшие тесты: `build/test-results/test/TEST-*.xml` (grep по `<failure`).
- **GUI агент проверяет сам через UI-робота**: `./gradlew.bat runIdeForUiTests` (в фоне) поднимает песочницу с Remote Robot на `127.0.0.1:8083` (**не 8082**: там песочница dotnet-плагина, и два агента иначе управляют IDE друг друга и закрывают её; другой порт — `-ProbotPort=N` и `ROBOT_PORT=N`),
  `tools/ui-robot/robot.py` открывает проект, ставит точки останова, запускает Run/Debug, снимает окно IDE; `. tools/ui-robot/scripts/session.sh` даёт
  `state`, `evaluate "выражение" [дети]`, `stop_all`, `invoke ACTION_ID`, `setting Name value`, `toolwindow ID`, `openfile store/x.go [строка]` и `robot_js скрипт.js "s|__X__|…|"`
  для скриптов из `tools/ui-robot/scripts` (markers, structure, coverage, gomod_banner, targets, test_results, replace_text, monitor_targets…; к каждому
  подклеивается `prelude.js` с `cls("io.github…")` — классы плагина Rhino иначе не видит; `const` в цикле Rhino хранит первое значение — писать `var`;
  `robot.py action` не срабатывает, если фокус на невидимом компоненте, — тогда `invoke`; format on save платформа зовёт только из `saveAllDocuments`).
  Скрипт `highlights.js` не видит подсветок аннотаторов в новом UI — смотреть снимок редактора. Работать на копии: `build/ui-robot/playground` (без `.idea`). Плагин в песочнице обновляется
  только перезапуском задачи; перед перезапуском закрыть IDE (`robot.py action Exit`, затем клик по `Exit` в диалоге). После проверки песочницу закрыть:
  порт даёт выполнять код внутри IDE. Логи песочницы — `.intellijPlatform/sandbox/idea-golang-support/IU-*/log_runIdeForUiTests/idea.log`, логи delve — рядом в `delve/`.
  Чего так не видно (подсказки по наведению, ощущение скорости), просить пользователя посмотреть вживую и прямо говорить, что не проверено.
- Python-скрипты с обратными слэшами не передавать через heredoc в Bash: слэши теряются и кавычки ломаются. Правки — инструментом Edit, скрипты — файлом в scratchpad.

## Устройство

`src/main/kotlin/io/github/golangsupport/`, пакет = область:

| Пакет | Что там |
|---|---|
| `lang` | `GoIdioms` (следующая строка по правилам: `if err != nil`, `defer`) и её показ через inline completion платформы; `GoGenerators` (чистые генераторы: конструктор, аксессоры, String, теги, заглушки интерфейса, тест, функция из вызова), `GoGenerateActions` (Alt+Insert), `GoIntentions` (Alt+Enter), `GoPostfixTemplates`, `GoSmartEnter` (Complete Statement и док-комментарий `// Name`); `GoLexer`, `GoDeclarations` (сканер: package, imports, func / type / var / const, поля и методы интерфейсов), PSI и Structure view (`GoPsi`), подсветка и аннотатор идентификаторов, индекс и Go to Class / Symbol, folding, commenter, скобки, live templates |
| `mod` | `GoModFile` (чистый разбор go.mod / go.work), язык и подсветка go.mod, `GoModulesService` (модуль файла, все модули проекта) |
| `view` | узел Dependencies в Project view (исходники из module cache), иконки файлов |
| `cli` | `GoCli` (поиск `go`, `commandLine`, `execute`, `runInBackground`, нотификации), `GoTool` (gopls, dlv, golangci-lint, goimports: поиск и `go install`), `GoEnvironment` (`go env -json`) |
| `build` | действия меню Go (Build, Vet, Generate, Modules) → Build tool window, разбор вывода компилятора (`GoBuildOutputParser`) |
| `run` | run configuration «Go» (`go run` / `go test`), редактор, producer и gutter-иконки, `GoLaunchArguments` (запрос `launch` для delve), `GoDebugSupport` — чистая часть отладки: аргументы `dlv`, строки для точек, выражение под мышью, `call`, hit conditions, контекст completion, inline values |
| `testing` | `GoTestEvents` (`go test -json` → service messages, дерево по id, подтесты под родителем), консоль, локатор, Rerun Failed; окно Go Tests и `GoTestStatuses` (статусы прогонов копит слушатель проекта: окно создаётся лениво, позже запусков) |
| `format` | gofmt / goimports за Reformat Code и при сохранении (в фоне: ждать процесс на EDT платформа запрещает — SEVERE в логе) |
| `templates` | New → Go File, New Go Module |
| `lint` | golangci-lint: разбор JSON-отчёта (`GoLintOutput`, v1 и v2), внешний аннотатор для сохранённых файлов; исправления к находкам (`GoLintFixes`: errcheck, `//nolint`), число результатов функции спрашивается у модуля с gopls через точку расширения `signatureProvider` |
| `sdk` | окно «Go on This Machine», проверка toolchain при открытии проекта |
| `help` | Go \| Help Page: HTML-страница во вкладке редактора (`HTMLEditorProvider`), клавиши из keymap |
| `monitor` | Go Monitor: `ProcessSampler` (CPU/память от ОС, JNA на Windows), `GoRuntimeTrace` (разбор `gctrace` / `schedtrace` / `inittrace`), `GoTelemetryProcessHandler` (сборка + запуск с `GODEBUG`, строки рантайма мимо консоли), `GoBuildInfo`/`GoProcesses` (Go-программы машины по `go version -m`), `GoSnapshot` (горутины через delve attach), `GoProfiles` (профили тестов, `go tool pprof`/`trace`), панель и графики (`TimeSeriesChart` из dotnet-плагина) |
| `settings` | `GoSettings` (application-level) и страница Settings \| Tools \| Go; `GoplsCatalogue` + страница gopls под ней: настройки сервера берутся из `gopls api-json` установленной версии, своих списков опций не заводить; форма генерируется по типам опций (`GoplsSettingsConfigurable`), то, что плагин задаёт gopls сам, — `GoplsDefaults` (здесь, а не в `lsp`: страница показывает это как действующие значения), хранится только отличие от них |
| `lsp` | **content-модуль**: gopls на `LspIntegrationProvider`; `GoplsNavigation` / `GoplsCodeVision` — чего нет в LSP-клиенте платформы: Go to Declaration через PSI-цели (ссылка под Ctrl + мышь), usages с объявления, implementations, счётчики над объявлениями; `GoplsCommands` — команды `gopls.*` (клик по линзе платформа шлёт уведомлением без ответа, здесь — запрос с прогрессом и ошибкой; `run_tests` → наш раннер, `generate` → Build window), `GoplsMenuActions` — подменю Go \| gopls, `GoplsLog` — окно лога сервера (stderr, logMessage, команды) и виджет статуса |
| `debugger` | свой DAP-клиент: `DapConnection` (framing, `request_seq`, события, запросы адаптера, trace), `DelveProcess` (`dlv dap` по TCP), `GoDebugRunner` (Debug конфигурации «Go» и attach), `GoDebugProcess` (XDebugProcess: initialize → launch → на `initialized` точки и `configurationDone`), фреймы / значения / точки / редакторы выражений; логи в `GoDebuggerLogs` |

Регистрация — `resources/META-INF/plugin.xml`. То, чему нужен платформенный LSP (есть не в каждой IDE), — content-модуль `io.github.golangsupport.lsp`:
дескриптор `resources/io.github.golangsupport.lsp.xml`, классы строго в одноимённом пакете (у модуля свой загрузчик по префиксу пакета).
Остальной код на этот пакет и на `com.intellij.platform.lsp.*` ссылаться не должен: без модуля платформы часть не грузится, а плагин обязан работать.
Обратная связь — через точки расширения плагина (`languageServerControl`). Платформенный DAP (`com.intellij.platform.dap.*`) не использовать вовсе: отладчик — свой клиент в пакете `debugger`.

Отладчик, что важно знать (проверено вживую):
- `dlv dap` работает только по TCP: `--listen=127.0.0.1:0`, порт — из первой строки stdout (`DAP server listening at:`). **`--log-dest` нельзя**: с ним и эта
  строка уходит в файл; лог сессии пишет `DelveHandle` из потока процесса.
- Программу собирает сам delve (`mode: debug | test`), `outputMode: remote` — вывод программы приходит событиями протокола.
- Отказ `launch` (сборка не прошла) приходит ответом `success: false` после событий `output` с текстом компилятора: `GoDebugProcess` копит их до старта и
  показывает уведомление «Debug has not started» с ними, сессию закрывает. Hit count, logpoints и условие уходят в `setBreakpoints` напрямую (`GoLineBreakpointHandler`).
- Горутина остановки — `threadId` события `stopped`; ids горутин идут не по порядку, угадывать по списку `threads` нельзя.
- В split-режиме отладчика (2026.1) `XDebugSession.getRunContentDescriptor` пишет SEVERE, но раннер всё равно отдаёт дескриптор (как dotnet-плагин): у ванильной IDEA split-фронтенд строит свой UI и дескриптор игнорит, а у форка с «наполовину» реализованным split (GIGA IDE) по null не строится ничего — отладка запускается, но фреймы/переменные не видны. Косметический SEVERE — меньшее зло, чем пустой UI на форках.
- delve новее toolchain отказывается запускать программу («Go version … is too old») — по умолчанию передаётся `--check-go-version=false` (настройка).
- Вызов функции в Evaluate у delve — `call f()`: префикс дописывает `GoEvaluate`; точка внутри вызываемой функции прерывает вызов («call stopped»). `setExpression` нет,
  `setVariable` берёт `variablesReference` контейнера — `GoValue` знает его, потому что список запрашивал сам. Указатель у delve — одна дочерняя переменная с пустым именем.
- Расширения платформы зовут наш код и на EDT, и на фоне без read action: файлы на EDT читать через `LoadTextUtil` (поток — slow operation), `element.project`
  и всё о PSI — внутри `ReadAction`, процессы на EDT не ждать. После проверки роботом смотреть в логе песочницы `Plugin to blame: Go` — должно быть 0.
- Semantic tokens платформа по умолчанию спрашивает только у файлов без своей подсветки: для Go это включено в `GoplsDescriptor`, там же gopls называются его
  модификаторы (`struct`, `interface`, …) — без этого сервер их не шлёт. Что именно он отдаёт — `tools/gopls/semantic_tokens.py`. Снимку робота, снятому сразу
  после открытия файла или при закрытии IDE, в вопросе цветов не верить: цвета приходят позже разметки (так было принято за ошибку то, что работало).
- `SMTestProxy.isDefect` истинно и для пропущенных тестов: сначала проверять `isIgnored`. errcheck указывает колонкой на скобку вызова, а не на имя.

Действия: `Go.MainMenu` — меню **Go** в главной строке (после Tools); `Go.ProjectViewPopup` — ПКМ в дереве проекта. Content-модули добавляют свои
действия в `Go.MainMenu` сами. Настройки — Settings | Tools | Go; на странице **только то, за чем есть реализация**, опция появляется вместе с фичей.

## Соглашения кода

Как в `../idea-dotnet-support`: Kotlin, строки до ~180 символов, плотный стиль, однострочные функции-выражения. Комментарии и KDoc — по-английски, короткие,
объясняют «почему» (находки вживую помечать «seen live»). Тексты UI — английские, действия в Title Case. Действия: `AnAction(), DumbAware`, `BGT`; в контекстном
меню неподходящее скрывать, в главном — выключать (`e.isFromContextMenu`). Команды `go`: `GoCli.commandLinesOrNotify { GoCli.commandLine(dir, ...) }` +
`GoCli.runInBackground`; инструменты — `GoCli.toolCommandLine` (добавляет каталог `go` в PATH). Блокирующие вызовы — не на EDT. Парсинг вывода и файлов —
чистыми функциями, чтобы тестировать без процесса. Новую фичу отмечать в `ROADMAP.md`.

## Тесты

`src/test/kotlin/io/github/golangsupport/*Test.kt`. Чистая логика — обычный JUnit 4 (`@Test`), без платформы: так тесты идут секунды. Для того, чему нужна
платформа, — `BasePlatformTestCase` (JUnit 3-стиль, `fun testXxx()`); light-проект общий между тестами, убирать за собой. Реальные `go`, `gopls`, `dlv` в тестах не запускать.

## Git

Репозиторий пока не инициализирован. Коммитить только по просьбе.
