package io.github.golangsupport.ide.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarSpec

/** Neighbours of declarations for the merge / split intentions (GoLand's "Go | Declarations" group). */
internal object GoDeclarationNeighbours {

    /** The sibling right before [element] with only blanks between (a comment in between makes it not adjacent). */
    fun previous(element: PsiElement): PsiElement? {
        var e = element.prevSibling
        while (e != null && GoEditText.isBlank(e)) e = e.prevSibling
        return e
    }

    /** A declaration that starts with its keyword (no doc comment inside the element) and has no comment after it. */
    fun plain(view: GoDeclarationView): Boolean = !view.hasInnerComment() && view.element.textRange.startOffset == view.keyword.textRange.startOffset

    /** The `var` / `const` spec around the caret, not across a function literal. */
    fun specAt(file: GoFile, offset: Int): PsiElement? {
        var e: PsiElement? = GoIntentionText.leafAt(file, offset)
        while (e != null && e !is PsiFile) {
            if (e is GoVarSpec || e is GoConstSpec) return e
            if (e is GoVarDeclaration || e is GoConstDeclaration) return GoDeclarationView(e).takeIf { !it.isGrouped }?.specs?.singleOrNull()
            if (e is GoBlock || e is GoFunctionLit) return null
            e = e.parent
        }
        return null
    }

    /** The `rparen` of a grouped declaration. */
    fun rparen(view: GoDeclarationView): PsiElement? = (view.element as? GoVarDeclaration)?.rparen ?: (view.element as? GoConstDeclaration)?.rparen
}

/** Merge declaration up: `var a string` and `var b int` (caret on the second) → `var ( a string; b int )`; into the group when the first is one. */
class GoMergeDeclarationUpIntention : GoCodeActionIntention() {
    override val defaultText: String = "Merge declaration up"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val view = GoDeclarationView.at(file, offset) ?: return null
        val previous = GoDeclarationNeighbours.previous(view.element)?.takeIf { it.javaClass == view.element.javaClass }?.let(::GoDeclarationView) ?: return null
        if (!GoDeclarationNeighbours.plain(view) || previous.hasInnerComment() || view.specs.isEmpty() || previous.specs.isEmpty()) return null
        // `iota` and implicit values would take other values in the other group
        if (view.specs.any(view::dependsOnGroup)) return null
        val text = file.viewProvider.contents
        val indent = GoEditText.indentOf(text, previous.keyword.textRange.startOffset)
        val moved = view.specs.map { "\t" + GoDeclarationView.reindented(it, if (view.isGrouped) 0 else 1) }
        if (previous.isGrouped) {
            val rparen = GoDeclarationNeighbours.rparen(previous) ?: return null
            val insert = GoIntentionText.insertBefore(text, rparen.textRange.startOffset, indent, moved)
            return GoEditPlan(listOf(insert, GoEditPlan.Edit(previous.element.textRange.endOffset, view.element.textRange.endOffset, "")))
        }
        val first = "\t" + GoDeclarationView.reindented(previous.specs.single(), 1)
        val group = "${previous.keyword.text} (\n" + (listOf(first) + moved).joinToString("") { "$indent$it\n" } + "$indent)"
        return GoEditPlan(listOf(GoEditPlan.Edit(previous.keyword.textRange.startOffset, view.element.textRange.endOffset, group)))
    }
}

/**
 * Merge declaration up via comma: `var a int` and `var b int` → `var a, b int`; `var a = 1` and `var b = "s"` → `var a, b = 1, "s"`;
 * adjacent specs of a group likewise; `a := 1` and `b := 2` → `a, b := 1, 2`. Not when the types differ, one has values and the other
 * not, a value is a multi-value call, the second value reads a name the first declares, or (in a `const` group) `iota` or implicit values.
 */
