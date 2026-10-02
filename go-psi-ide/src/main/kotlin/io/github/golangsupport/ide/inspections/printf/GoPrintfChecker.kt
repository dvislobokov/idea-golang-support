package io.github.golangsupport.ide.inspections.printf

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.psi.GoPsiUtil.operator
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates

/** A problem of a printf-like call: [anchor] (through [endAnchor] when set), [range] inside [anchor] or the whole of it. */
class GoPrintfProblem(
    val anchor: PsiElement,
    val range: TextRange?,
    val message: String,
    val fix: Fix? = null,
    val endAnchor: PsiElement? = null,
    /** A suggestion shown only as an Alt+Enter action, not highlighted (`%v` on an error in `Errorf`). */
    val suggestion: Boolean = false,
) {
    /** What a quick fix of the problem does (the inspection turns it into a `LocalQuickFix`). */
    sealed class Fix {
        /** Replaces the verb char at [offset] of the format literal [literal] by [to]. */
        class ReplaceVerb(val literal: GoStringLiteral, val offset: Int, val from: Char, val to: Char) : Fix()

        /** Removes the arguments [anchor]..[endAnchor] (extra values); with [literal], also offers appending `%v` placeholders for them. */
        class ExtraArguments(val count: Int, val literal: GoStringLiteral?, val insertAt: Int?) : Fix()
    }
}

/**
 * The checks of vet's `printf` pass over one printf-like call ([GoPrintfCall]): directives against arguments (count, `[n]` indexes,
 * `*` width and precision, verb against type, flags, `%w`), and for Print-like calls a Printf directive in the first argument and a
 * redundant newline in `...ln`. The format must be constant (vet skips other calls). Like vet, the checks of a call stop at its first
 * bad directive. Messages are vet's.
 */
class GoPrintfChecker(private val service: GoSemanticService) {
    private val types = GoPrintfTypes(service)

    fun check(call: GoPrintfCall): List<GoPrintfProblem> = if (call.isPrintf) checkPrintf(call) else checkPrint(call)

    /** A constant string argument: its value with source offsets when it is a literal, and the element to report on. */
    class StringArg(val value: GoStringValue, val anchor: PsiElement) {
        val literal: GoStringLiteral? get() = anchor as? GoStringLiteral

        /** The range of decoded chars `[from, to)` inside [anchor], or null (the whole anchor). */
        fun range(from: Int, to: Int): TextRange? = value.sourceRange(from, to)?.let { TextRange(it.first, it.last + 1) }
    }

    fun stringArg(expr: GoExpression?): StringArg? {
        var e = expr
        while (e is GoParenthesesExpr) e = e.inner as? GoExpression
        if (e == null) return null
        if (e is GoStringLiteral) return GoStringValue.decode(e.text)?.let { StringArg(it, e) }
        val constant = service.constantValue(e) as? GoConstant.Str ?: return null
        return StringArg(GoStringValue.ofConstant(constant.value), e)
    }

    private fun checkPrintf(call: GoPrintfCall): List<GoPrintfProblem> {
        val format = stringArg(call.format) ?: return emptyList()
        val values = call.values
        val name = call.name
        val out = ArrayList<GoPrintfProblem>()
        if ('%' !in format.value.value) {
            if (values.isNotEmpty()) out += extra(call, values, 0, format, "$name call has arguments but no formatting directives")
            return out
        }
        val parsed = GoFormatString.parse(format.value.value)
        var maxArg = -1
        var wraps = 0
        for (d in parsed.directives) {
            ProgressManager.checkCanceled()
            when (val outcome = checkDirective(call, format, d)) {
                is Outcome.Report -> { out += outcome.problem; return out }
                Outcome.Stop -> return out
                Outcome.Ok -> {}
            }
            if (d.verb == 'w') {
                if (call.kind != GoPrintKind.ERRORF) {
                    out += GoPrintfProblem(format.anchor, format.range(d.start, d.end), "$name does not support error-wrapping directive %w")
                    return out
                }
                wraps++
            }
            d.argNums.maxOrNull()?.let { if (it > maxArg) maxArg = it }
            if (d.verb == 'v' && d.flags.isEmpty() && call.kind == GoPrintKind.ERRORF) errorSuggestion(call, format, d)?.let { out += it }
        }
        parsed.error?.let { e ->
            val message = when (e.kind) {
                GoFormatString.Error.Kind.MISSING_VERB -> "$name format ${e.directive} is missing verb at end of string"
                GoFormatString.Error.Kind.MISSING_BRACKET -> "$name format ${e.directive} is missing closing ]"
                GoFormatString.Error.Kind.BAD_INDEX -> "$name format has invalid argument index [${e.index}]"
            }
            out += GoPrintfProblem(format.anchor, format.range(e.start, e.end), message)
            return out
        }
        if (wraps > 1 && !multipleWrapsAllowed(call)) out += GoPrintfProblem(format.anchor, null, "$name call has more than one error-wrapping directive %w")
        if (call.spread && maxArg >= values.size - 1) return out
        // With indexes, extra arguments are ignored (vet).
        if (parsed.anyIndex) return out
        if (maxArg + 1 < values.size) {
            val expect = maxArg + 1
            out += extra(call, values, expect, format, "$name call needs ${GoPrintfVerbs.count(expect, "arg")} but has ${GoPrintfVerbs.count(values.size, "arg")}")
        }
        return out
    }

