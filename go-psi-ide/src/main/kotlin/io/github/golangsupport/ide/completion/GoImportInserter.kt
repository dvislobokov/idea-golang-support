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

    fun addImport(file: GoFile, document: Document, path: String) {
        val specs = PsiTreeUtil.findChildrenOfType(file, GoImportSpec::class.java)
        if (specs.any { it.path == path && it.alias == null }) return
        val importList = PsiTreeUtil.getChildOfType(file, GoImportList::class.java)
        val declarations = importList?.importDeclarationList.orEmpty()
        val grouped = declarations.firstOrNull { it.lparen != null && it.rparen != null }
        when {
            grouped != null -> insertIntoGroup(grouped, document, path)
            declarations.isNotEmpty() -> {
                val single = declarations.first()
                val existing = single.importSpecList.map { it.text }
                val lines = (existing + quote(path)).sortedBy { specPath(it) }
                val text = "import (\n" + lines.joinToString("") { "\t$it\n" } + ")"
                document.replaceString(single.textRange.startOffset, single.textRange.endOffset, text)
            }
            else -> {
                val clause = file.packageClause
                if (clause == null) {
                    document.insertString(0, "import ${quote(path)}\n\n")
                } else {
                    document.insertString(clause.textRange.endOffset, "\n\nimport ${quote(path)}")
                }
            }
        }
    }

    private fun insertIntoGroup(declaration: GoImportDeclaration, document: Document, path: String) {
        val text = document.charsSequence
        val specs = declaration.importSpecList
        val isStd = isStdPath(path)
        // Blocks of consecutive specs (a blank line separates blocks).
        val blocks = ArrayList<MutableList<GoImportSpec>>()
        var previousLine = -10
        for (spec in specs) {
            val line = document.getLineNumber(spec.textRange.startOffset)
            if (blocks.isEmpty() || line > previousLine + 1) blocks += ArrayList<GoImportSpec>()
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
                document.insertString(lineStart, "$separator\t${quote(path)}\n")
            } else {
                document.insertString(rparen, "\n$separator\t${quote(path)}\n")
            }
            return
        }
        val before = block.firstOrNull { it.path > path }
        if (before != null) {
            val lineStart = document.getLineStartOffset(document.getLineNumber(before.textRange.startOffset))
            document.insertString(lineStart, "\t${quote(path)}\n")
        } else {
            val last = block.last()
            val lineEnd = document.getLineEndOffset(document.getLineNumber(last.textRange.endOffset))
            document.insertString(lineEnd, "\n\t${quote(path)}")
        }
    }

    private fun quote(path: String) = "\"$path\""

    private fun specPath(specText: String): String = specText.substringAfter('"').substringBefore('"')

    /** Standard-library paths have no dot in their first element (goimports' grouping rule). */
    fun isStdPath(path: String): Boolean = !path.substringBefore('/').contains('.')
}
