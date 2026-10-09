# Go Project Support: передача на поддержку

Версия на момент передачи: **0.2.184** (2026-10-05), ветка `migration` → `main`. Документ для команды, которая принимает плагин во внутреннем
контуре: что это, из чего состоит, как собрать и проверить, где что лежит и что править, если нужно добавить или убрать функцию.
Подробности по каждой подсистеме — в документах, на которые здесь даны ссылки; этот файл — карта.

## 1. Что это

Плагин **Go Project Support** (`io.github.golangsupport`) даёт поддержку Go в IDE на платформе IntelliJ, где её нет: IntelliJ IDEA,
PyCharm, WebStorm, Rider, внутренние форки платформы. С GoLand и плагином `org.jetbrains.plugins.go` объявлена несовместимость (тот же тип файлов).

Три кита:

| Что | Чем сделано | Где |
|---|---|---|
| Смысл кода: подсветка, навигация, completion, инспекции, рефакторинги, форматирование | **свой парсер и анализатор Go** (go-psi): лексер, Grammar-Kit-парсер, PSI, стабы, индексы, типы, resolve, поток данных | `go-psi-core`, `go-psi-semantic`, `go-psi-ide` |
| Отладка | **delve** (`dlv dap`), свой DAP-клиент на XDebugger API (платформенный модуль DAP есть не во всех IDE) | `src/.../debugger`, исходники `third_party/delve` |
| Всё остальное: сборка, запуск, тесты, модули, мониторинг, инструменты | команда `go` и инструменты экосистемы | `src/.../build`, `run`, `testing`, `mod`, `monitor`, `cli` |

**gopls** в плагине есть, но с 0.2.82 выключен по умолчанию и не запускается; включается одной настройкой (Settings | Tools | Go | Language Server).
Это запасной режим и код в content-модуле `lsp`, а не основной путь.

Пользовательские страницы (показываются в самой IDE): `docs/demo.html` — «о плагине», открывается раз на версию и из Go | Welcome…;
`docs/guide.html` — справочник по всем действиям, клавишам, настройкам (Go | Help Page). Сборка кладёт их в `welcome/` плагина.

## 2. Репозиторий: карта

```
idea-golang-support/
├── build.gradle.kts, settings.gradle.kts, gradle.properties   корневой проект плагина; pluginVersion и localIdePath здесь
├── build.ps1                      сборка одной командой (см. §3)
├── src/main/kotlin/io/github/golangsupport/   «хост»: всё, что не PSI (пакет = область, таблица в §4)
├── src/main/resources/META-INF/plugin.xml     регистрация всего; подключает дескрипторы go-psi через xi:include
├── src/ml/resources/META-INF/go-ml.xml        ML-ранжирование completion: только в сборке с -PmlEnabled=true
├── src/test/kotlin/…                          тесты хоста (121 файл)
├── go-psi-core/        лексер, грамматика (Go.bnf / Go.flex), PSI, стабы, индексы             49 main / 43 test
├── go-psi-semantic/    project model (go env, go.mod), типы, resolve, проверки, поток данных  51 main / 44 test
├── go-psi-ide/         IDE-фичи поверх PSI: completion, инспекции, intentions, рефакторинги… 365 main / 158 test
├── ml-core/            копия чистого Kotlin-движка ML completion (n-gram LM + линейный ранкер) из idea-ml-completion
├── third_party/delve/  исходники delve (тег v1.27.2, vendor/ внутри) обычными файлами репозитория: кладутся в плагин, собираются у пользователя
├── docs/               документация (список в §9)
├── tools/              скрипты: CI, зонды, UI-робот, гейты, сверка с go vet, иконки
├── testData/           метрики корпусных гейтов и пороги бенчмарков (могут только улучшаться)
├── playground/         Go-модуль для живой проверки (один тест в нём падает нарочно)
├── CHANGELOG.md        история по версиям: одна фича — одна patch-версия
├── ROADMAP.md, PLAN.md, MIGRATION.md   что сделано / что дальше / как шёл переход с gopls на свой PSI (по-русски)
└── CLAUDE.md           правила работы с репозиторием (для людей тоже актуальны: команды, соглашения, грабли)
```

Сборка подключает `go-psi-*` как библиотечные подпроекты; в ZIP попадает один jar плагина (`idea-golang-support-<версия>.jar`) плюс `ml-core.jar`.