    private fun extra(call: GoPrintfCall, values: List<GoExpression>, from: Int, format: StringArg, message: String): GoPrintfProblem {
        val count = values.size - from
        val literal = format.literal
        val insertAt = literal?.let { placeholderOffset(format) }
        return GoPrintfProblem(values[from], null, message, GoPrintfProblem.Fix.ExtraArguments(if (call.spread) 0 else count, literal, insertAt), endAnchor = values.last())
    }

    /** Where appended placeholders go: before a trailing `\n` of the format, else before the closing quote. */
    private fun placeholderOffset(format: StringArg): Int? {
        val text = format.value.value
        if (text.endsWith("\n")) format.range(text.length - 1, text.length)?.let { return it.startOffset }
        return format.value.closingQuote
    }

    /** The outcome of one directive: fine, a problem (the call's checks stop), or arguments hidden behind `args...` (they stop silently). */
    private sealed class Outcome {
        object Ok : Outcome()
        object Stop : Outcome()
        class Report(val problem: GoPrintfProblem) : Outcome()
    }

    /** vet's `okPrintfArg` for one directive. */
    private fun checkDirective(call: GoPrintfCall, format: StringArg, d: GoFormatString.Directive): Outcome {
        val name = call.name
        val values = call.values
        val range = format.range(d.start, d.end)
        fun report(message: String, fix: GoPrintfProblem.Fix? = null) = Outcome.Report(GoPrintfProblem(format.anchor, range, message, fix))
        // An index beyond the arguments (the parser only knows it is positive).
        if (d.indexed && !call.spread) {
            val bad = INDEX.findAll(d.text).map { it.groupValues[1] }.firstOrNull { (it.toIntOrNull() ?: 0) > values.size }
            if (bad != null) return report("$name format has invalid argument index [$bad]")
        }
        val verb = GoPrintfVerbs.of(d.verb)
        if (verb == null) {
            val arg = d.verbArg?.let { values.getOrNull(it) }
            if (arg != null && types.isFormatter(service.typeOf(arg))) return Outcome.Ok
            return report("$name format ${d.text} has unknown verb ${d.verb}")
        }
        GoPrintfVerbs.unsupportedFlag(verb, d.flags)?.let { return report("$name format ${d.text} has unrecognized flag $it") }
        for (star in d.starArgs) {
            argOutcome(call, format, star, d)?.let { return it }
            val arg = values[star]
            val type = service.typeOf(arg)
            if (types.isKnown(type) && !types.matches(type, GoPrintfArg.INT)) return report("$name format ${d.text} uses non-int ${arg.text} as argument of *")
        }
        val argNum = d.verbArg ?: return Outcome.Ok
        argOutcome(call, format, argNum, d)?.let { return it }
        val arg = values[argNum]
        val type = service.typeOf(arg)
        if (types.isFormatter(type)) return Outcome.Ok
        if (type is GoSignatureType && d.verb != 'p' && d.verb != 'T') return report("$name format ${d.text} arg ${arg.text} is a func value, not called")
        if (types.isKnown(type) && !types.matches(type, verb.args)) {
            val suggested = if (verb.args == GoPrintfArg.ERROR) null else GoPrintfTypes.suggestedVerb(type, types)
            val literal = format.literal
            val fix = if (suggested != null && suggested != d.verb && literal != null && format.value.isPlain(d.verbOffset)) {
                format.range(d.verbOffset, d.verbOffset + 1)?.let { GoPrintfProblem.Fix.ReplaceVerb(literal, it.startOffset, d.verb, suggested) }
            } else null
            return report("$name format ${d.text} has arg ${arg.text} of wrong type ${render(type)}", fix)
        }
        if ((verb.args and GoPrintfArg.STRING) != 0 && d.verb != 'T' && ('#' !in d.flags || d.verb in "qxX")) {
            recursiveMethod(arg)?.let { return report("$name format ${d.text} with arg ${arg.text} causes recursive $it method call") }
        }
        return Outcome.Ok
    }

