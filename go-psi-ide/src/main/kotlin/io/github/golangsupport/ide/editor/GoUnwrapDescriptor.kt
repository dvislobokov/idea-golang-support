package io.github.golangsupport.ide.editor

import com.intellij.codeInsight.unwrap.UnwrapDescriptorBase
import com.intellij.codeInsight.unwrap.Unwrapper
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoElseStatement
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement

/** Unwrap / Remove (Ctrl+Shift+Delete): the statements of an `if`, `else`, `for`, `func(){…}()`, `case` or `{}` take its place. */
class GoUnwrapDescriptor : UnwrapDescriptorBase() {
    override fun createUnwrappers(): Array<Unwrapper> = arrayOf(
        GoIfUnwrapper(), GoElseUnwrapper(), GoElseRemover(), GoForUnwrapper(), GoFunctionLiteralUnwrapper(), GoDeferGoRemover(),
        GoCaseUnwrapper(), GoBlockUnwrapper(),
    )
}

/**
 * An unwrapper that edits the document as text: [edit] says what to write, from the committed PSI; [kept] are the elements that stay
 * (highlighted while the user picks an option), [affected] the one that goes.
 */
abstract class GoUnwrapper(private val description: String) : Unwrapper {
    protected abstract fun edit(element: PsiElement): GoEditPlan.Edit?

    protected open fun kept(element: PsiElement): List<PsiElement> = emptyList()

    protected open fun affected(element: PsiElement): PsiElement = element

    override fun getDescription(e: PsiElement): String = description

    override fun collectElementsToIgnore(element: PsiElement, result: MutableSet<PsiElement>) {}

    override fun collectAffectedElements(e: PsiElement, toExtract: MutableList<in PsiElement>): PsiElement {
        toExtract.addAll(kept(e))
        return affected(e)
    }

    override fun unwrap(editor: Editor, element: PsiElement): List<PsiElement> {
        val edit = edit(element) ?: return emptyList()
        GoEditText.apply(element.containingFile, listOf(edit))
        return emptyList()
    }

    protected companion object {
        fun inList(element: PsiElement?): Boolean = element != null && GoEditText.isStatementList(element.parent)

        fun indent(element: PsiElement): String = GoEditText.indentOf(element.containingFile.node.chars, element.textRange.startOffset)

        /** The statements of [block], one level less indented, at the indentation of [statement]. */
        fun blockBody(statement: PsiElement, block: GoBlock): String {
            val rbrace = block.rbrace ?: return ""
            return GoEditText.body(block.containingFile, block.lbrace.textRange.endOffset, rbrace.textRange.startOffset, -1, indent(statement))
        }

        /** [statement] replaced by [init] (an init statement, kept for the variables it declares) and [body]. */
        fun replace(statement: PsiElement, init: GoStatement?, body: String): GoEditPlan.Edit {
            val lines = listOfNotNull(init?.let { indent(statement) + it.text }, body.takeIf { it.isNotBlank() }).joinToString("\n")
            return GoEditText.replaceStatement(statement, lines)
        }

        fun statementsOf(block: GoBlock?): List<PsiElement> = block?.let(GoEditText::statements).orEmpty()
    }
}

/** Unwrap 'if...': the then-branch (after the init statement) replaces the whole `if`, `else` included. */
class GoIfUnwrapper : GoUnwrapper("Unwrap 'if...'") {
    override fun isApplicableTo(e: PsiElement): Boolean = e is GoIfStatement && inList(e) && e.block?.rbrace != null

    override fun kept(element: PsiElement): List<PsiElement> = statementsOf((element as GoIfStatement).block)

    override fun edit(element: PsiElement): GoEditPlan.Edit? {
        val statement = element as? GoIfStatement ?: return null
        return replace(statement, statement.initStatement, blockBody(statement, statement.block ?: return null))
    }
}

/** Unwrap 'else...': the final `else` block replaces its whole `if … else if … else` chain. */
class GoElseUnwrapper : GoUnwrapper("Unwrap 'else...'") {
    override fun isApplicableTo(e: PsiElement): Boolean = e is GoElseStatement && (e.statement as? GoBlock)?.rbrace != null && inList(chain(e))

    override fun kept(element: PsiElement): List<PsiElement> = statementsOf((element as GoElseStatement).statement as? GoBlock)

    override fun affected(element: PsiElement): PsiElement = chain(element as GoElseStatement) ?: element

    override fun edit(element: PsiElement): GoEditPlan.Edit? {
        val top = chain(element as GoElseStatement) ?: return null
        return replace(top, null, blockBody(top, element.statement as? GoBlock ?: return null))
    }

    private fun chain(e: GoElseStatement): GoIfStatement? {
        var top = e.parent as? GoIfStatement ?: return null
        while (true) top = (top.parent as? GoElseStatement)?.parent as? GoIfStatement ?: return top
    }
}

