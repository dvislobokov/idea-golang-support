package io.github.golangsupport.lang

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.onSave.OptimizeImportsOnSaveOptions
import com.intellij.ide.actionsOnSave.ActionOnSaveBackedByOwnConfigurable
import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.ide.actionsOnSave.ActionOnSaveInfo
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.lang.LanguageImportStatements
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import io.github.golangsupport.GoBundle
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.settings.GoFormattingConfigurable
import io.github.golangsupport.settings.GoSettings

/**
 * Optimize Imports over the Go files being saved, apart from Reformat (Settings | Go | Formatting, mirrored on Tools | Actions on Save):
 * the import optimizer of the language ([LanguageImportStatements], the native one of go-psi-ide) through the processor of Code |
 * Optimize Imports, before the save. A project where the platform's own "Optimize imports" on save already covers Go files is left to it.
 */
class GoOptimizeImportsOnSave : ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {
    override fun isEnabledForProject(project: Project): Boolean = GoSettings.getInstance().optimizeImportsOnSave && !platformCovers(project)

    override fun processDocuments(project: Project, documents: Array<Document>) {
        val psiDocuments = PsiDocumentManager.getInstance(project)
        val files = documents.mapNotNull { document ->
            val file = psiDocuments.getPsiFile(document) as? GoFile ?: return@mapNotNull null
            psiDocuments.commitDocument(document)
            file.takeIf { LanguageImportStatements.INSTANCE.forFile(it).isNotEmpty() }
        }
        if (files.isNotEmpty()) OptimizeImportsProcessor(project, files.toTypedArray(), null).run()
    }

    companion object {
        fun platformCovers(project: Project): Boolean = OptimizeImportsOnSaveOptions.getInstance(project).let { it.isRunOnSaveEnabled && it.isFileTypeSelected(GoFileType) }
    }
}

/** The row "Optimize Go imports" of Settings | Tools | Actions on Save: the checkbox of Settings | Go | Formatting, read and set through that page. */
class GoOptimizeImportsOnSaveInfoProvider : ActionOnSaveInfoProvider() {
    override fun getActionOnSaveInfos(context: ActionOnSaveContext): Collection<ActionOnSaveInfo> = listOf(Info(context))

    override fun getSearchableOptions(): Collection<String> = listOf(GoBundle.message("actionsOnSave.optimizeImports"))

    private class Info(context: ActionOnSaveContext) :
        ActionOnSaveBackedByOwnConfigurable<GoFormattingConfigurable>(context, CONFIGURABLE_ID, GoFormattingConfigurable::class.java) {
        override fun getActionOnSaveName(): String = GoBundle.message("actionsOnSave.optimizeImports")

        override fun isActionOnSaveEnabledAccordingToStoredState(): Boolean = GoSettings.getInstance().optimizeImportsOnSave

        override fun isActionOnSaveEnabledAccordingToUiState(configurable: GoFormattingConfigurable): Boolean = configurable.optimizeImportsOnSave.isSelected

        override fun setActionOnSaveEnabled(configurable: GoFormattingConfigurable, enabled: Boolean) {
            configurable.optimizeImportsOnSave.isSelected = enabled
        }

        override fun getActionLinks() = listOf(createGoToPageInSettingsLink(CONFIGURABLE_ID))
    }

    private companion object {
        const val CONFIGURABLE_ID = "io.github.golangsupport.settings.formatting"
    }
}