## 3. Сборка и проверка

**Требования**: установленная IntelliJ IDEA 2026.1.4 (путь в `gradle.properties` → `localIdePath`), её JBR служит JDK. Системные JDK и Gradle
не нужны. Go-toolchain нужен только для живых проверок и корпусных гейтов (`GOROOT`, module cache).

```powershell
.\build.ps1                 # тесты + ZIP  → build\distributions\idea-golang-support-<версия>.zip
.\build.ps1 -NoTests        # только ZIP
.\build.ps1 -Ml             # сборка с ML (ранкер + модели из ..\ml-data\go\models) → …-<версия>-ml.zip
.\build.ps1 -Run            # собрать и поднять песочницу IDE на .\playground
.\build.ps1 -IdePath "D:\IDEs\PyCharm 2026.1"   # другая IDE как платформа
```

То же в Git Bash: `export JAVA_HOME="<IDE>\jbr"; ./gradlew.bat test buildPlugin -q --offline`.

**`--offline` обязателен** для `test`/`buildPlugin`: иначе Gradle лезет за `java-compiler-ant-tasks`, и через прокси падение выглядит как ошибка
configuration cache. Задачи `runIde*`, `corpusTest`, `benchmark` — только с `--no-configuration-cache`.

**Без интернета (Jenkins + Nexus)**: `docs/BUILD-OFFLINE.md`, файлы `tools/ci/` (`Jenkinsfile`, `nexus.init.gradle`, `truststore.sh`).

**Уровни проверки** (от быстрого к полному):

| Команда | Что | Когда |
|---|---|---|
| `./gradlew.bat compileKotlin -q` | компиляция | после любой правки |
| `./gradlew.bat :go-psi-ide:test --tests "io.github….inspections.*" --offline` | один пакет тестов (задача того модуля, где лежат тесты) | после правки фичи |
| `./gradlew.bat test buildPlugin checkKotlinAbi --offline -q` | все ~3000 тестов, ZIP, ABI публичных пакетов | перед «готово» |
| `./gradlew.bat :go-psi-core:corpusTest --no-configuration-cache` | лексер/парсер/стабы над всем GOROOT и module cache (минуты) | правка грамматики / стабов |
| `./gradlew.bat :go-psi-semantic:corpusTest --no-configuration-cache` | resolve и проверки над GOROOT (~8 мин), 0 ложных ошибок | правка типов / resolve / проверок |
| `./gradlew.bat benchmark --no-configuration-cache` | бенчмарки с порогами `testData/benchmark/thresholds.json` | правки производительности |
| `tools/gates.sh corpus:semantic bench` | те же гейты, вывод сокращён до метрик и падений | |

Упавшие тесты: `*/build/test-results/test/TEST-*.xml`, искать `<failure`. Известная нестабильность: `GoDeepExpressionTest` изредка падает
при трёх параллельных воркерах (recursion prevention под нагрузкой), отдельно проходит.

**Живая проверка**: `./gradlew.bat runIde --args="<путь к проекту>"` — песочница для человека; `runIdeForUiTests` — песочница с
Remote Robot на порту 8083 для сценариев `tools/ui-robot/` (робот-скрипты, `desktop.ps1` для настоящей мыши/клавиатуры/снимков; подробно
в `CLAUDE.md`, раздел «Сборка и проверка»). После проверки в логе песочницы `Plugin to blame: Go` должно быть 0.

**Установка пользователю**: Settings | Plugins | ⚙ | Install Plugin from Disk… → ZIP. Два файла на версию: обычный и `-ml` (46 МБ, с моделями
и страницей Smart Completion). Оба лежат в `build/distributions/` и отслеживаются git.

**Релиз**: поднять `pluginVersion` в `gradle.properties`, блок в `CHANGELOG.md` (формат — как у соседних), `./build.ps1` и `./build.ps1 -Ml`,
закоммитить ZIP-ы. Страница demo показывается пользователю заново на каждой новой версии — если менять, то `docs/demo.html`.

## 4. Где что лежит: хост (`src/main/kotlin/io/github/golangsupport/`)