    /** Null when value argument [argNum] exists; [Outcome.Stop] when `args...` may hold it; a "reads arg" problem when it is missing. */
    private fun argOutcome(call: GoPrintfCall, format: StringArg, argNum: Int, d: GoFormatString.Directive): Outcome? {
        val values = call.values
        if (argNum < values.size - 1) return null
        if (call.spread) return Outcome.Stop
        if (argNum < values.size) return null
        val message = "${call.name} format ${d.text} reads arg #${argNum + 1}, but call has ${GoPrintfVerbs.count(values.size, "arg")}"
        return Outcome.Report(GoPrintfProblem(format.anchor, format.range(d.start, d.end), message))
    }

    /** `%v` of an error in `Errorf`: suggest `%w` (an intention-level action, not a warning). */
    private fun errorSuggestion(call: GoPrintfCall, format: StringArg, d: GoFormatString.Directive): GoPrintfProblem? {
        val literal = format.literal ?: return null
        val argNum = d.verbArg ?: return null
        val arg = call.values.getOrNull(argNum) ?: return null
        if (call.spread && argNum >= call.values.size - 1) return null
        val type = service.typeOf(arg)
        if (type is GoBasicType || !types.isKnown(type) || !types.isError(type)) return null
        if (!format.value.isPlain(d.verbOffset)) return null
        val at = format.range(d.verbOffset, d.verbOffset + 1) ?: return null
        return GoPrintfProblem(
            literal, format.range(d.start, d.end), "${call.name} format %v has error arg ${arg.text}; use %w to wrap it",
            GoPrintfProblem.Fix.ReplaceVerb(literal, at.startOffset, 'v', 'w'), suggestion = true,
        )
    }

    private fun multipleWrapsAllowed(call: GoPrintfCall): Boolean {
        val file = call.call.containingFile as? GoFile ?: return true
        return multipleWrapsAllowed(service.packageOf(file)?.module?.goVersion)
    }

    private fun checkPrint(call: GoPrintfCall): List<GoPrintfProblem> {
        val values = call.values
        if (values.isEmpty()) return emptyList()
        val name = call.name
        val out = ArrayList<GoPrintfProblem>()
        stringArg(values.first())?.let { first ->
            GoPrintfVerbs.possibleDirective(first.value.value)?.let { m ->
                out += GoPrintfProblem(first.anchor, first.range(m.first, m.last + 1), "$name call has possible Printf formatting directive ${first.value.value.substring(m.first, m.last + 1)}")
            }
        }
        if (name.endsWith("ln")) {
            stringArg(values.last())?.let { last ->
                val text = last.value.value
                if (text.endsWith("\n")) out += GoPrintfProblem(last.anchor, last.range(text.length - 1, text.length), "$name arg list ends with redundant newline")
            }
        }
        val checked = if (call.spread) values.dropLast(1) else values
        for (arg in checked) {
            val type = service.typeOf(arg)
            if (type is GoSignatureType) {
                out += GoPrintfProblem(arg, null, "$name arg ${arg.text} is a func value, not called")
                continue
            }
            recursiveMethod(arg)?.let { out += GoPrintfProblem(arg, null, "$name arg ${arg.text} causes recursive $it method call") }
        }
        return out
    }

    /**
     * `String`/`Error` when [arg] is the receiver (or `*r`, `&r`) of the enclosing `String() string` / `Error() string` method and
     * its type has that method: printing it calls the method again.
     */
    private fun recursiveMethod(arg: GoExpression): String? {
        val method = PsiTreeUtil.getParentOfType(arg, GoMethodDeclaration::class.java) ?: return null
        val methodName = method.name ?: return null
        if (methodName != "String" && methodName != "Error") return null
        if (method.signature?.parameters?.parameterDeclarationList?.isNotEmpty() != false) return null
        val receiver = method.receiver ?: return null
        var e: GoExpression? = arg
        while (true) {
            e = when {
                e is GoParenthesesExpr -> e.inner as? GoExpression
                e is GoUnaryExpr && (e.operator == GoTypes.MUL || e.operator == GoTypes.AND) -> e.expression
                else -> break
            }
        }
        val ref = e as? GoReferenceExpression ?: return null
        if (ref.expression != null || ref.identifier?.text != receiver.name) return null
        if (service.resolve(ref).singleOrNull() !== receiver) return null
        val type = service.typeOf(arg)
        val has = service.methodsOf(type).any { it.name == methodName && it.signature.params.isEmpty() }
        return if (has) methodName else null
    }

    private fun render(type: GoType): String = service.render(GoTypePredicates.defaultType(type), true)

    companion object {
        private val INDEX = Regex("\\[(\\d+)]")

        /** `fmt.Errorf` takes several `%w` since Go 1.20: the `go` line of the module decides; no module means a current toolchain. */
        fun multipleWrapsAllowed(goVersion: String?): Boolean {
            val parts = goVersion?.removePrefix("go")?.split('.') ?: return true
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return true
            val minor = parts.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            return major > 1 || minor >= 20
        }
    }
}
