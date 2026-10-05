# Миграция: от gopls к своему PSI (go-psi) внутри idea-golang-support

Составлен 2026-10-02 после переноса кода go-psi в этот репозиторий (`82c34a1`). Анализ, на котором он основан, —
`docs/PSI-MIGRATION-history.md` (инвентарь IGS, таблица точек расширения, риски); здесь — только порядок действий.
Статус шага отмечать прямо здесь (`[ ]` → `[x]` с датой и коммитом), сделанное переносить в `ROADMAP.md`, историю — в `CHANGELOG.md`
(про PSI-модули — в `CHANGELOG.md (раздел «go-psi»)`).

Обозначения: **IGS** — корневой модуль плагина (`src/main/kotlin/io/github/golangsupport`), **PSI** — подпроекты
`go-psi-core` / `go-psi-semantic` / `go-psi-ide` (пакеты `lang.*`, `semantic.*`, `project.*`, `ide.*` того же корня),
**lsp** — content-модуль `io.github.golangsupport.lsp` (gopls).

## Принципы

1. **Один переключатель на фичу.** Каждая фича, которую сегодня даёт gopls, переезжает за своим флагом `GOPLS | NATIVE`
   в `GoSettings`; значение по умолчанию меняется на `NATIVE` только после проверки роботом и вживую. Оба пути живут
   рядом, пока нативный не стал умолчанием хотя бы на один релиз; потом gopls-путь удаляется.
2. **Исключающие переключатели, не порядок EP.** Один источник на фичу в один момент времени: gopls-обработчик
   возвращает `null`, нативный contributor выходит сразу, если флаг не его. `order="first"` — не средство дедупликации.
3. **Атомарные шаги.** Шаг либо целиком в одном коммите с зелёными гейтами, либо не начат. Подмена парсера (шаг 4)
   — самый большой такой шаг: structure view, breadcrumbs, folding, Go to Symbol, gutter run-иконок, навигация gopls —
   всё висит на старом `GoDeclaration`.
4. **Гейты перед «готово».** Для каждого шага: `./gradlew.bat test buildPlugin -q --offline`; корпусный гейт
   затронутого слоя (`:go-psi-core:corpusTest`, `:go-psi-semantic:corpusTest`, `--no-configuration-cache`);
   `checkKotlinAbi`; робот (`tools/ui-robot`, сценарии в `playground/`), 0 «Plugin to blame: Go» в `idea.log`.
   Регрессии производительности ловят `./gradlew.bat benchmark` (пороги `testData/benchmark/thresholds.json`) и
   `tools/ui-robot/autotest.py --perf` (P1–P9, цифры в `CHANGELOG.md (раздел «go-psi»)`, запись «Performance wave»).
5. **Иконки, цвета, настройки, тексты — из IGS.** `GoIcons`, `colorSchemes/GoDefault.xml`/`GoDarcula.xml` с ключами
   `GO_*`, `GoSettings` и страницы Settings | Tools | Go, `GoBundle` (en/ru). PSI-модули своих не заводят; что есть в
   `go-psi-core/src/main/resources/icons` и в `ide.highlighting.GoColorSettingsPage` — удаляется на шаге 2.
6. **Жёсткие правила PSI-модулей** — раздел «go-psi» в `CLAUDE.md` (нет LSP внутри PSI, нет кода GoLand, тесты на
   грамматику/стабы/типы, `STUB_VERSION`, трекеры `GoTrackers` вместо `PsiModificationTracker`).

## Карта шагов

| # | Шаг | Объём | Зависит от | Статус |
|---|---|---|---|---|
| 0 | Перенос кода как библиотечных модулей | — | — | [x] 2026-10-02, `82c34a1` |
| 1 | Переключатели фич: `GoFeatures` + настройки + чтение в lsp-кастомайзерах | день | — | [x] 2026-10-02, ветка `migration` |
| 2 | Единые `GoLanguage`/`GoFileType`; переименование старых текстовых помощников | день | — | [x] 2026-10-02, ветка `migration` |
| 3 | Подключение модулей в плагин: Gradle + `xi:include`, без регистрации парсера | полдня | 2 | [x] 2026-10-02, ветка `migration` |
| 4 | Подмена парсера + мост `GoDeclaration` + токены (атомарно) | 2–3 дня | 1, 3 | [x] 2026-10-02, ветка `migration` |
| 5 | Полный гейт шага 4: тесты, корпус, робот, живая проверка | день | 4 | [x] 2026-10-02 (робот); живая проверка — за пользователем |
| 6 | Stub-индексы вместо `GoDeclarationIndex` и `GoExportsIndex` | 1–2 дня | 5 | [x] 2026-10-02, ветка `migration` |
| 7 | Project model `project.api` поверх `cli`/`mod`/`settings`; library roots | 2 дня | 5 | [x] 2026-10-02 |
| 8 | Фичи с gopls на PSI, по одной за флагом (8a–8k) | по фиче | 6, 7 | [x] 2026-10-02 |
| 9 | Текстовые инструменты IGS на PSI (бенефициары) | по инструменту | 5 | [x] 2026-10-02 |
| 10 | Удаление старого: сканер, text-lexer, gopls-дубли, docs/psi → docs | день | 8 | [x] 2026-10-04 (лицензия MIT, `NOTICE.md`) |
| 11 | Волны `docs/FEATURES.md` (новые фичи поверх PSI) | вехи | 8 | [x] волны 1–7 (shared indexes GOROOT — 0.2.183, 2026-10-05) |
| 12 | gopls опционален → удалён (две вехи) | вехи | 8, 11 | [x] 12.1 (0.2.82); 12.2 не делаем |
| 13 | Собственный анализ вместо линтеров; линтеры по запросу (этапы A–C) | 2+3+4 недели | 5 | [x] A (0.2.181), B, C, линтеры по запросу |

Критический путь: 2 → 3 → 4 → 5 → 6/7 → 8. Шаг 1 делается параллельно и нужен к началу 8; шаг 9 можно вести
параллельно с 6–8 по одному инструменту. Шаг 13 начинается после 5 и идёт параллельно с 6–8: ему нужны только
PSI и типы. Шаг 12 — две вехи, которые закрывают 8 и 11/13 соответственно.

---

## Шаг 1. Переключатели фич

**Зачем.** Без них нельзя держать оба пути рядом и возвращаться к gopls, если нативная фича подвела у пользователя.

**Как.**
- `settings/GoSettings.kt`: `enum class GoFeatureSource { GOPLS, NATIVE }` и поля
  `syntaxErrors`, `diagnostics`, `completion`, `hover`, `navigation`, `usages`, `rename`, `semanticColors`,
  `codeVision`, `formatting` (`by enum(GoFeatureSource.GOPLS)`; `formatting` остаётся на существующем
  `GoFormatter`, добавляется значение `NATIVE`). Умолчания меняются по мере прохождения 8a–8k.
- Новый сервис `lang/GoFeatures.kt` в IGS: `fun native(feature: GoFeature, project: Project): Boolean` —
  `false` при `DumbService.isDumb` для фич, которым нужны индексы (всё, кроме syntaxErrors и formatting), и когда
  языковой сервер выключен — `true` для всего (gopls нет, работает что есть).
- Страница `GoLanguageServerConfigurable`: блок «Source of …» со строкой на фичу; Apply уже зовёт
  `GoLanguageServerControl.restartAll`, дескриптор пересобирается, кастомайзеры читают новые значения.
- `lsp/GoplsIntegration.kt`: кастомайзеры `lspCustomization` становятся условными —
  `diagnosticsCustomizer` (новый: `LspDiagnosticsSupport` с фильтром по `source`, см. 8g), `completionCustomizer`
  (`GoplsCompletionSupport` или `LspCompletionDisabled`), `hoverCustomizer`, `renameCustomizer`,
  `semanticTokensCustomizer`, `documentHighlightsCustomizer`, `codeLensCustomizer`. Обработчики
  `GoplsGotoDeclarationHandler`, `GoplsUsageSearcher`, `GoplsImplementationSearch`, `GoplsTargetElementEvaluator`,
  `GoplsTypeDeclarationProvider`, `GoplsExpressionTypeProvider`, `GoplsGotoSuperHandler`,
  `GoplsHighlightUsagesHandlerFactory`, `GoplsImplementationLineMarkerProvider`, code vision — первая строка:
  `if (GoFeatures.native(NAVIGATION/USAGES/..., project)) return null`.
- Тексты — `GoBundle.properties` + `_ru`; `GoBundleTest` проверяет паритет ключей.

**Проверка.** `GoSettingsTest`/`GoBundleTest`; робот: `setting <Name> NATIVE` → `state` показывает перезапуск gopls;
при выключенном сервере все `native(...)` истинны (JUnit, без платформы).

## Шаг 2. Единые `GoLanguage`/`GoFileType`, переименование клэшей

**Зачем.** Две регистрации языка «Go» и типа файла «Go» в одном плагине невозможны; у IGS и PSI совпадают FQN
`io.github.golangsupport.lang.GoLanguage` и `GoFileType`, а ещё ~25 простых имён (список — отчёт переноса,
`docs/PSI-MIGRATION-history.md` 4.3 и ниже).

**Как.**
- Удалить из `go-psi-core`: `lang/GoLanguage.kt` (объекты `GoLanguage`, `GoFileType`), `lang/GoIcons.kt`,
  `resources/icons/*`. Перенести файл IGS `lang/GoLanguage.kt` (объекты `GoLanguage`, `GoFileType`, класс `GoFile :
  PsiFileBase`) **физически в `go-psi-core`**, пакет не менять: `io.github.golangsupport.lang`. Иконку брать
  `IconLoader.getIcon("/icons/go.svg", GoFileType::class.java)` — ресурс живёт в корневом модуле, после шага 3 это
  один jar; в тестах core иконки нет, `IconLoader` отдаёт пустую, это допустимо. Старый `GoFile : PsiFileBase` из
  этого файла удалить: его `isTestFile`/`TEST_SUFFIX` уже есть в `lang.psi.GoFile`; 41 файл IGS меняет один импорт
  `lang.GoFile` → `lang.psi.GoFile` (на шаге 4, когда парсер сменится; до него старый класс пусть лежит в IGS под
  именем `lang.GoTextFile`, чтобы компилировалось).
- Переименовать в IGS текстовые помощники, которые останутся: `lang.GoLexer` → `GoTextLexer`,
  `lang.GoTokenType`/`GoTokenTypes` → `GoTextTokenType`/`GoTextTokens` (потребители: `GoIndent`, `GoEditing`,
  `GoDeclarations`, `run.GoDebugSupport`, `testing.GoSubtests`, `GoSyntaxHighlighter`, аннотатор). Это не PSI-листья,
  они сканируют текст, и часть из них переживёт миграцию (indent, подтесты по тексту) до шага 9.
- Остальные совпадения простых имён (`GoBraceMatcher`, `GoCommenter`, `GoFoldingBuilder`, `GoStructureViewFactory`,
  `GoGotoClassContributor`, `GoSyntaxHighlighterFactory`, …) живут в разных пакетах и конфликтуют только в
  `plugin.xml`: на шаге 4 регистрация переводится на PSI-класс, старый удаляется. `GoModFile`/`GoModule`/`GoRequire`/
  `GoReplace` (`mod` против `project.api`) — переименовать PSI-варианты в `GoModFileModel`/`GoModuleInfo`/… на шаге 7
  или оставить разные пакеты; `GoValue` (`debugger` vs `lang.psi`) — не трогать, пакеты разные.
- `ide.highlighting.GoColorSettingsPage`, `GoHighlightingColors`: удалить; карта токенов → ключи
  `lang.GoSyntaxHighlighter` IGS (внешние имена `GO_*` совпадают со схемами и с `GoColorSchemeTest`; недостающие
  ключи — `GO_IDENTIFIER`, `GO_RUNE` — добавить в схемы). `ide.annotator.GoSemanticHighlightingAnnotator` берёт
  ключи из `GoSemanticColors` IGS (они уже рассчитаны на gopls semantic tokens: типы, функции, переменные, поля…).

**Проверка.** `:go-psi-core:test` (`GoFileTypeTest`), корневой `GoPluginTest`, `GoColorSchemeTest`;
`rg "object GoLanguage|object GoFileType"` — по одному определению; `test buildPlugin --offline`.

## Шаг 3. Подключение модулей в плагин

