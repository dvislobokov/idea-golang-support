package io.github.golangsupport.ide.intentions

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.editor.GoDeclarationJoin
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoType
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoTypeRenderer

/** A `var` or `const` declaration, read the same way for both. */
internal class GoDeclarationView(val element: PsiElement) {
    val isConst: Boolean get() = element is GoConstDeclaration
    private val variable: GoVarDeclaration? get() = element as? GoVarDeclaration
    private val constant: GoConstDeclaration? get() = element as? GoConstDeclaration
    val keyword: PsiElement get() = variable?.`var` ?: constant!!.const
    val isGrouped: Boolean get() = (if (variable != null) variable!!.lparen else constant!!.lparen) != null
    val specs: List<PsiElement> get() = variable?.varSpecList ?: constant!!.constSpecList

    fun names(spec: PsiElement): List<String?> = when (spec) {
        is GoVarSpec -> spec.varDefinitionList.map { it.name }
        is GoConstSpec -> spec.constDefinitionList.map { it.name }
        else -> emptyList()
    }

    fun type(spec: PsiElement): GoType? = (spec as? GoVarSpec)?.type ?: (spec as? GoConstSpec)?.type

    fun values(spec: PsiElement): List<GoExpression> = (spec as? GoVarSpec)?.expressionList ?: (spec as? GoConstSpec)?.expressionList.orEmpty()

    /** A comment after the keyword: rewriting the declaration as text would lose or misplace it. */
    fun hasInnerComment(): Boolean = PsiTreeUtil.findChildrenOfType(element, PsiComment::class.java).any { it.textRange.startOffset > keyword.textRange.startOffset }

    /** A `const` spec that relies on `iota` or on the implicit repetition of the previous spec: its meaning depends on the group. */
    fun dependsOnGroup(spec: PsiElement): Boolean = isConst && (values(spec).isEmpty() || mentions(spec, setOf("iota")))

    companion object {
        /** The `var` / `const` declaration around the caret, not across a function literal. */
        fun at(file: GoFile, offset: Int): GoDeclarationView? {
            var e: PsiElement? = GoIntentionText.leafAt(file, offset)
            while (e != null && e !is PsiFile) {
                if (e is GoVarDeclaration || e is GoConstDeclaration) return GoDeclarationView(e)
                if (e is GoFunctionLit || e is GoBlock) return null
                e = e.parent
            }
            return null
        }

        /** Whether a plain identifier of [names] is used inside [element]. */
        fun mentions(element: PsiElement, names: Set<String?>): Boolean =
            PsiTreeUtil.findChildrenOfType(element, GoReferenceExpression::class.java).any { it.expression == null && it.identifier?.text in names }

        /** The text of [element] with every line after its first moved by [delta] levels (raw strings keep theirs). */
        fun reindented(element: PsiElement, delta: Int): String {
            val file = element.containingFile
            val text = file.node.chars
            val range = element.textRange
            val eol = GoEditText.lineEnd(text, range.startOffset)
            if (eol >= range.endOffset || delta == 0) return element.text
            return text.subSequence(range.startOffset, eol).toString() + "\n" + GoEditText.shift(file, eol + 1, range.endOffset, delta)
        }
    }
}

/**
 * Split into separate declarations: `var (a int; b = 2)` → one `var` per spec; `var a, b int` / `var a, b = 1, 2` → one `var` per name
 * (not for `var a, b = f()`, nor when a value uses one of the names). `const` likewise, but not a group that uses `iota` or implicit values.
 */
class GoSplitDeclarationIntention : GoCodeActionIntention() {
    override val defaultText: String = "Split into separate declarations"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val view = GoDeclarationView.at(file, offset) ?: return null
        if (view.hasInnerComment()) return null
        val keyword = view.keyword.text
        val lines = if (view.isGrouped) {
            val specs = view.specs.ifEmpty { return null }
            if (specs.any(view::dependsOnGroup)) return null
            specs.map { "$keyword ${GoDeclarationView.reindented(it, -1)}" }
        } else {
            val spec = view.specs.singleOrNull() ?: return null
            val names = view.names(spec)
            val values = view.values(spec)
            if (names.size < 2 || (values.isNotEmpty() && values.size != names.size)) return null
            if (values.any { GoDeclarationView.mentions(it, names.toSet()) }) return null
            val type = view.type(spec)?.let { " ${it.text}" }.orEmpty()
            names.mapIndexed { i, name -> "$keyword $name$type" + (values.getOrNull(i)?.let { " = ${it.text}" } ?: "") }
        }
        val start = view.keyword.textRange.startOffset
        val indent = GoEditText.indentOf(file.viewProvider.contents, start)
        return GoEditPlan(listOf(GoEditPlan.Edit(start, view.element.textRange.endOffset, lines.joinToString("\n$indent"))))
    }
}

