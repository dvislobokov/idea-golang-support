package io.github.golangsupport.ide.rules.builtin.staticcheck

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoChannelType
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import java.math.BigInteger

/** staticcheck SA1016: `os.Kill` / `syscall.SIGKILL` / `syscall.SIGSTOP` passed to `signal.Notify`, `Ignore` or `Reset`: they cannot be trapped. */
class GoUntrappableSignalRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1016"
    override val title: String get() = "Trapping a signal that cannot be trapped"
    override val description: String get() =
        "<code>SIGKILL</code> and <code>SIGSTOP</code> are never delivered to the process: <code>signal.Notify(c, os.Kill)</code> does nothing. " +
            "<code>syscall.SIGTERM</code> is probably meant."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "os/signal.Notify" && callee != "os/signal.Ignore" && callee != "os/signal.Reset") return
        for (argument in arguments) {
            val signal = unwrapSignalConversion(argument, ctx)
            when (GoStaticcheckPsi.referenceKey(signal, ctx)) {
                "os.Kill", "syscall.SIGKILL" -> ctx.report(argument, "${signal.text} cannot be trapped (did you mean syscall.SIGTERM?)",
                    GoRemoveArgumentFix("Remove ${signal.text} from list of arguments"))
                "syscall.SIGSTOP" -> ctx.report(argument, "syscall.SIGSTOP cannot be trapped", GoRemoveArgumentFix("Remove syscall.SIGSTOP from list of arguments"))
            }
        }
    }

    /** `x` of `os.Signal(x)`, else [argument]. */
    private fun unwrapSignalConversion(argument: GoExpression, ctx: GoRuleContext): GoExpression {
        val conversion = GoLintPsi.unparen(argument) as? GoCallExpr ?: return argument
        val ref = GoLintPsi.calleeReference(conversion) ?: return argument
        if (ref.identifier?.text != "Signal") return argument
        val spec = ctx.resolve(ref).singleOrNull() as? GoTypeSpec ?: return argument
        if (GoAnalysisPsi.packagePath(spec) != "os") return argument
        return GoStaticcheckPsi.arguments(conversion)?.singleOrNull() ?: argument
    }

    private companion object {
        val NAMES = setOf("Notify", "Ignore", "Reset")
    }
}

/** staticcheck SA1017: an unbuffered channel passed to `signal.Notify`: a signal sent while nobody receives is lost. */
class GoUnbufferedSignalChannelRule : GoStaticcheckCallRule() {
    override val id: String get() = ID
    override val title: String get() = "Unbuffered channel for signal.Notify"
    override val description: String get() =
        "<code>signal.Notify</code> does not block when sending: with <code>make(chan os.Signal)</code> a signal arriving before the receive is lost. " +
            "Use <code>make(chan os.Signal, 1)</code>."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "os/signal.Notify") return
        val channel = arguments.firstOrNull() ?: return
        val make = GoSignalChannels.unbufferedMake(channel, ctx, staticcheck = true) ?: return
        ctx.report(channel, "the channel used with signal.Notify should be buffered", GoBufferChannelFix(make))
    }

    companion object {
        const val ID = "SA1017"
        private val NAMES = setOf("Notify")
    }
}

/**
 * govet `sigchanyzer`: the same check as [GoUnbufferedSignalChannelRule] (SA1017) under vet's id and message; quiet while SA1017 runs.
 * Like vet, a channel made right in the call (`signal.Notify(make(chan os.Signal), …)`) is left alone.
 */
class GoSigchanyzerRule : GoStaticcheckCallRule() {
    override val id: String get() = "govet:sigchanyzer"
    override val linter: String get() = "govet"
    override val title: String get() = "Unbuffered os.Signal channel (vet)"
    override val description: String get() = "govet <code>sigchanyzer</code>: an unbuffered <code>os.Signal</code> channel passed to <code>signal.Notify</code>. Same check as SA1017."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "os/signal.Notify") return
        val channel = arguments.firstOrNull() ?: return
        val make = GoSignalChannels.unbufferedMake(channel, ctx, staticcheck = false) ?: return
        if (GoStaticcheckPsi.enabled(GoUnbufferedSignalChannelRule.ID, ctx)) return
        ctx.report(call, "misuse of unbuffered os.Signal channel as argument to signal.Notify", GoBufferChannelFix(make))
    }

    private companion object {
        val NAMES = setOf("Notify")
    }
}

internal object GoSignalChannels {

    /**
     * The unbuffered `make(chan T)` that [channel] is: the initializer of a local variable declared with it and never assigned again in
     * the function. [staticcheck] (SA1017) also takes the call itself and a constant size 0; vet takes a variable of `make(chan T)` only.
     */
    fun unbufferedMake(channel: GoExpression, ctx: GoRuleContext, staticcheck: Boolean): GoCallExpr? {
        when (val e = GoLintPsi.unparen(channel)) {
            is GoCallExpr -> return if (staticcheck && isUnbufferedMake(e, ctx, true)) e else null
            is GoReferenceExpression -> {
                if (e.expression != null) return null
                val variable = ctx.resolve(e).singleOrNull() as? GoVarDefinition ?: return null
                if (!GoPsiUtil.isInsideFunctionBody(variable)) return null
                val make = GoLintPsi.unparen(initializer(variable)) as? GoCallExpr ?: return null
                if (!isUnbufferedMake(make, ctx, staticcheck) || isReassigned(variable, ctx)) return null
                return make
            }
            else -> return null
        }
    }

    private fun initializer(variable: GoVarDefinition): GoExpression? = when (val parent = variable.parent) {
        is GoShortVarDeclaration -> parent.expressionList.takeIf { it.size == parent.varDefinitionList.size }?.getOrNull(parent.varDefinitionList.indexOf(variable))
        is GoVarSpec -> parent.expressionList.takeIf { it.size == parent.varDefinitionList.size }?.getOrNull(parent.varDefinitionList.indexOf(variable))
        else -> null
    }

    private fun isUnbufferedMake(call: GoCallExpr, ctx: GoRuleContext, zeroSize: Boolean): Boolean {
        val ref = GoLintPsi.calleeReference(call) ?: return false
        if (ref.expression != null || ref.identifier?.text != "make") return false
        val target = ctx.resolve(ref).singleOrNull() ?: return false
        if (!GoLintPsi.isBuiltin(target)) return false
        val args = call.arguments
        if (args.firstOrNull() !is GoChannelType) return false
        return when (args.size) {
            1 -> true
            2 -> zeroSize && (args[1] as? GoExpression)?.let { GoStaticcheckPsi.intConstant(it, ctx) } == BigInteger.ZERO
            else -> false
        }
    }

    /** Whether [variable] is the target of an assignment (`ch = …`) in its function. */
    private fun isReassigned(variable: GoVarDefinition, ctx: GoRuleContext): Boolean {
        val name = variable.name ?: return true
        val scope: PsiElement = GoPsiUtil.outermostBody(variable) ?: return true
        for (assignment in PsiTreeUtil.findChildrenOfType(scope, GoAssignmentStatement::class.java)) {
            val targets = assignment.leftHandExprList?.expressionList ?: continue
            for (target in targets) {
                val ref = GoLintPsi.unparen(target) as? GoReferenceExpression ?: continue
                if (ref.expression == null && ref.identifier?.text == name && ctx.resolve(ref).singleOrNull() == variable) return true
            }
        }
        return false
    }
}
