package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowAccess
import io.github.golangsupport.semantic.flow.GoFlowEdge
import io.github.golangsupport.semantic.flow.GoFlowNode
import io.github.golangsupport.semantic.flow.GoNilness
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition

/** Path searches and PSI shapes shared by the resource and concurrency checks (bodies, rows, locks, channels). */
internal object GoResourceFlow {

    /** `a, b := call` / `a, b = call` / `var a, b = call` with a single call on the right: the call and the targets. */
    fun callAssignment(statement: PsiElement): Pair<GoCallExpr, List<PsiElement>>? = when (statement) {
        is GoShortVarDeclaration -> (GoFlowChecks.unparen(statement.expressionList.singleOrNull()) as? GoCallExpr)?.let { it to statement.varDefinitionList }
        is GoAssignmentStatement -> (GoFlowChecks.unparen(statement.expressionList.singleOrNull()) as? GoCallExpr)?.takeIf { statement.assignOp?.assign != null }
            ?.let { it to statement.leftHandExprList?.expressionList.orEmpty() }
        is GoVarDeclaration -> statement.varSpecList.singleOrNull()?.let { spec -> (GoFlowChecks.unparen(spec.expressionList.singleOrNull()) as? GoCallExpr)?.let { it to spec.varDefinitionList } }
        else -> null
    }

    /** The resolved method or function [call] calls, with its package path: (declaration, path). */
    fun callee(call: GoCallExpr): Pair<PsiElement, String>? {
        val ref = GoFlowChecks.unparen(call.expression) as? GoReferenceExpression ?: return null
        val target = GoFlowChecks.service(call).resolve(ref).firstOrNull() ?: return null
        if (target !is GoFunctionDeclaration && target !is GoMethodDeclaration) return null
        return target to (GoAnalysisPsi.packagePath(target) ?: return null)
    }

    /** The receiver type name of a method declaration without the pointer star (`Client` for `func (c *Client) Do`). */
    fun receiverTypeName(method: GoMethodDeclaration): String? = method.receiver?.type?.text?.trimStart('*', '(', ' ')?.substringBefore('[')?.trimEnd(')', ' ')

    /** The node of [flow] that evaluates [element]; for an element inside a nested function literal, the node evaluating that literal. */
    fun nodeAt(flow: GoControlFlow, element: PsiElement): GoFlowNode? {
        var e: PsiElement? = element
        var outer: GoFunctionLit? = null
        while (e != null && e !== flow.owner) {
            if (e is GoFunctionLit) outer = e
            e = e.parent
        }
        if (e == null) return null
        return if (outer == null) flow.nodeOf(element) else outer.parent?.let { flow.nodeOf(it) }
    }

    /**
     * When [cond] compares a plain variable of [flow] with `nil`: the variable and whether the comparison is `==`.
     */
    fun nilTest(flow: GoControlFlow, cond: PsiElement?): Pair<GoNamedElement, Boolean>? {
        val c = GoFlowChecks.unparen(cond) as? GoConditionalExpr ?: return null
        val equal = when {
            c.eql != null -> true
            c.neq != null -> false
            else -> return null
        }
        val service = GoFlowChecks.service(c)
        val operand = when {
            GoNilness.isNilLiteral(c.right, service) -> GoFlowChecks.unparen(c.left)
            GoNilness.isNilLiteral(c.left, service) -> GoFlowChecks.unparen(c.right)
            else -> null
        } as? GoReferenceExpression ?: return null
        if (operand.expression != null) return null
        return (flow.variableOf(operand) ?: return null) to equal
    }

    /**
     * The edge of a nil test of [variable] on which the variable is not nil (`err != nil` taken, `err == nil` not taken), or of
     * [variable] being nil when [nonNil] is false.
     */
    fun isNilEdge(flow: GoControlFlow, edge: GoFlowEdge, variable: GoNamedElement?, nonNil: Boolean): Boolean {
        if (variable == null || edge.from.kind != GoFlowNode.Kind.CONDITION || edge.kind == GoFlowEdge.Kind.NORMAL) return false
        val (v, equal) = nilTest(flow, edge.from.element) ?: return false
        if (v != variable) return false
        val nilOnEdge = (edge.kind == GoFlowEdge.Kind.TRUE) == equal
        return nilOnEdge != nonNil
    }

