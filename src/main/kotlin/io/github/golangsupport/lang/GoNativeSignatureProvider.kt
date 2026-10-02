package io.github.golangsupport.lang

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lint.GoSignatureProvider
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoSignatureType

/**
 * What a function returns, from the PSI: the signature the call at the offset invokes. Asked before the gopls provider (`order="first"`),
 * in every mode: the signature of a call is plain PSI knowledge. Null when the call does not resolve, then gopls is asked.
 */
class GoNativeSignatureProvider : GoSignatureProvider {
    override fun resultCount(project: Project, file: VirtualFile, offset: Int): Int? = signature(project, file, offset)?.results?.size

    companion object {
        /** The signature of the call whose callee contains [offset] (the name of the function or method). */
        fun signature(project: Project, file: VirtualFile, offset: Int): GoSignatureType? = ReadAction.compute<GoSignatureType?, RuntimeException> {
            if (project.isDisposed || DumbService.isDumb(project)) return@compute null
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute null
            val call = callAt(psi, offset) ?: return@compute null
            try {
                GoSemanticService.getInstance(project).calleeSignature(call)
            } catch (_: IndexNotReadyException) {
                null
            }
        }

        /** The innermost call whose callee contains [offset]: `a.b().c()` at `c` is the outer call, at `b` the inner one. */
        fun callAt(file: PsiFile, offset: Int): GoCallExpr? {
            if (file !is GoFile) return null
            var call = PsiTreeUtil.getParentOfType(file.findElementAt(offset), GoCallExpr::class.java)
            while (call != null && call.expression?.textRange?.contains(offset) != true) call = PsiTreeUtil.getParentOfType(call, GoCallExpr::class.java)
            return call
        }
    }
}
