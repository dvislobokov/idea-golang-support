package io.github.golangsupport.ide.rules.builtin.vet

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.rules.GoRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoExpressionPsi
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoStaticcheckPsi
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType

/** govet `directive`: `//go:debug` outside the header of a `package main` or `_test.go` file, and odd spaces in `//go:` directives. */
class GoVetDirectiveRule : GoVetFileRule() {
    override val id: String get() = "govet:directive"
    override val title: String get() = "Misplaced //go:debug directive"
    override val description: String get() =
        "govet <code>directive</code>: <code>//go:debug</code> settings take effect only in the main package and in tests, and only above " +
            "the <code>package</code> clause; elsewhere they are ignored. A <code>//go:</code> directive separated from its arguments by a " +
            "non-ASCII or unusual space is reported too."
    override val needs: Set<GoRuleNeed> get() = GoRule.SYNTAX_ONLY

    override fun checkFile(file: GoFile, ctx: GoRuleContext) {
        val packageKeyword = file.packageClause?.`package`?.textRange?.startOffset ?: Int.MAX_VALUE
        val isMain = file.packageName == "main"
        val isTest = file.name.endsWith("_test.go")
        for (c in GoVetPsi.comments(file)) {
            var line = c.text
            if (!line.startsWith("//go:")) continue
            line.indexOf(" // ERROR ").takeIf { it >= 0 }?.let { line = line.substring(0, it) }
            var verb = line
            val i = line.indexOfFirst(::isSpace)
            if (i >= 0) {
                verb = line.substring(0, i)
                val ch = line[i]
                if (ch != ' ' && ch != '\t' && ch != '\n') ctx.report(c, "invalid space ${quoteRune(line.codePointAt(i))} in $verb directive")
            }
            if (verb != "//go:debug") continue
            when {
                !isMain && !isTest -> ctx.report(c, "//go:debug directive only valid in package main or test")
                c.textRange.startOffset >= packageKeyword -> ctx.report(c, "//go:debug directive only valid before package declaration")
            }
        }
    }

    /** Go's `unicode.IsSpace`. */
    private fun isSpace(c: Char): Boolean = c.code in SPACES || c.code in 0x2000..0x200a

    /** `%#q` of a space rune: `'\v'`, `'\u00a0'` (strconv.QuoteRune; every unusual space is unprintable there). */
    private fun quoteRune(r: Int): String = when (r) {
        0x0b -> "'\\v'"
        0x0c -> "'\\f'"
        0x0d -> "'\\r'"
        else -> if (r < 0x10000) "'\\u%04x'".format(r) else "'\\U%08x'".format(r)
    }

    private companion object {
        val SPACES = setOf(0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x20, 0x85, 0xa0, 0x1680, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000)
    }
}

/** staticcheck SA9009: `// go:generate` (a space after `//`) at the start of a line is an ordinary comment, not a compiler directive. */
class GoIneffectualDirectiveRule : GoStaticcheckFileRule() {
    override val id: String get() = "SA9009"
    override val title: String get() = "Ineffectual Go compiler directive"
    override val description: String get() =
        "A potential Go compiler directive was found, but is ineffectual as it begins with whitespace."

    override fun checkFile(file: GoFile, ctx: GoRuleContext) {
        val text = file.viewProvider.contents
        for (c in GoVetPsi.comments(file)) {
            val t = c.text
            if (!t.startsWith("//")) continue
            if (GoVetPsi.column(text, c.textRange.startOffset) != 1) continue
            val body = t.substring(2)
            var rest = body.trimStart(' ', '\t')
            if (rest.length == body.length || !rest.startsWith("go:")) continue
            rest = rest.substring(3)
            if (rest.isEmpty() || rest[0] !in 'a'..'z') continue
            ctx.report(c, "ineffectual compiler directive due to extraneous space: ${GoStaticcheckPsi.quote(t)}", RemoveSpaceFix())
        }
    }

    /** `// go:x` -> `//go:x`. */
    private class RemoveSpaceFix : LocalQuickFix {
        override fun getFamilyName(): String = "Remove the space before the directive"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val c = descriptor.psiElement as? PsiComment ?: return
            val ws = c.text.substring(2).takeWhile { it == ' ' || it == '\t' }.length
            if (ws == 0) return
            val file = c.containingFile
            val document = GoImportEdits.document(file) ?: return
            val start = c.textRange.startOffset + 2
            document.deleteString(start, start + ws)
            GoImportEdits.commit(file, document)
        }
    }
}

/** staticcheck SA4019: two `// +build` lines of the file header with the same set of terms. */
class GoDuplicateBuildConstraintsRule : GoStaticcheckFileRule() {
    override val id: String get() = "SA4019"
    override val title: String get() = "Multiple, identical build constraints in the same file"
    override val description: String get() =
        "Two <code>// +build</code> lines of the file header list the same terms (in any order): the second one adds nothing."

