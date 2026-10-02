package io.github.golangsupport.ide.usages

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Consumer
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoBreakStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoContinueStatement
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoLabelDefinition
import io.github.golangsupport.lang.psi.GoLabelRef
import io.github.golangsupport.lang.psi.GoLabeledStatement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoTypes

/**
 * Highlight Usages (Ctrl+Shift+F7, and the automatic caret highlighting) on Go control-flow
 * keywords:
 *
 * - on `func` of a declaration or literal, or on `return`: the function's `return` statements and
 *   top-level `panic(...)` calls (nested function literals excluded);
 * - on `break` / `continue`: the target `for` / `switch` / `select` keyword and every jump to it;
 * - on `for` / `switch` / `select`: the keyword and every `break` / `continue` that leaves it.
 *
 * Labels resolve through the label reference, which walks the enclosing function body (no index).
 */
class GoHighlightExitPointsHandlerFactory : HighlightUsagesHandlerFactoryBase(), DumbAware {

    override fun createHighlightUsagesHandler(editor: Editor, file: PsiFile, target: PsiElement): HighlightUsagesHandlerBase<*>? {
        if (file !is GoFile || !GoIdeFeatureGate.enabled(GoIdeFeature.USAGES, file.project)) return null
        val usages: List<PsiElement> = when (target.node?.elementType) {
            GoTypes.FUNC -> {
                val owner = target.parent
                if (owner is GoFunctionOrMethodDeclaration || owner is GoFunctionLit) exitPoints(owner) else return null
            }
            GoTypes.RETURN -> {
                val owner = functionOwner(target) ?: return null
                exitPoints(owner)
            }
            GoTypes.BREAK, GoTypes.CONTINUE -> {
                val jump = target.parent as? GoStatement ?: return null
                val loop = jumpTarget(jump) ?: return null
                listOf(keywordOf(loop)) + jumpsTo(loop)
            }
            GoTypes.FOR, GoTypes.SWITCH, GoTypes.SELECT -> {
                val statement = normalize(target.parent as? GoStatement ?: return null)
                val jumps = jumpsTo(statement)
                if (jumps.isEmpty()) return null
                listOf(target) + jumps
            }
            else -> return null
        }
        if (usages.isEmpty()) return null
        return Handler(editor, file, target, usages)
    }

    private class Handler(editor: Editor, file: PsiFile, private val target: PsiElement, private val usages: List<PsiElement>) :
        HighlightUsagesHandlerBase<PsiElement>(editor, file) {

        override fun getTargets(): List<PsiElement> = listOf(target)

        override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<in List<PsiElement>>) {
            selectionConsumer.consume(targets)
        }

        override fun computeUsages(targets: List<PsiElement>) {
            usages.forEach(::addOccurrence)
        }

        override fun highlightReferences(): Boolean = false

        /** Pure PSI (labels resolve by walking the function body), so it works during indexing. */
        override fun isDumbAware(): Boolean = true
    }

    companion object {
        /** `return` statements and statement-level `panic(...)` calls of [owner], not of nested literals. */
        @JvmStatic
        fun exitPoints(owner: PsiElement): List<PsiElement> {
            val result = ArrayList<PsiElement>()
            fun visit(e: PsiElement) {
                var child = e.firstChild
                while (child != null) {
                    when {
                        child is GoFunctionLit -> {}
                        child is GoReturnStatement -> result += child
                        child is GoCallExpr && isPanic(child) -> result += child
                        else -> visit(child)
                    }
                    child = child.nextSibling
                }
            }
            val body = PsiTreeUtil.getChildOfType(owner, io.github.golangsupport.lang.psi.GoBlock::class.java) ?: return emptyList()
            visit(body)
            return result
        }

        private fun isPanic(call: GoCallExpr): Boolean {
            val callee = call.expression as? GoReferenceExpression ?: return false
            return callee.expression == null && callee.identifier.text == "panic"
        }

        private fun functionOwner(element: PsiElement): PsiElement? =
            PsiTreeUtil.getParentOfType(element, GoFunctionLit::class.java, GoFunctionOrMethodDeclaration::class.java)

        /** The statement a `break` / `continue` leaves: its label's statement, or the innermost loop (and switch/select for break). */
        @JvmStatic
        fun jumpTarget(jump: GoStatement): GoStatement? {
            val label: GoLabelRef? = when (jump) {
                is GoBreakStatement -> jump.labelRef
                is GoContinueStatement -> jump.labelRef
                else -> return null
            }
            if (label != null) {
                val definition = label.reference?.resolve() as? GoLabelDefinition ?: return null
                return (definition.parent as? GoLabeledStatement)?.statement?.let(::normalize)
            }
            var e: PsiElement? = jump.parent
            while (e != null && e !is GoFunctionLit && e !is GoFunctionOrMethodDeclaration && e !is PsiFile) {
                if (e is GoForStatement) return e
                if (jump is GoBreakStatement && isBreakable(e)) return e as GoStatement
                e = e.parent
            }
            return null
        }

        private fun isBreakable(e: PsiElement): Boolean =
            e is GoExprSwitchStatement || e is GoTypeSwitchStatement || e is GoSelectStatement

        /** Unwraps choice wrappers (`SwitchStatement` around the expression/type switch). */
        private fun normalize(statement: GoStatement): GoStatement {
            var s = statement
            while (true) {
                val child = s.firstChild as? GoStatement ?: return s
                if (child.textRange != s.textRange) return s
                s = child
            }
        }

        private fun keywordOf(statement: GoStatement): PsiElement = PsiTreeUtil.getDeepestFirst(statement)

        /** `break` / `continue` statements inside [statement] that jump to it. */
        private fun jumpsTo(statement: GoStatement): List<PsiElement> {
            val result = ArrayList<PsiElement>()
            for (jump in PsiTreeUtil.findChildrenOfAnyType(statement, GoBreakStatement::class.java, GoContinueStatement::class.java)) {
                if (jumpTarget(jump) == statement) result += jump
            }
            return result
        }
    }
}
