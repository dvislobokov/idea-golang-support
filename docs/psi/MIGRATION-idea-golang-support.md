# Migration plan: go-psi into idea-golang-support

Status: analysis of `%USERPROFILE%\idea-golang-support` as of 2026-10-01 (HEAD `f732643`, plus an uncommitted
`GoBundle` / settings-page change in its working tree). Decision already taken: go-psi stays a standalone multi-module
project until its phases are stable, then its PSI (lexer, parser, PSI, stubs, indices, later project model, types and
resolve) moves into idea-golang-support, which remains the only shipped plugin. Everything idea-golang-support already has
(icons, bundle, settings, toolchain handling, run configurations, debugger, gopls integration) is reused, not duplicated.

Below, `IGS` = idea-golang-support, `igs` = package root `io.github.golangsupport`, `gopsi` = `io.github.golangsupport`.

## 1. Inventory

| Item | Value |
|---|---|
| Plugin id / name / vendor | `io.github.golangsupport` / "Go Project Support" / the author's GitHub login |
| Builds | `sinceBuild = "261"`, `untilBuild = null` (`build.gradle.kts`) |
| Platform | `intellijIdea(platformVersion=2026.1.4)` or `local(localIdePath)` = installed IDEA 2026.1.4 (`gradle.properties`) |
| IPGP | `org.jetbrains.intellij.platform` 2.19.0, single module; `bundledModule("intellij.platform.lsp")` |
| Kotlin / JDK | Kotlin plugin 2.3.21, `apiVersion = languageVersion = 2.3` (stdlib from the platform), JVM target 21 |
| Gradle | wrapper 9.7.1, `settings.gradle.kts` has only `rootProject.name`; configuration cache **on**, build cache **off**; tests need `--offline` on this machine |
| Descriptor | `src/main/resources/META-INF/plugin.xml` + content module `io.github.golangsupport.lsp` (`src/main/resources/io.github.golangsupport.lsp.xml`, own classloader by package prefix, depends on `intellij.platform.lsp` + `.impl`) |
| Own EPs | `io.github.golangsupport.languageServerControl` (`settings.GoLanguageServerControl`), `io.github.golangsupport.signatureProvider` (`lint.GoSignatureProvider`) |
| Tests | 32 classes in `src/test/kotlin/io/github/golangsupport/*Test.kt`; JUnit 4 pure tests + 7 `BasePlatformTestCase` (`GoPluginTest`, `GoExportsIndexTest`, `GoKeywordCompletionTest`, `GoValueCompletionTest`, `GoColorSchemeTest`, `GoPackageColoursTest`, `GoPagesTest`); test inputs are inline strings, no `testData/`; `src/test/resources/gopls-api.json` |
| UI checks | `runIdeForUiTests` (Remote Robot on 8083), `tools/ui-robot/robot.py`, scripts in `tools/ui-robot/scripts` |
| CI | none (no `.github/`); go-psi has `.github/workflows/build.yml` |
| License | none in either repository (no LICENSE/NOTICE files) |

Main source LOC (`src/main/kotlin/io/github/golangsupport`, ~18.4k lines, dense 180-column style):

| Package | Files | LOC | Package | Files | LOC |
|---|---|---|---|---|---|
| `lang` | 30 | 5329 | `testing` | 7 | 1118 |
| `lsp` (content module) | 17 | 2438 | `mod` | 7 | 980 |
| `debugger` | 11 | 1995 | `catalogue` | 5 | 774 |
| `monitor` | 9 | 1235 | `cli` | 2 | 400 |
| `run` | 9 | 1194 | `build` | 4 | 342 |
| `settings` | 6 | 1127 | `lint` | 2 | 332 |
| `sdk` | 2 | 289 | `templates` | 2 | 276 |
| `format` | 2 | 150 | `help` | 1 | 138 |
| `view` | 1 | 112 | root (`GoIcons`, `GoBundle`) | 2 | ~140 |

## 2. What exists today

**gopls wiring.** Platform LSP client API (not LSP4IJ), entirely inside the `lsp` content module:
`lsp.GoplsIntegrationProvider : LspIntegrationProvider` starts `lsp.GoplsDescriptor : ProjectWideLspClientDescriptor`
for `GoFileType` and `GoModFileType`. `GoplsDescriptor.lspCustomization` sets: `LspGoToDefinitionDisabled` (own
`GoplsGotoDeclarationHandler`), `LspFoldingRangeDisabled` (own folding), `LspSemanticTokensSupport` forced on for `GoFile`
with `GoSemanticColors.key`, `GoplsCompletionSupport : LspCompletionSupport` (re-ranking by prefix and expected type),
`LspDocumentHighlightsSupport` (setting `goplsHighlightUsages`), `LspCodeActionsSupport` with `intentionActionsSupport =
false`, code lens / commands routed to `GoplsCommands`. Diagnostics, hover, signature help, rename, inlay hints, find
references, workspace symbols use platform defaults. Settings restart the server through `GoLanguageServerControl`
(`lsp.GoplsControl`).

