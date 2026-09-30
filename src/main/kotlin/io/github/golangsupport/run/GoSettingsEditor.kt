package io.github.golangsupport.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import io.github.golangsupport.monitor.GoProfile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selectedValueMatches
import javax.swing.JComponent

class GoSettingsEditor(private val project: Project) : SettingsEditor<GoRunConfiguration>() {
    private val commandCombo = ComboBox(GoCommand.entries.toTypedArray())
    private val target = TextFieldWithBrowseButton()
    private val recursive = JBCheckBox("Packages below the directory as well (./...)")
    private val testPattern = JBTextField()
    private val benchmark = JBCheckBox("Run benchmarks instead of tests")
    private val benchmem = JBCheckBox("Memory allocations of the benchmarks (-benchmem)")
    private val fuzz = JBCheckBox("Fuzz instead of testing (-fuzz with the pattern)")
    private val coverage = JBCheckBox("Collect coverage (-coverprofile), shown in the editor and in the Go Tests window")
    private val race = JBCheckBox("Race detector (-race)")
    private val noTestCache = JBCheckBox("Always run the tests, never their cached result (-count=1)")
    private val short = JBCheckBox("Skip the long-running tests (-short)")
    private val failFast = JBCheckBox("Stop after the first failing test (-failfast)")
    private val timeout = JBTextField()
    private val goArguments = RawCommandLineEditor()
    private val programArguments = RawCommandLineEditor()
    private val workingDirectory = TextFieldWithBrowseButton()
    private val environment = EnvironmentVariablesComponent()
    private val runtimeTelemetry = JBCheckBox("Collect runtime telemetry for the Go Monitor")
    private val profile = ComboBox(GoProfile.entries.toTypedArray())
    private val binary = TextFieldWithBrowseButton()
    private val coreFile = TextFieldWithBrowseButton()
    private val remoteHost = JBTextField()
    private val remotePort = JBTextField()
    private val remotePid = JBTextField()
    private val pathSubstitutions = JBTextArea(3, 40)

    override fun createEditor(): JComponent {
        target.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor().withTitle("Package Directory or Go File"))
        workingDirectory.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Working Directory"))
        environment.labelLocation = java.awt.BorderLayout.WEST

        binary.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Binary"))
        coreFile.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Core Dump"))
        val goCommand = commandCombo.selectedValueMatches { it == GoCommand.RUN || it == GoCommand.TEST }
        val test = commandCombo.selectedValueMatches { it == GoCommand.TEST }
        val withBinary = commandCombo.selectedValueMatches { it == GoCommand.EXEC || it == GoCommand.CORE || it == GoCommand.REMOTE }
        val core = commandCombo.selectedValueMatches { it == GoCommand.CORE }
        val remote = commandCombo.selectedValueMatches { it == GoCommand.REMOTE }
        val runsAProgram = commandCombo.selectedValueMatches { it != GoCommand.CORE }