**Как.**
- Корневой `build.gradle.kts`: `pluginComposedModule(implementation(project(":go-psi-core")))`, то же для
  `:go-psi-semantic` и `:go-psi-ide` — классы попадают в главный jar (дескриптор v1 без `<content>`-модулей не
  грузит `lib/modules`). `lsp`-модуль видит эти классы (тот же classloader), обратное запрещено.
- `plugin.xml`: `<xi:include href="/META-INF/go-psi-core.xml"/>`, `go-psi-semantic.xml`, но **без** строк
  `fileType` (её даёт IGS) и без `lang.parserDefinition` (переезжает на шаге 4). Для этого в core-xml вынести
  `fileType`+`parserDefinition` в отдельный `go-psi-core-language.xml`, который IGS не включает. `go-psi-ide.xml`
  пока не включать: его EP включаются по одному на шаге 8 (ide-xml разбить на файлы по фичам:
  `go-psi-ide-editor.xml`, `-navigation.xml`, `-completion.xml`, `-inspections.xml`, `-formatter.xml`,
  `-documentation.xml`, `-refactoring.xml`).
- Проверить, что stub-индексы и `stubElementTypeHolder` из core-xml регистрируются без парсера: пока
  `lang.parserDefinition` старый, стабы не строятся (файловый тип тот же, `IStubFileElementType` другой) — это
  ожидаемо; индексы просто пусты.

**Проверка.** `buildPlugin`: в `lib/idea-golang-support-*.jar` есть `io/github/golangsupport/lang/psi/`,
`semantic/`; `verifyPlugin` (добавить в сборку IGS с `intellijPlatformNext` из каталога версий);
`runIde --args=playground` стартует, `idea.log` без ошибок регистрации EP; тесты IGS зелёные.

## Шаг 4. Подмена парсера, мост `GoDeclaration`, токены (атомарно)

**Зачем.** Всё, что в IGS работает по дереву, работает по `GoDeclaration` (одна PSI-нода на объявление, которое
нашёл текстовый сканер `GoDeclarations`) и по грубым токенам `GoTokenTypes`. После смены `lang.parserDefinition`
эти классы перестают существовать как PSI, поэтому их потребителей надо переписать в том же коммите.

**Как.**
1. `plugin.xml`: `lang.parserDefinition language="Go"` → `io.github.golangsupport.lang.parser.GoParserDefinition`;
   `lang.syntaxHighlighterFactory` → PSI-вариант (лексер `lang.lexer.GoLexer`, ключи IGS); `lang.psiStructureViewFactory`,
   `breadcrumbsInfoProvider`, `lang.foldingBuilder`, `lang.findUsagesProvider`, `gotoClassContributor`,
   `gotoSymbolContributor`, `lang.commenter`, `lang.braceMatcher`, `lang.quoteHandler` → классы из
   `go-psi-ide` (`go-psi-ide-editor.xml` включается здесь). `annotator` IGS (`GoIdentifierAnnotator`) остаётся,
   переписанный на PSI (см. ниже); PSI-аннотатор семантики подключается на 8d.
2. Удалить `lang/GoPsi.kt` (`GoParserDefinition`, `GoTreeBuilder`, `GoElementTypes`, `GoDeclaration`, structure/
   breadcrumbs). Сканер `GoDeclarations` и его `GoFileStructure`/`GoDeclarationInfo` **остаются** как текстовый
   инструмент до шага 10: часть потребителей (run-конфигурации по строкам, подтесты) переводится позже.
3. Мост `lang/GoDeclarationPsi.kt`: `of(element: PsiElement): GoDeclarationInfo?` — для `GoFunctionDeclaration`,
   `GoMethodDeclaration`, `GoTypeSpec`, `GoVarDefinition`/`GoConstDefinition` уровня пакета, `GoFieldDefinition`,
   `GoMethodSpec` возвращает `GoDeclarationKind` и ищет `GoDeclarationInfo` по **смещению имени**
   (`nameIdentifier.textRange.startOffset`), а не по началу элемента: док-комментарии входят в диапазон PSI.
   Обратный вызов `psiAt(file, info): GoNamedElement?` — по смещению имени.
4. Переписать потребителей (список — `rg "GoDeclaration\b|GoStructure\.|GoDeclarations\."`, 30 файлов):
   - *IGS lang:* `GoNavigation` (Go to Class/Symbol — на этом шаге через мост, на шаге 6 на stub-индексы),
     `GoTestNavigation` (`GoTestFinder`/`GoTestCreator`: функция ↔ `TestXxx`), `GoProjectInterfaces`,
     `GoImplementInterface`, `GoInterfaces`, `GoGenerateActions`, `GoIntentions`, `GoImports`, `GoIdioms`,
     `GoKeywordTemplates`, `GoExpectedTypes`, `GoStructTags`, `GoValueCompletion`, `GoEditing`,
     `GoSyntaxHighlighter`/аннотатор (проверки `elementType == IDENTIFIER`, `in COMMENTS/STRINGS` → `GoTypes.IDENTIFIER`,
     `GoTokenSets.COMMENTS`, `GoTokenSets.STRING_LITERALS`).
   - *catalogue:* `GoCatalogue`, `GoCatalogueCompletion`, `GoInterfaceSources` — через мост; на шаге 6 на стабы.
   - *lsp:* `GoplsNavigation` (`GoplsTargets`, `GoplsGotoDeclarationHandler`, `GoplsUsageSearcher`,
     `GoplsImplementationSearch`), `GoplsTypes` (`GoplsTargetElementEvaluator`, `GoplsTypeDeclarationProvider`,
     `GoplsExpressionTypeProvider`), `GoplsGotoSuper`, `GoplsLineMarkers`, `GoplsCodeVision`: цель, на которую
     вешается результат gopls, — `GoNamedElement` PSI (`nameIdentifier`), а не `GoDeclaration`.
   - *run/testing/debugger/templates:* `GoRunConfigurationProducer`, `GoRunConfigurationGenerator`,
     `GoRunLineMarkerContributor` (контекст — `GoFunctionDeclaration` с именем `main`/`TestXxx`/`BenchmarkXxx`/`FuzzXxx`),
     `GoDebugSupport`, `GoFunctionBreakpoints`, `GoSubtests`, `GoTestConsole`, `GoTestExplorer`, `GoFileTemplates`.
   - *completion IGS:* `GoKeywordCompletionContributor`, `GoValueCompletionContributor`,
     `GoCatalogueCompletionContributor`, `GoStructTagCompletionContributor`, `GoReturnCompletionContributor`,
     `lsp.GoplsPackageCompletionContributor`, `GoplsArgumentTypedHandler` — проверки позиции по PSI-листьям; логика
     пока без изменений.
5. Индексы: `GoDeclarationIndex` и `GoExportsIndex` — файловые, строятся по тексту, от парсера не зависят:
   оставить до шага 6. Поднять их `VERSION` не нужно.
6. Lazy-тела: `GoLazyBlockElementType` даёт инкрементальный репарс тел; `GoLineIndentProvider`,
   `GoEnterBetweenBracketsHandler`, `GoLayoutTypedHandler`, `GoDocCommentTypedHandler` работают по документу и
   текстовому лексеру — не трогать.

**Проверка.** Golden-тесты парсера внутри IGS (`:go-psi-core:test`), `GoDeclarationsTest`, `GoEditingTest`,
`GoInterfacesTest`, `GoTestNavigationTest`, `GoKeywordCompletionTest`, `GoValueCompletionTest`, `GoColorSchemeTest`;
робот: `structure.js`, `markers.js` (run-иконки), Ctrl+click в gopls-навигации, code vision, снимок редактора с
подсветкой; `idea.log` без `PsiInvalidElementAccessException` и без «Plugin to blame».

## Шаг 5. Полный гейт шага 4

`test buildPlugin --offline`; `:go-psi-core:corpusTest --no-configuration-cache` (лексер/AST diff/fuzz/стабы — 0
расхождений); `benchmark` (пороги не хуже); `runIdeForUiTests` на копии `playground` — весь список `playground/README.md`;
`tools/ui-robot/autotest.py --perf` — P1/P6/P7 не хуже цифр в `CHANGELOG.md (раздел «go-psi»)`; живая проверка пользователем:
печать в `net/http/server.go`, Structure, folding, Go to Symbol, run-иконки, gopls-навигация.

## Шаг 6. Stub-индексы вместо файловых

**Как.**
- Go to Class/Symbol: `ide.navigation.GoGotoClassContributor`/`GoGotoSymbolContributor` (go-psi-ide, по
  `GoTypesIndex`/`GoAllPublicNamesIndex`/`GoAllPrivateNamesIndex`) вместо `lang.GoNavigation`.
- `GoProjectInterfaces` (Implement Interface: интерфейсы проекта) → `GoTypesIndex` + `GoSemanticService.methodsOf`;
  `catalogue.GoExportsIndex` (экспорт проекта для каталога completion) → `GoAllPublicNamesIndex` с scope проекта;
  `GoInterfaceSources` → stub-PSI интерфейсов.
- Удалить `lang.GoDeclarationIndex` (`golang.declarations`) и `catalogue.GoExportsIndex`; потребители мостов из
  шага 4 переводятся на stub-PSI напрямую, мост `GoDeclarationPsi` остаётся только для текстовых инструментов.
- Resolve должен работать по стабам без загрузки AST чужих файлов: тесты с `AstLoadingFilter` уже есть в PSI-модулях;
  добавить такой же для `GoProjectInterfaces` и каталога.

**Проверка.** Портированный `GoExportsIndexTest`, `GoInterfacesTest`; робот: Implement Interface, каталог в completion,
Go to Symbol по проекту и по GOROOT после шага 7.

## Шаг 7. Project model и library roots

**Как.**
- `project.api` (PSI): `GoToolchainProvider`/`GoToolchainInfo` (GOROOT, версия, GOPATH, GOMODCACHE, GOOS/GOARCH,
  теги), `GoModuleGraphProvider`, `GoPackageResolver`. В IGS реализовать `GoToolchainProvider` поверх
  `cli.GoCli.findExecutable` + `cli.GoEnvironment` (`go env -json`), теги — `GoSettings.buildTags`, GOOS/GOARCH —
  новые поля настроек (сейчас их нет) с умолчанием host; зарегистрировать реализацию вместо `DefaultGoToolchainProvider`
  (у PSI свой чистый fallback по PATH/`C:\Program Files\Go` — оставить как запасной).
- Модульный граф: `DefaultGoModuleGraphProvider` PSI читает `go.mod`/`go.work`/`go.sum` сам (чистый парсер
  `project.impl.GoModFile`) и считает MVS; `mod.GoModulesService`/`mod.GoModFile` IGS дают то же для UI
  (Dependencies, tidy-баннер). Два парсера go.mod — лишнее: либо `mod.GoModFile` IGS делегирует в PSI-модель,
  либо наоборот; выбрать PSI-модель (у неё тесты MVS и корпус `testData/project`), `mod.*` оставить тонкой обёрткой.
  Изменения `go.mod` → `GoProjectModelTracker` (PSI) бампается из `GoModSaveListener`.
- **Library roots — решение.** IGS намеренно не делал GOROOT и module cache библиотечными корнями (дешёвая
  индексация, каталог вместо индекса). Resolve и стабы требуют проиндексированных зависимостей. Решение: PSI
  `GoRootsProvider` (`AdditionalLibraryRootsProvider`) включается для GOROOT/src (без `cmd/`) и для модулей из
  build list текущего проекта (не всего module cache). Цена измерена: GOROOT ~6–7 с CPU в один поток, 3–5 с
  при параллельной индексации (`docs/PERF-BACKLOG.md` п. 8), повторное открытие — из индексов на диске.
  `view.GoDependenciesTreeProvider` (узел Dependencies) остаётся, но файлы зависимостей теперь ещё и в индексах.
- Go | Reanalyze (`build.GoReanalyzeAction`) дополнительно зовёт `GoTrackers.invalidateAll()`.
- `go.mod`/`go.work` как язык: у IGS свой `GoModLanguage` + подсветка + completion; у PSI — только модель. Язык
  остаётся IGS, PSI не регистрирует go.mod (так и в анализе).

**Проверка.** Фикстуры `testData/project` (`:go-psi-semantic:test` project-пакет), `GoToolingTest`, `GoModFileTest`
IGS; робот: открыть `playground` с зависимостью, Go to Declaration в `fmt.Println` ($GOROOT) и в зависимость;
индексация при первом открытии (`--perf` P7) — записать цифру в CHANGELOG.

