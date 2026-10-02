package io.github.golangsupport.ide.formatter

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.codeStyle.PreFormatProcessor
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoElementFactory
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportDeclaration
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes

/**
 * Sorts the specs of every parenthesised import declaration like gofmt (`go/ast.SortImports`):
 * runs of specs on consecutive lines (a blank or comment-only line starts a new run) are sorted
 * by import path, then name, then line comment; each spec keeps the comments on its line.
 *
 * It runs before formatting (a pre-format processor rather than a post-format one) so that the
 * alignment of trailing comments is computed on the sorted order. Only the order of whole lines
 * changes; token and comment text is preserved. Unlike gofmt, duplicate imports are not removed.
 */
class GoImportSorter : PreFormatProcessor {

    override fun process(element: ASTNode, range: TextRange): TextRange {
        val file = element.psi?.containingFile as? GoFile ?: return range
        // import declarations are top-level (inside the file's IMPORT_LIST); walking the whole tree
        // for them was a noticeable part of reformatting a large file
        val decls = ArrayList<GoImportDeclaration>()
        collectImports(file.node, decls)
        for (decl in decls.filter { range.contains(it.textRange) }) sort(decl)
        return range
    }

    private fun collectImports(parent: ASTNode, out: MutableList<GoImportDeclaration>) {
        var c = parent.firstChildNode
        while (c != null) {
            when (c.elementType) {
                GoTypes.IMPORT_DECLARATION -> (c.psi as? GoImportDeclaration)?.let { out += it }
                GoTypes.IMPORT_LIST, TokenType.ERROR_ELEMENT -> collectImports(c, out)
            }
            c = c.treeNext
        }
    }

    private fun sort(decl: GoImportDeclaration) {
        val node = decl.node
        val text = node.text
        val base = node.startOffset
        val specs = node.getChildren(null).filter { it.elementType == GoTypes.IMPORT_SPEC }
        if (specs.size < 2 || node.findChildByType(GoTypes.LPAREN) == null) return

        val rparen = node.getChildren(null).lastOrNull { it.elementType == GoTypes.RPAREN }?.let { it.startOffset - base } ?: text.length
        val runs = ArrayList<List<ASTNode>>()
        var runStart = 0
        for (j in 1..specs.size) {
            if (j == specs.size || lineOf(text, tokenStart(specs[j]) - base) > 1 + lineOf(text, specs[j - 1].startOffset + specs[j - 1].textLength - base)) {
                runs += specs.subList(runStart, j)
                runStart = j
            }
        }
        val newText = StringBuilder(text)
        var changed = false
        // later runs first: replacing a run may change the length of the text before the next one
        for (run in runs.asReversed()) {
            if (sortRun(text, base, rparen, run, newText)) changed = true
        }
        if (!changed) return
        val dummy = GoElementFactory.createFileFromText(decl.project, "package p\n\n$newText\n")
        val replacement = PsiTreeUtil.findChildOfType(dummy, GoImportDeclaration::class.java) ?: return
        if (replacement.text != newText.toString()) return
        decl.replace(replacement)
    }

    /** A spec's line: [start, end) of its text without leading indentation and trailing line break. */
    private class Unit(val start: Int, val end: Int, val path: String, val name: String, val comment: String)

    private fun sortRun(text: String, base: Int, rparen: Int, run: List<ASTNode>, out: StringBuilder): Boolean {
        if (run.size < 2) return false
        val units = ArrayList<Unit>()
        var prevLine = -1
        for (spec in run) {
            // a doc comment bound to the spec is part of its node but lies on the lines before the
            // run (a comment line starts a new run): it stays in place, like in gofmt
            val s = tokenStart(spec) - base
            val specEnd = spec.startOffset + spec.textLength - base
            val line = lineOf(text, s)
            if (line == prevLine) return false // several specs on one line: leave the run alone
            prevLine = line
            var start = text.lastIndexOf('\n', s - 1) + 1
            while (start < s && (text[start] == ' ' || text[start] == '\t')) start++
            var end = text.indexOf('\n', s).let { if (it < 0) text.length else it }
            if (rparen in s until end) end = rparen // the closing parenthesis is on the spec's line
            while (end > start && (text[end - 1] == ' ' || text[end - 1] == '\t')) end--
            val pathNode = spec.findChildByType(GoTypes.STRING_LITERAL) ?: return false
            val path = pathNode.text.let { if (it.length >= 2) it.substring(1, it.length - 1) else it }
            val name = spec.firstChildNode.takeIf { it.elementType == GoTypes.IDENTIFIER || it.elementType == GoTypes.PERIOD }?.text ?: ""
            if (end < specEnd) return false
            val comment = text.substring(specEnd, end).trim()
            units += Unit(start, end, path, name, comment)
        }
        val sorted = units.sortedWith(compareBy<Unit>({ it.path }, { it.name }, { it.comment }))
        if (sorted == units) return false
        // replace from the last unit backwards so that earlier offsets stay valid
        for (i in units.indices.reversed()) {
            out.replace(units[i].start, units[i].end, text.substring(sorted[i].start, sorted[i].end))
        }
        return true
    }

    /** Offset of the first token of [spec] (its node may start with a bound doc comment). */
    private fun tokenStart(spec: ASTNode): Int {
        var c = spec.firstChildNode
        while (c != null && (c.elementType == TokenType.WHITE_SPACE || GoTokenSets.COMMENTS.contains(c.elementType))) c = c.treeNext
        return c?.startOffset ?: spec.startOffset
    }

    private fun lineOf(text: String, offset: Int): Int {
        var line = 0
        for (i in 0 until minOf(offset, text.length)) if (text[i] == '\n') line++
        return line
    }
}
