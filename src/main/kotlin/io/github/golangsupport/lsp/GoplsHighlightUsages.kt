package io.github.golangsupport.lsp

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiFile
import com.intellij.util.Consumer
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.settings.GoSettings

/**
 * What the editor highlights at the caret, from `documentHighlight` of gopls: the usages of the name, reads and writes apart, and on
 * `func` or a result type the exit points of the function. The handler of the platform for LSP files asks the same and shows the
 * usages, but not the exit points: gopls marks them with the kind `Text`, and the caret on `func` gave nothing (seen live); the
 * ranges of that kind are read usages here.
 */
class GoplsHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactory, DumbAware {
    override fun createHighlightUsagesHandler(editor: Editor, file: PsiFile): HighlightUsagesHandlerBase<PsiFile>? {
        if (GoFeatures.native(GoFeature.USAGES, file.project)) return null
        if (file !is GoFile || !GoSettings.getInstance().goplsHighlightUsages) return null
        val client = Gopls.client(file.project) ?: return null
        val virtualFile = file.virtualFile ?: return null
        return object : HighlightUsagesHandlerBase<PsiFile>(editor, file) {
            override fun getTargets(): List<PsiFile> = listOf(file)
            override fun selectTargets(targets: List<PsiFile>, selectionConsumer: Consumer<in List<PsiFile>>) = selectionConsumer.consume(targets)

            override fun computeUsages(targets: List<PsiFile>) {
                val document = editor.document
                for ((range, write) in Gopls.documentHighlights(client, virtualFile, document, Gopls.position(document, editor.caretModel.offset), TIMEOUT_MS)) {
                    if (write) myWriteUsages.add(range) else myReadUsages.add(range)
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 2_000
    }
}
