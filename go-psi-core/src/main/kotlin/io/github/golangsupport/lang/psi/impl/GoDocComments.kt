package io.github.golangsupport.lang.psi.impl

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDefinition
import org.jetbrains.annotations.ApiStatus

/** Doc comment lookup for [GoNamedElement.docComment] / [GoNamedElement.docText]. */
@ApiStatus.Internal
object GoDocComments {
    fun docComment(element: GoNamedElement): PsiComment? = run(element)?.firstOrNull()

    fun docText(element: GoNamedElement): String? = run(element)?.let { text(it.map { c -> c.text }) }

    /** The leading comments of the first holder that has any: the spec/field/element itself, then its group declaration. */
    private fun run(element: GoNamedElement): List<PsiComment>? {
        var holder: PsiElement? = when (element) {
            is GoVarDefinition, is GoConstDefinition, is GoFieldDefinition, is GoAnonymousFieldDefinition -> element.parent
            else -> element
        }
        // At most two levels: spec -> declaration (a field declaration has no group level).
        repeat(2) {
            val current = holder ?: return null
            leadingComments(current).takeIf { it.isNotEmpty() }?.let { return it }
            holder = current.parent?.takeIf { it.node.elementType in DECLARATIONS }
        }
        return null
    }

    private val DECLARATIONS = setOf(
        GoTypes.VAR_DECLARATION,
        GoTypes.CONST_DECLARATION,
        GoTypes.TYPE_DECLARATION,
        GoTypes.IMPORT_DECLARATION,
    )

    private fun leadingComments(holder: PsiElement): List<PsiComment> {
        val result = mutableListOf<PsiComment>()
        var child = holder.firstChild
        while (child != null) {
            when (child) {
                is PsiComment -> result += child
                is PsiWhiteSpace -> {}
                else -> break
            }
            child = child.nextSibling
        }
        return result
    }

    /** Port of `ast.CommentGroup.Text`. */
    fun text(comments: List<String>): String? {
        val lines = mutableListOf<String>()
        for (c in comments) {
            if (c.startsWith("//")) {
                if (DIRECTIVE.containsMatchIn(c)) continue
                val body = c.substring(2)
                lines += (if (body.startsWith(" ")) body.substring(1) else body).trimEnd(' ', '\t', '\r')
            } else {
                val body = c.removePrefix("/*").removeSuffix("*/")
                for (line in body.replace("\r", "").split('\n')) lines += line.trimEnd(' ', '\t')
            }
        }
        // Drop leading blank lines, collapse runs of blank lines into one, drop trailing ones.
        val out = mutableListOf<String>()
        for (line in lines) {
            if (line.isNotEmpty()) out += line else if (out.isNotEmpty() && out.last().isNotEmpty()) out += ""
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return if (out.isEmpty()) null else out.joinToString("\n", postfix = "\n")
    }

    /** `//go:generate`, `//line x`, `//export Name`: lowercase word + `:` without a space is a directive, like in go/ast. */
    private val DIRECTIVE = Regex("^//(line |extern |export |[a-z0-9]+:[a-z0-9])")
}
