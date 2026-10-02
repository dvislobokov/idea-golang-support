# go-psi API for library consumers

go-psi is consumed as an ordinary IntelliJ Platform plugin (`io.github.golangsupport`). A host plugin
declares `<depends>io.github.golangsupport</depends>` and compiles against the plugin jar. A working,
tested example lives in `samples/consumer-plugin` (see its README for the Gradle setup).

**Stability: `0.0.x` carries no compatibility promise.** The API may change in any release. The exact public
surface is recorded twice, and a change to either record is a reviewed diff: `docs/API-SURFACE.txt`
(readable, source-level, verified by `./gradlew apiSurfaceCheck`) and the per-module binary dumps
`<module>/api/<module>.api` (verified by `checkKotlinAbi`, see "Binary compatibility" below). A
binary-compatibility promise starts with the first `0.1.0`.

## Modules and packages

| Module | Public API packages | Contents |
|---|---|---|
| `go-psi-core` | `gopsi.lang.psi`, `gopsi.lang.stubs` | `GoFile`, generated PSI interfaces (`GoFunctionDeclaration`, `GoTypeSpec`, ...), `GoNamedElement`, `GoElementFactory`, token sets; stub classes `Go*Stub` |
| `go-psi-semantic` | `gopsi.semantic.api`, `gopsi.semantic.types`, `gopsi.project.api` | `GoSemanticService`, the `GoType` model, `GoPackageResolver`, `GoPackage`, `GoModuleGraph`, `GoToolchainInfo`, build contexts |
| `go-psi-ide` | `gopsi.ide.completion.api` | `GoCompletionRanker` extension hook |

Everything else (`*.impl`, `semantic.scope`, `semantic.resolve`, `ide.*` other than the above, element types,
stub builders) is `@ApiStatus.Internal` or lives in a non-API package. Do not depend on it.

In the shipped plugin all three modules are merged into one jar (`lib/plugin-<version>.jar`), so a consumer
needs a single `localPlugin(...)`/`plugin(...)` dependency.

## Entry points

```kotlin
val semantic = GoSemanticService.getInstance(project)          // project service
val packages = GoPackageResolver.getInstance(project)          // project service
val toolchain = GoToolchainProvider.getInstance().toolchainFor(project)  // application service, GoToolchainInfo?
val graph = GoModuleGraphProvider.getInstance(project).graphFor(vf)  // project service, GoModuleGraph?
```

- PSI: `PsiManager.findFile(vf) as? GoFile`; then `GoFile.packageName`, `imports`, `functions`, `methods`,
  `types`, `vars`, `consts`, `buildConstraint`, `isTestFile`. Every `GoNamedElement` has `name`, `isPublic()`,
  `nameIdentifier`, `docComment` (first `PsiComment` of the doc run) and `docText` (the `go/doc` rendering).
  Doc comments are bound into the declaration as its leading children; for specs of a grouped declaration
  (`var (...)`, `const (...)`, `type (...)`) the spec's own doc wins and the group's doc is the fallback, for
  `var`/`const` definitions the spec's doc is used, for fields the field declaration's. `GoFile.packageDoc` /
  `packageDocText` give the package doc. Doc lookups read the AST (they are not stub-based).
- Expression types: `semantic.typeOf(expr)`, `semantic.declarationType(namedElement)`,
  `semantic.constantValue(expr)`, `semantic.render(type)`. Unknown is a value (`GoUnknownType`), never an exception.
- Resolve: `semantic.resolve(GoReferenceExpression): List<PsiElement>` and `resolve(GoTypeReferenceExpression)`;
  the references themselves also resolve through `PsiReference.resolve()`.
- Calls and positions: `semantic.calleeSignature(call)` (the signature a call invokes, inferred type arguments
  substituted; null for builtins and conversions); `semantic.expectedTypeAt(expr): GoType?`, the type the
  position expects (assignment / declared variable type, call parameter by index with variadic tails and spread,
  none for conversions, `return` value by index — a `GoTupleType` of all results for a single call returning them
  —, composite literal element / key / struct field including elided nested literals, the other binary operand —
  an untyped constant yields to the typed side —, the element of a channel send, the tag of a `case`, `bool` for
  `if`/`for` conditions, `&&`, `||` and `!`, the key of a map index; null elsewhere, `<-ch` included);
  `semantic.enclosingResultTypes(element): List<GoType>?`, the result types of the innermost function declaration,
  method or function literal around the element (named results in order; empty for none; null outside functions).
- Members: `semantic.methodsOf(type)`, `lookupFieldOrMethod(type, name)`, `implements(type, iface)`.
- Diagnostics: `semantic.check(file)` returns go/types-style `GoDiagnostic`s.
- Packages: `GoPackageResolver.resolveImport(importPath, fromFile)` returns a `GoImportResolution`
  (`Resolved`, `InternalDenied`, `Unresolved`, `CPseudoPackage`), `packageOf(directory)` returns a `GoPackage`
  (import path, name, files per build context), `importPathOf(fileOrDirectory)`.
  `semantic.packageOf(goFile)` is the same for a PSI file.