class GoMergeDeclarationsByCommaIntention : GoCodeActionIntention() {
    override val defaultText: String = "Merge declaration up via comma"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        shortVar(file, offset)?.let { return it }
        val spec = GoDeclarationNeighbours.specAt(file, offset) ?: return null
        val view = GoDeclarationView(spec.parent ?: return null)
        if (view.hasInnerComment()) return null
        if (view.isGrouped) {
            val previous = GoDeclarationNeighbours.previous(spec)?.takeIf { it.javaClass == spec.javaClass } ?: return null
            if (view.isConst && (view.dependsOnGroup(spec) || view.dependsOnGroup(previous))) return null
            val merged = merge(view, previous, spec) ?: return null
            return GoEditPlan(listOf(GoEditPlan.Edit(previous.textRange.startOffset, spec.textRange.endOffset, merged)))
        }
        if (!GoDeclarationNeighbours.plain(view) || view.specs.size != 1) return null
        val previous = GoDeclarationNeighbours.previous(view.element)?.takeIf { it.javaClass == view.element.javaClass }?.let(::GoDeclarationView) ?: return null
        if (previous.isGrouped || previous.hasInnerComment() || previous.specs.size != 1) return null
        val merged = merge(view, previous.specs.single(), spec) ?: return null
        return GoEditPlan(listOf(GoEditPlan.Edit(previous.keyword.textRange.startOffset, view.element.textRange.endOffset, "${view.keyword.text} $merged")))
    }

    /** `names [T] [= values]` of [first] and [second], or null when they cannot share one spec. */
    private fun merge(view: GoDeclarationView, first: PsiElement, second: PsiElement): String? {
        val type = view.type(first)?.text
        if (type != view.type(second)?.text) return null
        val names = view.names(first) + view.names(second)
        if (names.any { it == null }) return null
        val values = values(view.names(first), view.values(first), view.names(second), view.values(second)) ?: return null
        if (values.isEmpty() && type == null) return null
        return names.joinToString(", ") + (type?.let { " $it" } ?: "") + (if (values.isEmpty()) "" else " = " + values.joinToString(", ") { it.text })
    }

    /** The values of both, one per name or none at all; null when that is not so or the second reads a name of the first. */
    private fun values(firstNames: List<String?>, first: List<GoExpression>, secondNames: List<String?>, second: List<GoExpression>): List<GoExpression>? {
        if (first.isEmpty() != second.isEmpty()) return null
        if (first.isNotEmpty() && (first.size != firstNames.size || second.size != secondNames.size)) return null
        val declared = firstNames.filter { it != "_" }.filterNotNull()
        if (second.any { value -> declared.any { GoLiteralText.mentions(value, it) } }) return null
        return first + second
    }

    private fun shortOf(statement: PsiElement): GoShortVarDeclaration? = ((statement as? GoSimpleStatement)?.statement ?: statement) as? GoShortVarDeclaration

    private fun shortVar(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val statement = GoIntentionText.statementAt(leaf) ?: return null
        val second = shortOf(statement) ?: return null
        val previous = GoDeclarationNeighbours.previous(statement) ?: return null
        val first = shortOf(previous) ?: return null
        if (GoLiteralText.hasComment(statement) || GoLiteralText.hasComment(previous)) return null
        val firstNames = first.varDefinitionList.map { it.name }
        val secondNames = second.varDefinitionList.map { it.name }
        if (firstNames.any { it == null } || secondNames.any { it == null }) return null
        if (firstNames.any { it != "_" && it in secondNames }) return null
        val values = values(firstNames, first.expressionList, secondNames, second.expressionList)?.ifEmpty { null } ?: return null
        val text = (firstNames + secondNames).joinToString(", ") + " := " + values.joinToString(", ") { it.text }
        return GoEditPlan(listOf(GoEditPlan.Edit(previous.textRange.startOffset, statement.textRange.endOffset, text)))
    }
}

/** Split declarations into two groups: `var ( a string; b<caret> int; c int )` → `var a string` and `var ( b int; c int )`. */
class GoSplitDeclarationsIntoTwoGroupsIntention : GoCodeActionIntention() {
    override val defaultText: String = "Split declarations into two groups"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val spec = GoDeclarationNeighbours.specAt(file, offset) ?: return null
        val view = GoDeclarationView(spec.parent ?: return null)
        if (!view.isGrouped || view.hasInnerComment()) return null
        val specs = view.specs
        val index = specs.indexOf(spec)
        if (index < 1) return null
        val head = specs.subList(0, index)
        val tail = specs.subList(index, specs.size)
        if (tail.any(view::dependsOnGroup)) return null
        val indent = GoEditText.indentOf(file.viewProvider.contents, view.keyword.textRange.startOffset)
        val keyword = view.keyword.text
        fun part(list: List<PsiElement>): String =
            if (list.size == 1) "$keyword ${GoDeclarationView.reindented(list.single(), -1)}"
            else "$keyword (\n" + list.joinToString("") { "$indent\t${GoDeclarationView.reindented(it, 0)}\n" } + "$indent)"
        val replacement = part(head) + "\n$indent" + part(tail)
        return GoEditPlan(listOf(GoEditPlan.Edit(view.keyword.textRange.startOffset, view.element.textRange.endOffset, replacement)))
    }
}