/** Group declarations: adjacent single `var` (or `const`) declarations → one `var ( … )` group. */
class GoGroupDeclarationsIntention : GoCodeActionIntention() {
    override val defaultText: String = "Group declarations"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val view = GoDeclarationView.at(file, offset) ?: return null
        if (!groupable(view)) return null
        val run = ArrayDeque(listOf(view))
        while (true) run.addFirst(neighbour(run.first(), backwards = true) ?: break)
        while (true) run.addLast(neighbour(run.last(), backwards = false) ?: break)
        if (run.size < 2) return null
        val start = run.first().keyword.textRange.startOffset
        val indent = GoEditText.indentOf(file.viewProvider.contents, start)
        val specs = run.joinToString("") { "$indent\t${GoDeclarationView.reindented(it.specs.single(), 1)}\n" }
        return GoEditPlan(listOf(GoEditPlan.Edit(start, run.last().element.textRange.endOffset, "${view.keyword.text} (\n$specs$indent)")))
    }

    /** The declaration of the same kind right before or after [view], with only whitespace between. */
    private fun neighbour(view: GoDeclarationView, backwards: Boolean): GoDeclarationView? {
        var e = if (backwards) view.element.prevSibling else view.element.nextSibling
        while (e != null && GoEditText.isBlank(e)) e = if (backwards) e.prevSibling else e.nextSibling
        if (e == null || e.javaClass != view.element.javaClass) return null
        val other = GoDeclarationView(e)
        // a comment bound to the first declaration stays before the group; one bound to a later declaration would end up inside it
        val later = if (backwards) view else other
        return other.takeIf { groupable(it) && later.element.textRange.startOffset == later.keyword.textRange.startOffset }
    }

    private fun groupable(view: GoDeclarationView): Boolean =
        !view.isGrouped && !view.hasInnerComment() && view.specs.size == 1 && !(view.isConst && GoDeclarationView.mentions(view.specs.single(), setOf("iota")))
}

/** Join declaration and assignment: `var x T` and `x = v` on the next statement → `x := v`, or `var x T = v` when `v` has another default type. */
class GoJoinDeclarationAndAssignmentIntention : GoCodeActionIntention() {
    override val defaultText: String = "Join declaration and assignment"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val view = GoDeclarationView.at(file, offset) ?: return null
        val joined = GoDeclarationJoin.of(view.element as? GoVarDeclaration ?: return null) ?: return null
        return GoEditPlan(listOf(GoEditPlan.Edit(joined.start, joined.end, joined.text)))
    }
}

/**
 * Convert to 'var' declaration: `x := v` → `var x T = v`, `T` written as the file names it (import name of another package, the import
 * added when missing). Not offered for a type that cannot be written here (unexported type of another package, untyped `nil`).
 */
class GoShortVarToVarIntention : GoCodeActionIntention() {
    override val defaultText: String = "Convert to 'var' declaration"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val outer = GoIntentionText.statementAt(leaf) ?: return null
        val short = ((outer as? GoSimpleStatement)?.statement ?: outer) as? GoShortVarDeclaration ?: return null
        val variable = short.varDefinitionList.singleOrNull() ?: return null
        val value = short.expressionList.singleOrNull() ?: return null
        val name = variable.name?.takeIf { it != "_" } ?: return null
        val type = GoSemanticService.getInstance(file.project).declarationType(variable)
        if (!GoTypePredicates.isKnown(type) || GoTypePredicates.isUntyped(type)) return null
        val source = GoSourceText(file)
        var writable = true
        GoTypeRenderer.render(type) { named -> if (!nameable(named, source)) writable = false; null }
        if (!writable) return null
        val replacement = "var $name ${source.type(type)} = ${value.text}"
        return GoEditPlan(listOf(GoEditPlan.Edit(short.textRange.startOffset, short.textRange.endOffset, replacement)), source.imports)
    }

    /** An unexported type of another package cannot be named in this file. */
    private fun nameable(named: GoNamedType, source: GoSourceText): Boolean {
        val exported = named.name.firstOrNull()?.isUpperCase() == true
        return exported || source.isOwnPackage(named.declaration) || (named.declaration.containingFile as? GoFile)?.packageName == "builtin"
    }
}

/** Convert to short variable declaration: `var x = v`, or `var x T = v` when `v` alone gives `x` the type `T`, → `x := v`. */
class GoVarToShortVarIntention : GoCodeActionIntention() {
    override val defaultText: String = "Convert to short variable declaration"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val view = GoDeclarationView.at(file, offset) ?: return null
        val declaration = view.element as? GoVarDeclaration ?: return null
        if (declaration.lparen != null || !GoEditText.isStatementList(declaration.parent) || view.hasInnerComment()) return null
        val spec = declaration.varSpecList.singleOrNull() ?: return null
        val variable = spec.varDefinitionList.singleOrNull() ?: return null
        val value = spec.expressionList.singleOrNull() ?: return null
        val name = variable.name?.takeIf { it != "_" } ?: return null
        if (spec.type != null) {
            val service = GoSemanticService.getInstance(file.project)
            val declared = service.declarationType(variable)
            val inferred = GoTypePredicates.defaultType(service.typeOf(value))
            if (!GoTypePredicates.isKnown(declared) || !GoTypePredicates.isKnown(inferred) || !GoTypePredicates.identical(declared, inferred)) return null
        }
        val start = declaration.`var`.textRange.startOffset
        return GoEditPlan(listOf(GoEditPlan.Edit(start, declaration.textRange.endOffset, "$name := ${value.text}")))
    }
}