| Пакет | Что там | Править, если… |
|---|---|---|
| `lang` | связка хоста с go-psi: `GoFeatures` (переключатель gopls/Built-in по фичам), генераторы (Alt+Insert: конструктор, аксессоры, String, теги, заглушки интерфейса, тест), intentions хоста, postfix-шаблоны, Smart Enter, отступы, live templates, вставка импорта по токенам (`GoImports`), идиомы следующей строки (`GoIdioms`) | нужна новая генерация кода, live template, правило отступов |
| `catalogue` | символы того, что проект может импортировать: сканер stdlib и module cache, файлы каталога (`GoCatalogueFiles.VERSION` поднимать при смене формата), completion по голому имени с автоимпортом | меняется, что видно в completion из неимпортированных пакетов |
| `cli` | поиск `go`, запуск команд (`GoCli`), инструменты (`GoTool`: gopls, dlv, golangci-lint, goimports), `go env`, журнал плагина `GoPluginLog` (файл `~/idea-golang-logs/plugin/`, окно Go \| Plugin Logs) | новая внешняя команда, новая категория лога |
| `build` | меню Go: Build, Vet, Generate, Modules → Build tool window; разбор вывода компилятора; Go Optimization (решения компилятора `-m`) | новое действие сборки, новый формат вывода |
| `run` | run configuration «Go» (`go run` / `go test`), gutter-иконки, аргументы delve (`GoLaunchArguments`), чистая часть отладки (`GoDebugSupport`) | параметры запуска/отладки |
| `testing` | `go test -json` → дерево тестов, консоль, Rerun Failed, окно Go Tests, статусы | формат событий тестов |
| `coverage` | покрытие через подсистему IDE (`go-coverage.xml`, optional) | |
| `format` | gofmt / goimports за Reformat Code и при сохранении | |
| `templates` | New → Go File, New Go Module | новый шаблон файла |
| `lint` | golangci-lint (выключен по умолчанию): разбор JSON, внешний аннотатор, фиксы; `docs/LINT-RULES.md`, `LINTING-CATALOG.md` | |
| `sdk` | окно «Go on This Machine», проверка toolchain при открытии проекта | |
| `help` | `GoPages`: demo/guide во вкладке редактора; клавиши `<kbd data-action="Id">` подставляются из keymap. Тест `GoPagesTest` требует, чтобы каждый пункт меню Go был назван в guide | добавили действие в меню Go — допишите строку в `docs/guide.html` |
| `monitor` | Go Monitor: CPU/память процесса, `GODEBUG`-трейсы рантайма, горутины через delve attach, профили `pprof`/`trace` | |
| `settings` | `GoSettings` (application-level, XML в конфиге IDE) и страницы Settings \| Tools \| Go: корень (toolchain, инструменты, Shared indexes URL), подстраницы в `GoSettingsPages.kt` (Language Server, Debugger, Editor and Completion, Imports, Formatting, Linters) и `GoSettingsToolchainPages.kt` (GOROOT, GOPATH), `GoMlConfigurable` (Smart Completion, только в ML-сборке), страница gopls (генерируется из `gopls api-json`) | новая настройка: поле в `GoSettings` + строка на странице её области + ключи в `GoBundle.properties` и `GoBundle_ru.properties` (`GoBundleTest` сверяет) |
| `sharedindex` | общие индексы `$GOROOT/src` на версию Go: `sharedIndexLocalFinder`, Go \| Build Shared Index for GOROOT…, загрузка по URL. Грузится только при наличии плагина Shared Indexes (`go-shared-indexes.xml`, optional depends). `docs/SHARED-INDEXES.md` | |
| `ci` | headless-инспекции → SARIF (`tools/ci/go-inspect.cmd|sh`, Go \| Export Inspections to SARIF…). `docs/CI.md` | |
| `problems`, `view`, `mod` | окно Problems, узел Dependencies в Project view, разбор go.mod / go.work для UI (`GoModFile`, обёртка над парсером project model) | |
| `lsp` | **content-модуль** gopls (`io.github.golangsupport.lsp.xml`): классы строго в этом пакете, остальной код на него не ссылается; без платформенного LSP-модуля плагин обязан работать | только если включаете gopls-режим |
| `debugger` | свой DAP-клиент: `DapConnection` (framing, запросы, события), `DelveProcess` (`dlv dap` по TCP, порт из первой строки stdout), `GoDebugProcess` (XDebugProcess), фреймы, значения, точки, редакторы выражений; логи `GoDebuggerLogs` (`~/idea-golang-logs/delve/`) | поведение отладчика; обновление delve — `git -C third_party/delve checkout <тег>` |

