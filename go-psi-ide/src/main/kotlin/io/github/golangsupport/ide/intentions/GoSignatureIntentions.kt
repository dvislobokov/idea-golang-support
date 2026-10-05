package io.github.golangsupport.ide.intentions

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoParameterDeclaration
import io.github.golangsupport.lang.psi.GoParameters
import io.github.golangsupport.lang.psi.GoSignature

/** The parameter lists of the signature at the caret: on the header of a function, method or function literal, or in a function type. */
internal object GoSignatureText {

    fun signatureAt(file: GoFile, offset: Int): GoSignature? {
        var e: PsiElement? = GoIntentionText.leafAt(file, offset)
        while (e != null && e !is PsiFile) {
            when (e) {
                is GoSignature -> return e
                is GoBlock -> return null
                is GoFunctionOrMethodDeclaration -> return e.signature
                is GoFunctionLit -> return PsiTreeUtil.getChildOfType(e, GoSignature::class.java)
            }
            e = e.parent
        }
        return null
    }

    /** The parameters and the named results of [signature]. */
    fun lists(signature: GoSignature): List<GoParameters> = listOfNotNull(signature.parameters, signature.result?.parameters)

    /** The type of [declaration] as written, blanks collapsed so that `[]int` and `[] int` compare equal. */
    fun typeText(declaration: GoParameterDeclaration): String? = declaration.type?.text?.replace(Regex("\\s+"), " ")

    fun hasComment(element: PsiElement): Boolean = PsiTreeUtil.findChildrenOfType(element, PsiComment::class.java).isNotEmpty()
}

/** Expand signature types: `(s1, s2 string) (i1, i2 int)` → `(s1 string, s2 string) (i1 int, i2 int)`. */
class GoExpandSignatureTypesIntention : GoCodeActionIntention() {
    override val defaultText: String = "Expand signature types"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val signature = GoSignatureText.signatureAt(file, offset) ?: return null
        val edits = GoSignatureText.lists(signature).flatMap { it.parameterDeclarationList }
            .filter { it.paramDefinitionList.size > 1 && !GoSignatureText.hasComment(it) }
            .mapNotNull { d ->
                val type = d.type?.text ?: return@mapNotNull null
                GoEditPlan.Edit(d.textRange.startOffset, d.textRange.endOffset, d.paramDefinitionList.joinToString(", ") { "${it.text} $type" })
            }
        return if (edits.isEmpty()) null else GoEditPlan(edits)
    }
}

/** Reuse signature types: `(s1 string, s2 string) (i1 int, i2 int)` → `(s1, s2 string) (i1, i2 int)`, adjacent named parameters of one type. */
class GoReuseSignatureTypesIntention : GoCodeActionIntention() {
    override val defaultText: String = "Reuse signature types"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val signature = GoSignatureText.signatureAt(file, offset) ?: return null
        val text = file.viewProvider.contents
        val edits = ArrayList<GoEditPlan.Edit>()
        for (list in GoSignatureText.lists(signature)) {
            val declarations = list.parameterDeclarationList
            var i = 0
            while (i < declarations.size) {
                if (!mergeable(declarations[i])) { i++; continue }
                var j = i
                val type = GoSignatureText.typeText(declarations[i])
                while (j + 1 < declarations.size && type != null && mergeable(declarations[j + 1]) && GoSignatureText.typeText(declarations[j + 1]) == type) j++
                if (j > i) {
                    val run = declarations.subList(i, j + 1)
                    val start = run.first().textRange.startOffset
                    val end = run.last().textRange.endOffset
                    if (!text.subSequence(start, end).contains("//") && !text.subSequence(start, end).contains("/*")) {
                        val names = run.flatMap { d -> d.paramDefinitionList.map { it.text } }
                        edits += GoEditPlan.Edit(start, end, names.joinToString(", ") + " " + run.last().type!!.text)
                    }
                }
                i = j + 1
            }
        }
        return if (edits.isEmpty()) null else GoEditPlan(edits)
    }

    /** Named and not variadic (`...T` must stay the last, alone). */
    private fun mergeable(d: GoParameterDeclaration): Boolean = d.paramDefinitionList.isNotEmpty() && d.type != null && !d.text.contains("...")
}
