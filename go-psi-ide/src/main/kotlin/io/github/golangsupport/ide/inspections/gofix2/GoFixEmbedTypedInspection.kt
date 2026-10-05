package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.directives.GoEmbed
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConversionExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec

/**
 * Typed embed (go1.16): an unexported `//go:embed` variable converted at every use — a `string` used only as `[]byte(x)`, or a `[]byte`
 * used only as `string(x)` — can be embedded with the type its uses want, without the copy per use. Only conversions passed directly
 * as call arguments (a converted `[]byte` stored and mutated would start mutating the shared variable), and only when no other file
 * of the directory mentions the name.
 */
class GoFixEmbedTypedInspection : GoFix2InspectionBase() {
    override val minVersion = "1.16"

    override fun detect(element: PsiElement, file: GoFile): GoFixFinding? {
        if (element !is GoVarDefinition) return null
        val spec = element.parent as? GoVarSpec ?: return null
        val type = spec.type ?: return null
        val wanted = when (type.text) {
            "string" -> "[]byte"
            "[]byte" -> "string"
            else -> return null
        }
        if (spec.varDefinitionList.size != 1 || spec.expressionList.isNotEmpty()) return null
        val declaration = spec.parent as? GoVarDeclaration ?: return null
        if (declaration.parent !is GoFile || declaration.varSpecList.size != 1) return null
        val name = element.name?.takeIf { it != "_" && !it.first().isUpperCase() } ?: return null
        if (!hasEmbedDirective(declaration) || mentionedElsewhere(file, name)) return null
        val uses = GoFixPsi.references(file, element)
        if (uses.isEmpty()) return null
        val conversions = uses.map { conversionOf(it, wanted) ?: return null }
        return GoFixFinding(element, "Embedded variable '$name' is used only as $wanted; it can be declared as $wanted", listOf("Declare '$name' as $wanted" to { _ ->
            listOf(GoFixEdit.replace(type, wanted)) + conversions.map { GoFixEdit.replace(it, name) }
        }), element.nameIdentifier?.textRange?.shiftLeft(element.textRange.startOffset))
    }

    /** The conversion `[]byte(x)` / `string(x)` around [ref], when it is a call argument. */
    private fun conversionOf(ref: GoReferenceExpression, wanted: String): GoExpression? {
        val conversion: GoExpression = when (wanted) {
            "[]byte" -> (ref.parent as? GoConversionExpr)?.takeIf { it.type?.text == "[]byte" } ?: return null
            else -> {
                val call = (ref.parent as? GoArgumentList)?.parent as? GoCallExpr ?: return null
                val callee = call.expression as? GoReferenceExpression ?: return null
                if (callee.expression != null || callee.identifier.text != "string" || GoFixPsi.args(call).singleOrNull() !== ref) return null
                if ((GoFixPsi.resolve(callee)?.containingFile as? GoFile)?.packageName != "builtin") return null
                call
            }
        }
        return conversion.takeIf { it.parent is GoArgumentList }
    }

    /** Whether a `//go:embed` comment is directly above [declaration] (other comments may sit between). */
    private fun hasEmbedDirective(declaration: GoVarDeclaration): Boolean {
        // a leading comment may be bound into the declaration node
        if (generateSequence(declaration.firstChild) { it.nextSibling }.takeWhile { it is PsiComment || it is PsiWhiteSpace }.any { it is PsiComment && GoEmbed.isEmbed(it.text) }) return true
        var leaf = PsiTreeUtil.prevLeaf(declaration)
        while (leaf != null && (leaf is PsiComment || (leaf is PsiWhiteSpace && leaf.text.count { it == '\n' } <= 1))) {
            if (leaf is PsiComment && GoEmbed.isEmbed(leaf.text)) return true
            leaf = PsiTreeUtil.prevLeaf(leaf)
        }
        return false
    }

    private fun mentionedElsewhere(file: GoFile, name: String): Boolean {
        val directory = file.originalFile.containingDirectory ?: return true
        val word = Regex("""\b${Regex.escape(name)}\b""")
        return directory.files.any { it is GoFile && it != file.originalFile && word.containsMatchIn(it.text) }
    }
}