## Шаг 8. Фичи с gopls на PSI (по одной, за флагом)

Порядок — по ценности и по риску. У каждой фичи: что подключить из PSI (EP из соответствующего `go-psi-ide-*.xml`),
что отключить у gopls, чем проверить. Умолчание переключается на `NATIVE` отдельным коммитом после робота и живой
проверки; через релиз gopls-путь удаляется (шаг 10).

- **8a. Синтаксические ошибки.** PSI всегда (`PsiErrorElement`); у gopls фильтровать диагностики с `source ==
  "syntax"`/`"compiler"` и сообщениями парсера (`diagnosticsCustomizer`), иначе двойное подчёркивание. Проверка:
  файл с незакрытой скобкой — одна ошибка; `GoLazyBodyTest` на инкрементальный репарс.
- **8b. Structure, breadcrumbs, folding, Go to Symbol.** Уже PSI после шага 4/6; флага нет.
- **8c. Навигация и usages** (`navigation`, `usages`): `typeDeclarationProvider`, `definitionsScopedSearch`,
  `codeInsight.gotoSuper`, `targetElementEvaluator`, `highlightUsagesHandlerFactory`, `lang.findUsagesProvider`
  из `go-psi-ide-navigation.xml`; references PSI дают Go to Declaration без `gotoDeclarationHandler`. gopls:
  `GoplsGotoDeclarationHandler`, `GoplsUsageSearcher`, `GoplsImplementationSearch`, `GoplsTargetElementEvaluator`,
  `GoplsTypeDeclarationProvider`, `GoplsGotoSuperHandler`, `GoplsHighlightUsagesHandlerFactory` возвращают `null`
  при `NATIVE`; `documentHighlightsCustomizer` → disabled. Проверка: robot `targets.js`, Find Usages по проекту и
  в GOROOT, `GoImplementationsAstLoadingTest`.
- **8d. Семантические цвета** (`semanticColors`): `annotator` `ide.annotator.GoSemanticHighlightingAnnotator` с
  ключами `GoSemanticColors`; gopls `semanticTokensCustomizer` → `LspSemanticTokensDisabled`. `GoIdentifierAnnotator`
  IGS (цвета по текстовым правилам) остаётся только для `GOPLS`-режима без сервера. Проверка: `GoPackageColoursTest`,
  снимок редактора роботом (highlights.js не видит аннотаторы в новом UI — смотреть снимок).
- **8e. Completion** (`completion`): `ide.completion.GoCompletionContributor` (`go-psi-ide-completion.xml`) вместо
  `GoplsCompletionSupport` (`completionCustomizer` → `LspCompletionDisabled`) и `GoplsPackageCompletionContributor`.
  Собственные contributor'ы IGS (keyword templates, values, catalogue, struct tags, return, idioms) остаются, но
  дедуплицируются по lookup string с PSI-списком и получают PSI-входы (шаг 9); ранжирование по ожидаемому типу —
  `GoExpectedTypes` → `semantic.api` (`expectedTypeAt`). Настройки `completion*` сохраняются. Проверка:
  `GoKeywordCompletionTest`, `GoValueCompletionTest`, go-psi completion-тесты, робот (completion после `.`,
  неимпортированный пакет, snippets), `--perf` P3.
- **8f. Hover / quick doc, parameter info** (`hover`): `psiTargetProvider`-документация и `codeInsight.parameterInfo`
  (`go-psi-ide-documentation.xml`); gopls `hoverCustomizer` → disabled, `GoplsArgumentTypedHandler` — проверить, что
  не дублирует parameter info. Проверка: робот Quick Documentation на `http.Handler`, Ctrl+P в вызове.
- **8g. Диагностики** (`diagnostics`): 10 инспекций `go-psi-ide-inspections.xml` + quick fixes; у gopls
  `diagnosticsCustomizer` фильтрует по `source`: при `NATIVE` отбрасывать `compiler`/`syntax`/типовые ошибки, но
  **оставлять анализаторы** (`staticcheck`, `vet`-класс: `printf`, `unusedparams`, …) — у PSI их нет до волны 2
  `FEATURES.md`. `build.GoBuildProblemsAnnotator.shouldShow()` учитывает флаг. Проверка: `broken.go` робота даёт
  тот же набор проблем, что в go-psi сценарии; нет двойных подчёркиваний; `:go-psi-semantic:corpusTest` 0 диагностик
  на GOROOT.
- **8h. Rename** (`rename`): `renamePsiElementProcessor`, `lang.refactoringSupport`, `namesValidator`
  (`go-psi-ide-refactoring.xml`); gopls `renameCustomizer` → disabled. Проверка: робот rename локальной, поля, метода
  с интерфейсом; `GoRenameTest`.
- **8i. Code vision и gutter реализаций** (`codeVision`): `GoImplementationLineMarkerProvider` PSI вместо
  `GoplsImplementationLineMarkerProvider`; usages/implementations code vision — PSI-провайдеры (их ещё нет:
  `FEATURES.md` §1 codeLens, S) — до их появления code vision остаётся gopls (`codeLensCustomizer` условный).
- **8j. Форматирование** (`GoFormatter.NATIVE`): `lang.formatter` + `langCodeStyleSettingsProvider` PSI
  (`go-psi-ide-formatter.xml`; gofmt-совместимость доказана корпусом 4621/4621) как ещё один вариант рядом с gofmt/
  goimports/golangci-lint fmt; `GoFormattingService` при `NATIVE` не запускает процесс; `formattingCustomizer` → disabled.
  Умолчание остаётся `GOFMT`, пока `--perf` P5 не покажет выигрыш и робот — идентичный результат.
- **8k. Остаётся на gopls:** inlay hints (до волны 1 `FEATURES.md`), рефакторинги и code actions gopls
  (`GoplsIntention*`, fill struct, organize imports — у PSI свой Optimize Imports, выбрать один), vulncheck,
  upgrades, меню gopls, лог и виджет статуса. Когда всё выше на `NATIVE`, gopls становится опциональным
  (`languageServerEnabled=false` — полноценная работа без него).

## Шаг 9. Текстовые инструменты IGS на PSI

Каждый — отдельный коммит с тестом; порядок по пользе:
- `lint.GoSignatureProvider`: нативная реализация через `semantic.api.signatureOf(GoCallExpr)` раньше
  `GoplsSignatureProvider` (без round-trip к gopls) — число результатов для фиксов линтера.
- `lang.GoExpectedTypes` → `semantic.api.expectedTypeAt`; `GoIdioms.returnValues` и `GoReturnCompletionContributor`
  → типы результатов охватывающей функции из PSI.
- `GoImplementInterface`, `GoProjectInterfaces`, `catalogue.GoInterfaceSources` → method sets из стабов/`GoLookup`.
- `GoStructTags`, `GoFieldAlignment` (есть `GoSizes` в PSI), `GoGenerators` (конструктор, аксессоры, String, заглушки) →
  структуры из PSI вместо regex.
- `GoKeywordTemplates` (переменные в области видимости для `for range`) → `GoScopes`.
- `run.GoDebugSupport` (`GoHoverExpression`, `GoInlineValues`, `GoBreakpointLines`), `testing.GoSubtests` → PSI
  вместо токенов; `GoRunLineMarkerContributor`/producer — уже PSI после шага 4.
- `GoIdentifierAnnotator` — удаляется после 8d.

**Дополнения шага 9 (2026-10-02, по просьбе пользователя), в работе у агентов 9-S/9-F/9-R (Opus):**
- Переключатель «Code actions» (`GoFeature.CODE_ACTIONS`, гейт `GoIdeFeature.CODE_ACTIONS`) — для intentions, переписывающих код по типам; одноимённые
  действия gopls и текстовые intentions IGS в native-режиме прячутся (commit `a152dd9`).
- `semantic.api`: `expectedTypeAt(expression)` (цель присваивания, аргумент по позиции, `return` по индексу, элемент/ключ/поле литерала, второй операнд,
  элемент канала при `ch <- v`, `case`, условия → `bool`) и `enclosingResultTypes(element)`; `ide.completion.GoExpectedTypes` делегирует.
- Intentions на PSI в go-psi-ide `ide.intentions`: Fill all fields / Fill required fields, Fill return values, Fill switch (`iota`-перечисления,
  type switch по реализациям интерфейса), **Fill select** (`<-ctx.Done()` для `context.Context` в scope, `<-t.C` для `*time.Timer`/`*time.Ticker`,
  `v := <-ch` для каналов на приём, `ch <- zero` для send-only, `<-time.After(d)` при `time.Duration` в scope, вариант с `default`), Handle error
  (`if err != nil { return zeros, err }`), Wrap error (`fmt.Errorf("f: %w", err)`). Общие помощники `GoZeroValues`, `GoScopeValues`.
- Умный `return` на PSI (`lang.GoReturnValues`): переменная нужного типа из scope (`err` для `error`), иначе нулевое значение; второй вариант с `%w`.
  Нативный `GoSignatureProvider` для линтера через `calleeSignature`.
- Серый текст (`GoInlineIdiomsProvider`, Tab принимает) по типам: после `select {` — кейсы из scope (ctx, таймеры, каналы); после `switch x {` над
  перечислением — все `case` по константам, над интерфейсом — `case *T:` по реализациям; после `for {` с каналом в scope — `select` внутри;
  `defer x.Close()` только при `Close() error` в method set, `defer mu.Unlock()` только после `Lock()`, `if err != nil` только когда последний результат —
  `error`. Каналы в серый текст не добавляем (закрытие — дело отправителя).
- `make` и каналы: completion внутри `make(` по ожидаемому типу (`chan T`, `[]T, 0, len(x)`, `map[K]V`), `v, ok := <-ch` после `<-ch`; шаблоны
  ключевых слов по scope: `make(chan T)` / буферизованный / `make([]T, 0, n)` / `make(map[K]V)`, `for v := range ch`, `close(ch)` / `defer close(ch)`
  для канала, созданного в этой функции и не закрытого; шаблоны `select` и «select loop» (`for { select { … } }`) по scope вместо фиксированного `select (+ctx)`.
- Структуры и интерфейсы на PSI: `GoStructPsi`, Implement Interface с method set из `GoSemanticService.methodsOf` для проекта, GOROOT и зависимостей
  (индексы шага 7), дисковый сканер — только как fallback вне индексов; генераторы, теги, выравнивание — поля и размеры из PSI/`GoSizes`.
- Отладчик и тесты на PSI: выражение под мышью, inline values, breakpoint-строки, подтесты `t.Run` — PSI в read action, текст как fallback.

## Шаг 10. Удаление старого

**Решение пользователя 2026-10-02:** gopls пока не удаляем — «gopls-дубли фич» из первого пункта откладываются до шага 12; удаляем только текстовый сканер и его потребителей. Переключателей больше нет по фичам: один «Language features: gopls / Built-in» на странице Language Server (внутри `GoFeature`/`GoFeatures.native` и группы `GoIdeFeatureGate` сохраняются ради dumb-режима), форматтер — своя настройка. Умолчание gopls до живой проверки пользователем.

- Удалить `lang.GoDeclarations` (сканер) и мост `GoDeclarationPsi`, `GoTextLexer`/`GoTextTokens`, если не осталось
  потребителей (indent и typed handlers могут остаться на текстовом лексере — решить по факту), gopls-дубли фич,
  переведённых на `NATIVE` релиз назад; флаги этих фич убрать из настроек.
- `docs/psi/*` → `docs/` (один комплект документации), `docs/psi/CHANGELOG.md` → раздел в `CHANGELOG.md` — **сделано 2026-10-02 (10-C)**: 14 файлов в `docs/`, `PSI-PLAN.md`, `PSI-README.md`, `PSI-MIGRATION-history.md`, changelog go-psi — раздел «go-psi» в `CHANGELOG.md`; ROADMAP/PLAN закрыли «свой парсер», в `COMPARE.md` колонка «Без gopls»;
  `tools/psi-ui-robot` слить с `tools/ui-robot` (сценарии P1–P9 и 16 шагов go-psi добавить к сценариям IGS) — **сделано 2026-10-02 (10-B)**: один робот, порт 8083, `autotest.py`/`perf.py` в `tools/ui-robot`, проект сценариев `tools/ui-robot/project-psi`; сценарий ещё ждёт переписывания под один переключатель (шаги 6, 12, 13, P2–P5 рассчитаны на go-psi completion/документацию/форматтер).