    override fun checkFile(file: GoFile, ctx: GoRuleContext) {
        val clause = file.packageClause ?: return
        val keyword = clause.`package` ?: return
        val text = file.viewProvider.contents
        val comments = GoVetPsi.comments(file).filter { it.textRange.startOffset < keyword.textRange.startOffset }
        val cutoff = docStart(comments, keyword.textRange.startOffset, text)
        val constraints = ArrayList<List<String>>()
        for (c in comments) {
            if (c.textRange.startOffset >= cutoff) break
            for (line in lines(c.text)) {
                if (!line.startsWith("+build ")) continue
                constraints += line.removePrefix("+build ").trim().split(' ', '\t').filter { it.isNotEmpty() }
            }
        }
        for (i in constraints.indices) for (j in i + 1 until constraints.size) {
            if (constraints[i].sorted() == constraints[j].sorted()) {
                val a = GoStaticcheckPsi.quote(constraints[i].joinToString(" "))
                val b = GoStaticcheckPsi.quote(constraints[j].joinToString(" "))
                ctx.report(clause, TextRange(keyword.startOffsetInParent, keyword.startOffsetInParent + keyword.textLength), "identical build constraints $a and $b")
            }
        }
    }

    /** The start of the package doc comment (the comment group ending on the line above `package`), or [keyword] without one. */
    private fun docStart(comments: List<PsiComment>, keyword: Int, text: CharSequence): Int {
        var i = comments.lastIndex
        if (i < 0 || GoVetPsi.newlines(text, comments[i].textRange.endOffset, keyword) > 1) return keyword
        while (i > 0 && GoVetPsi.newlines(text, comments[i - 1].textRange.endOffset, comments[i].textRange.startOffset) <= 1) i--
        return comments[i].textRange.startOffset
    }

    /** The lines of a comment as `ast.CommentGroup.Text` gives them: markers and the first space of a line comment removed. */
    private fun lines(comment: String): List<String> =
        if (comment.startsWith("//")) listOf(comment.substring(2).let { if (it.startsWith(" ")) it.substring(1) else it })
        else comment.removePrefix("/*").removeSuffix("*/").split('\n').map { it.trimEnd() }
}

/** staticcheck SA9004: in `const ( A T = 1; B = 2 )` only `A` has type `T`; `B` is an untyped constant. Fix: give every constant the type. */
class GoConstGroupTypeRule : GoStaticcheckFileRule() {
    override val id: String get() = "SA9004"
    override val title: String get() = "Only the first constant has an explicit type"
    override val description: String get() =
        "In a constant declaration such as <code>const ( First byte = 1; Second = 2 )</code> the constant <code>Second</code> does not have " +
            "the same type as <code>First</code>: the type is only passed on when no explicit value is assigned. With enumerations of a type " +
            "with methods (<code>String</code>) the later constants lose them."
    override val needs: Set<GoRuleNeed> get() = GoVetPsi.TYPES

    override fun checkFile(file: GoFile, ctx: GoRuleContext) {
        val text = file.viewProvider.contents
        for (decl in PsiTreeUtil.findChildrenOfType(file, GoConstDeclaration::class.java)) {
            if (decl.lparen == null) continue
            for (group in groups(decl.constSpecList, text)) check(group, ctx)
        }
    }

    /** `astutil.GroupSpecs`: runs of specs on consecutive lines. */
    private fun groups(specs: List<GoConstSpec>, text: CharSequence): List<List<GoConstSpec>> {
        val out = ArrayList<MutableList<GoConstSpec>>()
        var lastEnd = -1
        for (s in specs) {
            val start = start(s) ?: continue
            if (out.isEmpty() || GoVetPsi.newlines(text, lastEnd, start) != 1) out += mutableListOf(s) else out.last() += s
            lastEnd = end(s)
        }
        return out
    }

    private fun start(s: GoConstSpec): Int? = s.constDefinitionList.firstOrNull()?.textRange?.startOffset

    private fun end(s: GoConstSpec): Int = (s.expressionList.lastOrNull() ?: s.type ?: s.constDefinitionList.last()).textRange.endOffset

    private fun check(group: List<GoConstSpec>, ctx: GoRuleContext) {
        if (group.size < 2) return
        val typeNode = group[0].type ?: return
        val firstType = GoExpressionPsi.typeOf(typeNode)
        if (firstType is GoUnknownType) return
        for ((i, spec) in group.withIndex()) {
            if (i > 0 && spec.type != null) return
            if (spec.constDefinitionList.size != 1 || spec.expressionList.size != 1) return
            val value = spec.expressionList[0]
            val t = ctx.typeOf(value)
            if (t is GoUnknownType || !GoTypePredicates.convertible(t, firstType)) return
            val literal = when (value) {
                is GoLiteral, is GoStringLiteral -> true
                is GoUnaryExpr -> value.expression.let { it is GoLiteral || it is GoStringLiteral }
                else -> false
            }
            if (!literal) return
        }
        val first = group[0]
        val from = start(first)!! - first.textRange.startOffset
        val range = TextRange(from, end(first) - first.textRange.startOffset)
        ctx.report(first, range, "only the first constant in this group has an explicit type", AddTypeFix(group.size - 1))
    }

    /** Inserts the first spec's type after the name of each of the next [count] specs. */
    private class AddTypeFix(private val count: Int) : LocalQuickFix {
        override fun getFamilyName(): String = "Add type to all constants in group"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val first = descriptor.psiElement as? GoConstSpec ?: return
            val decl = first.parent as? GoConstDeclaration ?: return
            val specs = decl.constSpecList
            val index = specs.indexOf(first)
            val type = first.type?.text ?: return
            if (index < 0 || index + count >= specs.size) return
            val file = first.containingFile
            val document = GoImportEdits.document(file) ?: return
            for (k in count downTo 1) {
                val def = specs[index + k].constDefinitionList.firstOrNull() ?: continue
                document.insertString(def.textRange.endOffset, " $type")
            }
            GoImportEdits.commit(file, document)
        }
    }
}
