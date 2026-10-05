package io.github.golangsupport.settings

import com.intellij.application.options.editor.AutoImportOptionsProvider
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.options.UiDslUnnamedConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.rows
import io.github.golangsupport.GoBundle

/**
 * The Go section of Settings | Editor | General | Auto Import, GoLand's Go | Imports options: the import popup, unambiguous imports and
 * optimize imports on the fly ([io.github.golangsupport.ide.GoIdeOptions] over [GoSettings]) and the paths never offered for import.
 */
class GoAutoImportOptionsProvider : UiDslUnnamedConfigurable.Simple(), AutoImportOptionsProvider {
    private val settings get() = GoSettings.getInstance()

    override fun Panel.createContent() {
        group(GoBundle.message("autoImport.group")) {
            row { checkBox(GoBundle.message("autoImport.popup")).bindSelected(settings::importShowPopup) }
            row { checkBox(GoBundle.message("autoImport.unambiguous")).bindSelected(settings::importUnambiguousOnTheFly).comment(GoBundle.message("autoImport.unambiguous.comment")) }
            row { checkBox(GoBundle.message("autoImport.optimize")).bindSelected(settings::importOptimizeOnTheFly).comment(GoBundle.message("autoImport.optimize.comment")) }
            row { label(GoBundle.message("autoImport.exclude")) }
            row {
                textArea().rows(4).align(Align.FILL)
                    .bindText({ settings.importExcluded.joinToString("\n") }, { settings.importExcluded = GoSettingsLists.lines(it) })
                    .comment(GoBundle.message("autoImport.exclude.comment"))
            }
        }
        // the import popup and the imports on the fly are decided per highlighting pass: a restart shows the change at once
        onApply { ProjectManager.getInstance().openProjects.forEach { DaemonCodeAnalyzer.getInstance(it).restart() } }
    }
}

/** Lists edited as text, one item per line. */
object GoSettingsLists {
    fun lines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
