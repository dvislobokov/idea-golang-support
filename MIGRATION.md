# Миграция: от gopls к своему PSI (go-psi) внутри idea-golang-support

Составлен 2026-10-02 после переноса кода go-psi в этот репозиторий (`82c34a1`). Анализ, на котором он основан, —
`docs/psi/MIGRATION-idea-golang-support.md` (инвентарь IGS, таблица точек расширения, риски); здесь — только порядок действий.
Статус шага отмечать прямо здесь (`[ ]` → `[x]` с датой и коммитом), сделанное переносить в `ROADMAP.md`, историю — в `CHANGELOG.md`
(про PSI-модули — в `docs/psi/CHANGELOG.md`).

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
   `tools/psi-ui-robot/autotest.py --perf` (P1–P9, цифры в `docs/psi/CHANGELOG.md`, запись «Performance wave»).
5. **Иконки, цвета, настройки, тексты — из IGS.** `GoIcons`, `colorSchemes/GoDefault.xml`/`GoDarcula.xml` с ключами
   `GO_*`, `GoSettings` и страницы Settings | Tools | Go, `GoBundle` (en/ru). PSI-модули своих не заводят; что есть в
   `go-psi-core/src/main/resources/icons` и в `ide.highlighting.GoColorSettingsPage` — удаляется на шаге 2.
6. **Жёсткие правила PSI-модулей** — раздел «go-psi» в `CLAUDE.md` (нет LSP внутри PSI, нет кода GoLand, тесты на
   грамматику/стабы/типы, `STUB_VERSION`, трекеры `GoTrackers` вместо `PsiModificationTracker`).

## Карта шагов

| # | Шаг | Объём | Зависит от | Статус |
|---|---|---|---|---|
| 0 | Перенос кода как библиотечных модулей | — | — | [x] 2026-10-02, `82c34a1` |
| 1 | Переключатели фич: `GoFeatures` + настройки + чтение в lsp-кастомайзерах | день | — | [ ] |
| 2 | Единые `GoLanguage`/`GoFileType`; переименование старых текстовых помощников | день | — | [ ] |
| 3 | Подключение модулей в плагин: Gradle + `xi:include`, без регистрации парсера | полдня | 2 | [ ] |
| 4 | Подмена парсера + мост `GoDeclaration` + токены (атомарно) | 2–3 дня | 1, 3 | [ ] |
| 5 | Полный гейт шага 4: тесты, корпус, робот, живая проверка | день | 4 | [ ] |
| 6 | Stub-индексы вместо `GoDeclarationIndex` и `GoExportsIndex` | 1–2 дня | 5 | [ ] |
| 7 | Project model `project.api` поверх `cli`/`mod`/`settings`; library roots | 2 дня | 5 | [ ] |
| 8 | Фичи с gopls на PSI, по одной за флагом (8a–8k) | по фиче | 6, 7 | [ ] |
| 9 | Текстовые инструменты IGS на PSI (бенефициары) | по инструменту | 5 | [ ] |
| 10 | Удаление старого: сканер, text-lexer, gopls-дубли, docs/psi → docs | день | 8 | [ ] |
| 11 | Волны `docs/psi/FEATURES.md` (новые фичи поверх PSI) | вехи | 8 | [ ] |

Критический путь: 2 → 3 → 4 → 5 → 6/7 → 8. Шаг 1 делается параллельно и нужен к началу 8; шаг 9 можно вести
параллельно с 6–8 по одному инструменту.

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
`docs/psi/MIGRATION-idea-golang-support.md` 4.3 и ниже).

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
`tools/psi-ui-robot/autotest.py --perf` — P1/P6/P7 не хуже цифр в `docs/psi/CHANGELOG.md`; живая проверка пользователем:
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
  при параллельной индексации (`docs/psi/PERF-BACKLOG.md` п. 8), повторное открытие — из индексов на диске.
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

## Шаг 10. Удаление старого

