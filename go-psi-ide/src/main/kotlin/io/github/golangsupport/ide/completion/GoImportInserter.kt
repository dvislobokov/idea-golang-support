package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportDeclaration
import io.github.golangsupport.lang.psi.GoImportList
import io.github.golangsupport.lang.psi.GoImportSpec

/**
 * Adds an import spec the way goimports does: into an existing parenthesised import group whose
 * specs are of the same kind (standard library vs. module paths, groups separated by blank
 * lines) at its sorted position; a single-line import is turned into a group; without imports a
 * declaration is added after the package clause. Text-based, so the formatter is not needed.
 */
object GoImportInserter {

    fun addImport(ctx: InsertionContext, path: String) {
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
        when {
            grouped != null -> insertIntoGroup(grouped, document, path, line)
            declarations.isNotEmpty() -> {
                val single = declarations.first()
                val existing = single.importSpecList.map { it.text }
                val lines = (existing + line).sortedBy { specPath(it) }
                val text = "import (\n" + lines.joinToString("") { "\t$it\n" } + ")"
                document.replaceString(single.textRange.startOffset, single.textRange.endOffset, text)
            }
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

    private fun insertIntoGroup(declaration: GoImportDeclaration, document: Document, path: String, line: String) {
        val text = document.charsSequence
        val specs = declaration.importSpecList
        val isStd = isStdPath(path)
        // Blocks of consecutive specs (a blank line separates blocks).
        val blocks = ArrayList<MutableList<GoImportSpec>>()
        var previousLine = -10
        for (spec in specs) {
            val specLine = document.getLineNumber(spec.textRange.startOffset)
            if (blocks.isEmpty() || specLine > previousLine + 1) blocks += ArrayList<GoImportSpec>()
            blocks.last() += spec
            previousLine = document.getLineNumber(spec.textRange.endOffset)
        }
        val block = blocks.firstOrNull { b -> b.all { isStdPath(it.path) == isStd } }
        val rparen = declaration.rparen!!.textRange.startOffset
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

    /** Standard-library paths have no dot in their first element (goimports' grouping rule). */
    fun isStdPath(path: String): Boolean = !path.substringBefore('/').contains('.')
}
