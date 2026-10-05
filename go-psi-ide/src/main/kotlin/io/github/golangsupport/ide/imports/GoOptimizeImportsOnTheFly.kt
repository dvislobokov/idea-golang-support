package io.github.golangsupport.ide.imports

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoIdeOptions
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportList

/**
 * "Optimize imports on the fly": when the daemon has finished with a Go editor and the only problems of the file are unused imports,
 * they are removed (one undoable command), the way Java does it after its post-highlighting pass. Not while the caret is in the imports,
 * a completion list or a live template is open, or the file is not the user's (outside the content, read-only).
 */
class GoOptimizeImportsOnTheFly(private val project: Project) : DaemonCodeAnalyzer.DaemonListener {

    override fun daemonFinished(fileEditors: Collection<FileEditor>) {
        if (!GoIdeOptions.getInstance().importOptimizeOnTheFly) return
        for (editor in fileEditors.mapNotNull { (it as? TextEditor)?.editor }) {
            if (!readyToOptimize(project, editor)) continue
            val stamp = editor.document.modificationStamp
            ApplicationManager.getApplication().invokeLater({
                if (editor.isDisposed || editor.document.modificationStamp != stamp || !readyToOptimize(project, editor)) return@invokeLater
                optimize(project, editor)
            }, ModalityState.nonModal(), project.disposed)
        }
    }

    companion object {
        private const val UNUSED_IMPORT = "GoUnusedImport"

        /** EDT: the option is on, the document is committed and its highlights show unused imports and no error. */
        fun readyToOptimize(project: Project, editor: Editor): Boolean {
            if (!GoIdeOptions.getInstance().importOptimizeOnTheFly || editor.isViewer || !editor.document.isWritable) return false
            val documents = PsiDocumentManager.getInstance(project)
            if (documents.isUncommited(editor.document)) return false
            val file = documents.getPsiFile(editor.document) as? GoFile ?: return false
            val virtualFile = file.virtualFile ?: return false
            if (!GoIdeFeatureGate.enabled(GoIdeFeature.DIAGNOSTICS, project) || !ProjectFileIndex.getInstance(project).isInContent(virtualFile)) return false
            if (LookupManager.getActiveLookup(editor) != null || TemplateManager.getInstance(project).getActiveTemplate(editor) != null) return false
            val imports = PsiTreeUtil.getChildOfType(file, GoImportList::class.java)?.textRange
            if (imports != null && editor.caretModel.offset in imports.startOffset..imports.endOffset) return false
            var unused = false
            var error = false
            DaemonCodeAnalyzerEx.processHighlights(editor.document, project, null, 0, editor.document.textLength) { info: HighlightInfo ->
                if (info.inspectionToolId == UNUSED_IMPORT) unused = true
                else if (info.severity >= HighlightSeverity.ERROR) error = true
                !error
            }
            return unused && !error
        }

        /** Removes the unused imports of the file of [editor] in one command; write-safe context on the EDT. */
        fun optimize(project: Project, editor: Editor) {
            val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? GoFile ?: return
            val unused = GoImportEdits.unusedImportRanges(file)
            if (unused.isEmpty()) return
            WriteCommandAction.runWriteCommandAction(project, "Optimize Imports", null, {
                val document = GoImportEdits.document(file) ?: return@runWriteCommandAction
                GoImportEdits.removeSpecs(document, file.imports.filter { it.textRange in unused })
                GoImportEdits.commit(file, document)
            }, file)
        }
    }
}