LSP-backed features: diagnostics and quick fixes, completion of references, hover/quick doc, signature help, rename,
semantic colouring, usage highlighting, Go to Declaration / Type Declaration / Implementation / Super, Find Usages
(`GoplsUsageSearcher`, `GoplsImplementationSearch`, `GoplsTargetElementEvaluator`), expression type
(`GoplsExpressionTypeProvider`), code vision counts, implementation gutter icons, gopls code actions as intentions
(`GoplsIntention0..9`, `GoplsFillStructIntention`, `GoplsOrganizeImportsIntention`, `GoplsActionsIntention`), unimported
packages in completion (`GoplsPackageCompletionContributor`), result count for lint fixes (`GoplsSignatureProvider`),
gopls menu actions, log tool window, status bar widget.

**Native language layer (no gopls).** There is a `Language`, a file type and a fake parser:

- `lang/GoLanguage.kt`: `object GoLanguage : Language("Go")`, `object GoFileType` (name "Go", ext `go`, icon
  `GoIcons.File`), `class GoFile : PsiFileBase` with `isTestFile` / `TEST_SUFFIX`.
- `lang/GoLexer.kt`: hand-written stateless `GoLexer : LexerBase` with coarse tokens from `lang/GoTokenTypes.kt`
  (`GoTokenType`; `KEYWORD`, `IDENTIFIER`, `OPERATOR`, `STRING`, `RAW_STRING`, `CHAR`, `NUMBER`, `DIRECTIVE`, brackets,
  `COMMENTS`, `STRINGS`, plus keyword/builtin name sets). No ASI, no per-operator tokens.
- `lang/GoDeclarations.kt`: token scanner `GoDeclarations.scan(text): GoFileStructure` (package, imports, top-level
  declarations, fields, interface methods, groups) with `GoDeclarationKind` / `GoDeclarationInfo` / `GoImport`.
- `lang/GoPsi.kt`: `GoParserDefinition` whose parser is `GoTreeBuilder` (one marker per scanned declaration, flat tokens
  elsewhere), old `GoElementTypes` (one `DECLARATION_<KIND>` type per kind), PSI class `GoDeclaration :
  ASTWrapperPsiElement, PsiNameIdentifierOwner` (data from `GoStructure.of(file)`, a cached text scan), structure view,
  breadcrumbs. No stubs; `IFileElementType(GoLanguage)`.
- Indices: `lang.GoDeclarationIndex` (file-based, id `golang.declarations`, `T:`/`I:`/`M:` keys, version 2) and
  `catalogue.GoExportsIndex` (file-based, exported names/signatures per file).

Inputs of native analyzers: own lexer (`GoIndent`, `GoEditing` folds/comments/template contexts, `GoSyntaxHighlighter`,
`GoDebugSupport`, `GoSubtests`), text scanner (`GoDeclarations`, `GoCatalogue`, `GoInterfaceSources`, run/test producers,
`GoTestExplorerModel`), regexes on text (`GoIdioms` 38 regexes, `GoGenerators`, `GoInterfaces`, `GoStructTags`,
`GoExpectedTypes`, `GoKeywordTemplates`, `GoFieldAlignment`). PSI leaf token checks
(`elementType == GoTokenTypes.IDENTIFIER`, `in COMMENTS/STRINGS`) are in `GoIdentifierAnnotator`, `GoFindUsagesProvider`,
`GoCatalogueCompletionContributor`, `GoKeywordCompletionContributor`, `GoValueCompletionContributor`,
`GoRunLineMarkerContributor`, `lsp.GoplsPackageCompletionContributor`, `lsp.GoplsTypes`, `lsp.GoplsLineMarkers`,
`lsp.GoplsNavigation`. No TextMate.

