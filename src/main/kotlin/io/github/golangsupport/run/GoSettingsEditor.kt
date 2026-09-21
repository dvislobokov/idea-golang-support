package io.github.golangsupport.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class GoSettingsEditor(private val project: Project) : SettingsEditor<GoRunConfiguration>() {
    private val commandCombo = ComboBox(GoCommand.entries.toTypedArray())
    private val target = TextFieldWithBrowseButton()
    private val recursive = JBCheckBox("Packages below the directory as well (./...)")
    private val testPattern = JBTextField()
    private val benchmark = JBCheckBox("Run benchmarks instead of tests")
    private val goArguments = RawCommandLineEditor()
    private val programArguments = RawCommandLineEditor()
    private val workingDirectory = TextFieldWithBrowseButton()
    private val environment = EnvironmentVariablesComponent()

    override fun createEditor(): JComponent {
        target.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor().withTitle("Package Directory or Go File"))
        workingDirectory.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Working Directory"))
        environment.labelLocation = java.awt.BorderLayout.WEST

        return panel {
            row("Command:") { cell(commandCombo) }
            row("Package:") { cell(target).align(AlignX.FILL).comment("The directory of the package; for <code>go run</code> a single <code>.go</code> file will do") }
            row("Go tool arguments:") { cell(goArguments).align(AlignX.FILL).comment("Flags of the go command: <code>-race</code>, <code>-count=1</code>, <code>-ldflags=...</code>. Build tags come from Settings | Tools | Go") }
            row("Program arguments:") { cell(programArguments).align(AlignX.FILL).comment("For <code>go test</code> they follow <code>-args</code>") }
            row("Working directory:") { cell(workingDirectory).align(AlignX.FILL).comment("For <code>go run</code>; the package directory by default. Tests always run in the directory of their package") }
            row { cell(environment).align(AlignX.FILL) }
            row("Test pattern:") { cell(testPattern).align(AlignX.FILL).comment("For <code>go test</code>: the <code>-run</code> expression, e.g. <code>^TestOrder</code> or <code>^TestOrder$/^empty$</code>") }
            row { cell(recursive) }
            row { cell(benchmark).comment("<code>-bench</code> with the pattern, and <code>-run ^$</code>") }
        }
    }

    override fun resetEditorFrom(configuration: GoRunConfiguration) {
        val options = configuration.options
        commandCombo.selectedItem = options.command
        target.text = options.target.orEmpty()
        recursive.isSelected = options.recursive
        testPattern.text = options.testPattern.orEmpty()
        benchmark.isSelected = options.benchmark
        goArguments.text = options.goArguments.orEmpty()
        programArguments.text = options.programArguments.orEmpty()
        workingDirectory.text = options.workingDirectory.orEmpty()
        environment.envs = options.environment
        environment.isPassParentEnvs = options.passParentEnvironment
    }

    override fun applyEditorTo(configuration: GoRunConfiguration) {
        val options = configuration.options
        options.command = commandCombo.selectedItem as GoCommand
        options.target = target.text.trim().ifEmpty { null }
        options.recursive = recursive.isSelected
        options.testPattern = testPattern.text.trim().ifEmpty { null }
        options.benchmark = benchmark.isSelected
        options.goArguments = goArguments.text.ifBlank { null }
        options.programArguments = programArguments.text.ifBlank { null }
        options.workingDirectory = workingDirectory.text.ifBlank { null }
        options.environment = environment.envs.toMutableMap()
        options.passParentEnvironment = environment.isPassParentEnvs
    }
}