- `ROADMAP.md`/`PLAN.md`: пункт «свой парсер» уровня 4 закрыт; `COMPARE.md` — колонка «без gopls».
- Лицензии: `LICENSE` для IGS и `NOTICE.md` (go/parser, `internal/types/testdata` — BSD-3 Go authors;
  фрагменты go-lang-idea-plugin — Apache-2.0) — до первого релиза с PSI. **Сделано 2026-10-04:** MIT; фрагментов go-lang-idea-plugin нет.

## Шаг 11. Новые фичи

По волнам `docs/FEATURES.md` §11: постфиксы/live templates (у IGS уже есть `GoPostfixTemplates`,
`liveTemplates/Go.xml` — объединять, не дублировать), inlay hints, struct size, Smart Enter (`GoSmartEnter` IGS
переводится на PSI), surround/unwrap, Code Vision, затем анализ (exhaustive switch, Printf, struct tags, error flow),
генерация и рефакторинги. Решение D1 (`go` вне project model) в IGS уже принято де-факто: run/test/coverage/debug
живут в IGS и зовут `go` и `dlv`; PSI-модули по-прежнему не зовут.

## Шаг 12. Отказ от gopls: две вехи

Ответ на вопрос «можно ли полностью отказаться от gopls» — да, в два этапа; граница между ними — объём волн
`FEATURES.md`, не риск.

**Веха 12.1 — gopls опционален.** Условие: все фичи 8a–8h и 8j на умолчании `NATIVE`, прошли релиз без отката.
Тогда при `languageServerEnabled=false` плагин даёт всё для ежедневной работы: парсинг, навигация, usages, цвета,
completion, doc, parameter info, rename, типовые диагностики, форматирование. gopls включают ради того, чего в PSI
ещё нет: inlay hints, code vision, анализаторы, code actions (fill struct/switch/returns, create from usage), extract/
inline/change signature, диагностики `go.mod`. Что сделать в самой вехе:
- `languageServerEnabled` по умолчанию `false` для новых установок; существующим — уведомление один раз с выбором.
- `GoToolchainCheckActivity` не предлагает установить gopls, пока сервер выключен; `GoplsStatusWidget`, окно gopls,
  меню `Go.Gopls` скрываются при выключенном сервере (сейчас часть видна всегда — проверить роботом).
- `build.GoBuildProblemsAnnotator` (ошибки `go build` без gopls) остаётся как подстраховка на cgo и build-варианты.
- Замер `--perf` без gopls: память процесса IDE и P1/P3 — в CHANGELOG рядом с цифрами «с gopls».
- Паритет проверяется списком `playground/README.md`: каждая строка «через gopls» получает колонку «без gopls».

**Веха 12.2 — gopls удалён.** Не делаем (решение пользователя 2026-10-03): код gopls остаётся за переключателем, по умолчанию выключен.
Исходный план — для истории. Условие: закрыты волны 1 (inlay hints, code vision), 2 (анализ, шаг 13 этап A),
3 (создание кода: create from usage, implement interface, fill struct/switch/returns), 7 (introduce variable,
extract function, inline, change signature) `FEATURES.md`, а также диагностики `go.mod` (unused require,
`replace` в никуда — `FEATURES.md` §5). Что удаляется: content-модуль `lsp` целиком (`GoplsIntegration`,
`Gopls*`, `io.github.golangsupport.lsp.xml`), `bundledModule("intellij.platform.lsp")` из сборки, EP
`languageServerControl`/`signatureProvider` (остаётся нативный провайдер), страницы `GoLanguageServerConfigurable`/
`GoplsSettingsConfigurable`, настройки `gopls*`, флаги `GoFeatureSource` (остаётся один путь), `tools/gopls/probe.py`.
Vulncheck и upgrades переезжают на `go` CLI (`govulncheck`, `go list -m -u -json`) в `build.*`/`mod.*` — это сетевые
команды, не анализ. Что не будет никогда без отдельного решения: cgo (`C.xxx` — sentinel, тела не анализируются;
gopls делает это тоже плохо), одновременный анализ нескольких GOOS/GOARCH (у нас — переиндексация по тегам, как
смена env у gopls), полный паритет со staticcheck (~150 правил; покрывается топ-30 своими правилами + линтер по
запросу, шаг 13).

Выигрыш после 12.2: плагин работает в любой IDE на платформе, включая форки без `intellij.platform.lsp`, без
отдельного процесса на 300–800 МБ на проект, без расхождений «что видит IDE / что видит gopls» при правках.

## Шаг 13. Собственный анализ вместо линтеров; линтеры по запросу

**Зачем.** `lint.GoLintAnnotator` — внешний аннотатор golangci-lint по сохранённому файлу (`lintOnTheFly`): процесс на
каждое сохранение, результат через секунды, конкуренция с IDE за CPU. Реально из него используют 10–20 проверок:
`errcheck`, `govet` (printf, shadow, copylocks, structtag, unreachable), `staticcheck` SA-класс, `unused`,
`ineffassign`, `gosimple`; остальное — стиль и метрики. Цель: всё, что должно быть «вживую», делает PSI
(мгновенно, инкрементально по телам функций, с quick-fix), линтер остаётся инструментом по запросу и для CI.

**Что уже есть (type checker):** unresolved, unused var/import/label/value, type mismatch, arity, duplicates,
generics, missing return, init cycles — 0 ложных срабатываний на GOROOT (`:go-psi-semantic:corpusTest`), результат
кэшируется по телам функций (`CHANGELOG.md (раздел «go-psi»)`, «Per-body diagnostics»).

**Этап A — vet-класс без data flow (~2 недели, после шага 5, параллельно с 6–8).** Каждая проверка — инспекция в
`go-psi-ide/.../inspections` с quick-fix, корпусный гейт на GOROOT (0 ложных; исключения — список с причиной), тест с
`<warning>`-маркерами. Порядок:
- `Printf`-семейство: verb vs тип аргумента, число аргументов, `%w` только в `Errorf`, обёртки пользователя как у
  vet (функция с `format string, args ...any`, зовущая printf-функцию) — fix: исправить verb;
- `structtag`: синтаксис, дубли ключей/имён, `json:"-,"` — fix: исправить тег;
- exhaustive `switch` по `iota`-перечислениям и «sealed» интерфейсам (определение — `FEATURES.md` §5; weak warning,
  выключена по умолчанию) + fix «fill switch»;
- `unreachable`, self-assignment, `x == nil` для значения, которое не может быть nil, сравнение функций;
- `errors.As` с не-указателем, `errors.Is` vs `==`, `context.Context` не первым параметром, `context.Background()`
  в обработчике, где есть входящий контекст;
- `ineffassign`-лайт: присваивание, перекрытое следующим присваиванием без чтения в том же блоке (без CFG —
  только прямая последовательность);
- `//go:build`: синтаксис выражения, неизвестные GOOS/GOARCH; `//go:embed`: паттерн без совпадений.
Критерий окончания A: на `playground` и на `golang.org/x/tools` набор срабатываний PSI ⊇ срабатывания `go vet`
по этим анализаторам (сравнить скриптом по JSON `go vet -json`), ложных — 0 на GOROOT.

**Этап B — data-flow (~3 недели).** Фреймворк per-function: CFG по PSI-блокам (`if`/`for`/`switch`/`select`/
`goto`/`defer`/`panic`/`return`, `GoTerminating` уже есть), простой DFA (reaching definitions, liveness) с кэшем в
`GoBodyCache` под трекером тела. Проверки на нём:
- `errcheck`: результат-ошибка не присвоен/не проверен; `err` перезаписан до проверки; проверяется не тот `err`
  (fix: `if err != nil { return ..., err }`);
- nil-flow: разыменование после `x == nil` без выхода; `defer resp.Body.Close()` до проверки ошибки;
- `copylocks`: копирование значения с `sync.Mutex`/`sync.WaitGroup` (по `GoSizes`/method set `Lock`);
- `wg.Add` внутри горутины, захват переменной цикла в `go`/`defer` при `go < 1.22` в `go.mod`, отправка в закрытый
  канал (локально);
- `shadow` (опционально, выключена по умолчанию), неиспользуемые параметры (`unusedparams`, fix с call sites — M).
Критерий B: `errcheck` и `nilness` по `golang.org/x/tools` — паритет с линтером ±5% при 0 ложных на GOROOT;
`GoHighlightingPassBenchmark.afterBodyEdit` не хуже +10%.

**Этап C — декларативный движок правил (~3–4 недели), `docs/RULES.md`.** Правило = паттерн PSI (с
метапеременными) + условия на типы/константы + сообщение + шаблон замены; загрузка из ресурсов плагина и из
`.go-psi-rules.yaml` проекта; импортер синтаксиса ruleguard (`m.Match(...).Where(...).Report(...)`) для переноса
готовых наборов. После движка перенос правила staticcheck/gocritic — минуты: первыми топ-30 SA по частоте
срабатываний (`SA1006`, `SA1019` deprecated через doc-комментарии, `SA4006`, `SA4009`, `SA5011`, `SA6005`, `S1000`-серия
упрощений с fix), затем `gocritic` (`ifElseChain`, `singleCaseSwitch`, `sloppyLen`, …). Каждое правило проходит тот же
корпусный гейт. Критерий C: 30 правил SA + 20 gocritic, прогон корпуса < +15% к check-гейту.

**Линтеры по запросу (параллельно с A, ~3 дня).**
- `GoLintAnnotator` перестаёт быть `externalAnnotator` по умолчанию: настройка `lintOnTheFly` → `false`; код
  аннотатора остаётся для тех, кто включит.
- Действие **Go | Lint** (файл / пакет / проект; `golangci-lint run --out-format json` или `go vet -json`, если
  golangci-lint не установлен) → Problems tool window с теми же quick-fix (`GoLintFixes`, `GoSignatureProvider`);
  прогресс в `Task.Backgroundable`, отмена убивает процесс.
- Перед коммитом: чекбокс «Lint changed files» в Commit-диалоге (`CheckinHandlerFactory`), как «Run inspections».
- Профиль инспекций: нативные проверки, дублирующие линтер (printf, structtag, errcheck, …), помечены в описании
  («replaces golangci-lint: govet/printf»), чтобы при включённом линтере пользователь выключил одно из двух.
- CI остаётся на golangci-lint; документировать в `docs/guide.html`.

**Что теряется.** Проверки, не перенесённые в A–C, видны только по запросу/в CI. Стиль и метрики (`gocyclo`,
`lll`, `revive`-стиль, `gofumpt`-правила) своими не делаются — только линтер.

**Умолчания по этапам.** После A: `lintOnTheFly=false` для новых установок. После B: линтер не предлагается к
установке в `GoToolchainCheckActivity` (ставится по первому Go | Lint). После C: golangci-lint в настройках —
раздел «External tools», не «Code quality».

## Риски и как их снимаем

| Риск | Снятие |
|---|---|
| Регрессия всего, что висит на `GoDeclaration` при подмене парсера | шаг 4 атомарный; робот `structure.js`, `markers.js`, `targets.js`; живой прогон до merge |
| Двойные подчёркивания/дубли (gopls + PSI) в смешанной фазе | исключающие флаги (шаг 1), фильтр диагностик по `source` (8a/8g), дедупликация completion по lookup string |
| Индексация GOROOT и зависимостей при первом открытии (library roots) | только GOROOT/src и build list; shared indexes недоступны (`PERF-BACKLOG` п. 8); замер P7 до/после |
| Configuration cache: `corpusTest`/`benchmark` не сериализуются | запускать с `--no-configuration-cache`; обычный `test` — с кэшем |
| `--offline`: артефакты grammarkit/JFlex/abi-tools | уже в кэше Gradle; при смене версий — один онлайн-запуск, записать в CLAUDE.md |
| Split mode / remote dev, форки IDE без LSP-модуля | PSI-код в главном модуле не зависит от `lsp`; при `languageServerEnabled=false` все фичи `NATIVE` |
| Память: кэши типов (45 МБ на 16k строк GOROOT) | `GoCacheMemoryBenchmark`, P9; дальше — `docs/LIBRARY-SUMMARIES.md` |
| Кодировка/EOL: часть перенесённых файлов с CRLF | `.gitattributes` IGS нормализует; при первом касании файла Git переписывает в LF |
| Лицензии не оформлены | шаг 10 до релиза |
| Ложные срабатывания собственных проверок (хуже, чем отсутствие проверки) | корпусный гейт на GOROOT и `golang.org/x` для каждой инспекции, 0 ложных как условие merge; новые классы — выключены по умолчанию первый релиз |
| Data-flow (этап B) замедляет подсветку | кэш в `GoBodyCache` по телу, `afterBodyEdit` в бенчмарках как порог, DFA только по запросу инспекции |
| Пользователи теряют привычные проверки линтера после `lintOnTheFly=false` | Go \| Lint и чекбокс в Commit; уведомление один раз со ссылкой на настройку |

