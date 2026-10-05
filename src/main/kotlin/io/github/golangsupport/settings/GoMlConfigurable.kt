package io.github.golangsupport.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.github.golangsupport.GoBundle
import io.github.golangsupport.ml.GoMlModels
import io.github.golangsupport.ml.GoMlSettings
import javax.swing.JLabel

/**
 * Settings | Go | Smart Completion (only in a build with the models, META-INF/go-ml.xml): the switch of the ML ranking of
 * completion candidates and, for trying a new training, a directory with other `lm.cml` / `rank.cml`.
 */
@Suppress("unused")
class GoMlConfigurable(@Suppress("UNUSED_PARAMETER") project: Project) : BoundConfigurable(GoBundle.message("page.ml")) {
    private val settings get() = GoMlSettings.getInstance()
    private val status = JLabel()

    override fun createPanel(): DialogPanel = panel {
        row { checkBox(GoBundle.message("ml.enabled")).bindSelected(settings::enabled).comment(GoBundle.message("ml.enabled.comment")) }
        row { checkBox(GoBundle.message("ml.showMarker")).bindSelected(settings::showMarker).comment(GoBundle.message("ml.showMarker.comment")) }
        row(GoBundle.message("ml.modelDirectory")) {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(GoBundle.message("ml.modelDirectory")))
                .align(AlignX.FILL).bindText(settings::modelDirectory).comment(GoBundle.message("ml.modelDirectory.comment"))
        }
        row(GoBundle.message("ml.status")) { cell(status) }
    }

    override fun reset() {
        super.reset()
        status.text = GoMlModels.getInstance().status(settings.modelDirectory)
    }

    override fun apply() {
        val before = settings.modelDirectory
        super.apply()
        if (settings.modelDirectory != before) GoMlModels.getInstance().reset()
        status.text = GoMlModels.getInstance().status(settings.modelDirectory)
    }
}
