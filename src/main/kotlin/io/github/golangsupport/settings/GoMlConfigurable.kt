package io.github.golangsupport.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.bindValue
import com.intellij.ui.dsl.builder.panel
import io.github.golangsupport.GoBundle
import io.github.golangsupport.ml.GoMlModels
import io.github.golangsupport.ml.GoMlSettings
import javax.swing.JLabel

/**
 * Settings | Go | Smart Completion (only in a build with the models, META-INF/go-ml.xml): the switch of the ML ranking of
 * completion candidates, the grey-text (inline) completion of the transformer and, for trying a new training, a directory with other models.
 */
@Suppress("unused")
class GoMlConfigurable(@Suppress("UNUSED_PARAMETER") project: Project) : BoundConfigurable(GoBundle.message("page.ml")) {
    private val settings get() = GoMlSettings.getInstance()
    private val status = JLabel()
    private val nnStatus = JLabel()

    override fun createPanel(): DialogPanel = panel {
        row { checkBox(GoBundle.message("ml.enabled")).bindSelected(settings::enabled).comment(GoBundle.message("ml.enabled.comment")) }
        row { checkBox(GoBundle.message("ml.showMarker")).bindSelected(settings::showMarker).comment(GoBundle.message("ml.showMarker.comment")) }
        row(GoBundle.message("ml.modelDirectory")) {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(GoBundle.message("ml.modelDirectory")))
                .align(AlignX.FILL).bindText(settings::modelDirectory).comment(GoBundle.message("ml.modelDirectory.comment"))
        }
        row(GoBundle.message("ml.status")) { cell(status) }
        group(GoBundle.message("ml.inline.group")) {
            row { checkBox(GoBundle.message("ml.inline.enabled")).bindSelected(settings::inlineEnabled).comment(GoBundle.message("ml.inline.enabled.comment")) }
            row(GoBundle.message("ml.inline.threshold")) {
                spinner(0.5..0.99, 0.05).bindValue(settings::inlineThreshold).comment(GoBundle.message("ml.inline.threshold.comment"))
            }
            row { checkBox(GoBundle.message("ml.inline.showClosers")).bindSelected(settings::inlineShowClosers).comment(GoBundle.message("ml.inline.showClosers.comment")) }
            row { checkBox(GoBundle.message("ml.inline.guessStrings")).bindSelected(settings::inlineGuessStrings).comment(GoBundle.message("ml.inline.guessStrings.comment")) }
            row(GoBundle.message("ml.nnStatus")) { cell(nnStatus) }
        }
    }

    override fun reset() {
        super.reset()
        refreshStatus()
    }

    override fun apply() {
        val before = listOf(settings.modelDirectory, settings.inlineEnabled, settings.inlineThreshold, settings.inlineShowClosers, settings.inlineGuessStrings)
        super.apply()
        if (listOf(settings.modelDirectory, settings.inlineEnabled, settings.inlineThreshold, settings.inlineShowClosers, settings.inlineGuessStrings) != before) GoMlModels.getInstance().reset()
        refreshStatus()
    }

    private fun refreshStatus() {
        val models = GoMlModels.getInstance()
        status.text = models.status(settings.modelDirectory)
        nnStatus.text = models.nnStatus(settings.modelDirectory)
    }
}
