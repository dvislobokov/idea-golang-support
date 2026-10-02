package io.github.golangsupport.ide.documentation

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoFieldDefinition

/**
 * Doc comments of Go declarations, following `go/ast` doc attribution: the comment group directly
 * above a declaration (bound to it by the parser's doc-comment binder); for a spec of a grouped
 * `type`/`var`/`const` declaration the spec's own comment, else the declaration's comment when it
 * has a single spec; for struct fields also a trailing line comment.
 */
object GoDocComment {

    /** The raw doc text of [element] (comment markers removed), or null when there is none. */
    @JvmStatic
    fun docText(element: PsiElement): String? {
        val text = when (element) {
            is GoFunctionOrMethodDeclaration, is GoMethodSpec, is GoPackageClause, is GoImportSpec -> leading(element)
            is GoTypeSpec -> leading(element) ?: (element.parent as? GoTypeDeclaration)?.takeIf { it.typeSpecList.size == 1 }?.let(::leading)
            is GoVarDefinition -> (element.parent as? GoVarSpec)?.let { spec ->
                leading(spec) ?: (spec.parent as? GoVarDeclaration)?.takeIf { it.varSpecList.size == 1 }?.let(::leading) ?: trailing(spec)
            }
            is GoConstDefinition -> (element.parent as? GoConstSpec)?.let { spec ->
                leading(spec) ?: (spec.parent as? GoConstDeclaration)?.takeIf { it.constSpecList.size == 1 }?.let(::leading) ?: trailing(spec)
            }
            is GoFieldDefinition, is GoAnonymousFieldDefinition ->
                (element.parent as? GoFieldDeclaration)?.let { leading(it) ?: trailing(it) }
            else -> null
        }
        return text?.takeIf { it.isNotBlank() }
    }

    /** Comment children at the start of [owner] (the binder attaches the preceding comment group). */
    private fun leading(owner: PsiElement): String? {
        val comments = ArrayList<PsiComment>()
        var child = owner.firstChild
        while (child != null && (child is PsiComment || child is PsiWhiteSpace)) {
            if (child is PsiComment) comments += child
            child = child.nextSibling
        }
        if (comments.isEmpty()) {
            // Fallback: a comment group right before the owner that the binder left outside.
            var prev = owner.prevSibling
            val before = ArrayList<PsiComment>()
            while (prev != null) {
                if (prev is PsiComment) before += prev
                else if (prev is PsiWhiteSpace) { if (StringUtil.countNewLines(prev.text) >= 2) break }
                else break
                prev = prev.prevSibling
            }
            comments += before.asReversed()
        }
        if (comments.isEmpty()) return null
        return commentText(comments.map { it.text })
    }

    /** A `// comment` on the same line after [owner]. */
    private fun trailing(owner: PsiElement): String? {
        var next = owner.nextSibling
        while (next is PsiWhiteSpace && !next.text.contains('\n')) next = next.nextSibling
        if (next !is PsiComment) return null
        return commentText(listOf(next.text))
    }

    /** Strips `//` / `/* */` markers and drops directive lines (`//go:generate`, `//nolint:...`). */
    @JvmStatic
    fun commentText(comments: List<String>): String {
        val lines = ArrayList<String>()
        for (c in comments) {
            if (c.startsWith("//")) {
                if (DIRECTIVE.matches(c)) continue
                val body = c.removePrefix("//")
                lines += body.removePrefix(" ")
            } else if (c.startsWith("/*")) {
                val body = c.removePrefix("/*").removeSuffix("*/")
                lines += dedent(body.split('\n').map { it.trimEnd('\r') })
            }
        }
        // Trim leading/trailing blank lines.
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        return lines.joinToString("\n")
    }

    private fun dedent(lines: List<String>): List<String> {
        val prefix = lines.filter { it.isNotBlank() }.minOfOrNull { l -> l.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        return lines.map { if (it.length >= prefix) it.substring(prefix) else it.trim() }
    }

    private val DIRECTIVE = Regex("//(line |extern |export |[a-z0-9]+:[a-z0-9]).*")
}
