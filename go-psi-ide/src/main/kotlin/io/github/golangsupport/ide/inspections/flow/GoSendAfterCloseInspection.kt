package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSendStatement
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowNode
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions

/**
 * `close(ch)` followed on some path by `ch <- v` (a panic) or another `close(ch)` (a panic too), with `ch` not reassigned in
 * between. Only forward edges are followed: the next iteration of a loop is not considered (a `closed` flag usually guards it),
 * and paths through a condition that reads a variable assigned after the `close` in its block are dropped for the same reason.
 * Channels assigned inside a function literal or whose address is taken are not checked.
 */
class GoSendAfterCloseInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        val reported = HashSet<PsiElement>()
        for (node in flow.nodes) {
            if (!flow.isReachable(node) || node.kind != GoFlowNode.Kind.STATEMENT) continue
            val statement = node.element as? GoSimpleStatement ?: continue
            val arg = closeArgument(flow, statement) ?: continue
            val channel = flow.variableOf(arg) ?: continue
            if (changedElsewhere(flow, channel)) continue
            val flags = writtenAfter(flow, statement)
            val seen = BooleanArray(flow.nodes.size)
            val stack = ArrayDeque<GoFlowNode>()
            fun push(from: GoFlowNode) {
                for (e in from.successors) if (e.to.index > from.index && !seen[e.to.index]) { seen[e.to.index] = true; stack.addLast(e.to) }
            }
            push(node)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (GoResourceFlow.writes(n, channel)) continue
                if (n.kind == GoFlowNode.Kind.CONDITION && n.accesses.any { !it.isWrite && it.variable in flags }) continue
                val hit = misuse(flow, n, channel)
                if (hit != null) {
                    if (reported.add(hit.first)) holder.registerProblem(hit.first, hit.second, ProblemHighlightType.WARNING)
                    continue
                }
                when (n.kind) {
                    GoFlowNode.Kind.RETURN, GoFlowNode.Kind.PANIC, GoFlowNode.Kind.TERMINATE, GoFlowNode.Kind.DEFERRED, GoFlowNode.Kind.EXIT -> continue
                    else -> push(n)
                }
            }
        }
    }

    /** `ch` of a statement `close(ch)` calling the builtin. */
    private fun closeArgument(flow: GoControlFlow, statement: GoSimpleStatement): GoReferenceExpression? {
        val call = GoFlowChecks.unparen(statement.expressions.singleOrNull()) as? GoCallExpr ?: return null
        val callee = GoFlowChecks.unparen(call.expression) as? GoReferenceExpression ?: return null
        if (callee.expression != null || callee.identifier.text != "close") return null
        val arg = GoFlowChecks.unparen(call.argumentList?.expressions?.singleOrNull()) as? GoReferenceExpression ?: return null
        if (arg.expression != null) return null
        val target = GoFlowChecks.service(call).resolve(callee).firstOrNull() ?: return null
        if ((target.containingFile as? GoFile)?.packageName != "builtin") return null
        return arg.takeIf { flow.variableOf(it) != null }
    }

    /** A send on [channel] or a second `close(channel)` at [node]: the element to report and the message. */
    private fun misuse(flow: GoControlFlow, node: GoFlowNode, channel: GoNamedElement): Pair<PsiElement, String>? {
        val element = node.element ?: return null
        when (node.kind) {
            GoFlowNode.Kind.STATEMENT, GoFlowNode.Kind.COMM -> {}
            else -> return null
        }
        val send = element as? GoSendStatement ?: PsiTreeUtil.getChildOfType(element, GoSendStatement::class.java)
        if (send != null) {
            val target = GoFlowChecks.unparen(send.leftHandExprList?.expressionList?.singleOrNull()) as? GoReferenceExpression
            if (target != null && target.expression == null && flow.variableOf(target) == channel) return send to "send on closed channel ${channel.name}"
        }
        if (element is GoSimpleStatement) {
            val arg = closeArgument(flow, element)
            if (arg != null && flow.variableOf(arg) == channel) return element to "channel ${channel.name} closed twice"
        }
        return null
    }

    /** [channel] is assigned inside a function literal or its address is taken: its value may change behind the graph. */
    private fun changedElsewhere(flow: GoControlFlow, channel: GoNamedElement): Boolean {
        if (!flow.isEscaping(channel)) return false
        val name = channel.name ?: return true
        for (ref in PsiTreeUtil.findChildrenOfType(flow.body, GoReferenceExpression::class.java)) {
            if (ref.expression != null || ref.identifier.text != name) continue
            if ((ref.parent as? GoUnaryExpr)?.and != null) return true
            val inLiteral = PsiTreeUtil.getParentOfType(ref, GoFunctionLit::class.java)?.let { PsiTreeUtil.isAncestor(flow.body, it, true) } == true
            val list = ref.parent as? GoLeftHandExprList ?: continue
            val assigned = list.parent is GoAssignmentStatement || (list.parent as? GoSimpleStatement)?.statement is GoAssignmentStatement
            if (inLiteral && assigned) return true
        }
        return false
    }

    /** Variables of [flow] assigned by the statements after [statement] in its block (a `closed = true` flag). */
    private fun writtenAfter(flow: GoControlFlow, statement: PsiElement): Set<GoNamedElement> {
        val out = HashSet<GoNamedElement>()
        var s = PsiTreeUtil.getNextSiblingOfType(statement, GoStatement::class.java)
        while (s != null) {
            flow.nodeFor(s)?.accesses?.forEach { if (it.isWrite) out += it.variable }
            for (inner in PsiTreeUtil.findChildrenOfType(s, GoStatement::class.java)) flow.nodeFor(inner)?.accesses?.forEach { if (it.isWrite) out += it.variable }
            s = PsiTreeUtil.getNextSiblingOfType(s, GoStatement::class.java)
        }
        return out
    }
}