## Language id: `"Go"`

The id of the language is `"Go"` (`GoLanguage.id`), **case-sensitive**. A host plugin registers its own
language-keyed extensions with it:

```xml
<idea-plugin>
  <depends>io.github.golangsupport</depends>
  <extensions defaultExtensionNs="com.intellij">
    <localInspection language="Go" shortName="MyGoInspection" groupName="Go" enabledByDefault="true"
                     implementationClass="my.plugin.MyGoInspection"/>
    <annotator language="Go" implementationClass="my.plugin.MyGoAnnotator"/>
  </extensions>
</idea-plugin>
```

`language="go"` (lowercase) is silently ignored by the platform: no error, the extension is just never found.
go-psi therefore logs a warning once per session (`idea.log`, logger `GoLanguageIdCheckActivity`) naming
every plugin that registered an extension with `language="go"` in one of the common language-keyed extension
points (parser definition, highlighter, annotator, inspection, completion, formatter, folding, documentation,
find usages, line markers, ...; the list is `GoLanguageIdCheck.EXTENSION_POINTS`).

## Resolving a package

```kotlin
val resolver = GoPackageResolver.getInstance(project)
when (val r = resolver.resolveImport("net/http", file.virtualFile)) {
    is GoImportResolution.Resolved -> r.pkg.goFiles          // VirtualFiles of the package for the build context
    is GoImportResolution.Unresolved -> r.reason
    else -> r.packageOrNull
}
```

## Threading and read actions

- All PSI, semantic and package-resolver reads need a read action (the platform's usual rule). Inspections,
  annotators, completion and references already run in one.
- Semantic methods never take write locks and are cached on go-psi's own modification trackers (not
  `PsiModificationTracker.MODIFICATION_COUNT`); calling them repeatedly is cheap.
- Do not call the semantic API on the EDT outside a read action, and never hold a `GoType` across read actions
  when it can be avoided: types are plain Kotlin values, but they point to PSI through declaration pointers that
  are validated on use.
- Resolve and `typeOf` work from stubs for other files and do not load their AST; `GoFunctionOrMethodDeclaration.block`
  and anything inside function bodies always load the AST of the file.

## Dumb mode

Tested behaviour (`GoDumbModeTest` in go-psi-semantic): `GoSemanticService.typeOf`, `declarationType`, `resolve`
(value and type references), `check`, `packageOf`, `GoPackageResolver.resolveImport`/`packageOf`/`importPathOf`
return normally in dumb mode, with exactly the same results as in smart mode, and never throw
`IndexNotReadyException`. go-psi-semantic uses no stub or file-based index: other files and packages are read
through the VFS and their stub trees/PSI, so it needs no guard and does not wait for indexing.

The stub indices (`gopsi.lang.stubs.index.*`) are queried only by go-psi-ide: go to symbol/class and the
implementations search/line markers. They are platform extension points that are not `DumbAware`, so the platform
skips them while indexing. Folding, exit-point highlighting and completion are `DumbAware` and use no index. A host
that queries the go-psi stub indices itself must check `DumbService.isDumb(project)` or use
`DumbService.runReadActionInSmartMode` (not on the EDT), like with any stub index.

## Extension points exposed to hosts

- `GoCompletionRanker` (`gopsi.ide.completion.api`), registered under the extension point `io.github.golangsupport.completionRanker`: score completion candidates (for example an ML ranker).
- `GoToolchainProvider` / `GoModuleGraphProvider` (`gopsi.project.api`): service interfaces a host can override with
  its own SDK model (the destination plugin supplies the toolchain from its settings).
- `GoReferenceProvider` (`gopsi.lang.psi`): application service through which `GoReferenceExpression` resolves (implemented by go-psi-semantic; not meant to be replaced).

## Build coordinates for consumers

Compile against the ZIP (`./gradlew :plugin:buildPlugin`, `plugin/build/distributions/go-psi-<version>.zip`) with
IntelliJ Platform Gradle Plugin 2.x: `localPlugin(file(...))`. The platform baseline is IntelliJ IDEA 2026.1 (build 261)
and Kotlin `apiVersion` 2.3. Code in a consumer is limited to Kotlin 2.3 stdlib APIs.

## Platform API notes

`./gradlew :plugin:verifyPlugin` against IntelliJ IDEA 261 (2026.1) and 262 (2026.2): compatible, no internal API,
no deprecated API. The remaining experimental usages are deliberate:

| Experimental API | Used in | Why it is needed |
|---|---|---|
| `com.intellij.model.Pointer` (reported on 261 only) | `GoDocumentationTarget.createPointer()` | The return type of `DocumentationTarget.createPointer()`, the platform's documentation API; there is no stable alternative. |
| `AdditionalLibraryRootsListener.fireAdditionalLibraryChanged` (+ the interface) | `GoRootsProvider` | After GOROOT/module-cache roots change, the platform must be told to re-index the synthetic library roots of the `AdditionalLibraryRootsProvider`; the stable `ProjectRootManagerEx.makeRootsChange` would re-index the whole project. |
| `StubIndex.getMaxContainingFileCount` | `GoImplementations.candidateCount` | A cheap upper bound of the files per fingerprint key, so the implementations search can skip hopeless, expensive stub queries. |

