package io.github.golangsupport.ide.rules

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeSpec

/**
 * One lint rule (a golangci-lint linter, a staticcheck check, a revive rule, ...) run by the [GoRules] dispatcher: the file is walked
 * once and every node is handed to the rules subscribed to its [scope]. Registered through the `io.github.golangsupport.goRule`
 * extension point; stateless (one instance serves every file and thread). Prefer the typed bases ([GoCallRule], [GoExpressionRule], ...).
 *
 * How to add one: docs/RULES-ENGINE.md.
 */
abstract class GoRule {
    /** golangci-style id, unique: `errcheck`, `SA4006`, `revive:var-naming`, `gocritic:dupBranchBody`. Shown as `[id]` before every message. */
    abstract val id: String

    /** The source linter as golangci-lint names it (`errcheck`, `staticcheck`, `revive`): `//nolint:<linter>` and `.golangci.yml` sections. */
    abstract val linter: String

    /** Older linter names `//nolint` also accepts (`gosimple` for an S-check that golangci-lint v2 moved under `staticcheck`). */
    open val linterAliases: Set<String> get() = emptySet()

    /** One line for the settings list. */
    abstract val title: String

    /** Short HTML for the settings page and the docs. */
    open val description: String get() = title

    open val defaultLevel: GoRuleLevel get() = GoRuleLevel.WARNING

    /** On without any configuration (the native default analysis). */
    open val enabledByDefault: Boolean get() = true

    /** On when a configuration enables [linter] (or `default: all`) and says nothing about this rule; false for opt-in rules of a linter. */
    open val enabledWithLinter: Boolean get() = true

    abstract val scope: GoRuleScope

    /** What [check] reads. Rules needing more than [GoRuleNeed.SYNTAX] are skipped while the IDE indexes. */
    open val needs: Set<GoRuleNeed> get() = SYNTAX_ONLY

    /** Duplicates a gopls analyzer: quiet while diagnostics come from gopls (the DIAGNOSTICS feature gate is off). */
    open val overlapsGopls: Boolean get() = false

    /** The options of the rule with their defaults ([GoRuleContext.option]); the keys are the `.golangci.yml` setting names. */
    open val options: List<GoRuleOption<*>> get() = emptyList()

    /** Short names of inspections this rule replaced: `//noinspection <alias>` keeps suppressing it. */
    open val aliases: Set<String> get() = emptySet()

    /** Checks one node of [scope] (a [GoCallExpr], [GoExpression], [GoStatement], function, [GoTypeSpec] or the [GoFile]). */
    open fun check(element: PsiElement, ctx: GoRuleContext) {}

    /** Checks a whole package ([GoRuleScope.PACKAGE] only); results are cached per package until it changes. */
    open fun checkPackage(pkg: GoRulePackage, ctx: GoPackageRuleContext) {}

    override fun toString(): String = id

    companion object {
        val EP_NAME: ExtensionPointName<GoRule> = ExtensionPointName.create("io.github.golangsupport.goRule")

        val SYNTAX_ONLY: Set<GoRuleNeed> = setOf(GoRuleNeed.SYNTAX)
    }
}

/** The node kinds the dispatcher routes; each node goes to every enabled rule of its kind, once. */
enum class GoRuleScope {
    /** Every call expression (conversions written as calls included). */
    CALL,

    /** Every expression, calls and function literals included. */
    EXPRESSION,

    /** Statements of statement lists (and the init statements of `if` / `switch` / `for`), not top-level declarations. */
    STATEMENT,

    /** Function and method declarations and function literals. */
    FUNCTION,

    /** Type specs: `type T struct{...}`, interfaces, aliases. */
    TYPE_SPEC,

    /** The file, once per pass. */
    FILE,

    /** All files of the package, once per package and change, reported in the file being highlighted ([GoRule.checkPackage]). */
    PACKAGE,
}

/** What a rule reads; decides when it can run. */
enum class GoRuleNeed {
    /** The PSI of the file only: runs in dumb mode. */
    SYNTAX,

    /** Types and resolve (GoSemanticService over stub indices). */
    TYPES,

    /** The control-flow graph of the enclosing function. */
    FLOW,

    /** Other files of the project through indices. */
    PROJECT_INDEX,
}

/** The level a problem is shown with (golangci `severity`). */
enum class GoRuleLevel(val highlightType: ProblemHighlightType) {
    ERROR(ProblemHighlightType.GENERIC_ERROR),
    WARNING(ProblemHighlightType.WARNING),
    WEAK_WARNING(ProblemHighlightType.WEAK_WARNING),

    /** Not highlighted in the editor: shown by Inspect Code and offered on Alt+Enter. */
    INFO(ProblemHighlightType.INFORMATION);

    companion object {
        /** `error`, `warning`/`warn`, `weak_warning`/`weak warning`/`weak`, `info`/`information`, any case; null for anything else. */
        fun parse(text: String?): GoRuleLevel? = when (text?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_')) {
            "error" -> ERROR
            "warning", "warn" -> WARNING
            "weak_warning", "weak" -> WEAK_WARNING
            "info", "information" -> INFO
            else -> null
        }
    }
}