/** Remove 'else...': the `else` branch (a block or an `else if` chain) goes, the `if` stays. */
class GoElseRemover : GoUnwrapper("Remove 'else...'") {
    override fun isApplicableTo(e: PsiElement): Boolean = e is GoElseStatement && e.parent is GoIfStatement

    override fun edit(element: PsiElement): GoEditPlan.Edit? {
        val previous = GoEditText.prevNonBlank(element) ?: return null
        return GoEditPlan.Edit(previous.textRange.endOffset, element.textRange.endOffset, "")
    }
}

/** Unwrap 'for...': the loop body replaces the loop; the init statement of a three-clause loop is kept. */
class GoForUnwrapper : GoUnwrapper("Unwrap 'for...'") {
    override fun isApplicableTo(e: PsiElement): Boolean = e is GoForStatement && inList(e) && e.block?.rbrace != null

    override fun kept(element: PsiElement): List<PsiElement> = statementsOf((element as GoForStatement).block)

    override fun edit(element: PsiElement): GoEditPlan.Edit? {
        val statement = element as? GoForStatement ?: return null
        return replace(statement, statement.forClause?.initStatement, blockBody(statement, statement.block ?: return null))
    }
}

/** Unwrap 'func() {...}()': the body of a function literal called in place without arguments (also under `go`/`defer`) replaces the statement. */
class GoFunctionLiteralUnwrapper : GoUnwrapper("Unwrap 'func() {...}()'") {
    override fun isApplicableTo(e: PsiElement): Boolean = e is GoCallExpr && statement(e) != null

    override fun kept(element: PsiElement): List<PsiElement> = statementsOf(literal(element as GoCallExpr)?.block)

    override fun affected(element: PsiElement): PsiElement = statement(element as GoCallExpr) ?: element

    override fun edit(element: PsiElement): GoEditPlan.Edit? {
        val call = element as? GoCallExpr ?: return null
        val statement = statement(call) ?: return null
        return replace(statement, null, blockBody(statement, literal(call)?.block ?: return null))
    }

    private fun literal(call: GoCallExpr): GoFunctionLit? = (call.expression as? GoFunctionLit)?.takeIf { call.arguments.isEmpty() }

    /** The statement that is just this call: `func() {…}()`, `go func() {…}()` or `defer func() {…}()`. */
    private fun statement(call: GoCallExpr): GoStatement? {
        if (literal(call)?.block?.rbrace == null) return null
        val statement = when (val parent = call.parent) {
            is GoDeferStatement -> parent
            is GoGoStatement -> parent
            else -> GoEditText.expressionStatement(call)
        }
        return statement?.takeIf { inList(it) }
    }
}

/** Remove 'defer' / Remove 'go': the call runs in place. */
class GoDeferGoRemover : GoUnwrapper("Remove 'defer'") {
    override fun isApplicableTo(e: PsiElement): Boolean = (e is GoDeferStatement && e.expression != null) || (e is GoGoStatement && e.expression != null)

    override fun getDescription(e: PsiElement): String = if (e is GoGoStatement) "Remove 'go'" else "Remove 'defer'"

    override fun edit(element: PsiElement): GoEditPlan.Edit? {
        val (keyword, expression) = when (element) {
            is GoDeferStatement -> element.defer to element.expression
            is GoGoStatement -> element.go to element.expression
            else -> return null
        }
        return GoEditPlan.Edit(keyword.textRange.startOffset, (expression ?: return null).textRange.startOffset, "")
    }
}

/** Unwrap 'case...': the statements of one `case` (or `default`) of a `switch` / `select` replace the whole statement. */
class GoCaseUnwrapper : GoUnwrapper("Unwrap 'case...'") {
    override fun isApplicableTo(e: PsiElement): Boolean = (e is GoExprCaseClause || e is GoTypeCaseClause || e is GoCommClause) && inList(e.parent) && colon(e) != null

    override fun kept(element: PsiElement): List<PsiElement> = GoEditText.statements(element)

    override fun affected(element: PsiElement): PsiElement = element.parent

    override fun edit(element: PsiElement): GoEditPlan.Edit? {
        val statement = element.parent ?: return null
        val colon = colon(element) ?: return null
        val body = GoEditText.body(element.containingFile, colon.textRange.endOffset, GoEditText.contentEnd(element), -1, indent(statement))
        return replace(statement, null, body)
    }

    private fun colon(clause: PsiElement): PsiElement? = when (clause) {
        is GoExprCaseClause -> clause.colon
        is GoTypeCaseClause -> clause.colon
        is GoCommClause -> clause.colon
        else -> null
    }
}

/** Unwrap braces: a bare block `{ … }` gives its statements to the enclosing list. */
class GoBlockUnwrapper : GoUnwrapper("Unwrap braces") {
    override fun isApplicableTo(e: PsiElement): Boolean = e is GoBlock && inList(e) && e.rbrace != null

    override fun kept(element: PsiElement): List<PsiElement> = statementsOf(element as GoBlock)

    override fun edit(element: PsiElement): GoEditPlan.Edit? = (element as? GoBlock)?.let { replace(it, null, blockBody(it, it)) }
}
