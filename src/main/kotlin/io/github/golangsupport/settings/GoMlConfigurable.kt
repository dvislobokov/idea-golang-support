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
import io.github.golangsupport.ide.completion.GoCompletionAssistSettings
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
        row(GoBundle.message("ml.acceptanceWeight")) {
            spinner(0.0..2.0, 0.1).bindValue(GoCompletionAssistSettings.getInstance()::acceptanceWeight).comment(GoBundle.message("ml.acceptanceWeight.comment"))
        }
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
            row(GoBundle.message("ml.inline.dotThreshold")) {
                spinner(0.05..0.99, 0.05).bindValue(settings::inlineDotThreshold).comment(GoBundle.message("ml.inline.dotThreshold.comment"))
            }
            row {
                checkBox(GoBundle.message("ml.inline.bigModel")).bindSelected(settings::inlineBigModel)
                    .comment(GoBundle.message(if (GoMlModels.isNnBigBundled) "ml.inline.bigModel.comment" else "ml.inline.bigModel.absent"))
                    .enabled(GoMlModels.isNnBigBundled || settings.modelDirectory.isNotBlank())
            }
            row { checkBox(GoBundle.message("ml.inline.showClosers")).bindSelected(settings::inlineShowClosers).comment(GoBundle.message("ml.inline.showClosers.comment")) }
            row { checkBox(GoBundle.message("ml.inline.guessStrings")).bindSelected(settings::inlineGuessStrings).comment(GoBundle.message("ml.inline.guessStrings.comment")) }
            row(GoBundle.message("ml.inline.emptyLineThreshold")) {
                spinner(0.05..0.99, 0.05).bindValue(settings::inlineEmptyLineThreshold).comment(GoBundle.message("ml.inline.emptyLineThreshold.comment"))
            }
            row { checkBox(GoBundle.message("ml.inline.debugLog")).bindSelected(settings::inlineDebugLog).comment(GoBundle.message("ml.inline.debugLog.comment")) }
            row(GoBundle.message("ml.nnStatus")) { cell(nnStatus) }
        }
    }

    override fun reset() {
        super.reset()
        refreshStatus()
    }

    override fun apply() {
        val before = modelChoice()
        super.apply()
        // the models are read again in the background (the network switch 31 M / 50 M reloads the network and drops the editor sessions)
        if (modelChoice() != before) GoMlModels.getInstance().reset()
        refreshStatus()
    }

    private fun modelChoice() = listOf(settings.modelDirectory, settings.inlineEnabled, settings.inlineBigModel, settings.inlineThreshold, settings.inlineShowClosers)

    private fun refreshStatus() {
        val models = GoMlModels.getInstance()
        status.text = models.status(settings.modelDirectory)
        nnStatus.text = models.nnStatus(settings.modelDirectory)
    }
}
