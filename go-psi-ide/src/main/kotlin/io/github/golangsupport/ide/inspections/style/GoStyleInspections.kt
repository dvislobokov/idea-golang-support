package io.github.golangsupport.ide.inspections.style

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoEditFix
import io.github.golangsupport.ide.inspections.GoInspectionText
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoBreakStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoContinueStatement
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.GoElseStatement
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoGotoStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoImportDeclaration
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoValue
import io.github.golangsupport.ide.rules.builtin.simple.GoSimplePsi
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType

/**
 * GoLand's "Error string should not be capitalized or end with punctuation" (`GoErrorStringFormat`, staticcheck ST1005): the literal
 * message of `errors.New("…")` / `fmt.Errorf("…", …)`. Capitalised: the first letter is upper-case and the rest of the first word has
 * no upper-case letters or digits (`URL not found`, `IPv4 …` are initialisms and pass). Punctuation: the text ends with `.`, `:`, `!`
 * or a newline. Fixes: lowercase the first letter; remove the trailing punctuation.
 */
class GoErrorStringFormatInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoCallExpr) return
        val literal = element.arguments.firstOrNull() as? GoStringLiteral ?: return
        val ref = GoLintPsi.calleeReference(element) ?: return
        val name = ref.identifier?.text
        if (name != "New" && name != "Errorf" || ref.expression == null) return
        val target = GoLintPsi.calleeTarget(element) ?: return
        val path = GoAnalysisPsi.packagePath(target)
        if (!(name == "New" && path == "errors" || name == "Errorf" && path == "fmt")) return
        val body = body(literal) ?: return
        if (isCapitalized(body)) holder.registerProblem(literal, TextRange(1, 2), "Error string should not be capitalized", LOWERCASE)
        val punct = trailingPunctuation(body, literal.text.startsWith("`"))
        if (punct > 0) holder.registerProblem(literal, TextRange(literal.textLength - 1 - punct, literal.textLength - 1),
            "Error string should not end with punctuation or a newline", REMOVE_PUNCTUATION)
    }

    companion object {
        private fun body(literal: GoStringLiteral): String? = literal.text.takeIf { it.length >= 2 }?.substring(1, literal.textLength - 1)

        fun isCapitalized(body: String): Boolean {
            val first = body.firstOrNull() ?: return false
            if (!first.isUpperCase()) return false
            val word = body.takeWhile { !it.isWhitespace() }
            return word.drop(1).none { it.isUpperCase() || it.isDigit() }
        }

        /** The length of the trailing `.`, `:`, `!` or `\n` (escaped in an interpreted literal) of [body], or 0. */
        fun trailingPunctuation(body: String, raw: Boolean): Int = when {
            !raw && body.endsWith("\\n") && !body.endsWith("\\\\n") -> 2
            body.isNotEmpty() && body.last() in ".:!" -> 1
            raw && body.endsWith("\n") -> 1
            else -> 0
        }

        private val LOWERCASE = GoEditFix("Lowercase the first letter") { literal ->
            val start = literal.textRange.startOffset + 1
            val c = literal.text.getOrNull(1)?.takeIf { it.isUpperCase() } ?: return@GoEditFix null
            listOf(GoEditPlan.Edit(start, start + 1, c.lowercase()))
        }

        private val REMOVE_PUNCTUATION = GoEditFix("Remove the trailing punctuation") { literal ->
            val text = literal.text
            val n = trailingPunctuation(text.substring(1, text.length - 1), text.startsWith("`")).takeIf { it > 0 } ?: return@GoEditFix null
            val end = literal.textRange.endOffset - 1
            listOf(GoEditPlan.Edit(end - n, end, ""))
        }
    }
}

/**
 * GoLand's "Redundant 'else' in 'if'" (`GoRedundantElseInIf`, golint `indent-error-flow`): `if c { …; return } else { … }` where the
 * `if` block ends with `return`, `break`, `continue`, `goto` or `panic(…)`: the `else` block can follow the `if` unindented. Not for
 * `else if` chains or an `if` that is itself an `else` branch. Fix (when the `if` has no init statement, which would scope its
 * variables to the `else`, and when no top-level declaration of the `else` block reuses a name visible at the `if` or mentioned after it):
 * remove the `else` and outdent its block.
 */
class GoRedundantElseInIfInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoIfStatement || element.parent is GoElseStatement) return
        val elseStatement = element.elseStatement ?: return
        if (elseStatement.statement !is GoBlock || !inStatementList(element)) return
        val last = element.block?.statementList?.lastOrNull() ?: return
        val what = terminator(last) ?: return
        val safe = element.initStatement == null && !hasComments(elseStatement) && outdentKeepsNames(element, elseStatement.statement as GoBlock)
        val fixes = if (safe) arrayOf<LocalQuickFix>(FIX) else emptyArray()
        holder.registerProblem(elseStatement, TextRange(0, elseStatement.`else`.textLength),
            "'if' block ends with a '$what' statement, so drop this 'else' and outdent its block", *fixes)
    }

    companion object {
        /**
         * The top-level declarations of [elseBlock] move into the statement list of [ifStatement]: none of their names may be visible at the
         * `if` (`v, err := b()` with an outer `err` would assign it or fail to compile) or mentioned after it (a later `x :=` would clash,
         * a later use would see the moved variable).
         */
        private fun outdentKeepsNames(ifStatement: GoIfStatement, elseBlock: GoBlock): Boolean {
            val names = elseBlock.statementList.flatMap(GoPsiUtil::declarationsOf).mapNotNull { it.name }.filter { it != "_" }.distinct()
            if (names.isEmpty()) return true
            val list = ifStatement.parent
            val later = list.children.filter { it is GoStatement && it.textRange.startOffset > ifStatement.textRange.startOffset }
            return names.none { n -> GoScopes.resolveName(ifStatement, n).isNotEmpty() || later.any { GoSimplePsi.mentions(it, n) } }
        }

        private fun inStatementList(s: PsiElement): Boolean = s.parent.let { it is GoBlock || it is GoExprCaseClause || it is GoTypeCaseClause || it is GoCommClause }

        private fun hasComments(e: PsiElement) = PsiTreeUtil.findChildOfType(e, PsiComment::class.java) != null

        fun terminator(s: GoStatement): String? = when (s) {
            is GoReturnStatement -> "return"
            is GoBreakStatement -> "break"
            is GoContinueStatement -> "continue"
            is GoGotoStatement -> "goto"
            is GoSimpleStatement -> {
                val call = s.leftHandExprList?.expressionList?.singleOrNull() as? GoCallExpr
                val ref = call?.let(GoLintPsi::calleeReference)
                if (s.statement == null && ref != null && ref.expression == null && ref.identifier?.text == "panic" &&
                    GoLintPsi.calleeTarget(call)?.let(GoLintPsi::isBuiltin) == true) "panic" else null
            }
            else -> null
        }

        private val FIX = GoEditFix("Remove redundant 'else'") { e ->
            val elseStatement = e as? GoElseStatement ?: return@GoEditFix null
            val ifStatement = elseStatement.parent as? GoIfStatement ?: return@GoEditFix null
            val ifBlock = ifStatement.block ?: return@GoEditFix null
            val elseBlock = elseStatement.statement as? GoBlock ?: return@GoEditFix null
            val lbrace = elseBlock.lbrace
            val rbrace = elseBlock.rbrace ?: return@GoEditFix null
            val text = e.containingFile.viewProvider.contents
            val inner = text.substring(lbrace.textRange.endOffset, rbrace.textRange.startOffset)
            val lines = inner.trimEnd().lines().dropWhile { it.isBlank() }
            val replacement = if (lines.isEmpty()) "" else "\n" + lines.joinToString("\n") { outdent(it) }
            listOf(GoEditPlan.Edit(ifBlock.textRange.endOffset, elseBlock.textRange.endOffset, replacement))
        }

        private fun outdent(line: String): String = when {
            line.startsWith("\t") -> line.substring(1)
            line.startsWith("    ") -> line.substring(4)
            else -> line
        }
    }
}

/**
 * GoLand's "Unsorted imports" (`GoUnsortedImport`): the specs of an `import (...)` group (specs between blank lines) not in the order of
 * their paths, as gofmt / goimports sort them. Reported once per group, at the first spec out of order. Fix: sort every group of the
 * declaration (offered when each spec of an unsorted group sits on its own line and no comment line separates them).
 */
class GoUnsortedImportInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoImportDeclaration || element.lparen == null) return
        val text = file.viewProvider.contents
        for (group in groups(element, text)) {
            val i = (1 until group.size).firstOrNull { key(group[it]) < key(group[it - 1]) } ?: continue
            val fixes = if (groups(element, text).all { sortable(it, text) }) arrayOf<LocalQuickFix>(FIX) else emptyArray()
            holder.registerProblem(group[i], "Import is not sorted", *fixes)
        }
    }

    companion object {
        private fun key(spec: GoImportSpec): String = spec.path + "\u0000" + (spec.alias ?: "")

        /** The specs of [declaration] split at blank lines and comment-only lines. */
        fun groups(declaration: GoImportDeclaration, text: CharSequence): List<List<GoImportSpec>> {
            val out = ArrayList<MutableList<GoImportSpec>>()
            var previous: GoImportSpec? = null
            for (spec in declaration.importSpecList) {
                // A trailing comment of the previous spec's line stays with it; a blank or comment-only line between starts a group.
                val between = previous?.let { text.substring(lineEnd(text, it.textRange.endOffset).coerceAtMost(spec.textRange.startOffset), spec.textRange.startOffset) }
                if (previous == null || BLANK_LINE.containsMatchIn(between!!)) out += ArrayList<GoImportSpec>()
                out.last() += spec
                previous = spec
            }
            return out
        }

        private fun sortable(group: List<GoImportSpec>, text: CharSequence): Boolean =
            group.size < 2 || group.all { GoInspectionText.wholeLines(text, it.textRange.startOffset, lineEnd(text, it.textRange.endOffset)) != null } &&
                group.zipWithNext().all { (a, b) -> text.substring(lineEnd(text, a.textRange.endOffset), b.textRange.startOffset).isBlank() }

        /** The end of the line holding [offset] (a trailing line comment included), before the line break. */
        private fun lineEnd(text: CharSequence, offset: Int): Int {
            var end = offset
            while (end < text.length && text[end] != '\n') end++
            return if (end > offset && text[end - 1] == '\r') end - 1 else end
        }

        private val BLANK_LINE = Regex("\n[ \t\r]*\n|//|/\\*")

        private val FIX = GoEditFix("Sort imports") { spec ->
            val declaration = spec.parent as? GoImportDeclaration ?: return@GoEditFix null
            val text = spec.containingFile.viewProvider.contents
            groups(declaration, text).filter { g -> (1 until g.size).any { key(g[it]) < key(g[it - 1]) } }.mapNotNull { g ->
                if (!sortable(g, text)) return@mapNotNull null
                val starts = g.map { GoInspectionText.lineStart(text, it.textRange.startOffset) }
                val lines = g.mapIndexed { i, s -> text.substring(starts[i], lineEnd(text, s.textRange.endOffset)) }
                val sorted = g.indices.sortedBy { key(g[it]) }.map { lines[it] }
                GoEditPlan.Edit(starts.first(), lineEnd(text, g.last().textRange.endOffset), sorted.joinToString("\n"))
            }
        }
    }
}

/**
 * GoLand's "Struct initialization without field names" (`GoStructInitializationWithoutFieldNames`): a struct literal that lists values
 * without keys (`Point{1, 2}`, the elided `{"empty", nil, 0}` of a `[]struct{…}` table): adding a field breaks it and the reader has to
 * count. Unlike vet `composites` this includes types of the current package and anonymous structs, as GoLand does (seen live on a test
 * table). The message and range (the `{…}`) are GoLand's. Fix: add the field names.
 */
class GoStructInitializationWithoutFieldNamesInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoLiteralValue) return
        val elements = element.elements
        if (elements.isEmpty() || elements.any { it.key != null }) return
        val struct = structOf(element) ?: return
        if (struct.fields.isEmpty()) return
        val fixes = if (elements.size == struct.fields.size && namable(struct)) arrayOf<LocalQuickFix>(FIX) else emptyArray()
        holder.registerProblem(element, "Fields are assigned without explicit names", *fixes)
    }

    companion object {
        /** Every field can be written as a key: a blank `_` field cannot (`_: x` does not compile). */
        private fun namable(struct: GoStructType): Boolean = struct.fields.none { it.name == "_" }

        /** The struct type a literal value builds, through named types and an elided `&T` / `*T`; null for other literals. */
        fun structOf(value: GoLiteralValue): GoStructType? = literalType(value)?.let { t ->
            val base = (t as? GoPointerType)?.takeIf { value.parent !is GoCompositeLit }?.elem ?: t
            base.underlying() as? GoStructType
        }

        private fun literalType(value: GoLiteralValue): GoType? = when (val parent = value.parent) {
            is GoCompositeLit -> GoSemanticService.getInstance(value.project).typeOf(parent)
            is GoValue, is GoKey -> {
                val element = parent.parent as? GoElement
                val outer = element?.parent as? GoLiteralValue
                val outerType = outer?.let(::literalType)?.let { (it as? GoPointerType)?.takeIf { outer.parent !is GoCompositeLit }?.elem ?: it }?.underlying()
                when (outerType) {
                    is GoSliceType -> outerType.elem
                    is GoArrayType -> outerType.elem
                    is GoMapType -> if (parent is GoKey) outerType.key else outerType.value
                    else -> null
                }
            }
            else -> null
        }

        private val FIX = GoEditFix("Add field names") { e ->
            val value = e as? GoLiteralValue ?: return@GoEditFix null
            val fields = structOf(value)?.takeIf(::namable)?.fields ?: return@GoEditFix null
            val elements = value.elements
            if (elements.size != fields.size || elements.any { it.key != null }) return@GoEditFix null
            elements.mapIndexed { i, el -> GoEditPlan.Edit(el.textRange.startOffset, el.textRange.startOffset, "${fields[i].name}: ") }
        }
    }
}
