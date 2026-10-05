package io.github.golangsupport.ide.slicer

import com.intellij.ide.util.treeView.AbstractTreeStructure
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.slicer.SliceAnalysisParams
import com.intellij.slicer.SliceLanguageSupportProvider
import com.intellij.slicer.SliceTreeBuilder
import com.intellij.slicer.SliceUsage
import com.intellij.slicer.SliceUsageCellRendererBase
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.Processor
import io.github.golangsupport.lang.psi.GoReferenceExpression

/** Analyze | Data Flow to Here / from Here for Go (`lang.sliceProvider`): one level of [GoSliceFlow] per node of the tree. */
class GoSliceProvider : SliceLanguageSupportProvider {
    override fun createRootUsage(element: PsiElement, params: SliceAnalysisParams): SliceUsage = GoSliceUsage(element, params)

    override fun getExpressionAtCaret(atCaret: PsiElement, dataFlowToThis: Boolean): PsiElement? = GoSliceFlow.target(atCaret)

    /** The dialog title names the variable ("Analyze Dataflow to variable b"), not the reference expression class (seen live). */
    override fun getElementForDescription(element: PsiElement): PsiElement = (element as? GoReferenceExpression)?.let { GoSliceFlow.variableOf(it) } ?: element

    override fun getRenderer(): SliceUsageCellRendererBase = GoSliceRenderer()

    // the leaf grouping and the nullness analysis are Java's; their actions are not registered for Go (registerExtraPanelActions)
    override fun startAnalyzeLeafValues(structure: AbstractTreeStructure, finalRunnable: Runnable) {}

    override fun startAnalyzeNullness(structure: AbstractTreeStructure, finalRunnable: Runnable) {}

    override fun registerExtraPanelActions(group: DefaultActionGroup, builder: SliceTreeBuilder) {}
}

class GoSliceUsage : SliceUsage {
    private val terminal: Boolean

    constructor(element: PsiElement, params: SliceAnalysisParams) : super(element, params) {
        terminal = false
    }

    constructor(element: PsiElement, parent: SliceUsage, terminal: Boolean) : super(element, parent) {
        this.terminal = terminal
    }

    override fun processUsagesFlownDownTo(element: PsiElement, processor: Processor<in SliceUsage>) {
        if (terminal) return
        for (step in GoSliceFlow.sources(element, scope.toSearchScope())) if (!processor.process(GoSliceUsage(step.element, this, step.terminal))) return
    }

    override fun processUsagesFlownFromThe(element: PsiElement, processor: Processor<in SliceUsage>) {
        for (step in GoSliceFlow.consumers(element, scope.toSearchScope())) if (!processor.process(GoSliceUsage(step.element, this, step.terminal))) return
    }

    override fun copy(): SliceUsage {
        val element = this.element ?: error("the element of a slice usage is gone")
        val parent = this.parent
        return if (parent == null) GoSliceUsage(element, params) else GoSliceUsage(element, parent, terminal)
    }

    override fun canBeLeaf(): Boolean = terminal || super.canBeLeaf()
}

/** The line of the usage as Find Usages shows it, with the file and the line after it. */
class GoSliceRenderer : SliceUsageCellRendererBase() {
    override fun customizeCellRendererFor(usage: SliceUsage) {
        for (chunk in usage.presentation.text) append(chunk.text, chunk.simpleAttributesIgnoreBackground)
        val element = usage.element ?: return
        val file = element.containingFile ?: return
        val line = PsiDocumentManager.getInstance(element.project).getDocument(file)?.getLineNumber(element.textOffset)?.plus(1)
        append("  ${file.name}${line?.let { ":$it" }.orEmpty()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}
