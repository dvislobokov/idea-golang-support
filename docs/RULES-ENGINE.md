# The lint rule engine

Package `io.github.golangsupport.ide.rules` (module `go-psi-ide`). Native ports of golangci-lint linters, staticcheck checks and
revive rules run as **rules** of one inspection, `GoRules` ("Go lint rules", group Go). The platform walks a file once; the
dispatcher hands each node only to the rules subscribed to its kind. Native rules are the default analysis; golangci-lint is optional.

## Adding a rule

```kotlin
package io.github.golangsupport.ide.rules.builtin

/** revive `early-return`-like example: an `if` whose only statement is `return` at the end of a function. */
class GoMyRule : GoStatementRule() {
    override val id get() = "revive:my-rule"          // golangci-style id, shown as "[revive:my-rule] ..."
    override val linter get() = "revive"               // //nolint:revive and the .golangci.yml section
    override val title get() = "Short title for the settings list"
    override val defaultLevel get() = GoRuleLevel.WEAK_WARNING
    override val enabledByDefault get() = false        // opt-in rules stay quiet without configuration
    override val needs get() = setOf(GoRuleNeed.TYPES) // SYNTAX rules also run while the IDE indexes
    override val options get() = listOf(MAX)

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoIfStatement) return
        val max = ctx.option(MAX)
        // ... ctx.typeOf(expr), ctx.resolve(ref), ctx.flowOf(statement) ...
        ctx.report(statement.`if`, "message without the id", MyFix())
    }

    companion object { val MAX = GoRuleOption.int("max", 3, "What it limits") }
}
```

Register it in `go-psi-ide-rules.xml`: `<goRule implementation="io.github.golangsupport.ide.rules.builtin.GoMyRule"/>`. Rules are
stateless singletons. Write a test in `go-psi-ide/src/test/kotlin/io/github/golangsupport/ide/rules/` (see `GoRulesTest`).

## Scopes

| Scope | Node | Base class |
|---|---|---|
| `CALL` | every `GoCallExpr` | `GoCallRule` |
| `EXPRESSION` | every `GoExpression` (calls and literals included) | `GoExpressionRule` |
| `STATEMENT` | statements of statement lists, `else if`, init/post statements of `if`/`switch`/`for` | `GoStatementRule` |
| `FUNCTION` | function and method declarations, function literals (`GoRuleFunction`) | `GoFunctionRule` |
| `TYPE_SPEC` | `GoTypeSpec` | `GoTypeSpecRule` |
| `FILE` | the file, once per pass (at the package clause) | `GoFileRule` |
| `PACKAGE` | all files of the package (`GoRulePackage`), once per package and change | `GoPackageRule` |

Pick the narrowest scope: an `EXPRESSION` rule is called for every expression of the file. Filter early with `is` and cheap
syntax checks before asking for types.

PACKAGE rules report through `GoPackageRuleContext.report(element, ...)` on any file of the package; results are cached on the package
directory (GoTrackers: package dependencies plus every file of the package) and shown when that file is highlighted. Their fixes are
kept in the cache, so they must not hold PSI. Read other files by text or stubs where possible (see `GoPackageCommentsRule.hasPackageDoc`).

## Needs

`SYNTAX` (PSI only, runs in dumb mode), `TYPES` (`GoSemanticService`), `FLOW` (`ctx.flowOf(element)`: the cached control-flow graph
of the enclosing function), `PROJECT_INDEX` (other files, indices). Anything beyond `SYNTAX` is skipped while indexing.
`overlapsGopls = true` makes a rule quiet while diagnostics come from gopls (the `DIAGNOSTICS` feature gate is off).

## Configuration and options

Effective settings per rule, later layers win: rule defaults < `GoRuleConfigSource` (the `.golangci.yml` reader of the host, extension
point `io.github.golangsupport.goRuleConfigSource`) < the user's `GoRuleSettings` (project, `.idea/goRules.xml`, overrides only).

- enabled: user, else the config's rule entry, else the config's linter switch (on means the rule's `enabledWithLinter`), else
  `linters.default` (`all` / `none`), else `enabledByDefault`;
- level: user, else the config's rule entry, else `defaultLevel` (`ERROR`, `WARNING`, `WEAK_WARNING`, `INFO` = not highlighted);
- options: `GoRuleOption` defaults < `linterOptions[linter]` < the config's rule options < the user's options. Values are raw (YAML
  scalars and lists, or strings from the settings) and parsed by the option; a bad value falls back to the default.

A config source returns the same `GoRuleConfig` instance while the file has not changed: the rule set keeps one resolved snapshot
(enabled rules per scope, with the dumb-mode and gopls-mode variants) per instance, so a disabled rule costs nothing per node.

## Suppression

Every message starts with `[id]`. Honoured, in addition to the platform's `//noinspection GoRules`:

- `//nolint`, `//nolint:<linter>[,<linter>|<id>|all]` (no space after `//`): at the end of a line it covers the line, and the whole
  declaration when the line starts a top-level declaration, type spec or function; on its own line, the node starting on the next
  line at the same column, or the declaration it documents. `linterAliases` lets old names work (`//nolint:gosimple`);
- staticcheck `//lint:ignore <id>[,<id>] reason` (globs like `SA4*`) placed the same way, `//lint:file-ignore <id> reason`;
- `//noinspection <alias>` for the short names in `aliases` (inspections a rule replaced) and `//noinspection <id>` for ids without `:`.

## What is analysed

`GoAnalysisScope.isAnalysed(file)`: project content only (no library roots: GOROOT, module cache), not under `vendor`, `testdata`,
`node_modules` or directories starting with `.` / `_`, not generated (`// Code generated ... DO NOT EDIT.` before the package clause).

## Testing

Fixture style: `myFixture.enableInspections(GoRules())`, then `checkHighlighting` with `<warning descr="[id] message">` (or
`<weak_warning>`, `<error>` by level), or compare `line: description` lists. Cover: the positive cases, the negatives the linter
leaves alone, options (`GoRuleSettings.setOption`), the rule disabled (`setEnabled(id, false)`), and `//nolint:<linter>`. Reset the
settings in tear-down (`GoRuleSettings.loadState(State())`).

## Performance notes

- One walk: the visitor dispatches by `is` checks on the node; empty subscriber arrays skip the work.
- Subscriber lists are filtered once per configuration and run variant, not per node.
- Suppression comments are found by a text scan, and only when a rule reports something.
- Types, resolve and flow graphs are cached by go-psi (GoBodyCache / GoTrackers); never cache on `MODIFICATION_COUNT`.
- An exception in a rule is logged with the rule id and does not stop the other rules.