Ключевые факты об отладчике (проверены вживую) собраны в `CLAUDE.md`, раздел «Отладчик, что важно знать» — читать перед любой правкой `debugger`.

## 5. Где что лежит: go-psi

**`go-psi-core`** (`io.github.golangsupport.lang.*`): `lexer/` (JFlex `Go.flex`), `parser/` (Grammar-Kit `Go.bnf`, `GoParserUtil` для
неоднозначностей — каждая описана в `docs/GRAMMAR.md` со ссылкой на go/parser), `psi/` (интерфейсы и реализации узлов), `stubs/` (стабы
верхнего уровня; `GoFileElementType.STUB_VERSION`), `index/` (stub-индексы: `GoTypesIndex`, функции, методы, экспорты).
Генерация после правки грамматики: `./gradlew.bat :go-psi-core:generateParser :go-psi-core:generateLexer`; golden-файлы обновляются
`-Dgopsi.updateGoldens=true` с ручным просмотром диффа; затем корпусный гейт. Правило: внутри тел функций ничего не стабится, resolve идёт по стабам без загрузки AST чужих файлов.

**`go-psi-semantic`** (`semantic.*`, `project.*`): `project/` — модель проекта (GOROOT, `go.mod`, build-теги, `go list -m`; бинарник `go`
зовётся только здесь и всегда с fallback); `semantic/types` — система типов; `infer` — вывод типов выражений (`GoExpressionTyper`, `GoTypeBuilder`);
`resolve` — разрешение имён; `scope` — области и universe; `check` — проверки (`GoChecker`: ошибки компилятора как в `go vet`/`gc`);
`flow` — поток данных (nil, константные условия, недостижимый код); `cache` — трекеры инвалидации (`GoTrackers`, `GoBodyCache`: для кэшей
типов/resolve только они, не `PsiModificationTracker.MODIFICATION_COUNT`); `api/GoSemanticService` — единственный вход для IDE-слоя. `docs/SEMANTIC.md`, `docs/PROJECT-MODEL.md`.

**`go-psi-ide`** (`ide.*`): по пакету на область, по дескриптору на область в `go-psi-ide/src/main/resources/META-INF/go-psi-ide-*.xml`
(analysis, completion, documentation, editing, editor, formatter, gofix, hierarchy, highlighting, hints, injection[-json|-sql|-sh], inspections,
intentions, navigation, paste, refactoring, refactoring2, rules, spelling, asm, codevision). `plugin.xml` хоста включает их через `xi:include`.
`GoIdeFeatureGate` выключает группы фич, когда пользователь выбрал gopls. Описание того, что есть и как устроено, — `docs/IDE-FEATURES.md`
(таблица по фичам с версиями), чего нет — `docs/FEATURES.md`, формы/формат — `docs/FORMATTER.md`, движок правил — `docs/RULES-ENGINE.md`.

Цифры на момент передачи: 111 инспекций (`go-psi-ide-inspections.xml`), 60 intentions (`go-psi-ide-intentions.xml`) плюс 13 в `plugin.xml`,
описания в `inspectionDescriptions/` и `intentionDescriptions/` (HTML, по имени класса/shortName).

## 6. Рецепты: добавить / убрать

**Инспекция.** Класс в `go-psi-ide/.../ide/inspections/<область>/`, наследник `LocalInspectionTool` с visitor'ом PSI (образец — любая соседняя,
например `GoConstantConditionInspection` для потока данных или простая из `inspections/`). Регистрация — `<localInspection>` в
`go-psi-ide-inspections.xml` (`shortName`, `displayName`, `groupName="Go"`, `level`). Описание — `inspectionDescriptions/<shortName>.html`.
Тест — в `go-psi-ide/src/test/.../inspections/` на `GoSemanticIdeTestBase` с маркерами `<warning descr="…">`/`<error>`; инспекции группы
Go fix — `GoFixInspectionTestBase`; для паритета с GoLand — `GoParityInspectionTestBase`. Убрать: удалить регистрацию, класс, описание, тест, строку в `docs/IDE-FEATURES.md` и упоминание в `guide.html`.