## Чек-лист текущего состояния

- [x] Перенос: 3 подпроекта, `testData`, `tools`, `docs/psi`; `test checkKotlinAbi buildPlugin --offline` зелёные; ZIP без изменений.
- [x] Шаг 1 — переключатели (2026-10-02). Отступления: строки «Source of …» на странице настроек появятся вместе с первой нативной фичей (8a) — на странице только то, за чем есть реализация; `GoFormatter.NATIVE` — на 8j по той же причине. Кастомайзеры дескриптора читают только настройку (`GoFeatures.configuredNative`), dumb-режим учитывают обработчики.
- [x] Шаг 2 — единые `GoLanguage`/`GoFileType` (2026-10-02). Отступления: старый `lang.GoFile : PsiFileBase` оставлен под своим именем в `lang/GoFile.kt` (с `lang.psi.GoFile` не конфликтует, удаляется на шаге 4); палитра `GO_*` живёт в `go-psi-core` как `lang.GoColors` (ключи IGS дословно, компаньон `GoSyntaxHighlighter` — алиасы) — иначе аннотатору go-psi-ide не на что компилироваться; `GO_IDENTIFIER`/`GO_RUNE` не добавлены (PSI-подсветка их не требует после удаления); `GoIdeIcons` → `AllIcons.Nodes.*` как у `GoDeclarationIcons`; корень уже `pluginComposedModule(:go-psi-core)` (иначе `GoLanguage` не найти в рантайме) — остальное подключение на шаге 3.
- [x] Шаг 3 — модули в плагине (2026-10-02): три `pluginComposedModule`, `plugin.xml` включает `go-psi-core.xml` (стабы, индексы) и `go-psi-semantic.xml` (сервисы); `go-psi-core-language.xml` (fileType, парсер, AST factory) — шаг 4; `go-psi-semantic-roots.xml` (`GoRootsProvider`, registry `gopsi.libraryRoots`) вынесен отдельно и **не включён** — решение по library roots на шаге 7; `go-psi-ide-{editor,formatter,navigation,refactoring,documentation,completion,inspections}.xml` — шаг 8. `verifyPlugin` — по локальной IDEA 2026.1.4 (`localIdePath`), падает только на несовместимостях; internal/override-only находки старого кода остаются в отчёте `build/reports/pluginVerifier`. Проверено: jar один (все классы PSI в нём), verdict «Compatible», песочница на playground: Structure, подсветка, gopls стартует, 0 «Plugin to blame: Go».
- [x] Шаг 4 — парсер (2026-10-02): `lang.parserDefinition` → `lang.parser.GoParserDefinition` (`go-psi-core-language.xml`), editor-фичи — `go-psi-ide-editor.xml`
  (structure, breadcrumbs, folding, Go to Class/Symbol по stub-индексам, commenter, скобки, кавычки, find-usages provider); `GoPsi.kt` и старый `lang.GoFile` удалены,
  мост `lang/GoDeclarationPsi` (+ `GoFileStructure.findByName`), `GoSyntaxHighlighter` на PSI-лексере, `GoIdentifierAnnotator` на PSI-листьях (директивы `//go:` красит он:
  у PSI-лексера нет токена), gopls-обработчики целятся в `GoNamedElement`; старые goto-контрибьюторы и find-usages provider IGS удалены. Отличия от старого, видимые
  роботом: заголовок метода в Structure без получателя (`Add(item Item)`), PSI-свёртка не сворачивает вложенные блоки тел и серии `//`-комментариев (старые `GoBlockFolds`/
  `GoCommentRuns` — кандидаты на перенос в `ide.folding`, волна 11). Попутно: semantic tokens у gopls не спрашиваются для файлов > 100 000 байт (его лимит; иначе исключение платформы на каждый запрос).
- [x] Шаг 5 — гейт (2026-10-02): `test buildPlugin checkKotlinAbi`, `verifyPlugin` (Compatible); `:go-psi-core:corpusTest` 0 расхождений, `:go-psi-semantic:corpusTest`
  метрики без изменений; `benchmark`: core/semantic в допуске, у go-psi-ide `GoCompletionLatencyBenchmark` шумит на этой машине ±40 % в обе стороны (база `1a42af5` при повторе
  подряд тоже вышла за порог, HEAD прошёл) — регресса от миграции нет, пороги не трогал; робот IGS (8083): structure.js, folds.js, markers.js (run, подтесты, implementations gopls),
  targets.js, navigation.js (usages 4/7, implementations 2), Ctrl+B из `order_test.go` и `main.go`, Go to Symbol/Class, отступы, format on save, снимок редактора — цвета прежние,
  0 «Plugin to blame: Go». `tools/psi-ui-robot/autotest.py --attach --perf` (порт 8084, минимально адаптирован к раскладке IGS): P8 `check()` warm 0.3 мс / после правки тела
  соседа 0.6 мс (база 0.4), P9 53 МБ (база 45 МБ, с gopls в процессе), P6 первое открытие 1057 мс, P7 без GOROOT (roots не включены — шаг 7); P2–P5 падают по построению: completion,
  документация, форматтер go-psi на шаге 4 не подключены (их даёт gopls), сценарий переписывается на шаге 10. Живая проверка пользователем — отдельно.