        return panel {
            row("Kind:") { cell(commandCombo).comment("<code>go run</code> and <code>go test</code> build and run; Binary runs or debugs what is built; Core dump and Remote are for Debug only") }
            row("Package:") { cell(target).align(AlignX.FILL).comment("The directory of the package; for <code>go run</code> a single <code>.go</code> file will do") }.visibleIf(goCommand)
            row("Binary:") { cell(binary).align(AlignX.FILL).comment("Built already, with <code>-gcflags=all=-N -l</code> for a debugging that shows every variable; for Remote, the path on that machine") }.visibleIf(withBinary)
            row("Core dump:") { cell(coreFile).align(AlignX.FILL).comment("<code>GOTRACEBACK=crash</code> writes one on Linux; a minidump on Windows") }.visibleIf(core)
            row("dlv dap at:") {
                cell(remoteHost.apply { columns = 20 })
                label(":")
                cell(remotePort.apply { columns = 6 })
            }.visibleIf(remote).comment("Started there with <code>dlv dap --listen=0.0.0.0:2345</code>; the requests act on that machine")
            row("Process id there:") { cell(remotePid.apply { columns = 10 }).comment("A process of the remote machine to attach to; empty or 0 launches the binary above there instead") }.visibleIf(remote)
            row("Path substitutions:") { cell(JBScrollPane(pathSubstitutions)).align(AlignX.FILL).comment("<code>local=remote</code>, a line each: the sources here and the paths the binary was built with (<code>substitutePath</code> of delve)") }.visibleIf(withBinary)
            row("Go tool arguments:") { cell(goArguments).align(AlignX.FILL).comment("Flags of the go command: <code>-race</code>, <code>-count=1</code>, <code>-ldflags=...</code>. Build tags come from Settings | Tools | Go") }.visibleIf(goCommand)
            row("Program arguments:") { cell(programArguments).align(AlignX.FILL).comment("For <code>go test</code> they follow <code>-args</code>") }.visibleIf(runsAProgram)
            row("Working directory:") { cell(workingDirectory).align(AlignX.FILL).comment("For <code>go run</code> and a binary; the package directory by default. Tests always run in the directory of their package") }.visibleIf(runsAProgram)
            row { cell(environment).align(AlignX.FILL) }.visibleIf(runsAProgram)
            row("Test pattern:") { cell(testPattern).align(AlignX.FILL).comment("For <code>go test</code>: the <code>-run</code> expression, e.g. <code>^TestOrder</code> or <code>^TestOrder$/^empty$</code>") }.visibleIf(test)
            row { cell(recursive) }.visibleIf(test)
            row { cell(benchmark).comment("<code>-bench</code> with the pattern, and <code>-run ^$</code>; the results go to the Benchmarks tab of the Go Tests window, next to the run before") }.visibleIf(test)
            row { cell(benchmem) }.visibleIf(test)
            row { cell(fuzz).comment("Runs until a failing input is found or the run is stopped; <code>-fuzztime=30s</code> in the go tool arguments bounds it") }.visibleIf(test)
            row { cell(coverage) }.visibleIf(test)
            row { cell(race) }.visibleIf(goCommand)
            row { cell(noTestCache) }.visibleIf(test)
            row { cell(short) }.visibleIf(test)
            row { cell(failFast) }.visibleIf(test)
            row("Timeout:") { cell(timeout.apply { columns = 10 }).comment("<code>-timeout</code>: <code>30s</code>, <code>5m</code>; empty is the default of go test, 10 minutes. The output is always verbose: <code>-json</code> implies <code>-v</code>") }.visibleIf(test)
            row { cell(runtimeTelemetry).comment("For <code>go run</code>: the program is built and started with <code>GODEBUG=gctrace=1,schedtrace=1000</code>; the heap, the collections and the scheduler show in the Go Monitor tool window, not in the console") }.visibleIf(goCommand)
            row("Profile:") { cell(profile).comment("For <code>go test</code>: <code>-cpuprofile</code>, <code>-memprofile</code>, <code>-blockprofile</code>, <code>-mutexprofile</code> or <code>-trace</code>; after the run a notification opens it in <code>go tool pprof</code> / <code>go tool trace</code>") }.visibleIf(test)
        }
    }

    override fun resetEditorFrom(configuration: GoRunConfiguration) {
        val options = configuration.options
        commandCombo.selectedItem = options.command
        target.text = options.target.orEmpty()
        recursive.isSelected = options.recursive
        testPattern.text = options.testPattern.orEmpty()
        benchmark.isSelected = options.benchmark
        benchmem.isSelected = options.benchmem
        race.isSelected = options.race
        noTestCache.isSelected = options.noTestCache
        short.isSelected = options.short
        failFast.isSelected = options.failFast
        timeout.text = options.timeout.orEmpty()
        fuzz.isSelected = options.fuzz
        coverage.isSelected = options.coverage
        goArguments.text = options.goArguments.orEmpty()
        programArguments.text = options.programArguments.orEmpty()
        workingDirectory.text = options.workingDirectory.orEmpty()
        environment.envs = options.environment
        environment.isPassParentEnvs = options.passParentEnvironment
        runtimeTelemetry.isSelected = options.runtimeTelemetry
        profile.selectedItem = options.profile
        binary.text = options.binary.orEmpty()
        coreFile.text = options.coreFile.orEmpty()
        remoteHost.text = options.remoteHost.orEmpty()
        remotePort.text = options.remotePort.toString()
        remotePid.text = if (options.remotePid > 0) options.remotePid.toString() else ""
        pathSubstitutions.text = options.pathSubstitutions.orEmpty()
    }

    override fun applyEditorTo(configuration: GoRunConfiguration) {
        val options = configuration.options
        options.command = commandCombo.selectedItem as GoCommand
        options.target = target.text.trim().ifEmpty { null }
        options.recursive = recursive.isSelected
        options.testPattern = testPattern.text.trim().ifEmpty { null }
        options.benchmark = benchmark.isSelected
        options.benchmem = benchmem.isSelected
        options.race = race.isSelected
        options.noTestCache = noTestCache.isSelected
        options.short = short.isSelected
        options.failFast = failFast.isSelected
        options.timeout = timeout.text.trim().ifEmpty { null }
        options.fuzz = fuzz.isSelected
        options.coverage = coverage.isSelected
        options.goArguments = goArguments.text.ifBlank { null }
        options.programArguments = programArguments.text.ifBlank { null }
        options.workingDirectory = workingDirectory.text.ifBlank { null }
        options.environment = environment.envs.toMutableMap()
        options.passParentEnvironment = environment.isPassParentEnvs
        options.runtimeTelemetry = runtimeTelemetry.isSelected
        options.profile = profile.selectedItem as GoProfile
        options.binary = binary.text.trim().ifEmpty { null }
        options.coreFile = coreFile.text.trim().ifEmpty { null }
        options.remoteHost = remoteHost.text.trim().ifEmpty { null }
        options.remotePort = remotePort.text.trim().toIntOrNull() ?: 0
        options.remotePid = remotePid.text.trim().toIntOrNull() ?: 0
        options.pathSubstitutions = pathSubstitutions.text.ifBlank { null }
    }
}
