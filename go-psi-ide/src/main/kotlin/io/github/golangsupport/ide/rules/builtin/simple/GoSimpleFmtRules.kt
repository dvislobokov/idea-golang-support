package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoExpressionPsi
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoStaticcheckPsi
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoLookup
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypePredicates

private val SPRINTF = setOf("Sprintf")

/** `fmt.Sprintf(...)` (not unwrapped from parentheses). */
private fun isSprintf(e: PsiElement?, ctx: GoRuleContext): Boolean = e is GoCallExpr && GoSimplePsi.isCallTo(e, "fmt.Sprintf", ctx)

/**
 * staticcheck S1025: `fmt.Sprintf("%s", x)` where `x` is a string already, has a string underlying type or a byte slice one (a
 * conversion), or is a `fmt.Stringer` (`x.String()`). Types implementing `fmt.Formatter`, `reflect.Value` and type parameters are left alone.
 */
class GoRedundantSprintfRule : GoSimpleCallRule() {
    override val id: String get() = "S1025"
    override val title: String get() = "Don't use fmt.Sprintf(\"%s\", x) unnecessarily"
    override val description: String get() =
        "In many instances, there are easier and more efficient ways of getting a value's string representation. Whenever a value's " +
            "underlying type is a string already, or the type has a String method, they should be used directly."

    private enum class Kind(val message: String, val fixName: String) {
        STRINGER("should use String() instead of fmt.Sprintf", "Replace with call to String method"),
        STRING("the argument is already a string, there's no need to use fmt.Sprintf", "Remove unnecessary call to fmt.Sprintf"),
        UNDERLYING_STRING("the argument's underlying type is a string, should use a simple conversion instead of fmt.Sprintf", "Replace with conversion to string"),
        BYTES("the argument's underlying type is a slice of bytes, should use a simple conversion instead of fmt.Sprintf", "Replace with conversion to string"),
    }

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val (kind, _) = match(call, ctx) ?: return
        ctx.report(call, kind.message, *GoRewriteFix.offer(kind.fixName, call, ctx, ::fix))
    }

    private fun match(call: GoCallExpr, ctx: GoRuleContext): Pair<Kind, GoExpression>? {
        if (GoSimplePsi.calleeName(call) != "Sprintf") return null
        val args = GoStaticcheckPsi.arguments(call) ?: return null
        if (args.size != 2) return null
        if (GoStaticcheckPsi.stringConstant(args[0], ctx) != "%s") return null
        if (GoSimplePsi.callee(call, SPRINTF, ctx) != "fmt.Sprintf") return null
        val arg = args[1]
        val type = ctx.typeOf(arg)
        if (type is GoTypeParamType || !GoTypePredicates.isKnown(type)) return null
        if (GoAnalysisPsi.isNamed(type, "reflect", "Value")) return null
        if (GoSimplePsi.method(type, "Format", 2, 0, ctx) != null) return null
        val kind = when {
            GoSimplePsi.hasStringMethod(type, "String", ctx) -> Kind.STRINGER
            GoSimplePsi.isPlainString(type) -> Kind.STRING
            type.underlying().let { it is GoBasicType && it.kind == GoBasicKind.STRING } -> Kind.UNDERLYING_STRING
            GoSimplePsi.isStringConvertibleByteSlice(type, ctx) -> Kind.BYTES
            else -> return null
        }
        return kind to arg
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val call = element as? GoCallExpr ?: return null
        val (kind, arg) = match(call, ctx) ?: return null
        if (GoSimplePsi.commentsOutside(call, listOf(arg.textRange))) return null
        val text = when (kind) {
            Kind.STRINGER -> {
                // Sprintf prefers Error() to String(), and an interface may hold a Formatter or an error: only a concrete Stringer is safe
                val type = ctx.typeOf(arg)
                if (type.underlying() is GoInterfaceType || GoSimplePsi.hasStringMethod(type, "Error", ctx)) return null
                "${GoSimplePsi.operand(arg)}.String()"
            }
            Kind.STRING -> GoSimplePsi.inPlaceOf(call, arg)
            Kind.UNDERLYING_STRING, Kind.BYTES -> if (GoSimplePsi.shadowsBuiltin(call.containingFile, "string")) return null else "string(${arg.text})"
        }
        return GoSimplePsi.replaceWith(call, text)
    }
}