- [x] Шаг 6 — stub-индексы (2026-10-02): `GoProjectInterfaces` по `GoTypesIndex` (спеки с `GoInterfaceType`, методы и встроенные из стабов, кэш на `GoTrackers.projectWideDependencies`, в dumb-режиме — последний список), `catalogue.GoProjectPackages` по `GoAllPublicNamesIndex` (сигнатуры рендерит `GoStubTexts` из дерева стабов: текста сигнатур стабы не хранят; `stamp` — `GoTrackers.projectOutOfBlock`), `GoInterfaceSources` для пакетов проекта — PSI (AST только выбранного интерфейса), GOROOT и module cache — сканер до шага 7. `GoDeclarationIndex`, `GoExportsIndex` удалены. Тесты с guard на загрузку AST (`GoProjectInterfacesTest`, `GoProjectPackagesTest`). Долг: смена `module` в go.mod не сбрасывает кэши до правки Go-файла — закрывается трекером project model на шаге 7.
- [x] Шаг 7 — project model и library roots (2026-10-02): `sdk.GoIgsToolchainProvider` переопределяет `GoToolchainProvider` (`go` плагина, его `go env` через `GoEnvironment`, теги и GOOS/GOARCH из `GoSettings.analysisGoos/analysisGoarch` — поля без UI до 8c; fallback — чистая детекция go-psi); в `project.api` добавлен `GoToolchainProvider.modificationTracker` (ABI обновлён осознанно); Go | Reanalyze сбрасывает `GoTrackers` и project model. Единая модель go.mod: `mod.GoModFile` — обёртка над `project.impl.GoModFileParser.directives` (один токенизатор; строки require/replace сохранены для баннера, узлов Dependencies и фиксов; `GoModFileTest`). **Library roots — решение пользователя: через настройку.** `go-psi-semantic-roots.xml` включён; `project.impl.GoLibraryRootsPolicy` (application service: NONE / STANDARD_LIBRARY / STANDARD_LIBRARY_AND_DEPENDENCIES, по умолчанию registry-ключ) переопределён `sdk.GoIgsLibraryRootsPolicy` на `GoSettings.libraryRoots`; на корневой странице Settings | Tools | Go строка «Index for navigation»: «Standard library» (GOROOT/src без `cmd`) или «Standard library and dependencies» (ещё и модули build list, умолчание); смена значения зовёт `GoRootsProvider.scheduleRootsUpdate` для открытых проектов — переиндексация без перезапуска (`GoRootsProviderTest`, `GoLibraryRootsTest`). Замеры на `playground`: холодные индексы (2026-10-02, до настройки) — 43 209 файлов, индексация 7,2 с, dumb ~11 с; с индексами на диске `--perf` P7: smart через 1,0 с, GOROOT в библиотеках через 2,4 с, 472 файла проекта к индексации, повторное открытие 1,3 с и 0 файлов; P9 44 МБ; P8 `check()` warm 0,2 мс, после правки тела соседа 0,5 мс, после правки сигнатуры соседа 9,1 с (полный пересчёт 135 КБ файла с резолвом в GOROOT — цена анализатора go-psi, см. `PERF-BACKLOG`, пользователю до 8b не видна). Робот (8084): Structure `(Order) Add(item Item)`, folds вложенных `for`/`if`, Ctrl+B `fmt.Println` → `print.go` GOROOT, `uuid.NewString` → module cache, `Total` → проект; переключение настройки вживую: библиотеки 1 ↔ 2 (`gopsi.goroot`, `gopsi.modules`), в логе «On updated roots of library 'Go'», 0 «Plugin to blame: Go». Долг шага 4 закрыт: получатель в заголовке метода Structure (формат старого плагина `(Type) Name(...)`), свёртка вложенных блоков, `switch`/`select` и серий `//` от двух строк.
- [x] 8e — completion (2026-10-02): `go-psi-ide-completion.xml` включён; contributor и confidence go-psi спрашивают гейт (COMPLETION) и пропускают code fragments (`virtualFile?.parent == null` — фрагмент отладчика `expression.go`); у contributor'а `id="goPsiCompletion"`, шаблоны ключевых слов IGS зарегистрированы `order="first, before goPsiCompletion"` и в native-режиме через `runRemainingContributors` прячут голые ключевые слова и сниппеты PSI с тем же lookup string (`GoKeywordTemplates.hides`: объявления с `psiElement` остаются); каталог, теги структур, значения, return — без пересечений. gopls: `completionCustomizer` всегда `GoplsCompletionSupport`, `shouldRunCodeCompletion` = `!native(COMPLETION)` — на время индексации отвечает gopls. Известный пробел Built-in: `completionStructBraces`/`completionArguments` действуют только на элементы gopls и каталога (PSI — шаг 9). Робот: `q.` на `Point{}` → Sum, X, Y; `fmt.` → 54 члена; `o.` в файле, испорченном предыдущими дописками без `}`, даёт top-level список — артефакт зонда, не бага; GOPLS-режим без изменений (30 элементов `o.`). `--perf` P3 (тот же сценарий, NATIVE против gopls утром): member_first 1344 мс (69 элементов; gopls 224) — холодный резолв `net/http` для файла, member_warm 38 мс (gopls 60), statement_first 409 (gopls 77), statement_warm 35 (gopls 60); первый вызов — в `PERF-BACKLOG`.
- [x] 8f — документация и parameter info (2026-10-02): `go-psi-ide-documentation.xml` включён; `GoDocumentationTargetProvider`, `GoParameterInfoHandler`, `GoExpressionTypeProvider` при закрытом гейте (HOVER) ничего не отвечают (`codeInsight.parameterInfo` — список, берётся первый ответивший, порядок не нужен); у gopls при native выключены `hoverCustomizer` и `signatureHelpCustomizer` (платформенный `LspParameterInfoHandler` зарегистрирован для всех языков — до этого parameter info давали бы оба). Робот: GOPLS — цель документации только `LspDocumentationTarget`; NATIVE — только `GoDocumentationTarget` «Println», parameter info в `Println(` — контроллер платформы с подсказкой `a ...any` от PSI.
- [x] 8g — диагностики (2026-10-02): `go-psi-ide-inspections.xml` включён (аннотатор вынесен в `go-psi-ide-highlighting.xml` для 8d); все 10 инспекций через `GoDiagnosticsInspectionBase.checkFile` и `GoImportOptimizer.supports` следуют гейту (DIAGNOSTICS); `GoplsDiagnosticsSupport.accepts(source, nativeSyntax, nativeDiagnostics)`: при native отбрасываются `compiler`, анализаторы (`printf`, `unusedparams`, `SA…`, `ST…`), `go list`, `go mod tidy` остаются; `GoBuildProblems.shouldShow(project)` снова показывает сообщения сборки при Built-in (слово компилятора о том, чего checker не нашёл). Робот (`semantic.go`: лишний импорт, неиспользуемая переменная, несовпадение типа, неразрешённое имя): GOPLS — 5 ошибок gopls; NATIVE — те же 5 мест только от инспекций (`GoUnusedImport`, `GoUnusedVariable` как WARNING, `GoTypeMismatch` ×2, `GoUnresolvedReference`), дублей нет. Строки «Completion», «Documentation and parameter info», «Diagnostics» на странице Language Server; умолчания GOPLS.
- [x] 8d — семантические цвета (2026-10-02): `go-psi-ide-highlighting.xml` включён; `GoSemanticHighlightingAnnotator` при закрытом гейте (SEMANTIC_COLORS) ничего не красит; gopls `semanticTokensCustomizer` всегда объект, `shouldAskServerForSemanticTokens` = Go-файл ≤ 100 000 байт и `!native(SEMANTIC_COLORS)` — в dumb-режиме красит gopls; `GoIdentifierAnnotator` IGS красит идентификаторы по текстовым правилам только при источнике gopls или в dumb-режиме (`coloursIdentifiers`), директивы `//go:` — всегда (больше их никто не красит). Ключи — только `GoColors` (= `GO_*` IGS; в схемах IGS определены 8 из них, остальные наследуют платформенные — как и с токенами gopls). Строка «Semantic colours» на странице Language Server; умолчание GOPLS. `GoIdentifierAnnotatorTest`, `GoIdeFeatureGateTest.testClosedGateSemanticColours`; снимок редактора роботом — ниже.
- [x] 8h — rename (2026-10-02): `go-psi-ide-refactoring.xml` включён; манипуляторы, namesValidator и renameInputValidator пассивны и зарегистрированы всегда; `GoRefactoringSupportProvider.isInplaceRenameAvailable` и `GoRenameMethodProcessor.canProcessElement` при закрытом гейте (RENAME) — false; gopls `renameCustomizer` всегда `LspRenameSupport` с `shouldRunRename` = `!native(RENAME)` (в dumb-режиме переименовывает gopls). Поток платформы: `RenameHandlerRegistry` собирает все доступные `renameHandler` и при двух и больше показывает диалог выбора — до этого шага в режиме gopls у локальной переменной стояли бы `VariableInplaceRenameHandler` (через провайдер go-psi) и `LspRenameHandler`; гейт оставляет один. Default `PsiElementRenameHandler` — только когда никто не ответил. Робот (`rename_handlers.js`): ниже. Строка «Rename» на странице Language Server; умолчание GOPLS.
- [x] 8i — code vision (2026-10-02): у go-psi-ide появились `ide.codevision.GoUsagesCodeVisionProvider` / `GoImplementationsCodeVisionProvider` (`DaemonBoundCodeVisionProvider`, те же якоря, слова и действия по клику, что у gopls-провайдеров: функции кроме `main`/`init`/тестов, методы, типы, методы интерфейсов; usages — `ReferencesSearch` в use scope до 100+, implementations — по stub-индексам только на интерфейсной стороне), дескриптор `go-psi-ide-codevision.xml` включён; гейт — группа IMPLEMENTATION_MARKERS (= переключатель «Code vision»), в dumb-режиме молчат. Подсчёт usages резолвит ссылки и грузит AST файлов со словом — как и gopls в процессе; implementations — без AST (тест-страж). Строка «Code vision» на странице Language Server; умолчание GOPLS. Робот: ниже.
- [x] 8j — форматтер (2026-10-02): `go-psi-ide-formatter.xml` включён; `GoFormatter.NATIVE` («Built-in») в Reformat Code with — `GoFormattingService.canFormat` только для внешних инструментов (`isExternalTool`), с Built-in файл достаётся `CoreFormattingService` (`order="last"`) и модели go-psi-ide с `GoImportSorter`; format on save при Built-in — `ReformatCodeProcessor` на EDT без процесса; у gopls появился явный `formattingCustomizer` (раньше не было): disabled, когда форматирует плагин, при None — `shouldFormatThisFileExclusivelyByServer` для Go, иначе None молча стал бы Built-in (у Go-файла теперь есть `lang.formatter`). Эксклюзивность проверена по байткоду: `FormattingServiceUtil.findService` берёт первый сервис, который claim-ит файл, Core — последний. Побочный эффект: форматирование выделения и ad hoc (вставка) теперь идут через порт и при gofmt — раньше их не было вовсе. Гейт в go-psi-ide не нужен. `GoNativeFormatterTest` (8). Умолчание GOFMT; `--perf` P5 — позже. Робот (8084): code vision NATIVE — 11 подсказок usages и 2 implementations от `go.psi.*`, gopls 0; GOPLS — gopls-провайдеры (счётчики асинхронные, зонд видит 0), PSI 0. Rename: GOPLS — доступен только `LspRenameHandler`; NATIVE — у локальной `VariableInplaceRenameHandler`, у метода никого (default `PsiElementRenameHandler` с диалогом). Reformat Code с Built-in: `func  _fmtProbe( a int,b string )string{ return b+b }` → `func _fmtProbe(a int, b string) string { return b + b }`. **Найден и исправлен freeze EDT 12 с** (дамп `threadDumps-freeze-20261002-173452`): мой зонд code vision держал read action, а `GoIgsToolchainProvider.toolchainFor` на каждый `resolveImport` заново искал `go` по PATH и GOROOT на диске (`GoCli.findExecutable`, `detectPure`); теперь ответ кэшируется по ключу настроек/`go env` и сбрасывается `invalidate()` из Go | Reanalyze (`GoToolchainProviderTest.testTheAnswerIsCachedUntilTheSettingsChangeOrAReanalyze`).
- [x] 8a [x] 8b [x] 8c [x] 8d [x] 8e [x] 8f [x] 8g [x] 8h [x] 8i [x] 8j.
- [x] 8a — синтаксические ошибки (2026-10-02): переключатель `syntaxErrorsSource`: NATIVE — `PsiErrorElement` показаны, у gopls отброшены диагностики с `source == "syntax"` (`GoplsDiagnosticsSupport.accepts`); GOPLS — ошибки парсера скрыты `lang.GoSyntaxErrorFilter` (`highlightErrorFilter`), gopls показывает свои; без сервера — всегда парсер. Первая группа «Source of features» на странице Language Server (строка «Syntax errors»); apply перезапускает демон и gopls (диагностики gopls кэшированы платформой до следующей публикации — рестарт демона их не перефильтровывает, рестарт сервера — да). Сообщение парсера больше не называет вставленную точку с запятой (`<<syntheticSemi>>`, goldens recovery обновлены, позиции те же). Умолчание пока GOPLS. Робот (8084, `broken.go` с незакрытой скобкой, `errors.js`): GOPLS — 1 ошибка gopls «expected ';', found 'EOF'»; NATIVE — 1 ошибка парсера; `GoSyntaxErrorFilterTest`, `GoplsDiagnosticsTest`, `GoLazyBodyTest` 8/8.
- [x] 8b — уже PSI с шагов 4/6, флага нет.
- [x] 8c — навигация и usages (2026-10-02): `go-psi-ide-navigation.xml` включён; в go-psi-ide сервис `ide.GoIdeFeatureGate` (NAVIGATION / USAGES / IMPLEMENTATION_MARKERS, по умолчанию всё включено), в IGS переопределён `lang.GoIgsIdeFeatureGate` → `GoFeatures.native(NAVIGATION | USAGES | CODE_VISION)`; каждое расширение дескриптора спрашивает гейт на входе. Платформа берёт один `targetElementEvaluator` и один `codeInsight.gotoSuper` на язык (`forLanguage`): PSI-реализации зарегистрированы `order="first"` и при закрытом гейте делегируют следующему для Go (единственное допустимое `order="first"` — не ради дедупликации, а потому что слот один). Найдено и исправлено: с шага 4 Find Usages в режиме gopls показывал каждое место дважды (ссылки PSI резолвятся независимо от переключателя + usages gopls) — `lsp.GoplsFindUsagesHandlerFactory` отдаёт handler без поиска ссылок, когда источник usages — gopls (`GoplsFindUsagesHandlerTest`). Строки «Navigation» и «Usages and highlighting» на странице Language Server. Умолчание пока GOPLS. Робот (`nav8c.js`, `goto_targets.js`): GOPLS — handler gopls, usages `Priced` 1 / `Order.Total` 6 только от gopls, реализации 2, маркеры gopls; NATIVE — handler PSI, usages 1 / 5 (gopls ещё считает вызов через интерфейс `priced.Total()`; у PSI это вопрос «Include Interface Methods» своего handler'а), реализации `Priced` 2, Go to Super — PSI, 6 маркеров PSI, Ctrl+B через ссылки PSI: `fmt.Println` → GOROOT, `uuid.NewString` → module cache, `Total` → проект. Dumb-режим под NATIVE не смотрел (по построению отвечает gopls). «Plugin to blame: Go» — только от моих же JS-зондов (класс content-модуля через чужой загрузчик, чтение PSI вне read action), от плагина 0. Известный плавающий тест: `semantic.GoDeepExpressionTest.testLongStringConcatenationTypesAndFoldsWithoutStackOverflow` падает в полном прогоне `:go-psi-semantic:test` примерно раз из трёх с `RecursionManager` «Inconsistent depth» (тестовый assert, enters/exits расходятся на 1 из-за соседнего теста), в одиночку и при повторе проходит; код не менялся с переноса — разобрать отдельно.
- [x] Шаг 9 (2026-10-02, четыре агента Opus: S/F/I/R; робот — ниже), [x] шаг 10 (A, B, C; лицензии — 2026-10-04: MIT в `LICENSE`, порты go/parser и go/printer и копии testdata GOROOT в `NOTICE.md`), [ ] шаг 11.
- [x] 10-A — текстовый сканер удалён (2026-10-02): `lang.GoDeclarations`, `GoDeclarationPsi`, `GoTextLexer`, `GoTextTokens` и текстовые intentions (Handle error, Add if err != nil check, Add missing return — их заменяют intentions go-psi-ide; при источнике gopls их место занимают действия gopls, своего «Handle error» без сервера больше нет). Вместо них: `lang.GoDeclarationKind` (`of`/`ofName`/`at`) и `GoDeclarationInfo.of/topLevel/all` только из PSI, `GoTokens` (лексер go-psi) для текста до коммита (отступы, вставка импорта, контекст live-шаблонов, completion выражений отладчика), `GoNames`, `GoImports.importsOf`; `catalogue.GoSourceScanner` оставлен для файлов вне индексов (каталог stdlib/module cache с диска раз на версию; `GoCatalogueFiles.VERSION` → 2). Потребители: run-иконки и producer (были не полностью PSI), Go to Test, Generate Test, шаблоны, макросы, intentions, выравнивание, теги, gopls-обработчики (`GoplsTargets`), отладчик (`GoDebugPsi`: null на незакоммиченном документе), подтесты (ничего в dumb-режиме). Робот (8083, объединённый): `order_test.go` — 7 run-иконок (тесты, подтесты таблицы `TestTotal/empty`, бенчмарк), `main.go` — «Run the program», Go to Test из `order.go` → `order_test.go`; 0 новых «Plugin to blame: Go». Долг: Generate Test предлагается и на строке док-комментария (`GoDeclarationInfo.range` с `func`, а поиск «функция под кареткой» включает док); фикстура `GoFixesTest` называет уже несуществующий `GoHandleErrorIntention`.
- [x] 9-S — `semantic.api`: `expectedTypeAt(expression)` и `enclosingResultTypes(element)` (ABI +2, логика `ide.completion.GoExpectedTypes` переехала в `semantic.infer.GoExpectedType` и расширена: конверсии без ожидания, spread-аргумент, `return f()` кортежем, левый операнд, ключ map); `lang.GoNativeSignatureProvider` первым в `signatureProvider` (gopls — только когда вызов не резолвится); умный `return` на PSI (`lang.GoReturnValues`: переменная точного типа из scope, `err` для `error`, иначе нулевое значение; второй вариант `fmt.Errorf("…: %w", err)` при импортированном `fmt`; текстовый `GoIdioms.returnValues` — fallback до шага 10); `GoInlineIdiomsProvider` по типам (`Close() error` в method set, `Unlock` после `Lock`, `if err != nil` только при `error` последним результатом) и новые серые идиомы: `select {` → кейсы из scope (ctx, таймеры, каналы), `for {` → `select` внутри, `switch x {` → все константы перечисления / `case T:` по реализациям интерфейса (до 50, не в dumb-режиме); `make(` по ожидаемому типу (`GoMakeCompletionContributor`: `chan T`, `[]T, 0, len(x)`, `map[K]V`), `v, ok := <-ch` после `<-ch` (`GoChannelReceiveCompletionContributor`). `GoExpectedTypeTest` (~40 позиций), `GoNativeTypesTest` (16).
- [x] 9-F — go-psi-ide `ide.intentions` за гейтом CODE_ACTIONS: Fill all fields / Fill required fields (embedded — по имени типа, промоутнутые поля ключами не бывают), Fill return values (+ режим «Add missing return»), Fill switch (константы типа / реализации интерфейса), Fill select / Fill select with default, Handle error (+ `if err := f(); err != nil`), Wrap error with fmt.Errorf; `GoZeroValues`, `GoScopeValues`; дескриптор `go-psi-ide-intentions.xml` с описаниями. В native-режиме скрыты действия gopls `Fill <Struct>`/`Fill anonymous struct`, `Add cases for <T>`, `Fill in return values`, `GoplsFillStructIntention`, а текстовые `GoHandleErrorIntention`/`GoCheckErrorIntention`/`GoAddMissingReturnIntention` отступают. Известно: две константы одного значения дадут дубль `case`; выравнивание `Key: value` — gofmt при сохранении. `GoCodeActionIntentionsTest` (20), `GoCodeActionsSwitchTest`.
- [x] 9-I — `lang.GoStructPsi` (поля, теги, embedded, типы из PSI); генераторы и Add Struct Tags на PSI (тег перед хвостовым комментарием, `A, B T` тегируется раз); `GoFieldAlignment.analyze(GoStructType)` через `GoSizes`; Reorder Fields переведён на PSI (моя правка `GoIntentions.kt`, текст — fallback при незакоммиченном документе); Implement Interface: существующие методы и стиль получателя из type checker, method set интерфейса через `GoInterfaceSources.methodsFor` → stub-индексы + `methodsOf(declarationType(spec))` + `GoTypeRenderer.render(type, qualifier)` (alias/dot-import/реальное имя пакета), дисковый сканер — только вне индексов (roots «Standard library» для зависимости, dumb-режим). Сгруппированные параметры пишутся развёрнуто (`Less(i int, j int)`). Исправлен regex `GoInterfaces` для `...pkg.T`. `GoStructPsiTest` (7: io.Reader, fmt.Stringer, sort.Interface, heap.Interface, io.ReadWriteCloser, alias, Formatter с импортом).
- [x] 9-R — `lang.GoScopeInputs` (переменные в scope с типами из `declarationType`, stdlib-типы по импорту без GOROOT): шаблоны `for range`/`ctx`/`t.Run` по типам, умный `select` (ctx под своим именем → каналы → таймеры → send-only) и «select loop», `make`-шаблоны, `v, ok := <-ch`, `close`/`defer close` для незакрытого канала функции; TOP-шаблоны недостающих методов интерфейсов через `methodsOf`; `GoDebugPsi` (выражение под мышью, inline values по resolve, breakpoint-строки по узлам) в одном read action только при закоммиченном документе; `GoSubtests.find` через `*testing.T` и вложенные пути `outer/inner`. `GoScopeInputsTest` (9), `GoDebugPsiTest` (6).
- Шаг 9, долг: `GoTypeRenderer.render(type, qualifier)` держит qualifier в общем `var` (не потокобезопасно; как и `GoChecker`) — перевести на ThreadLocal; `GoplsActionsIntention` (общий popup gopls) ещё перечисляет fill-действия; серые идиомы молчат, если PSI отстаёт больше чем на одну клавишу (проверить вживую). Робот (8084, Built-in для Code actions и Completion): Alt+Enter на `Point{}` — «Fill all fields», «Fill required fields» (gopls «Fill Point» скрыт), Fill all fields → `X: 0, Y: 0, Name: "", Tags: nil`; на `select { case <-t.C: }` в функции с `ctx` и `in <-chan int` — «Fill select», «Fill select with default», Fill select дописал `case <-ctx.Done(): return ctx.Err()`; `ch = make(` при `var ch chan int` — первый элемент `chan int`. Серый текст и умный `return` вживую не смотрел (inline-рендер робот не читает).
- [~] Шаг 11, волна 1 (2026-10-02, четыре агента Opus A/B/C/D; версии 0.2.2–0.2.13, по фиче на версию в `CHANGELOG.md`): A — postfix templates, live templates и Smart Enter IGS на PSI
  (выражение и тип из PSI, контексты шаблонов statement / top level / struct field / expression, текстовый движок `GoStatements` удалён); B — go-psi-ide `ide.editor`: Surround With
  (`GoSurround.kt` IGS удалён), Unwrap/Remove, Move Statement, Join Lines, дескриптор `go-psi-ide-editing.xml`, без гейта; C — `ide.hints`: набор подсказок gopls за `GoFeature.INLAY_HINTS`
  (gopls не спрашивают по файлу при Built-in, при индексации отвечает он), struct size inlay без гейта, `GoInlayHintsBenchmark` (688 подсказок `server.go`: 15 мс warm), `GoTypeRenderer`
  переведён на ThreadLocal (долг шага 9 закрыт); D — `ide.editor.paste` (импорты при вставке; внешний текст через EP `pasteImportResolver`, в IGS отвечает каталог stdlib),
  `ide.spelling` (optional depends `com.intellij.modules.spellchecker`, `bundledModule("intellij.spellchecker")` в go-psi-ide), `ide.documentation.GoDocLinks` (ссылки `[pkg.Name]` —
  references, Quick Documentation с переходом). Робот (8083, Built-in, `scripts/editop.js`): Smart Enter `if x > 0` → тело с кареткой; `load("p").err` → `v, err := load("p")` + `if err != nil { return 0, err }`;
  `m.for` → `for k, v := range m {}`; `fori` в теле раскрывается, на верхнем уровне — нет; Join Lines `var s string`+`s = "x"` → `s := "x"`, аргументы вызова → `greet("b", 2, false)`;
  Move B над A с комментарием, первый statement блока вверх не двигается; Unwrap `if` сохраняет init и убирает else; Surround двух строк в `if  {}`, `load("x")` → `v, err := load("x")` + проверка с `return "", err`;
  inlay: `= 0/1/2`, `name:` `times:` `loud:`, `: int`, `24 bytes, 11 padding (16 if reordered)`, при gopls — 28 подсказок gopls без дублей и struct size остаётся; Typo на `Wrold`, `tpyo`, `helloWrold`;
  Ctrl+B по `[Point.Move]`/`[Kind]` — к объявлениям; вставка `strings.Repeat(...)` в `lint.go` добавила `"strings"` в группу; «Plugin to blame: Go» за сессию 0. Гейты: `test checkKotlinAbi buildPlugin` зелёные (ZIP 0.2.13), `:go-psi-semantic:corpusTest` — метрики без изменений (только `millis`);
  `benchmark` на этой машине в тот вечер шумел: нетронутые лексер и парсер +70–80 % к записанным порогам, `GoHighlightingPassBenchmark.afterBodyEdit/afterTopLevelEdit` +75 % — в той же пропорции
  (отношение к `GoParserBenchmark.server` 11.5 и 13.2 против записанных 10.8 и 13.0), `GoInlayHintsBenchmark` в пороге с запасом; пороги не трогал — перемерить на холодной машине. Волны 2–7 — впереди.
- [~] Шаг 11, волна 2 (2026-10-02, три агента Opus E/F/G; версии 0.2.14–0.2.22): E — `ide.inspections` на новой базе `GoAnalysisInspectionBase` (гейт DIAGNOSTICS): exhaustive switch
  (фикс «Add missing cases» делит `GoSwitchCases` с Fill switch, который перестал дублировать константы с равными значениями), struct tags (vet structtag + повторы имён json/xml/yaml/db,
  тег у неэкспортируемого поля), `context.Context` (не первый параметр, подмена/затенение `ctx`, `Background()` где есть `ctx`), `errors.As` (vet errorsas) и `== ErrX` (weak warning);
  F — `ide.inspections.printf` (чистый парсер формата с картой смещений, таблицы vet, обёртки пакета через `GoBodyCache`, глубина 3), `GoPrintfInspection` с тремя фиксами,
  completion глаголов по `%` (`GoFormatVerbCompletion`, typed handler + confidence); G — `ide.intentions`: change quote (`GoStringQuotes`), invert `if` (и early return / continue),
  merge / split условия, `if` ↔ `switch`, split / group объявлений, join declaration and assignment (общий `GoDeclarationJoin` с Join Lines), `:=` ↔ `var`. Гейт: `:go-psi-ide:test` 467,
  `:test` 358, 0 падений, `checkKotlinAbi` зелёный. Робот (8083, Built-in, `wave2.go`): все 12 ожидаемых предупреждений с текстами vet (в т. ч. `example.com/playground/store.logf format %d`
  через обёртку), фиксы Add missing cases / Fix quoting / Use ctx / Take the address / `!errors.Is` с импортом / `%d`→`%s` / `%v`→`%w`; intentions raw string, invert `if` с комментарием,
  early continue, `if`→`switch` с `case 2, 3:`, `n := count()`; список глаголов после `%` (20 пунктов); при gopls после перезапуска демона 0 своих проблем (смена настройки роботом напрямую
  демон не перезапускает — старая подсветка висит до правки); «Plugin to blame: Go» 0. **Урок:** три агента в одном дереве портят общую тестовую песочницу `.intellijPlatform/sandbox/go-psi-ide`
  (Trigram index, `FileDeletedException`) и подвешивают демоны Gradle — следующие волны гонять в worktree или по одному агенту на модульный прогон. Решено 2026-10-03: уровень exhaustive switch
  переведён на WEAK WARNING (как в плане §13; на `reflect.Kind`-подобных `switch` без `default` обычный WARNING шумел), включена по умолчанию.
  Бенчмарк перемерен на холодной машине 2026-10-03 (демоны остановлены, одна JVM): `go-psi-core` снова +50–86 % по парсеру/индексу/стабам, но базовый
  коммит 6b3358c без единой правки в `go-psi-core` в соседнем worktree даёт те же цифры — это состояние машины, не регрессия; пороги не меняются.
- [x] Шаг 11, волна 3 (2026-10-03, три агента Opus A/B/C, каждый в worktree; версии 0.2.23–0.2.30): C — Generate `String()` для enum (`stringer`-вид, поиск констант
  `GoEnumConstants` общий с Fill switch / exhaustive switch) и Equal Method (сравнение по типам полей), группировка импортов goimports (`GoImportGroups`: Optimize Imports
  перегруппировывает, auto-import вставляет в свою группу; Reformat Code по-прежнему только сортирует, как gofmt); A — smart completion по ожидаемому типу с литералами,
  цепочки `x.F.M` (один уровень, лимиты), голые имена экспортов пакетов проекта из стаб-индекса (каталог хоста их тогда не дублирует); B — create function / method /
  field / variable / type from usage на типах, quick fix «Implement 'I' for T» на ошибке «does not implement» (API `GoImplementStubs` для Ctrl+I хоста ещё не подключён),
  дубли gopls скрыты. Гейты: `:go-psi-core:test` 98, `:go-psi-semantic:test` 153, `:go-psi-ide:test` 522, `:test` 364, 0 падений; `checkKotlinAbi` зелёный;
  `:go-psi-ide:corpusTest` 4621/4621; бенчмарк go-psi-ide HEAD против базы cd920e2 подряд с остановленными демонами — HEAD не хуже базы (база падала по порогам
  чаще: 7 против 5; на этой машине пороги сейчас не выдерживает ни одна сторона). Робот (8083, Built-in, `store/wave3.go`, `editop.js` с новыми `smart`/`pick`,
  `dialog_ok.js`): smart → `W3Item{}` и `w3load` без `n`; `Emai` → `u.Profile.Email`; `Forma` → `money.Format` с импортом в группу модуля, без дубля каталога;
  Create function / method / field / variable / type, Implement `http.Handler` — по одному пункту, без gopls; Optimize Imports разнёс std / local; String() без
  `W3Crimson`; Equal с `slices`/`bytes`/`maps`/`.Equal`, `Fn` снят; «Plugin to blame: Go» 0. Робот нашёл и исправлено с тестами: импорт хоста (`GoImports.add`)
  вставлялся в конец блока, поле в однострочную структуру — на строку скобки, «Create variable 'time'» на `time.Time` без импорта. **Урок:** worktree агента
  создаётся от `master`, а не от текущей ветки — агенты сами делали `git reset --hard migration`; в брифе указывать базу явно. Клик робота по OK модального
  диалога в RDP не доходит, а скрипты с `invokeLater` без `ModalityState.any()` при открытом диалоге висят — `dialog_ok.js`.
- [x] Шаг 11, быстрые задачи (2026-10-03, три агента Sonnet, worktree от `migration` с наложенным незакоммиченным диффом в индексе — дифф агента потом
  `git add -N . && git diff`; версии 0.2.31–0.2.33): Ctrl+I хоста на `GoImplementStubs`, `GoDocComment`, `GoBuildConstraint` (парсер — `GoBuildConstraintEvaluator`
  project model, списки GOOS/GOARCH — `GoPlatforms`). Гейты: `:go-psi-ide:test` 548, `:test` 365, 0 падений. Робот (8083, Built-in, `store/wave4.go`,
  новый `enable_inspection.js`): 4 предупреждения doc-комментариев и оба фикса; Ctrl+I `W4Square` → `W4Shape`: `Area`/`Describe` с получателем `self`, `Name`
  не продублирован; `// +build linx,!cgo darwin` → два предупреждения, Add //go:build line → `(linx && !cgo) || darwin`, Replace with 'linux', `darwin &&` → ошибка
  синтаксиса; «Plugin to blame: Go» 0. Замечено: анонимный интерфейс в заглушке пишется как `interface{Write(…)}` (вид go/types, gofmt поставил бы пробелы).
- [x] Шаг 11, быстрые задачи, вторая партия (2026-10-03, три агента Sonnet в worktree от запушенного `556967b`; версии 0.2.34–0.2.36): `GoTimeLayout` и подсказка
  `go.time.layout`, `ide.directives` (`GoEmbedDirective`, ссылки embed / linkname / generate), автопопап тегов. Агент тегов написал второй провайдер completion,
  а у хоста уже был свой (`GoStructTagCompletionContributor`, `order="first"`, `stopHere`): робот показал только хостовые списки. Провайдер go-psi-ide удалён, его
  определение стиля встроено в хост (имя в стиле соседних полей первым), typed handler оставлен. Урок: перед брифом искать фичу и в хосте, а не только в FEATURES.
  Гейты: `:go-psi-ide:test` и `:test` без падений, checkKotlinAbi зелёный, корпус go-psi-ide 4621/4621. Робот (8083, Built-in, `store/wave5.go`, `wave5_embed.go`):
  подсказки раскладок у пяти вызовов, три предупреждения и оба фикса; `//go:embed` — две ошибки, Ctrl+B открыл `assets/greeting.txt`, фикс дал `import _ "embed"`;
  `json:` → `json:""` со списком, `userId` первым рядом с `firstName`, опции после запятой; «Plugin to blame: Go» за день 0.
- [x] Шаг 11, волна 4 — data flow (2026-10-03; версии 0.2.37–0.2.43). Каркас `semantic.flow` (CFG, liveness, reaching definitions, nilness) и первую партию
  проверок делал оркестратор с агентом Opus, затем три агента в worktree: линт без потока (`ide.inspections.lint`, Opus), ресурсы и конкурентность (Opus),
  остаток потока ошибок и nil (Sonnet). Новый гейт — `FlowCorpusTest` в `:go-psi-ide:corpusTest`: все 25 проверок по GOROOT/src, 0 падений; шум разобран
  регрессионными тестами (`error(nil)`, `unsafe.Sizeof`, сгенерированные файлы, обмен значений, повторный `Lock` под `defer Unlock`, флаг, отдача замка,
  `Unlock(bool)` не замок, `os.Exit` перед `return`, внутренний err с выходом, слайсы и nil-проверка результата); nilnil (195 на GOROOT) выключен по умолчанию.
  Гейты: `:test` 366, go-psi-core 98, go-psi-semantic 176, go-psi-ide 638, checkKotlinAbi и buildPlugin зелёные. Робот (8083, Built-in, `store/wave6.go`,
  `store/wave7.go`): все ожидаемые проблемы, фиксы Move defer / Remove assignment / Use a pointer receiver / Remove self-assignment / Assign the result /
  Return nil / Delete unreachable code; «Plugin to blame: Go» 0. Уроки: предупреждение на том же диапазоне, что и ошибка компилятора, платформа скрывает вместе
  с его фиксами (ShowIntentionsPass) — фикс к `append` повешен на ошибку чекера; новая проверка контекста дублировала `GoContextPlacement` на параметре —
  перед брифом искать существующую проверку той же темы. `GoGotoContributorTest` проверяет локальные имена по элементам: `processNames` отдаёт и
  устаревшие ключи постоянного тестового индекса (функция `local` из другого теста ломала его).
- [x] Шаг 11, после волны 4, первая партия (2026-10-03; три агента в worktree: Opus — иерархии, Opus — Introduce / Safe Delete, Sonnet — go.mod;
  версии 0.2.44–0.2.47). Гейты: `:test` 384, go-psi-core 98, go-psi-semantic 176, go-psi-ide 690, checkKotlinAbi и buildPlugin зелёные. Робот (8083,
  Built-in): go.mod — все 8 проблем; Ctrl+Alt+V `a + b` → `n`, `w*h` с выбором «Replace all 2 occurrences»; Ctrl+Alt+C → `const r8Failed`; Safe Delete
  функции с doc-комментарием, поля, конфликт на используемом поле; Call Hierarchy (вызывающие, «via H8Shape»), Type Hierarchy (подтипы, супертипы).
  Находки робота: `dialog_ok.js` закрывает диалог кодом OK и не вызывает `doOKAction` — Safe Delete так не выполняется, нужен клик по кнопке; нажатие из
  `ModalityState.any()` даёт SEVERE «Write-unsafe context» с нашим кодом в стеке (артефакт робота, у пользователя клик идёт в обычном контексте — вживую
  не проверено); битый `go 1.x` в go.mod gopls отвергает исключением в LSP-клиенте платформы. Тестовые дескрипторы go-psi-ide перечислены вручную в
  `go-psi-ide/src/test/resources/META-INF/plugin.xml` — новый `go-psi-ide-*.xml` добавлять и туда.
- [x] Шаг 11, вторая партия (2026-10-03; Opus — Rename Package, Opus — неиспользуемые параметры, Sonnet — инъекции RE2 / JSON; версии
  0.2.48–0.2.50). Строковый литерал стал хостом инъекций (mixin в `Go.bnf`; дерево и стабы те же, корпус go-psi-core без изменений, кроме времени);
  JSON — optional-зависимость плагина (`bundledPlugin("com.intellij.modules.json")` в сборке хоста и go-psi-ide). Корпус GOROOT показал 1004 срабатывания
  неиспользуемых параметров: экспортированные функции и `*testing.T` исключены (802 осталось), но корпус эту проверку не оценивает — поиск ссылок и
  реализаций по файлам GOROOT там не работает (присваивание полю и реализация неэкспортированного метода интерфейса пропускаются в проекте —
  регрессионный тест). Гейты: `:test` 384, go-psi-core 98, go-psi-semantic 176 (`GoDeepExpressionTest` однажды упал на защите от рекурсии, три
  перезапуска зелёные), go-psi-ide 725; checkKotlinAbi (дамп go-psi-core обновлён: `GoStringLiteral` — хост инъекций) и buildPlugin зелёные. Робот (8083,
  Built-in): три предупреждения параметров и три фикса (`u9Wait(4)`, `import "time"` удалён); ошибки lookahead / backreference / висячая запятая JSON и
  подсветка инъекций; переименование пакета с квалификатора — каталог, `package`, оба импорта (с алиасом и без), квалификатор. Переименование роботом
  запускается `RenameProcessor` в `ModalityState.nonModal()` (без диалога): нажатие кнопок диалога из `any()` давало SEVERE «Write-unsafe context».
- [x] Шаг 11, третья партия (2026-10-03; Sonnet — виджет GOOS/GOARCH и неиспользуемые `require`, Sonnet — SQL, Opus — Safe Delete параметров;
  версии 0.2.51–0.2.54). Удаление параметров вынесено в общий `GoParameterRemoval` (фикс инспекции и Safe Delete). Database — optional-зависимость
  (`bundledPlugin("com.intellij.database")` в хосте и go-psi-ide). Гейты: `:test` 400, go-psi-core 98, go-psi-semantic 176, go-psi-ide 747;
  checkKotlinAbi и buildPlugin зелёные. Робот (8083, Built-in): предупреждение `example.com/unused` и фикс (строка ушла, пустых строк нет); SQL-подсветка
  в `db.Query`, `tx.ExecContext` и `const …Query`, `"SELECT 1"` без инъекции (без источника данных Database показывает своё «No data sources are
  configured» — не наше); Safe Delete `y` в методе (вызов и method expression) и `b` с аргументом-вызовом; виджет `windows/amd64` → `js/wasm · integration`
  → обратно. Ошибок плагина в логе нет.
- [x] Шаг 11, партия рефакторингов (2026-10-03; пять агентов Opus по очереди, не больше трёх сразу: Extract, Inline, CI/SARIF, Change Signature,
  Move; версии 0.2.55–0.2.59). Move стартовал на worktree с наложенными патчами Extract и Inline; конфликт двух строк в `GoRename.kt` (Extract и
  Change Signature) решён вручную. Гейты: `:test` 411, go-psi-core 98, go-psi-semantic 176, go-psi-ide 843; checkKotlinAbi и buildPlugin зелёные.
  `go-inspect` вживую на playground: 48 инспекций, 27 файлов, 92 находки, код 1. Первый прогон нашёл три ошибки (GNU tar в `.cmd`, инспекции без
  индикатора прогресса — 1148 падений и пустой отчёт, код 0 при сплошных падениях) — исправлены. Робот (8083, Built-in): Extract выражения
  (`extracted(w, h) / 2`) и операторов (`a, b := extracted1(x)`); Inline переменной, вызова и константы (`(x + 1) * (10 + 5)`), отказ подсказкой;
  Change Signature процессором (`c9Total("a", 1, 2, 3)`, spread сохранён) и снимок диалога; Move `M9Item` с методом в новый пакет `m9model` (импорт,
  квалификатор) и снимок диалога. Снимок робота не видит модальных окон: диалог рисуется в PNG скриптом (`Window.paint`). Ошибок плагина в логе нет.
  Сделаны все рефакторинги плана: остаётся 12.1.
- [x] Шаг 11, пятая партия (2026-10-03; агенты Opus в worktree, не больше трёх сразу; версии 0.2.60–0.2.66). Робот проверил иерархию интерфейса
  (Change Signature по пяти объявлениям, Add Method с делегированием и заглушками), цикл импортов и internal, ассемблер. Форма Add Method,
  Unchecked error, настройки линтеров, Inspect Project и страницы в JCEF вживую ещё не смотрели — следующий проход робота.
  Решение пользователя: golangci-lint необязателен и выключен по умолчанию, проверки — свои (память `no-external-linters`).
- [x] 12.1 gopls опционален (0.2.82, 2026-10-03): выключен по умолчанию и не стартует. 12.2 «gopls удалён» — не делаем (решение
  пользователя 2026-10-03): код gopls остаётся за переключателем Language features.
- [x] 13 линтеры по запросу (0.2.60–0.2.66: golangci-lint необязателен, свои линтеры в его формате), [x] 13A vet-класс (2026-10-05, 0.2.181: все проверки списка были сделаны волнами 2–4 и G7; добавлено сравнение с nil
  значения, которое не бывает nil, и гейт `tools/vet/compare.py` против `go vet -json` по SARIF headless-прогона; playground: 0 vet-only при 33 наших находках, golang.org/x/tools
  v0.40.1 и playground для `go vet` чисты, так что гейт содержателен только на коде с находками), [x] 13B data-flow
  (волна 4), [x] 13C движок правил (0.2.67–0.2.76, 136 правил).