Fixed in this round: `CompletionConfidence.shouldSkipAutopopup(PsiElement, PsiFile, int)` (deprecated) is replaced by
the overload taking an `Editor`; the language id check avoids the plugin list API because
`PluginManagerCore.getPlugins()` and `PluginManager.getPlugins()` are internal on 262, so the check reads
the language-keyed extension points instead of the plugin list.

## Java callers and default arguments

Kotlin API methods with default arguments carry `@JvmOverloads` (constructors of `GoDiagnostic`, `GoParam`,
`GoMethod`, `GoInterfaceType`, `GoElementFactory.createFileFromText`, `GoLookup`, `GoTypeRenderer`,
`GoTypePredicates.comparable`). Interface methods cannot use `@JvmOverloads`; `GoPackageResolver.packageOf` and
`GoSemanticService.lookupFieldOrMethod`/`render` declare the shorter overloads explicitly. `docs/API-SURFACE.txt`
is the reference for what Java sees.

## Using go-psi-core and go-psi-semantic without the plugin

`./gradlew publishToMavenLocal` publishes two artifacts to `~/.m2` (version from `gradle.properties`, each with a
`-sources` jar):

```
io.github.golangsupport:go-psi-core:<version>       lexer, parser, PSI, stubs, indices (gopsi.lang.*)
io.github.golangsupport:go-psi-semantic:<version>   types, resolve, project model (depends on go-psi-core)
```

`go-psi-ide` is not published (it is meaningful only inside an IDE). The POMs do not declare the IntelliJ
Platform: it is `provided`, the consumer supplies it from its own IntelliJ Platform setup (go-psi is compiled
against IntelliJ IDEA 2026.1.5, build 261; the POM property `intellij.platform.version` records it).

```kotlin
repositories { mavenLocal(); /* plus the repositories of your IntelliJ Platform setup */ }
dependencies {
    implementation("io.github.golangsupport:go-psi-semantic:0.0.7")  // brings go-psi-core
    // the IntelliJ Platform itself: intellijPlatform { intellijIdea("2026.1.5") } or your own artifacts
}
```

Limitation: the PSI needs an IntelliJ application environment (application and project services, parser
definitions, the stub machinery, `Language`/`FileType` registries). Inside an IDE or a platform test fixture that is
given. A plain JVM tool has to build one with `CoreApplicationEnvironment` / `CoreProjectEnvironment` from the
platform core jars (register `GoLanguage`, its parser definition and file type, and the services declared in
`META-INF/go-psi-core.xml` and `META-INF/go-psi-semantic.xml`); go-psi does not ship such a bootstrap. The project
model reads toolchain information through `GoToolchainProvider`, which a host can replace.

## Binary compatibility

The JVM ABI of the public API is guarded by the Kotlin Gradle plugin's built-in ABI validation
(`kotlin { abiValidation { ... } }`, Kotlin 2.4.20; no extra plugin). It is configured once in the root
`build.gradle.kts` for `go-psi-core`, `go-psi-semantic` and `go-psi-ide`, and the committed dumps are
`go-psi-core/api/go-psi-core.api`, `go-psi-semantic/api/go-psi-semantic.api` and
`go-psi-ide/api/go-psi-ide.api`.

- Scope: classes (including nested classes, companions and file facades) of `gopsi.lang.psi`, `gopsi.lang.stubs`,
  `gopsi.semantic.api`, `gopsi.semantic.types`, `gopsi.project.api`, `gopsi.ide.completion.api`, minus their
  non-API subpackages (`lang.psi.impl`, `lang.stubs.index`) and anything annotated `@ApiStatus.Internal`. The
  Grammar-Kit generated PSI interfaces stay in. A new subpackage under an API package would show up as a dump diff;
  exclude it in the root script (`abiExcludedSubpackages`) if it is not API.
- `./gradlew checkKotlinAbi` (part of `check` and `build`) fails with a unified diff of the dump when the compiled
  API differs from the committed file. (`checkLegacyAbi`/`updateLegacyAbi` are deprecated aliases of the same tasks.)
- Update deliberately: review the diff, decide that the change is intended (in `0.0.x` any change is allowed, from
  `0.1.0` only additions without a deprecation cycle), run `./gradlew updateKotlinAbi`, and commit the `.api` files
  together with the code and a `CHANGELOG.md` line. Never run `updateKotlinAbi` just to make the build green.
- Roles of the two checks: the `.api` dumps are the binary contract per module (signatures, modifiers, supertypes,
  bridges/default-argument synthetics); `docs/API-SURFACE.txt` is the compact human-readable list across modules,
  produced from the merged plugin classpath. A public API change normally touches both; update both in one commit.
