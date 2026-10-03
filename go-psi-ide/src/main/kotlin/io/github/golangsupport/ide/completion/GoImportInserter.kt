package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.formatter.GoImportGroups
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportDeclaration
import io.github.golangsupport.lang.psi.GoImportList
import io.github.golangsupport.lang.psi.GoImportSpec

/**
 * Adds an import spec the way goimports does: into the block (specs separated from the others by
 * blank lines) of its group ([GoImportGroups]: standard library, third-party, the main module) at
 * its sorted position, or as a new block where that group goes; a single-line import is turned
 * into a grouped declaration; without imports a declaration is added after the package clause.
 * Text-based, so the formatter is not needed.
 */
object GoImportInserter {

    /** Into the file being completed; not into a dialog's code fragment ([GoCodeFragments]): the refactoring adds it to the files it changes. */
    fun addImport(ctx: InsertionContext, path: String) {
        if (GoCodeFragments.isFragment(ctx.file)) return
        val document = ctx.document
        PsiDocumentManager.getInstance(ctx.project).commitDocument(document)
        val file = PsiDocumentManager.getInstance(ctx.project).getPsiFile(document) as? GoFile ?: return
        addImport(file, document, path)
        PsiDocumentManager.getInstance(ctx.project).commitDocument(document)
    }

    /** Adds `import [alias] "path"`; nothing when the file has that spec already. */
    fun addImport(file: GoFile, document: Document, path: String, alias: String? = null) {
        val specs = PsiTreeUtil.findChildrenOfType(file, GoImportSpec::class.java)
        if (specs.any { it.path == path && it.alias == alias }) return
        val line = (alias?.let { "$it " } ?: "") + quote(path)
        val importList = PsiTreeUtil.getChildOfType(file, GoImportList::class.java)
        val declarations = importList?.importDeclarationList.orEmpty()
        val grouped = declarations.firstOrNull { it.lparen != null && it.rparen != null }
        // `import "C"` keeps its own declaration: the cgo preamble is the comment above it
        val single = declarations.firstOrNull { d -> d.importSpecList.none { it.path == "C" } }
        val locals = GoImportGroups.localPrefixes(file)
        when {
            grouped != null -> insertIntoGroup(grouped, document, path, line, locals)
            single != null -> {
                val existing = single.importSpecList.map { it.text }
                val groups = (existing + line).groupBy { GoImportGroups.groupOf(specPath(it), locals) }.toSortedMap().values
                val text = "import (\n" + groups.joinToString("\n") { group -> group.sortedBy { specPath(it) }.joinToString("") { "\t$it\n" } } + ")"
                document.replaceString(single.textRange.startOffset, single.textRange.endOffset, text)
            }
            declarations.isNotEmpty() -> document.insertString(declarations.last().textRange.endOffset, "\n\nimport $line")
            else -> {
                val clause = file.packageClause
                if (clause == null) {
                    document.insertString(0, "import $line\n\n")
                } else {
                    document.insertString(clause.textRange.endOffset, "\n\nimport $line")
                }
            }
        }
    }

    private fun insertIntoGroup(declaration: GoImportDeclaration, document: Document, path: String, line: String, locals: List<String>) {
        val text = document.charsSequence
        val specs = declaration.importSpecList
        val group = GoImportGroups.groupOf(path, locals)
        fun groupOf(spec: GoImportSpec) = GoImportGroups.groupOf(spec.path, locals)
        // Blocks of consecutive specs (a blank line separates blocks).
        val blocks = ArrayList<MutableList<GoImportSpec>>()
        var previousLine = -10
        for (spec in specs) {
            val specLine = document.getLineNumber(spec.textRange.startOffset)
            if (blocks.isEmpty() || specLine > previousLine + 1) blocks += ArrayList<GoImportSpec>()
            blocks.last() += spec
            previousLine = document.getLineNumber(spec.textRange.endOffset)
        }
        // a block of the path's group; a block written by hand with several groups in it, when it has one of the group
        val block = blocks.firstOrNull { b -> b.all { groupOf(it) == group } } ?: blocks.firstOrNull { b -> b.any { groupOf(it) == group } }
        val rparen = declaration.rparen!!.textRange.startOffset
        val next = blocks.firstOrNull { b -> b.minOf(::groupOf) > group }
        if (block == null && next != null) {
            // A new block above the first block of a later group (the standard library above the modules).
            val lineStart = document.getLineStartOffset(document.getLineNumber(next.first().textRange.startOffset))
            document.insertString(lineStart, "\t$line\n\n")
            return
        }
        if (block == null) {
            // A new block at the end of the group.
            val lineStart = document.getLineStartOffset(document.getLineNumber(rparen))
            val separator = if (specs.isEmpty()) "" else "\n"
            if (text.subSequence(lineStart, rparen).isBlank()) {
                document.insertString(lineStart, "$separator\t$line\n")
            } else {
                document.insertString(rparen, "\n$separator\t$line\n")
            }
            return
        }
        val before = block.firstOrNull { it.path > path }
        if (before != null) {
            val lineStart = document.getLineStartOffset(document.getLineNumber(before.textRange.startOffset))
            document.insertString(lineStart, "\t$line\n")
        } else {
            val last = block.last()
            val lineEnd = document.getLineEndOffset(document.getLineNumber(last.textRange.endOffset))
            document.insertString(lineEnd, "\n\t$line")
        }
    }

    private fun quote(path: String) = "\"$path\""

    private fun specPath(specText: String): String = specText.substringAfter('"').substringBefore('"')
}
