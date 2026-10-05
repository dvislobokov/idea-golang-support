package io.github.golangsupport.ide.inspections.gofix

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.settings.CodeVisionGroupSettingProvider
import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoFile

/**
 * The lenses at the top of a Go file with Go fix findings, as GoLand has them: "Batch syntax update" runs the Go fix inspections over the
 * file ([GoSyntaxUpdate.run], results in Inspection Results), "What's New" opens the section of the plugin's guide about the modernizers.
 * Both above the package clause, gated by [GoIdeFeature.DIAGNOSTICS] (they are about inspections).
 */
object GoSyntaxUpdateLenses {
    const val BATCH_ID = "go.syntax.update"
    const val WHATS_NEW_ID = "Go modernizer whats new"

    /** The host's action that opens the guide at the Go fix section (registered by the plugin, absent in a standalone go-psi). */
    const val GUIDE_ACTION = "Go.HelpPage.GoFix"

    private val CACHE = Key.create<Pair<Pair<Long, Set<String>>, Int>>("go.syntax.update.findings")

    /**
     * The findings of [file]: the daemon's Go fix highlights once its inspection pass has finished for the current text
     * ([GoSyntaxUpdate.countHighlights]); before that, the Go fix tools run over the file ([GoSyntaxUpdate.countFindings]) once per file
     * version and set of enabled tools (both lenses ask, and the answer is the same until the file is edited or the profile changes).
     */
    fun findings(file: GoFile): Int {
        val tools = GoSyntaxUpdate.enabledToolNames(file.project).takeIf { it.isNotEmpty() } ?: return 0
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
        if (document != null) GoSyntaxUpdate.countHighlights(file, document, tools)?.let { return it }
        val key = (document?.modificationStamp ?: file.modificationStamp) to tools
        file.getUserData(CACHE)?.let { (cachedKey, count) -> if (cachedKey == key) return count }
        return GoSyntaxUpdate.countFindings(file).also { file.putUserData(CACHE, key to it) }
    }

    fun batchText(count: Int): String = when {
        count == 1 -> "Update syntax (1 place)"
        count >= GoSyntaxUpdate.MAX_COUNT -> "Update syntax (${GoSyntaxUpdate.MAX_COUNT}+ places)"
        else -> "Update syntax ($count places)"
    }

    /** The package clause, else the start of the file: the lens line. */
    fun anchor(file: GoFile): TextRange = file.packageClause?.textRange ?: TextRange(0, minOf(1, file.textLength))
}

abstract class GoSyntaxUpdateLensProvider : DaemonBoundCodeVisionProvider {
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()
    override val defaultAnchor: CodeVisionAnchorKind get() = CodeVisionAnchorKind.Top
    override val groupId: String get() = id

    protected abstract fun text(count: Int): String

    protected abstract fun invoke(editor: Editor, file: GoFile)

    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        if (file !is GoFile || DumbService.isDumb(file.project) || !GoIdeFeatureGate.enabled(GoIdeFeature.DIAGNOSTICS, file.project)) return emptyList()
        val count = GoSyntaxUpdateLenses.findings(file).takeIf { it > 0 } ?: return emptyList()
        val text = text(count)
        return listOf(GoSyntaxUpdateLenses.anchor(file) to ClickableTextCodeVisionEntry(text, id, { _, clicked -> invoke(clicked, file) }, null, text, name, emptyList()))
    }
}

/** "Update syntax (N places)": a click runs Update Syntax on this file, without the scope dialog. */
class GoBatchSyntaxUpdateCodeVisionProvider : GoSyntaxUpdateLensProvider() {
    override val id: String get() = GoSyntaxUpdateLenses.BATCH_ID
    override val name: String get() = "Batch syntax update"

    override fun text(count: Int): String = GoSyntaxUpdateLenses.batchText(count)

    override fun invoke(editor: Editor, file: GoFile) {
        if (file.isValid) GoSyntaxUpdate.run(file.project, AnalysisScope(file))
    }
}

/** "What's New": what the Go fix inspections modernize, in the plugin's guide (the host's [GoSyntaxUpdateLenses.GUIDE_ACTION]). */
class GoModernizerWhatsNewCodeVisionProvider : GoSyntaxUpdateLensProvider() {
    override val id: String get() = GoSyntaxUpdateLenses.WHATS_NEW_ID
    override val name: String get() = "What's New"

    override fun text(count: Int): String = "What's New"

    override fun invoke(editor: Editor, file: GoFile) {
        val action = ActionManager.getInstance().getAction(GoSyntaxUpdateLenses.GUIDE_ACTION) ?: return
        ActionManager.getInstance().tryToExecute(action, null, editor.contentComponent, "GoCodeVision", true)
    }
}

class GoBatchSyntaxUpdateCodeVisionSettings : CodeVisionGroupSettingProvider {
    override val groupId: String get() = GoSyntaxUpdateLenses.BATCH_ID
    override val groupName: String get() = "Batch syntax update"
    override val description: String get() =
        "At the top of a Go file with Go fix findings: run the Go fix inspections over the file and apply their fixes in batch. Off by default: the number of syntax updates is in the inspections widget."
}

class GoModernizerWhatsNewCodeVisionSettings : CodeVisionGroupSettingProvider {
    override val groupId: String get() = GoSyntaxUpdateLenses.WHATS_NEW_ID
    override val groupName: String get() = "What's New"
    override val description: String get() = "Next to Batch syntax update: what the Go fix inspections modernize, in the plugin's guide. Off by default."
}

/**
 * Turns the two lenses off once per installation: GoLand shows no lens in the file (seen live: the provider is registered, but the count
 * of syntax updates lives in the inspections widget). The platform has no per-provider "off by default" for a plugin
 * (`CodeVisionSettingsDefaults` is a single application-wide extension), so the default is written into the settings once; a user who
 * turns the lenses on later keeps them.
 */
class GoSyntaxUpdateLensDefaults : ProjectActivity {
    override suspend fun execute(project: Project) = apply()

    companion object {
        const val APPLIED_KEY = "go.syntax.update.lenses.defaultOff"

        fun apply() {
            val properties = PropertiesComponent.getInstance()
            if (properties.getBoolean(APPLIED_KEY)) return
            properties.setValue(APPLIED_KEY, true)
            val settings = CodeVisionSettings.getInstance()
            settings.setProviderEnabled(GoSyntaxUpdateLenses.BATCH_ID, false)
            settings.setProviderEnabled(GoSyntaxUpdateLenses.WHATS_NEW_ID, false)
        }
    }
}
