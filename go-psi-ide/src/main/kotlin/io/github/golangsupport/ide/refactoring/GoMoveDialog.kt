package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * The Move dialog: what moves, the target directory (the source directory means another file of the same package; another
 * directory of the project means another package, created when missing) and the file name in it. "Move methods too" matters
 * within one package only: across packages methods always follow their type.
 */
class GoMoveDialog(
    project: Project,
    private val moved: List<String>,
    directory: String,
    fileName: String,
    hasTypes: Boolean,
    private val sourcePackage: String,
) : DialogWrapper(project, true) {

    private val directoryField = TextFieldWithBrowseButton().apply {
        text = directory.replace('/', java.io.File.separatorChar)
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Target Directory"))
    }
    private val fileField = JBTextField(fileName, 30)
    private val methodsBox = JBCheckBox("Move methods of the types too", true).apply { isEnabled = hasTypes }
    private val previewBox = JBCheckBox("Preview usages", false)

    val isPreviewUsages: Boolean get() = previewBox.isSelected

    init {
        title = GoMoveProcessor.TITLE
        init()
    }

    fun options(): GoMoveOptions = GoMoveOptions(directoryField.text.trim().replace('\\', '/'), fileField.text.trim(), methodsBox.isSelected)

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(JBLabel("<html>Move from package <b>$sourcePackage</b>:<br>" + moved.joinToString("<br>") { "&nbsp;&nbsp;" + it.escape() } + "</html>"))
        .addLabeledComponent("To &directory:", directoryField)
        .addLabeledComponent("&File name:", fileField)
        .addComponent(methodsBox)
        .addComponent(previewBox)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = fileField

    override fun doValidate(): ValidationInfo? = when {
        directoryField.text.isBlank() -> ValidationInfo("Choose a directory", directoryField)
        fileField.text.isBlank() || fileField.text.contains('/') || fileField.text.contains('\\') -> ValidationInfo("Enter a file name", fileField)
        else -> null
    }

    private fun String.escape(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