**Intention / quick fix.** Класс в `ide/intentions/`, `<intentionAction>` в `go-psi-ide-intentions.xml`, описание и `before/after.go.template`
в `intentionDescriptions/<Class>/`, тест с `GoIntentionTestSupport`. Текст пункта Alt+Enter должен совпадать с GoLand, если фича парная
(дампы `docs/goland-analysis/dumps/*.txt`).

**Completion** (`go-psi-ide/.../ide/completion/`). Контекст — `GoCompletionContext` (где каретка) и `GoCompletionSemantics` (типы, ожидаемый тип
через `GoExpectedTypes`); кандидаты собирает `GoCompletionContributor` из `GoScopeCandidates`, `GoMemberCandidates`, `GoChainCandidates`,
`GoKeywordCandidates`, `GoSnippets`, `GoSmartLiterals` (литералы по ожидаемому типу, в том числе `func(...) {}`), `GoFillStructCompletion`,
`GoStructTagCompletion`, `GoFormatVerbCompletion`; элемент строит `GoLookupElementFactory`; порядок — `GoCompletionWeigher` (внутри модуля) и
`GoLookupPriority` (общая шкала с каталогом хоста `catalogue`); имена — `GoNameSuggestions`. Тесты на `GoCompletionTestBase` (`complete(...)`,
`select(...)`, `checkInsert`); stdlib в тестах настоящий (`gopsi.goroot`, по умолчанию `C:\Program Files\Go`).

**Действие в меню Go.** `AnAction(), DumbAware`, `getActionUpdateThread = BGT`; регистрация в `plugin.xml` в группу `Go.MainMenu`
(ПКМ в дереве — `Go.ProjectViewPopup`); команды `go` — `GoCli.commandLinesOrNotify { … }` + `GoCli.runInBackground`; строка в `docs/guide.html`
(иначе падает `GoPagesTest`); запись в `ROADMAP.md`.

**Настройка.** Поле в `GoSettings`, строка на странице области в `GoSettingsPages`/`GoSettingsConfigurable`, два ключа в
`messages/GoBundle.properties` и `GoBundle_ru.properties`. На страницах только то, за чем есть реализация.

**Грамматика / типы / resolve.** Только с тестами (golden-файлы парсера, `/*ref*/` `/*def*/` для resolve, `/*T: type*/` для типов), затем
корпусный гейт соответствующего модуля; метрики `testData/metrics/*.json` могут только улучшаться. Любое изменение сериализованной формы —
поднять `STUB_VERSION` и версии индексов.

**Выключить подсистему целиком.** Optional-подсистемы (coverage, copyright, shared indexes, spelling, injection JSON/SQL/sh, ML) подключены
через `<depends optional>` или `xi:include` с `fallback` в `plugin.xml`: достаточно убрать строку. Группы go-psi-ide — через `xi:include`
того же файла: убрать include выключает область целиком (например, `go-psi-ide-gofix.xml` — всю группу Go fix).

**ML Smart Completion.** `docs/ML.md`. Движок — `ml-core/` (копия; обновляется `tools/ml/sync-ml-core.sh <checkout idea-ml-completion>`),
IDE-сторона — `go-psi-ide/.../ide/ml/` (`GoMlCompletionRanker`, `GoMlModels`, `GoMlFeatures`, `GoMlSettings`), страница —
`settings/GoMlConfigurable`. Модели `lm.cml` + `rank.cml` обучаются CLI репозитория idea-ml-completion и подаются сборке `-Pml.models=<dir>`.
`GoMlFeatureParityTest` следит, что признаки экспорта датасета и признаки в IDE совпадают.

## 7. Соглашения

- Kotlin, строки до ~180 символов, плотный стиль, однострочные функции-выражения. Комментарии и KDoc по-английски, коротко, «почему», находки вживую — пометка «seen live».
- Тексты UI — английские, действия в Title Case; тексты страниц настроек — через `GoBundle.message`.
- Блокирующие вызовы не на EDT; PSI только под read action; файлы на EDT читать через `LoadTextUtil`. Платформенные расширения зовут код и на EDT, и в фоне без read action.
- Парсинг вывода и файлов — чистыми функциями (тестируются без процесса). Реальные `go`, `gopls`, `dlv` в тестах не запускаются.
- Чистая логика — JUnit 4 (`@Test`), платформа — `BasePlatformTestCase` (JUnit 3-стиль, `fun testXxx()`), light-проект общий.
- Никакого LSP/gopls внутри `go-psi-*`. Не декомпилировать и не копировать код GoLand; разрешённые источники (go-lang-idea-plugin Apache-2.0, intellij-rust MIT) — с атрибуцией в `NOTICE.md`.
- Одна фича — одна patch-версия в `CHANGELOG.md`; сделанное отмечать в `ROADMAP.md`, планы — `PLAN.md`.

