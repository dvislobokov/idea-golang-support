package io.github.golangsupport.ide.asm

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoTypes
import javax.swing.Icon

/**
 * Gutter markers between Go and its assembly, in the slow pass (they read the sibling files of the directory):
 * on the name of a Go function without a body → the `TEXT` symbols defining it (one per architecture, a popup when several);
 * on the symbol of `TEXT ·name(SB)` → the Go declaration. Registered for both languages; the navigation group of the gate.
 */
class GoAsmLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName(): String = "Go assembly implementations"
    override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val first = elements.firstOrNull() ?: return
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, first.project)) return
        for (leaf in elements) {
            val type = leaf.node?.elementType
            if (type === GoTypes.IDENTIFIER) {
                val function = leaf.parent as? GoFunctionDeclaration ?: continue
                if (function.nameIdentifier !== leaf || function.block != null) continue
                ProgressManager.checkCanceled()
                val targets = GoAsmNavigation.asmSymbols(function)
                if (targets.isEmpty()) continue
                result += NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod).setTargets(targets)
                    .setTooltipText("Implemented in assembly").setPopupTitle("Assembly of ${function.name}").createLineMarkerInfo(leaf)
            } else if (type === GoAsmTokenTypes.SYMBOL) {
                val symbol = leaf.parent as? GoAsmSymbol ?: continue
                if (!symbol.isTextDefinition) continue
                ProgressManager.checkCanceled()
                val targets = GoAsmNavigation.goFunctions(symbol)
                if (targets.isEmpty()) continue
                result += NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementingMethod).setTargets(targets)
                    .setTooltipText("Go declaration").setPopupTitle("Go declaration of ${symbol.text}").createLineMarkerInfo(leaf)
            }
        }
    }
}
