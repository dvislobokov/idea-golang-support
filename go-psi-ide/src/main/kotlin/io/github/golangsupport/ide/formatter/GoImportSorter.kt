package io.github.golangsupport.ide.formatter

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.codeStyle.PreFormatProcessor
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoElementFactory
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportDeclaration
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.project.api.GoModuleGraphProvider

/**
 * Sorts the specs of every parenthesised import declaration like gofmt (`go/ast.SortImports`):
 * runs of specs on consecutive lines (a blank or comment-only line starts a new run) are sorted
 * by import path, then name, then line comment; each spec keeps the comments on its line.
 *
 * It runs before formatting (a pre-format processor rather than a post-format one) so that the
 * alignment of trailing comments is computed on the sorted order. Only the order of whole lines
 * changes; token and comment text is preserved. Unlike gofmt, duplicate imports are not removed.
 * Specs do not move between groups here (gofmt does not move them either); Optimize Imports does that ([GoImportGroups]).
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

/**
 * The import groups of goimports (`goimports -local <main module>`): cgo's `"C"`, the standard library (no dot in the first path
 * element), third-party modules, the main module(s) of the file. Optimize Imports regroups a parenthesised declaration into them
 * ([regroup]); auto-import puts a new path into its group. Reformat Code ([GoImportSorter]) does not regroup: gofmt does not either.
 */
object GoImportGroups {
    enum class Group { CGO, STANDARD, THIRD_PARTY, LOCAL }

    fun groupOf(path: String, locals: Collection<String>): Group = when {
        path == "C" -> Group.CGO
        locals.any { path == it || path.startsWith("$it/") } -> Group.LOCAL
        '.' !in path.substringBefore('/') -> Group.STANDARD
        else -> Group.THIRD_PARTY
    }

    /** The module paths of the main modules (go.mod, or every `use` of go.work) the file belongs to; empty outside a module. Read action. */
    fun localPrefixes(file: PsiFile): List<String> {
        val virtualFile = file.originalFile.virtualFile ?: file.virtualFile ?: return emptyList()
        return GoModuleGraphProvider.getInstance(file.project).graphFor(virtualFile)?.mainModules?.map { it.path }?.filter { it.isNotEmpty() }.orEmpty()
    }

    /** One spec: its line, the comment lines just above it (they move with it), and what gofmt sorts by. */
    private class Spec(val lines: List<String>, val path: String, val name: String, val comment: String)

    private val SPEC = Regex("""^(?:([\p{L}_][\p{L}\p{N}_]*|\.)\s+)?("(?:[^"\\]|\\.)*"|`[^`]*`)\s*(//.*|/\*.*?\*/)?$""")

    /**
     * The text of a parenthesised import declaration ([declaration], `import (` … `)`) with its specs in goimports groups, each sorted
     * by path, name, comment and separated by one blank line; null when nothing changes or the layout is not one spec per line (then
     * gofmt's sort is all it gets). Comment lines above a spec move with it; those after the last spec stay at the end.
     */
    fun regroup(declaration: String, locals: Collection<String>): String? {
        val open = declaration.indexOf('(')
        val close = declaration.lastIndexOf(')')
        if (open < 0 || close < open) return null
        val afterOpen = declaration.indexOf('\n', open)
        val beforeClose = declaration.lastIndexOf('\n', close)
        if (afterOpen < 0 || afterOpen >= beforeClose) return null
        if (declaration.substring(open + 1, afterOpen).isNotBlank() || declaration.substring(beforeClose + 1, close).isNotBlank()) return null
        val specs = ArrayList<Spec>()
        var pending = ArrayList<String>()
        for (raw in declaration.substring(afterOpen + 1, beforeClose).split('\n')) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("//") || line.startsWith("/*") && line.indexOf("*/") == line.length - 2 -> pending += line
                else -> {
                    val match = SPEC.matchEntire(line) ?: return null
                    val path = match.groupValues[2].let { it.substring(1, it.length - 1) }
                    specs += Spec(pending + line, path, match.groupValues[1], match.groupValues[3])
                    pending = ArrayList()
                }
            }
        }
        if (specs.size < 2) return null
        val groups = specs.groupBy { groupOf(it.path, locals) }.toSortedMap().values
            .map { group -> group.sortedWith(compareBy<Spec>({ it.path }, { it.name }, { it.comment })) }
        val body = groups.joinToString("\n") { group -> group.joinToString("") { spec -> spec.lines.joinToString("") { "\t$it\n" } } } +
            pending.joinToString("") { "\t$it\n" }
        val result = declaration.substring(0, open + 1) + "\n" + body + declaration.substring(close)
        return result.takeIf { it != declaration }
    }
}