**go.mod / go.work.** `mod/GoModLanguage.kt`: `GoModLanguage("GoModule")`, `GoModFileType` ("Go Module", file names
`go.mod;go.work`), hand-written `GoModLexer`, flat `GoModParserDefinition`, highlighter, commenter, folding,
completion (`GoModCompletionContributor`). Pure model `mod/GoModFile.kt` (`GoRequire`, `GoReplace`), project service
`mod.GoModulesService` (nearest go.mod, all modules via `FilenameIndex`, `workspace()` for go.work, vendor excluded),
Dependencies tool window, tidy banner (`GoModNotificationProvider`, `GoModSaveListener`), project-tree dependency nodes
(`view.GoDependenciesTreeProvider`, sources from the module cache, deliberately **not** library roots).

**Toolchain.** No `SdkType`, no project SDK. `cli.GoCli.findExecutable()` (setting `GoSettings.goPath` = path of the
`go` executable, else PATH/GOROOT/well-known dirs), `cli.GoEnvironment` (`go env -json`: GOROOT, GOPATH, GOBIN),
`cli.GoTool` (gopls, dlv, golangci-lint, goimports lookup and `go install`), `sdk.GoToolchainCheckActivity`,
`sdk.GoEnvironmentAction` ("Go on This Machine"). Build tags: `GoSettings.buildTags`. No GOOS/GOARCH setting, no GOPATH
mode model.

**Run/test/debug/format.** `run.GoConfigurationType` / `GoRunConfiguration` (`go run` / `go test`),
`GoRunConfigurationProducer`, `GoRunLineMarkerContributor`, `GoRunConfigurationGenerator`; `testing.GoTestRunState`
(`go test -json` to SM runner), `GoTestLocator`, Go Tests tool window, coverage, benchmarks; own DAP client to `dlv dap`
(`debugger.GoDebugRunner`, `GoDebugProcess`, breakpoint types `GoLineBreakpointType`, `GoPanicBreakpointType`,
`GoFunctionBreakpointType`); `format.GoFormattingService : AsyncDocumentFormattingService` (gofmt / goimports /
golangci-lint fmt / none, setting `formatter`), `format.GoFormatOnSave`; `lint.GoLintAnnotator` (golangci-lint external
annotator), `build.GoBuildProblemsAnnotator` (last `go build` errors, only when gopls is absent or off).

**UI resources.** `GoIcons` (root package; `/icons/*.svg` + `_dark`, `forFile()` used by `view.GoFileIconProvider`),
`GoIconMappings.json` (expui tool-window icons), `GoBundle` (`messages/GoBundle.properties` + `_ru`, own language
setting, used by settings pages; `GoBundleTest` checks key parity). Settings: application service `settings.GoSettings`
(`@State GoSupportSettings`, `golang-support.xml`); pages `GoSettingsConfigurable` (Tools | Go), `GoLanguageServerConfigurable`,
`GoplsSettingsConfigurable`, `GoDebuggerConfigurable`, `GoEditorConfigurable`, `GoCodeQualityConfigurable`. Notification
group `Go` (`GoCli.NOTIFICATION_GROUP`). Tool windows: Go Dependencies, Go Monitor, Go Tests, gopls. Actions: `Go.MainMenu`
group (Build, Vet, Generate, Modules, Reanalyze `build.GoReanalyzeAction`, Environment, Debugger, Help, Welcome),
`Go.GenerateGroup`, `Go.ProjectViewPopup`, `Go.Gopls` (lsp module). Colour schemes `colorSchemes/GoDefault.xml`,
`GoDarcula.xml` keyed by `GO_*` attribute names; live templates `liveTemplates/Go.xml`; file templates.

## 3. Extension points

Legend: **keep** = unchanged; **rewire** = same registration, implementation moved to new tokens/PSI; **replace** = class
replaced by go-psi; **switch** = must become switchable between gopls and go-psi.