- Удалить `lang.GoDeclarations` (сканер) и мост `GoDeclarationPsi`, `GoTextLexer`/`GoTextTokens`, если не осталось
  потребителей (indent и typed handlers могут остаться на текстовом лексере — решить по факту), gopls-дубли фич,
  переведённых на `NATIVE` релиз назад; флаги этих фич убрать из настроек.
- `docs/psi/*` → `docs/` (один комплект документации), `docs/psi/CHANGELOG.md` → раздел в `CHANGELOG.md`;
  `tools/psi-ui-robot` слить с `tools/ui-robot` (сценарии P1–P9 и 16 шагов go-psi добавить к сценариям IGS).
- `ROADMAP.md`/`PLAN.md`: пункт «свой парсер» уровня 4 закрыт; `COMPARE.md` — колонка «без gopls».
- Лицензии: `LICENSE` для IGS и `NOTICE.md` (go/parser, `internal/types/testdata` — BSD-3 Go authors;
  фрагменты go-lang-idea-plugin — Apache-2.0) — до первого релиза с PSI.

## Шаг 11. Новые фичи

По волнам `docs/psi/FEATURES.md` §11: постфиксы/live templates (у IGS уже есть `GoPostfixTemplates`,
`liveTemplates/Go.xml` — объединять, не дублировать), inlay hints, struct size, Smart Enter (`GoSmartEnter` IGS
переводится на PSI), surround/unwrap, Code Vision, затем анализ (exhaustive switch, Printf, struct tags, error flow),
генерация и рефакторинги. Решение D1 (`go` вне project model) в IGS уже принято де-факто: run/test/coverage/debug
живут в IGS и зовут `go` и `dlv`; PSI-модули по-прежнему не зовут.

## Риски и как их снимаем

| Риск | Снятие |
|---|---|
| Регрессия всего, что висит на `GoDeclaration` при подмене парсера | шаг 4 атомарный; робот `structure.js`, `markers.js`, `targets.js`; живой прогон до merge |
| Двойные подчёркивания/дубли (gopls + PSI) в смешанной фазе | исключающие флаги (шаг 1), фильтр диагностик по `source` (8a/8g), дедупликация completion по lookup string |
| Индексация GOROOT и зависимостей при первом открытии (library roots) | только GOROOT/src и build list; shared indexes недоступны (`PERF-BACKLOG` п. 8); замер P7 до/после |
| Configuration cache: `corpusTest`/`benchmark` не сериализуются | запускать с `--no-configuration-cache`; обычный `test` — с кэшем |
| `--offline`: артефакты grammarkit/JFlex/abi-tools | уже в кэше Gradle; при смене версий — один онлайн-запуск, записать в CLAUDE.md |
| Split mode / remote dev, форки IDE без LSP-модуля | PSI-код в главном модуле не зависит от `lsp`; при `languageServerEnabled=false` все фичи `NATIVE` |
| Память: кэши типов (45 МБ на 16k строк GOROOT) | `GoCacheMemoryBenchmark`, P9; дальше — `docs/psi/LIBRARY-SUMMARIES.md` |
| Кодировка/EOL: часть перенесённых файлов с CRLF | `.gitattributes` IGS нормализует; при первом касании файла Git переписывает в LF |
| Лицензии не оформлены | шаг 10 до релиза |

## Чек-лист текущего состояния

- [x] Перенос: 3 подпроекта, `testData`, `tools`, `docs/psi`; `test checkKotlinAbi buildPlugin --offline` зелёные; ZIP без изменений.
- [ ] Шаг 1 — переключатели.
- [ ] Шаг 2 — единые `GoLanguage`/`GoFileType`.
- [ ] Шаг 3 — модули в плагине.
- [ ] Шаг 4–5 — парсер.
- [ ] Шаг 6 — stub-индексы. [ ] Шаг 7 — project model, library roots.
- [ ] 8a [ ] 8b [ ] 8c [ ] 8d [ ] 8e [ ] 8f [ ] 8g [ ] 8h [ ] 8i [ ] 8j.
- [ ] Шаг 9, [ ] шаг 10, [ ] шаг 11.
