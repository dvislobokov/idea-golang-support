package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoCallRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.types.GoTupleType

/** staticcheck SA1004: `time.Sleep(5)` with a small integer literal sleeps nanoseconds. */
class GoSleepNanosecondsRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1004"
    override val title: String get() = "Suspiciously small untyped constant in time.Sleep"
    override val description: String get() =
        "<code>time.Sleep</code> takes a <code>time.Duration</code> in nanoseconds: <code>time.Sleep(5)</code> sleeps 5ns. Write the unit: <code>5 * time.Second</code>."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "time.Sleep") return
        val literal = arguments.singleOrNull() as? GoLiteral ?: return
        if (literal.int == null) return
        val text = literal.text
        if (text.isEmpty() || !text.all { it in '0'..'9' } || text.length > 9) return
        val n = text.toInt()
        if (n == 0 || n > 120) return // time.Sleep(0) yields; larger values are probably meant
        val recommendation = if (n == 1) "time.Sleep(time.Nanosecond)" else "time.Sleep($n * time.Nanosecond)"
        ctx.report(literal, "sleeping for $n nanoseconds is probably a bug; be explicit if it isn't: $recommendation")
    }

    private companion object {
        val NAMES = setOf("Sleep")
    }
}

/** staticcheck SA1005: `exec.Command("git status")`: the program name with its arguments in one string. */
class GoExecCommandRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1005"
    override val title: String get() = "Invalid first argument to exec.Command"
    override val description: String get() =
        "<code>exec.Command</code> runs a program, not a shell line: <code>exec.Command(\"git status\")</code> looks for a program named " +
            "<code>git status</code>. Pass the arguments separately: <code>exec.Command(\"git\", \"status\")</code>."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "os/exec.Command") return
        val name = arguments.firstOrNull() ?: return
        val value = GoStaticcheckPsi.stringConstant(name, ctx) ?: return
        if (' ' !in value || '\\' in value || '/' in value) return
        val words = value.split(' ', '\t').filter { it.isNotEmpty() }
        val splittable = name is GoStringLiteral && name.string != null && words.size > 1 && words.none { w -> w.any { it == '"' || it == '\'' || it == '`' || it == '$' } }
        val fixes = if (splittable) arrayOf(GoReplaceWithTextFix("Split into program name and arguments", words.joinToString(", ") { "\"$it\"" })) else emptyArray()
        ctx.report(name, "first argument to exec.Command looks like a shell command, but a program name or path are expected", *fixes)
    }

    private companion object {
        val NAMES = setOf("Command")
    }
}

/** staticcheck SA1006: `fmt.Printf(s)` with a dynamic format and no arguments: a `%` in `s` is misread. Fix: `fmt.Print(s)`. */
class GoDynamicFormatRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1006"
    override val title: String get() = "Printf with dynamic first argument and no further arguments"
    override val description: String get() =
        "<code>fmt.Printf(msg)</code> treats a <code>%</code> in <code>msg</code> as a verb. Without arguments use <code>fmt.Print(msg)</code>."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        val index = FORMAT_INDEX[callee] ?: return
        if (arguments.size != index + 1) return
        val format = arguments[index]
        if (!(format is GoCallExpr || format is GoReferenceExpression && format.expression == null)) return
        if (ctx.typeOf(format) is GoTupleType) return
        if (ctx.semantic.constantValue(format) != null) return
        val alternative = callee.dropLast(1)
        val newName = alternative.substringAfterLast('.')
        ctx.report(call, "printf-style function with dynamic format string and no further arguments should use print-style function instead",
            GoRenameCalleeFix("Use $alternative instead of $callee", newName))
    }

    private companion object {
        val FORMAT_INDEX = mapOf("fmt.Printf" to 0, "fmt.Sprintf" to 0, "log.Printf" to 0, "fmt.Fprintf" to 1)
        val NAMES = setOf("Printf", "Sprintf", "Fprintf")
    }
}

/** staticcheck SA1013: `f.Seek(io.SeekStart, 0)`: the whence constant passed as the offset. Fix: swap the arguments. */
class GoSeekerArgumentsRule : GoCallRule() {
    override val id: String get() = "SA1013"
    override val linter: String get() = "staticcheck"
    override val title: String get() = "io.Seeker.Seek with swapped arguments"
    override val description: String get() =
        "The first argument of <code>Seek</code> is the offset, the second is <code>io.SeekStart</code> / <code>SeekCurrent</code> / <code>SeekEnd</code>: " +
            "<code>f.Seek(io.SeekStart, 20)</code> has them swapped."
    override val needs: Set<GoRuleNeed> get() = TYPES

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val ref = GoLintPsi.calleeReference(call) ?: return
        if (ref.expression == null || ref.identifier?.text != "Seek") return
        val arguments = GoStaticcheckPsi.arguments(call) ?: return
        if (arguments.size != 2) return
        val first = arguments[0] as? GoReferenceExpression ?: return
        if (first.expression == null || first.identifier?.text !in WHENCE) return
        val target = ctx.resolve(first).singleOrNull() as? GoConstDefinition ?: return
        if (GoAnalysisPsi.packagePath(target) != "io") return
        ctx.report(call, "the first argument of io.Seeker is the offset, but an io.Seek* constant is being used instead", GoSwapArgumentsFix("Swap arguments"))
    }

    private companion object {
        val TYPES = setOf(GoRuleNeed.TYPES)
        val WHENCE = setOf("SeekStart", "SeekCurrent", "SeekEnd")
    }
}

/** staticcheck SA1032: `errors.Is(ErrX, err)`: the sentinel error first. Fix: swap the arguments. */
class GoErrorsIsOrderRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1032"
    override val title: String get() = "Wrong order of arguments to errors.Is"
    override val description: String get() =
        "<code>errors.Is(err, target)</code> takes the error first and the sentinel second: <code>errors.Is(io.EOF, err)</code> has them swapped."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "errors.Is" || arguments.size != 2) return
        val (err, target) = arguments
        if (!isSentinel(err, ctx) || isSentinel(target, ctx)) return
        val other = GoLintPsi.unparen(target)
        if (other is GoReferenceExpression && other.expression == null && other.identifier?.text == "nil") return
        ctx.report(call, "arguments have the wrong order", GoSwapArgumentsFix("Swap arguments"))
    }

    /** A package-level variable (`io.EOF`, `ErrNotFound`). */
    private fun isSentinel(e: GoExpression, ctx: GoRuleContext): Boolean {
        val ref = GoLintPsi.unparen(e) as? GoReferenceExpression ?: return false
        val target = ctx.resolve(ref).singleOrNull() as? GoVarDefinition ?: return false
        return !GoPsiUtil.isInsideFunctionBody(target)
    }

    private companion object {
        val NAMES = setOf("Is")
    }
}