    /**
     * The first `return` some path from [start] (exclusive) reaches without passing a node in [stops]: the resource acquired at
     * [start] is not released there. Paths through a panic or `os.Exit` count for nothing. [cut] drops an edge given whether the
     * guard (say, the error of the acquiring call) is still the one assigned at [start]; [killsGuard] tells where it is reassigned.
     */
    fun leakingReturn(
        start: GoFlowNode,
        stops: (GoFlowNode) -> Boolean,
        killsGuard: (GoFlowNode) -> Boolean = { false },
        cut: (GoFlowEdge, Boolean) -> Boolean = { _, _ -> false },
    ): GoFlowNode? {
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Pair<GoFlowNode, Boolean>>()
        for (e in start.successors) if (!cut(e, true)) stack.addLast(e.to to true)
        while (stack.isNotEmpty()) {
            val (node, guard) = stack.removeLast()
            if (!seen.add(node.index * 2 + if (guard) 1 else 0)) continue
            if (stops(node)) continue
            when (node.kind) {
                GoFlowNode.Kind.RETURN -> return node
                GoFlowNode.Kind.PANIC, GoFlowNode.Kind.TERMINATE, GoFlowNode.Kind.DEFERRED, GoFlowNode.Kind.EXIT -> continue
                else -> {}
            }
            val next = guard && !killsGuard(node)
            for (e in node.successors) if (!cut(e, next)) stack.addLast(e.to to next)
        }
        return null
    }

    /** Whether [node] writes [variable]. */
    fun writes(node: GoFlowNode, variable: GoNamedElement): Boolean = node.accesses.any { it.variable == variable && it.isWrite }

    /**
     * The read accesses of a tracked [variable] sorted by use: those under a selector (`v.f`, `v.m()`) and nil tests are kept apart
     * from the rest; null when some read hands the value elsewhere (an argument, `return v`, a store, an assignment).
     */
    fun selectorUses(flow: GoControlFlow, variable: GoNamedElement): List<Pair<GoFlowAccess, GoReferenceExpression>>? {
        val out = ArrayList<Pair<GoFlowAccess, GoReferenceExpression>>()
        for (a in flow.accessesOf(variable)) {
            if (a.isWrite) continue
            val ref = a.element as? GoReferenceExpression ?: return null
            val parent = ref.parent
            when {
                parent is GoReferenceExpression && parent.expression === ref -> out += a to parent
                parent is GoConditionalExpr && nilTest(flow, parent)?.first == variable -> {}
                else -> return null
            }
        }
        return out
    }

    /** `x, err := f()` followed by `if err … {…}`: the statement holding [declaration] and the `if` right after it, else null. */
    fun followingCheck(declaration: PsiElement, errName: String?): Pair<GoStatement, GoIfStatement?>? {
        val statement = PsiTreeUtil.getParentOfType(declaration, GoStatement::class.java, false)?.let { if (it.parent is GoSimpleStatement) it.parent as GoStatement else it }
            ?: return null
        if (statement.parent !is GoBlock) return null
        val next = PsiTreeUtil.getNextSiblingOfType(statement, GoStatement::class.java) as? GoIfStatement
        val check = next?.takeIf { s ->
            errName != null && PsiTreeUtil.findChildrenOfType(s.condition, GoReferenceExpression::class.java).any { it.expression == null && it.identifier.text == errName } ||
                errName != null && (s.condition as? GoReferenceExpression)?.identifier?.text == errName
        }
        return statement to check
    }

    /** Inserts `defer <text>` on its own line after the statement or the `if` the problem element leads to. */
    class AddDeferFix(private val text: String, private val errName: String?) : LocalQuickFix {
        override fun getFamilyName(): String = "Add defer $text"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val (statement, check) = followingCheck(descriptor.psiElement, errName) ?: return
            val anchor: PsiElement = check ?: statement
            val file = statement.containingFile
            val document = GoImportEdits.document(file) ?: return
            val chars = document.charsSequence
            var indentStart = statement.textRange.startOffset
            while (indentStart > 0 && (chars[indentStart - 1] == ' ' || chars[indentStart - 1] == '\t')) indentStart--
            val indent = chars.subSequence(indentStart, statement.textRange.startOffset).toString()
            document.insertString(anchor.textRange.endOffset, "\n${indent}defer $text")
            GoImportEdits.commit(file, document)
        }
    }

    /** Whether the fix [AddDeferFix] can be offered: an error check follows, or there is no error to check first. */
    fun canAddDefer(target: PsiElement, errName: String?): Boolean {
        val (_, check) = followingCheck(target, errName) ?: return false
        return errName == null || check != null
    }
}