## 8. Известные грабли (коротко; полный список в `CLAUDE.md`)

- Платформа 2026.1 (`sinceBuild = 261`): API LSP-клиента — `LspIntegrationProvider`/`LspClientDescriptor`; split-режим отладчика требует
  старта сессии через `XDebuggerManager.newSessionBuilder(...)`, дескриптор нужен всегда (форки).
- `dlv dap` только по TCP, `--log-dest` нельзя; программу собирает сам delve; delve новее toolchain — `--check-go-version=false`.
- Подмена набираемого символа — только обёрткой `editorTypedHandler` (`GoLayoutTypedHandler` в `lang/GoLayout.kt`), точка расширения не динамическая.
- Code actions у gopls — `triggerKind: Invoked`; применять через `GoplsEdits.apply` (`lsp/GoplsActions.kt`).
- Проверка «Plugin to blame: Go = 0» в логе песочницы после любой живой проверки.
- Kotlin API не новее 2.3 (`apiVersion`/`languageVersion`, stdlib из платформы).

## 9. Документы

| Файл | О чём |
|---|---|
| `CLAUDE.md` | правила репозитория, команды, устройство, грабли (самый полный «readme») |
| `CHANGELOG.md` | история по версиям; раздел «go-psi» — история парсера |
| `ROADMAP.md` / `PLAN.md` / `MIGRATION.md` | сделано / дальше / переход с gopls на PSI по шагам |
| `docs/IDE-FEATURES.md`, `docs/FEATURES.md` | что есть в go-psi-ide и как устроено / чего ещё нет |
| `docs/PSI-README.md` → `GRAMMAR.md`, `SEMANTIC.md`, `PROJECT-MODEL.md`, `PSI-PLAN.md`, `PERF-BACKLOG.md`, `TESTING.md`, `API.md` | парсер и анализатор |
| `docs/FORMATTER.md`, `RULES.md`, `RULES-ENGINE.md`, `LINT-RULES.md`, `LINTING-CATALOG.md` | форматтер, движок правил, линтеры |
| `docs/CI.md`, `BUILD-OFFLINE.md`, `SHARED-INDEXES.md`, `ML.md`, `INLINE-SUGGESTIONS.md` | headless-инспекции и SARIF, сборка без интернета, индексы GOROOT, ML, inline-подсказки |
| `docs/goland-analysis/` | живая разведка GoLand: дампы меню, Alt+Enter, инспекций, completion; эталон поведения |
| `docs/demo.html`, `docs/guide.html` | страницы для пользователя (внутри IDE) |
| `docs/AGENT_BRIEFS.md` | как ставились задачи агентам (история, формат брифов) |

## 10. Инструменты (`tools/`)

| Каталог | Что |
|---|---|
| `ci/` | `go-inspect.cmd|sh` (headless-инспекции → SARIF), `Jenkinsfile`, `nexus.init.gradle`, `truststore.sh` |
| `vet/` | `compare.py` — гейт «наши находки ⊇ go vet» по SARIF (`uvx pytest tools/vet`) |
| `ui-robot/` | Remote Robot сценарии, `desktop.ps1` (настоящая мышь/клавиатура/снимки), WSL-песочница, проекты для проб |
| `gates.sh`, `benchmark/` | корпусные гейты и бенчмарки с коротким выводом |
| `gopls/`, `dlv-dap/` | зонды: что отвечает сервер / адаптер без IDE |
| `icons/generate.py` | все SVG плагина (править фигуры там, потом запускать) |
| `ml/` | синхронизация `ml-core` |
| `lint-rules/`, `astdump/`, `portshorttest/` | вспомогательные |

Python-инструменты запускаются через `uv`/`uvx` (системный Python 3.14; если нет wheel — `uvx --python 3.12`).