/** staticcheck S1028: `errors.New(fmt.Sprintf(...))` is `fmt.Errorf(...)`. */
class GoErrorsNewSprintfRule : GoSimpleCallRule() {
    override val id: String get() = "S1028"
    override val title: String get() = "Simplify error construction with fmt.Errorf"
    override val description: String get() = "<code>errors.New(fmt.Sprintf(...))</code> is <code>fmt.Errorf(...)</code>."

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        match(call, ctx) ?: return
        ctx.report(call, "should use fmt.Errorf(...) instead of errors.New(fmt.Sprintf(...))", *GoRewriteFix.offer("Use fmt.Errorf", call, ctx, ::fix))
    }

    private fun match(call: GoCallExpr, ctx: GoRuleContext): GoCallExpr? {
        if (GoSimplePsi.calleeName(call) != "New") return null
        val inner = GoStaticcheckPsi.arguments(call)?.singleOrNull() ?: return null
        if (!isSprintf(inner, ctx)) return null
        if (GoSimplePsi.callee(call, NEW, ctx) != "errors.New") return null
        return inner as GoCallExpr
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val call = element as? GoCallExpr ?: return null
        val sprintf = match(call, ctx) ?: return null
        val list = sprintf.argumentList ?: return null
        // %w means nothing to Sprintf but wraps in Errorf
        val format = GoStaticcheckPsi.arguments(sprintf)?.firstOrNull()?.let { GoStaticcheckPsi.stringConstant(it, ctx) } ?: return null
        if (format.contains("%w")) return null
        if (GoSimplePsi.commentsOutside(call, listOf(list.textRange))) return null
        val qualifier = GoSimplePsi.qualifier(sprintf) ?: return null
        return GoSimplePsi.replaceWith(call, "${qualifier}Errorf${list.text}")
    }

    private companion object {
        val NEW = setOf("New")
    }
}

/**
 * staticcheck S1038: `fmt.Print(fmt.Sprintf(...))` is `fmt.Printf(...)` (likewise `Sprint`, `Fprint`, the `ln` variants with a literal
 * format, `log.Print` / `Fatal` / `Panic`, and the `Error` / `Fatal` / `Log` / `Skip` methods of `testing` and the `log.Logger` methods
 * when the `f` variant of the same receiver is the library one).
 */
class GoPrintSprintfRule : GoSimpleCallRule() {
    override val id: String get() = "S1038"
    override val title: String get() = "Unnecessarily complex way of printing formatted string"
    override val description: String get() = "Instead of using <code>fmt.Print(fmt.Sprintf(...))</code>, one can use <code>fmt.Printf(...)</code>."

