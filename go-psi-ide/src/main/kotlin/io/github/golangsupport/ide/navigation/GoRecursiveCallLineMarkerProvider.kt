package io.github.golangsupport.ide.navigation

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.semantic.api.GoSemanticService
import javax.swing.Icon

/**
 * "Recursive call" in the gutter, like GoLand: a call whose callee resolves to the enclosing function or method declaration (`f(n-1)`,
 * `r.walk(x)`, `T.f(r)` / `(*T).f(r)`; a call from a function literal inside it counts too). Computed in the slow pass, on the callee's
 * identifier leaf; one marker per line. The name check runs before resolve, so most calls cost nothing.
 */
class GoRecursiveCallLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = "Go recursive call"

    override fun getIcon(): Icon = AllIcons.Gutter.RecursiveMethod

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val first = elements.firstOrNull() ?: return
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.IMPLEMENTATION_MARKERS, first.project)) return
        val document = PsiDocumentManager.getInstance(first.project).getDocument(first.containingFile) ?: return
        val lines = HashSet<Int>()
        for (leaf in elements) {
            if (leaf.node.elementType !== GoTypes.IDENTIFIER) continue
            ProgressManager.checkCanceled()
            if (!isRecursiveCall(leaf)) continue
            if (!lines.add(document.getLineNumber(leaf.textRange.startOffset))) continue
            result += LineMarkerInfo(leaf, leaf.textRange, AllIcons.Gutter.RecursiveMethod, { _: PsiElement -> TOOLTIP }, null as GutterIconNavigationHandler<PsiElement>?,
                GutterIconRenderer.Alignment.RIGHT) { TOOLTIP }
        }
    }

    private fun isRecursiveCall(leaf: PsiElement): Boolean {
        val reference = leaf.parent as? GoReferenceExpression ?: return false
        if (reference.identifier !== leaf) return false
        val call = reference.parent as? GoCallExpr ?: return false
        if (call.expression !== reference) return false
        val function = PsiTreeUtil.getParentOfType(call, GoFunctionOrMethodDeclaration::class.java) ?: return false
        if (function.name != leaf.text) return false
        return GoSemanticService.getInstance(leaf.project).resolve(reference).any { it.isEquivalentTo(function) }
    }

    companion object {
        const val TOOLTIP = "Recursive call"
    }
}