| EP | Class | Verdict |
|---|---|---|
| fileType Go | `lang.GoFileType` | keep (single registration; go-psi's is dropped) |
| fileType Go Module | `mod.GoModFileType` | keep; go-psi must not register go.mod |
| fileIconProvider | `view.GoFileIconProvider` | keep |
| testSourcesFilter | `testing.GoTestSourcesFilter` | keep |
| lang.parserDefinition Go | `lang.GoParserDefinition` | **replace** by `lang.parser.GoParserDefinition` |
| lang.syntaxHighlighterFactory Go | `lang.GoSyntaxHighlighterFactory` | rewire (new lexer, existing keys) |
| annotator Go | `lang.GoIdentifierAnnotator` | rewire to PSI, later resolve-based |
| lang.psiStructureViewFactory | `lang.GoStructureViewFactory` | replace by PSI-based |
| breadcrumbsInfoProvider | `lang.GoBreadcrumbsProvider` | replace by PSI-based |
| lang.foldingBuilder Go | `lang.GoFoldingBuilder` | keep (text scan works on any PSI), PSI later |
| testFinder / testCreator | `lang.GoTestFinder` / `GoTestCreator` | rewire (`GoDeclaration` lookups) |
| fileBasedIndex | `lang.GoDeclarationIndex` | keep until stub indices, then delete |
| fileBasedIndex | `catalogue.GoExportsIndex` | keep until stub indices, then replace |
| lang.findUsagesProvider | `lang.GoFindUsagesProvider` | rewire; WordsScanner from new lexer |
| gotoClassContributor / gotoSymbolContributor | `lang.GoGotoClassContributor` / `GoGotoSymbolContributor` | rewire now, replace by stub-index contributors in Phase 3 |
| lang.commenter / braceMatcher / quoteHandler | `lang.GoCommenter` / `GoBraceMatcher` / `GoQuoteHandler` | rewire to `GoTypes` tokens |
| lineIndentProvider, enterHandlerDelegate, langCodeStyleSettingsProvider | `lang.GoLineIndentProvider`, `GoEnterBetweenBracketsHandler`, `GoCodeStyleSettingsProvider` | keep (text lexer); revisit with formatter |
| inline.completion.provider | `lang.GoInlineIdiomsProvider` | keep |
| additionalTextAttributes, colorSettingsPage | `colorSchemes/*`, `lang.GoColorSettingsPage` | keep; drop go-psi's page |
| defaultLiveTemplates, liveTemplateContext, liveTemplateMacro | `Go.xml`, `GoTemplateContext`, `GoTypeNameMacro`, `GoErrorReturnMacro` | keep; context later via PSI |
| completion.contributor | `GoStructTagCompletionContributor`, `GoReturnCompletionContributor`, `GoKeywordCompletionContributor`, `GoValueCompletionContributor`, `catalogue.GoCatalogueCompletionContributor`, `debugger.GoExpressionCompletionContributor` | keep, rewire token checks; **switch** catalogue vs native reference completion |
| codeInsight.template.postfixTemplateProvider, codeInsight.implementMethod, lang.smartEnterProcessor, lang.surroundDescriptor | `GoPostfixTemplateProvider`, `GoImplementMethodsHandler`, `GoSmartEnterProcessor`, `GoSurroundDescriptor` | keep; PSI-based later |
| typedHandler, editorTypedHandler | `GoDocCommentTypedHandler`, `GoLayoutTypedHandler` | keep |
| intentionAction (8) | `GoHandleErrorIntention` ... `GoGenerateTestIntention` | keep |
| postStartupActivity | `GoCatalogueStartupActivity`, `GoProjectInterfacesStartup`, `GoWelcomePageActivity`, `GoToolchainCheckActivity`, `GoPluginAdvisorActivity`, `GoRunConfigurationStartupActivity` | keep |
| lang.parserDefinition / syntaxHighlighterFactory / commenter / foldingBuilder / completion.contributor GoModule | `mod.*` | keep |
| toolWindow, editorNotificationProvider, treeStructureProvider | Go Dependencies, `GoModNotificationProvider`, `GoDependenciesTreeProvider` | keep |
| notificationGroup, projectConfigurable (6), iconMapper, registryKey | `Go`, settings pages, `GoIconMappings.json` | keep; add go-psi options to existing pages |
| programRunner, xdebugger.*, attachDebuggerProvider | `debugger.*` | keep |
| configurationType, runConfigurationProducer, runLineMarkerContributor, consoleFilterProvider | `run.*` | keep; producer/marker rewired |
| formattingService, actionOnSave | `format.GoFormattingService`, `GoFormatOnSave` | keep; **switch** with native formatter (Phase 6) |
| internalFileTemplate, defaultTemplatePropertiesProvider, directoryProjectGenerator, newProjectWizard.generator | `templates.*` | keep |
| externalAnnotator | `lint.GoLintAnnotator`, `build.GoBuildProblemsAnnotator` | keep |
| lsp: platform.lsp.integrationProvider | `GoplsIntegrationProvider` | keep, customizers **switch** per feature |
| lsp: codeInsight.gotoSuper, gotoDeclarationHandler, targetElementEvaluator, typeDeclarationProvider, lang.expressionTypeProvider, customUsageSearcher, definitionsScopedSearch | `GoplsGotoSuperHandler`, `GoplsGotoDeclarationHandler`, `GoplsTargetElementEvaluator`, `GoplsTypeDeclarationProvider`, `GoplsExpressionTypeProvider`, `GoplsUsageSearcher`, `GoplsImplementationSearch` | **switch**; stand down when native resolve is on (Phase 5) |
| lsp: highlightUsagesHandlerFactory | `GoplsHighlightUsagesHandlerFactory` | switch (Phase 5/6) |
| lsp: intentionAction (13) | `GoplsIntention0..9`, `GoplsOrganizeImportsIntention`, `GoplsFillStructIntention`, `GoplsActionsIntention` | keep (gopls refactorings stay) |
| lsp: completion.contributor | `GoplsPackageCompletionContributor` | switch (native import index) |
| lsp: lineMarkerProvider, daemonBoundCodeVisionProvider (2) | `GoplsImplementationLineMarkerProvider`, `GoplsUsagesCodeVisionProvider`, `GoplsImplementationsCodeVisionProvider` | switch (Phase 6) |
| lsp: typedHandler, statusBarWidgetFactory, toolWindow gopls, `languageServerControl`, `signatureProvider` | `GoplsArgumentTypedHandler`, `GoplsStatusWidgetFactory`, `GoplsLogToolWindowFactory`, `GoplsControl`, `GoplsSignatureProvider` | keep; add a native `GoSignatureProvider` ordered first |

New registrations brought by go-psi (via an included `META-INF/go-psi-core.xml` without the `fileType` line):
`stubElementTypeHolder` / stub element types, `stubIndex` entries, later `psi.referenceContributor` or mixin references,
`lang.elementManipulator`, `lang.namesValidator`, `lang.refactoringSupport`, `codeInsight.parameterInfo`.

## 4. Transplant plan

### 4.1 Target layout

Keep the root package `io.github.golangsupport`; the transplant is the textual rename
`io.github.golangsupport` -> `io.github.golangsupport`. Resulting packages:

| go-psi | IGS |
|---|---|
| `gopsi.lang.lexer` (`GoLexer`, `_GoLexer`) | `igs.lang.lexer` |
| `gopsi.lang.parser` (`GoParser`, `GoParserUtil`, `GoParserDefinition`) | `igs.lang.parser` |
| `gopsi.lang.psi`, `.psi.impl` (`GoTypes`, `GoTokenType`, `GoElementType`, `GoTokenSets`, `GoFile`, mixins) | `igs.lang.psi`, `igs.lang.psi.impl` |
| `gopsi.lang.stubs`, stub indices | `igs.lang.stubs`, `igs.lang.stubs.index` |
| `gopsi.lang.GoLanguage`, `GoFileType`, `GoIcons` | **dropped**: existing `igs.lang.GoLanguage`, `igs.lang.GoFileType`, `igs.GoIcons` |
| `gopsi.semantic.*` | `igs.semantic.*` (`api`, `types`, `resolve`, `cache`) |
| `gopsi.project.*` | `igs.project.*`, consuming `igs.mod` and `igs.cli` (no second go.mod model) |
| `gopsi.ide.*` | merged into existing `igs.lang` editor classes; new features in `igs.ide.<feature>` |

Gradle: keep the go-psi split as subprojects of IGS. `settings.gradle.kts`: `include("go-psi-core", "go-psi-semantic")`;
root `build.gradle.kts` adds `pluginComposedModule(implementation(project(":go-psi-core")))` and the same for
`:go-psi-semantic` (classes land in the main plugin jar, same classloader; this is what go-psi's `plugin` module does).
`go-psi-ide` is not transplanted as a module: its classes merge into the root module next to the code they replace.
The subprojects apply `org.jetbrains.intellij.platform.module` (+ `...grammarkit` for core) with the same 2.19.0 version
and the root's Kotlin 2.3.21 with `apiVersion/languageVersion = 2.3`. `GoLanguage`, `GoFileType` and `GoIcons` (with
`src/main/resources/icons/go*.svg`) move physically into `go-psi-core/src/main/...` **without changing their package**, so
core compiles standalone and the 30+ consumers keep their imports. Grammar in `go-psi-core/src/main/grammar/Go.bnf|Go.flex`,
generated sources in `go-psi-core/build/generated/sources/grammarkit-*` (never committed). `testData/` at the IGS root
(`testData/lexer`, `parser`, `stubs`, `metrics`, ...), passed by the root `subprojects { tasks.withType<Test> }` block
as `gopsi.testDataPath`; property names `gopsi.*` stay as strings to avoid rewriting test bases. `corpusTest` stays an
`intellijPlatformTesting.testIde` task in `go-psi-core`, run with `--no-configuration-cache`; add `opentest4j` to
test dependencies. `tools/astdump` moves to `IGS/tools/astdump`.

### 4.2 Pieces dropped in favour of IGS

- `gopsi.lang.GoIcons` and `go-psi-core/src/main/resources/icons/go.svg`: use `igs.GoIcons.File` (and `forFile`).
- go-psi's `GoLanguage`/`GoFileType`/`fileType` registration: the IGS ones (id "Go", name "Go") are identical in id.
- `gopsi.ide.highlighting.GoHighlightingColors`, `GoColorSettingsPage`, `GoSyntaxHighlighterFactory`: the token map moves
  into `igs.lang.GoSyntaxHighlighter`, keys stay `GoSyntaxHighlighter.KEYWORD` etc. (external names `GO_*` match the colour
  schemes and `GoColorSchemeTest`; add `GO_IDENTIFIER`, `GO_RUNE` there if needed).
- Phase 4 SDK detection and go.mod/go.work grammar: replaced by `GoCli.findExecutable`, `GoEnvironment`,
  `GoSettings.goPath/buildTags`, `GoModFile`, `GoModulesService`. go-psi defines interfaces in `project.api`
  (`GoToolchainInfo`: GOROOT, version, GOPATH, GOMODCACHE, GOOS/GOARCH, tags; `GoModuleGraph`) and IGS implements them.
- Any go-psi settings or texts: fields in `GoSettings`, rows on the existing pages, strings in `GoBundle*.properties`.
- go-psi `plugin/` module, its `plugin.xml`, id `io.github.golangsupport`, CI workflow (copied to IGS instead).

### 4.3 Old classes that clash with go-psi simple names

Rename the text-level helpers before the swap so the new PSI names are free: `lang.GoLexer` -> `lang.GoTextLexer`,
`lang.GoTokenType`/`GoTokenTypes` -> `GoTextTokenType`/`GoTextTokens` (still used by `GoIndent`, `GoEditing`,
`GoDeclarations`, `run.GoDebugSupport`, `testing.GoSubtests`; they are text tools, never PSI leaves). Delete
`lang.GoParserDefinition`, `GoTreeBuilder`, old `GoElementTypes`, `GoDeclaration`, and `lang.GoFile` (its `isTestFile` /
`TEST_SUFFIX` move into `igs.lang.psi.GoFile`; 41 files change one import).

### 4.4 Ordered steps

1. **In go-psi first:** set Kotlin `apiVersion/languageVersion = 2.3`; add `<incompatible-with>io.github.golangsupport</incompatible-with>`
   to go-psi's `plugin.xml`; give `GoFile` `isTestFile`; keep icons/file type trivial. *Verify by:* `./gradlew build` green in go-psi.
2. **Wait for a clean IGS tree** (current uncommitted `GoBundle` work committed). *Verify by:* `git status` clean.
3. **Copy and rename:** copy `go-psi-core`, `go-psi-semantic`, `testData`, `tools/astdump`; run a sed over `*.kt`, `*.java`,
   `Go.bnf`, `Go.flex`, `*.xml`: `io.github.golangsupport` -> `io.github.golangsupport`, `io/github/golangsupport`
   -> `io/github/golangsupport`; move directories accordingly. *Verify by:* `rg` for the old package name returns nothing.
4. **Gradle wiring** (4.1). *Verify by:* `./gradlew.bat :go-psi-core:generateParser :go-psi-core:generateLexer compileKotlin -q --offline`
   with `JAVA_HOME` = IDEA 2026.1.4 JBR; grammarkit/JFlex artifacts may need one online run.
5. **Unify language objects:** delete go-psi `GoLanguage/GoFileType/GoIcons`, move IGS ones into core, rename the clashing
   old classes (4.3). *Verify by:* `--tests "io.github.golangsupport.GoPluginTest"` and core `GoFileTypeTest`.
6. **Swap the parser (atomic with 7 and 8):** in `plugin.xml` point `lang.parserDefinition language="Go"` at
   `io.github.golangsupport.lang.parser.GoParserDefinition`; `xi:include` `/META-INF/go-psi-core.xml` (fileType line removed).
   *Verify by:* go-psi parser golden tests inside IGS, `GoDeclarationsTest`, `GoEditingTest`.
7. **Bridge `GoDeclaration` consumers:** replace the PSI class with a helper `lang.GoDeclarationPsi.of(element)` that maps
   go-psi named declarations (`GoFunctionDeclaration`, `GoMethodDeclaration`, `GoTypeSpec`, field/const/var definitions,
   `GoMethodSpec`) to `GoDeclarationKind` and looks up `GoDeclarationInfo` by **name offset** (doc comments may extend PSI
   ranges). Rewrite: `GoStructureViewFactory`, `GoBreadcrumbsProvider`, `GoGotoContributor`, `GoFindUsagesProvider`,
   `GoTestFinder`, `GoIdentifierAnnotator`, `GoRunLineMarkerContributor`, `lsp.GoplsGotoSuperHandler`,
   `GoplsImplementationLineMarkerProvider`, `GoplsTargets`/`GoplsGotoDeclarationHandler`/`GoplsUsageSearcher`/
   `GoplsImplementationSearch`, `GoplsTargetElementEvaluator`/`GoplsTypeDeclarationProvider`/`GoplsExpressionTypeProvider`,
   code vision. *Verify by:* `GoInterfacesTest`, `GoTestNavigationTest`, robot `structure.js`, Ctrl+click and code vision live.
8. **Rewire token consumers:** `IDENTIFIER`/`DOT`/comment/string checks to `GoTypes.IDENTIFIER`, `GoTokenSets.COMMENTS`,
   `STRING_LITERALS` in the files listed in section 2; `GoCommenter`, `GoBraceMatcher`, `GoQuoteHandler`,
   `GoSyntaxHighlighter` map; `GoSemanticColors` unchanged. *Verify by:* `GoKeywordCompletionTest`, `GoValueCompletionTest`,
   `GoColorSchemeTest`, robot editor snapshot.
9. **Full gate:** `./gradlew.bat test buildPlugin -q --offline`, `:go-psi-core:corpusTest --no-configuration-cache`,
   `runIdeForUiTests` on `playground`, 0 "Plugin to blame: Go" in `idea.log`.
10. **Stubs (Phase 3):** register stub indices; move Go to Class/Symbol to them; reimplement `GoProjectInterfaces` and
    project packages of the catalogue over stubs; delete `GoDeclarationIndex` and `GoExportsIndex`. *Verify by:* go-psi stub
    tests, `GoExportsIndexTest` ported, `AstLoadingFilter` assertions.
11. **Project model (Phase 4)** behind `project.api`, implemented by `cli`/`mod`/`settings`. *Verify by:* fixture projects in
    `testData/project`, Go | Reanalyze also drops go-psi caches.
12. **Resolve/types (Phase 5) and IDE features (Phase 6)** switched per feature (section 5).

## 5. Coexistence with gopls

| Feature | Source until | Switch mechanism |
|---|---|---|
| Syntax errors | go-psi from step 6 | native `PsiErrorElement`s always; filter gopls parse diagnostics (`diagnosticsCustomizer` = custom `LspDiagnosticsSupport`) to avoid two underlines |
| Semantic diagnostics | gopls until Phase 6 inspections | `diagnosticsCustomizer` -> `LspDiagnosticsDisabled` per setting; `GoBuildProblemsAnnotator.shouldShow()` extended |
| Completion of references | gopls until Phase 6 | `completionCustomizer` -> `LspCompletionDisabled`; `GoplsCompletionSupport` ranking moves to a native weigher |
| Hover / quick doc | gopls until Phase 6 docs | `hoverCustomizer` -> `LspHoverDisabled` |
| Go to declaration, usages, implementations | gopls handlers until Phase 5 | gopls handlers return null when native resolve is on; then remove their registrations |
| Rename | gopls until Phase 6 | `renameCustomizer` -> `LspRenameDisabled`, native `RenamePsiElementProcessor` |
| Formatting | `GoFormattingService` (gofmt) | stays default; native `FormattingModelBuilder` only with `GoFormatter.NATIVE`; `formattingCustomizer` -> `LspFormattingDisabled` |
| Semantic colours | gopls tokens over `GoIdentifierAnnotator` | `semanticTokensCustomizer` -> `LspSemanticTokensDisabled` when the resolve annotator is on |
| Refactorings, vulncheck, upgrades, code actions | gopls | stay; no plan to replace |

Mechanism: one enum-per-feature setting in `GoSettings` (`GOPLS`/`NATIVE`, defaults follow the phase), read through a small
main-module service `igs.lang.GoFeatures.native(feature, project)` that also returns false in dumb mode. The `lsp`
customizers read it when the descriptor is built; changing the setting already calls
`GoLanguageServerControl.restartAll`, which rebuilds the descriptor. Native contributors/annotators check the same
service and return early. Ordering is not a reliable deduplication tool; prefer exclusive switches. During a mixed
phase, an `order="first"` completion contributor may run `result.runRemainingContributors` and drop lookup strings
already produced natively.

## 6. Public API go-psi must expose

- `lang.psi.GoFile`: `packageName`, `imports` (path, alias, dot/blank), `topLevelDeclarations`, `isTestFile`,
  `buildConstraint`, `directives` (`//go:generate`, `//go:embed`), plus generated PSI interfaces and `GoNamedElement`.
- `lang.stubs` indices: package-level names by package, functions, methods by receiver, types, interfaces, exported names.
- `project.api`: `GoPackage` (import path, name, directory, files for the current build context), `GoPackageService.of(dir|file)`,
  `GoImportResolver.resolve(importPath, fromFile)`, `GoModuleGraph`, and the `GoToolchainInfo` interface IGS implements.
- `semantic.api`: `resolve(GoReferenceExpression)`, `typeOf(GoExpression): GoType`, `expectedTypeAt(file, offset)`,
  `signatureOf(GoCallExpr)` (params, results), `methodSet(GoType)`, `implementations(GoInterfaceType)`, `fieldsOf(struct)` with sizes.

First beneficiaries: `lint.GoSignatureProvider` (native implementation ordered before `GoplsSignatureProvider`, no gopls
round-trip); `lang.GoExpectedTypes` and `GoplsCompletionSupport` expected-type ranking; `GoIdioms.returnValues` and
`GoReturnCompletionContributor` (result types of the enclosing function); `GoImplementInterface`, `GoProjectInterfaces`,
`catalogue.GoInterfaceSources` (method sets from stubs); `GoStructTags`, `GoFieldAlignment`, `GoGenerators` (struct PSI);
`GoKeywordTemplates` (variables in scope for `for range`); `run.GoDebugSupport` (`GoHoverExpression`, `GoInlineValues`,
`GoBreakpointLines`) and `testing.GoSubtests` (PSI instead of tokens); `GoIdentifierAnnotator` (shadowing-aware colours).

## 7. Risks and incompatibilities

- **Kotlin:** go-psi uses 2.4.20 without API limits; IGS pins 2.3 because the platform stdlib is 2.3.20. Compile go-psi
  with `apiVersion/languageVersion = 2.3` now, or calls into 2.4 stdlib fail at runtime.
- **Platform version:** go-psi tests on downloaded 2026.1.5, IGS on local 2026.1.4 (both 261). Align on one; IGS's
  `localIdePath` will not exist on CI. `verifyPlugin` with 2026.2.x should move into IGS's build.
- **Gradle:** IGS has configuration cache on and needs `--offline` for tests (proxy); grammarkit downloads Grammar-Kit and
  JFlex, and `testIde` corpus tasks are not CC-compatible. Use `--no-configuration-cache` for `corpusTest`.
- **Duplicate types:** both projects register language "Go" and file type "Go"; a sandbox with both plugins fails. go-psi
  must be declared incompatible with `io.github.golangsupport` until the merge. Colour key external names `GO_*` exist in
  both; only one `createTextAttributesKey` per name may remain.
- **Stub/index ids:** pick stable external ids (`go.FILE` already used by go-psi; keep `golang.*` for remaining file
  indices); bumping `GoFileElementType.STUB_VERSION` reindexes user projects.
- **Library roots vs catalogue:** go-psi Phase 4 plans `AdditionalLibraryRootsProvider` for GOROOT and GOMODCACHE; IGS
  deliberately avoids library roots (catalogue files, dependency tree nodes) to keep indexing cheap. Resolve needs stubs of
  dependencies, so this is a design decision to make explicitly (likely: library roots for GOROOT and direct deps only).
- **Content module:** go-psi code must live in the main module; the `lsp` module can see it, not the other way round.
- **Split mode / remote dev:** IGS runs everything on the backend; keep `go-psi-core` free of non-core APIs so a frontend
  lexer/parser module remains possible; typed handlers (`GoLayoutTypedHandler`) and highlighting latency need a live check
  in split mode and on GIGA IDE forks.
- **Licensing:** neither repo has a LICENSE. Ported go/parser logic and `internal/types/testdata` are BSD-3 (Go authors);
  go-lang-idea-plugin fragments are Apache-2.0. Choose an IGS license and add `NOTICE.md` before shipping.
- **Tests:** IGS uses inline strings and quick tests; go-psi adds golden files, `updateGoldens`, corpus gates needing GOROOT
  and GOMODCACHE, `opentest4j`. Keep `*CorpusTest` excluded from `test`; full `test` time will grow noticeably.
- **Behaviour regressions:** structure view, breadcrumbs, folding, Go to Symbol and gopls navigation all hang on
  `GoDeclaration`; steps 6-8 must land in one change, verified with the UI robot.