/**
 * A typed option of a rule with its default. Raw values come from the configuration (YAML scalars and lists, so numbers, booleans,
 * strings, lists) or the user's settings (strings); an unparsable value falls back to [default].
 */
class GoRuleOption<T : Any> private constructor(val name: String, val default: T, val description: String, private val parser: (Any) -> T?) {

    fun parse(raw: Any?): T = raw?.let { runCatching { parser(it) }.getOrNull() } ?: default

    override fun toString(): String = "$name=$default"

    companion object {
        fun int(name: String, default: Int, description: String = ""): GoRuleOption<Int> = GoRuleOption(name, default, description) {
            when (it) {
                is Number -> it.toInt()
                else -> it.toString().trim().toIntOrNull()
            }
        }

        fun bool(name: String, default: Boolean, description: String = ""): GoRuleOption<Boolean> = GoRuleOption(name, default, description) {
            if (it is Boolean) it else it.toString().trim().lowercase().toBooleanStrictOrNull()
        }

        fun string(name: String, default: String, description: String = ""): GoRuleOption<String> = GoRuleOption(name, default, description) { it.toString() }

        /** A YAML list, or a comma-separated string from the settings. */
        fun stringList(name: String, default: List<String>, description: String = ""): GoRuleOption<List<String>> = GoRuleOption(name, default, description) {
            if (it is Collection<*>) it.mapNotNull { e -> e?.toString() } else it.toString().split(',').map(String::trim).filter(String::isNotEmpty)
        }
    }
}

/** The effective raw option values of a rule (defaults < configuration < user settings), read through [GoRuleOption]. */
class GoRuleOptions(val raw: Map<String, Any?>) {
    operator fun <T : Any> get(option: GoRuleOption<T>): T = option.parse(raw[option.name])

    override fun equals(other: Any?): Boolean = other is GoRuleOptions && other.raw == raw

    override fun hashCode(): Int = raw.hashCode()

    override fun toString(): String = raw.toString()

    companion object {
        val EMPTY: GoRuleOptions = GoRuleOptions(emptyMap())
    }
}

/** A rule over call expressions. */
abstract class GoCallRule : GoRule() {
    final override val scope: GoRuleScope get() = GoRuleScope.CALL
    final override fun check(element: PsiElement, ctx: GoRuleContext) = checkCall(element as GoCallExpr, ctx)
    abstract fun checkCall(call: GoCallExpr, ctx: GoRuleContext)
}

/** A rule over expressions (filter the kinds you want with `is`: one rule sees every expression). */
abstract class GoExpressionRule : GoRule() {
    final override val scope: GoRuleScope get() = GoRuleScope.EXPRESSION
    final override fun check(element: PsiElement, ctx: GoRuleContext) = checkExpression(element as GoExpression, ctx)
    abstract fun checkExpression(expression: GoExpression, ctx: GoRuleContext)
}

/** A rule over statements of statement lists. */
abstract class GoStatementRule : GoRule() {
    final override val scope: GoRuleScope get() = GoRuleScope.STATEMENT
    final override fun check(element: PsiElement, ctx: GoRuleContext) = checkStatement(element as GoStatement, ctx)
    abstract fun checkStatement(statement: GoStatement, ctx: GoRuleContext)
}

/** A rule over functions: [GoRuleFunction] wraps a declaration or a literal. */
abstract class GoFunctionRule : GoRule() {
    final override val scope: GoRuleScope get() = GoRuleScope.FUNCTION
    final override fun check(element: PsiElement, ctx: GoRuleContext) {
        GoRuleFunction.of(element)?.let { checkFunction(it, ctx) }
    }
    abstract fun checkFunction(function: GoRuleFunction, ctx: GoRuleContext)
}

/** A rule over type specs. */
abstract class GoTypeSpecRule : GoRule() {
    final override val scope: GoRuleScope get() = GoRuleScope.TYPE_SPEC
    final override fun check(element: PsiElement, ctx: GoRuleContext) = checkTypeSpec(element as GoTypeSpec, ctx)
    abstract fun checkTypeSpec(spec: GoTypeSpec, ctx: GoRuleContext)
}

/** A rule over the whole file, once per pass. */
abstract class GoFileRule : GoRule() {
    final override val scope: GoRuleScope get() = GoRuleScope.FILE
    final override fun check(element: PsiElement, ctx: GoRuleContext) = checkFile(element as GoFile, ctx)
    abstract fun checkFile(file: GoFile, ctx: GoRuleContext)
}

/** A rule over all files of a package. */
abstract class GoPackageRule : GoRule() {
    final override val scope: GoRuleScope get() = GoRuleScope.PACKAGE
    final override fun check(element: PsiElement, ctx: GoRuleContext) {}
    abstract override fun checkPackage(pkg: GoRulePackage, ctx: GoPackageRuleContext)
}
