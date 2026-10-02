package io.github.golangsupport.lang.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference

/**
 * Supplies [PsiReference]s for Go PSI elements. The core module only knows which elements are
 * reference sites (reference expressions, type references, label references, import paths); the
 * semantic module registers the implementation as an application service. Without an
 * implementation (core alone) elements have no references.
 */
interface GoReferenceProvider {
    /** The reference of [element], or null when it has none. */
    fun getReference(element: PsiElement): PsiReference?

    companion object {
        @JvmStatic
        fun getInstance(): GoReferenceProvider? =
            ApplicationManager.getApplication()?.getService(GoReferenceProvider::class.java)

        @JvmStatic
        fun referenceOf(element: PsiElement): PsiReference? = getInstance()?.getReference(element)
    }
}