    /** [newline]: the format needs a `\n` at its end (the `ln` functions of fmt). */
    private class Match(val call: GoCallExpr, val sprintf: GoCallExpr, val newName: String, val message: String, val newline: Boolean)

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val m = match(call, ctx) ?: return
        ctx.report(call, m.message, *GoRewriteFix.offer("Use ${m.newName}", call, ctx, ::fix))
    }

    private fun match(call: GoCallExpr, ctx: GoRuleContext): Match? {
        val name = GoSimplePsi.calleeName(call) ?: return null
        if (name !in NAMES) return null
        val args = GoStaticcheckPsi.arguments(call) ?: return null
        val sprintf = args.lastOrNull() as? GoCallExpr ?: return null
        if (args.size > 2 || GoSimplePsi.calleeName(sprintf) != "Sprintf") return null
        val ref = GoLintPsi.calleeReference(call) ?: return null
        val target = ctx.resolve(ref).singleOrNull() ?: return null
        val key = GoSimplePsi.memberKey(target) ?: return null
        if (!isSprintf(sprintf, ctx)) return null
        val sprintfArgs = GoSimplePsi.args(sprintf)
        FMT[key]?.let { expected ->
            if (args.size != expected || sprintfArgs.isEmpty()) return null
            if (!name.endsWith("ln")) return Match(call, sprintf, name + "f", "should use fmt.${name}f instead of fmt.$name(fmt.Sprintf(...))", false)
            val format = sprintfArgs.first()
            if (format !is GoLiteral && format !is GoStringLiteral) return null
            val newName = name.dropLast(2) + "f"
            return Match(call, sprintf, newName, "should use fmt.$newName instead of fmt.$name(fmt.Sprintf(...)) (but don't forget the newline)", true)
        }
        if (args.size != 1) return null
        LOG[key]?.let { return Match(call, sprintf, it, "should use log.$it(...) instead of $key(fmt.Sprintf(...))", false) }
        val (receiverKey, alternative) = METHODS[key] ?: return null
        val receiver = ref.expression ?: return null
        val selection = ctx.semantic.lookupFieldOrMethod(ctx.typeOf(receiver), alternative, ctx.file) as? GoLookup.Selection.Method ?: return null
        if (GoSimplePsi.memberKey(selection.method.declaration) != "$receiverKey.$alternative") return null
        val recv = GoExpressionPsi.render(receiver)
        return Match(call, sprintf, alternative, "should use $recv.$alternative(...) instead of ${GoExpressionPsi.render(ref)}(fmt.Sprintf(...))", false)
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val call = element as? GoCallExpr ?: return null
        val m = match(call, ctx) ?: return null
        val identifier = GoLintPsi.calleeReference(call)?.identifier ?: return null
        val inner = GoSimplePsi.argumentsRange(m.sprintf) ?: return null
        if (GoSimplePsi.commentsOutside(m.sprintf, listOf(inner))) return null
        var text = GoSimplePsi.argumentsText(m.sprintf) ?: return null
        if (m.newline) {
            // only an interpreted literal can take the `\n` the ln function added
            val format = GoStaticcheckPsi.arguments(m.sprintf)?.firstOrNull() as? GoStringLiteral ?: return null
            if (format.string == null) return null
            val at = format.textRange.endOffset - 1 - inner.startOffset
            text = text.substring(0, at) + "\\n" + text.substring(at)
        }
        return listOf(
            GoEditPlan.Edit(identifier.textRange.startOffset, identifier.textRange.endOffset, m.newName),
            GoEditPlan.Edit(m.sprintf.textRange.startOffset, m.sprintf.textRange.endOffset, text),
        )
    }

    private companion object {
        val NAMES = setOf("Print", "Sprint", "Println", "Sprintln", "Fprint", "Fprintln", "Error", "Fatal", "Fatalln", "Log", "Panic", "Panicln", "Skip")

        /** fmt functions and their number of arguments. */
        val FMT = mapOf("fmt.Print" to 1, "fmt.Sprint" to 1, "fmt.Println" to 1, "fmt.Sprintln" to 1, "fmt.Fprint" to 2, "fmt.Fprintln" to 2)

        val LOG = mapOf(
            "log.Fatal" to "Fatalf", "log.Fatalln" to "Fatalf", "log.Panic" to "Panicf", "log.Panicln" to "Panicf", "log.Print" to "Printf", "log.Println" to "Printf",
        )

        /** Method -> (the receiver its `f` variant must belong to, the `f` variant). */
        val METHODS: Map<String, Pair<String, String>> = buildMap {
            for ((m, f) in listOf("Error" to "Errorf", "Fatal" to "Fatalf", "Log" to "Logf", "Skip" to "Skipf")) {
                put("testing.common.$m", "testing.common" to f)
                put("testing.TB.$m", "testing.TB" to f)
            }
            for ((m, f) in listOf("Fatal" to "Fatalf", "Fatalln" to "Fatalf", "Panic" to "Panicf", "Panicln" to "Panicf", "Print" to "Printf", "Println" to "Printf")) {
                put("log.Logger.$m", "log.Logger" to f)
            }
        }
    }
}

/** staticcheck S1039: `fmt.Sprint("literal")` (and `fmt.Sprintf` of a literal without `%`) is the literal. */
class GoSprintLiteralRule : GoSimpleCallRule() {
    override val id: String get() = "S1039"
    override val title: String get() = "Unnecessary use of fmt.Sprint"
    override val description: String get() = "Calling <code>fmt.Sprint</code> with a single string argument is unnecessary and identical to using the string directly."

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val name = match(call, ctx) ?: return
        ctx.report(call, "unnecessary use of fmt.$name", *GoRewriteFix.offer("Replace with string literal", call, ctx, ::fix))
    }

    private fun match(call: GoCallExpr, ctx: GoRuleContext): String? {
        val name = GoSimplePsi.calleeName(call) ?: return null
        if (name !in NAMES) return null
        val lit = GoStaticcheckPsi.arguments(call)?.singleOrNull() as? GoStringLiteral ?: return null
        if (name == "Sprintf" && lit.text.contains('%')) return null
        if (GoSimplePsi.callee(call, NAMES, ctx) != "fmt.$name") return null
        return name
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val call = element as? GoCallExpr ?: return null
        val name = match(call, ctx) ?: return null
        val lit = GoStaticcheckPsi.arguments(call)?.singleOrNull() as? GoStringLiteral ?: return null
        // "\x25" has no '%' in the source but is a verb to Sprintf
        if (name == "Sprintf" && GoStaticcheckPsi.stringConstant(lit, ctx)?.contains('%') != false) return null
        if (GoSimplePsi.commentsOutside(call, listOf(lit.textRange))) return null
        return GoSimplePsi.replaceWith(call, lit.text)
    }

    private companion object {
        val NAMES = setOf("Sprint", "Sprintf")
    }
}
