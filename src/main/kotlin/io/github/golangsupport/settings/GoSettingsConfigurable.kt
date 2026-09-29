package io.github.golangsupport.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.util.ui.UIUtil
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.cli.GoTool
import javax.swing.JButton

/** What the part of the plugin with the language server does when the settings it was started with change. Implemented where the LSP API of the platform is. */
interface GoLanguageServerControl {
    fun restart(project: Project)

    companion object {
        val EP: ExtensionPointName<GoLanguageServerControl> = ExtensionPointName.create("io.github.golangsupport.languageServerControl")
        fun restartAll(project: Project) = EP.extensionList.forEach { it.restart(project) }
    }
}

/** Settings | Tools | Go. Only what has an implementation behind it. */
class GoSettingsConfigurable(private val project: Project) : BoundConfigurable("Go") {
    private val settings get() = GoSettings.getInstance()
    private val goPath = TextFieldWithBrowseButton()
    private val goStatus = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val toolRows = GoTool.entries.associateWith { ToolRow(it) }

    private inner class ToolRow(val tool: GoTool) {
        val path = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("${tool.command} Executable"))
        }
        val install = JButton("Install").apply { addActionListener { runInstallation() } }
        val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

        /** The page lives in a modal dialog, which hides the Build tool window: the command runs here and its last line is shown. */
        private fun runInstallation() {
            install.isEnabled = false
            status.text = "Running: go ${tool.installCommand().joinToString(" ")}"
            ApplicationManager.getApplication().executeOnPooledThread {
                val output = StringBuffer()
                val exitCode = tool.installBlocking { output.append(it) }
                ApplicationManager.getApplication().invokeLater({
                    install.isEnabled = true
                    if (exitCode == 0) refresh() else status.text = "Failed: " + output.lines().lastOrNull { it.isNotBlank() }.orEmpty().trim()
                }, ModalityState.any())
            }
        }

        fun refresh() {
            val found = tool.find()
            status.text = found?.path ?: "Not installed: go install ${tool.module}@latest"
            install.text = if (found == null) "Install" else "Update"
        }
    }

    override fun createPanel(): DialogPanel {
        goPath.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("go Executable"))
        return panel {
            group("Toolchain") {
                row("Path to go:") { cell(goPath).align(AlignX.FILL).comment("Empty: PATH, GOROOT and the default installation directories") }
                row { cell(goStatus) }
                row("Build tags:") {
                    textField().align(AlignX.FILL).bindText(settings::buildTags)
                        .comment("<code>-tags</code> of build, run, test and vet, of the language server, the linter and the debugger")
                }
                row { checkBox("Create run configurations for the programs of the project").bindSelected(settings::createRunConfigurations).comment("One per directory with a <code>func main</code>, when the project is opened; a deleted one does not come back") }
                row("Test arguments:") { textField().align(AlignX.FILL).bindText(settings::testArguments).comment("Added to every <code>go test</code>: <code>-race -count=1</code>") }
            }
            group("Language Server (gopls)") {
                row { checkBox("Use gopls for errors, completion, navigation and refactorings").bindSelected(settings::languageServerEnabled) }
                row { checkBox("Staticcheck analyzers").bindSelected(settings::goplsStaticcheck) }
                row { checkBox("Format with gofumpt, a stricter gofmt").bindSelected(settings::goplsGofumpt) }
                row { checkBox("Inlay hints: parameter names, types of variables, values of constants").bindSelected(settings::goplsInlayHints) }
                row { checkBox("Log every message of the protocol").bindSelected(settings::goplsTrace).comment("<code>-rpc.trace</code> in the log window of gopls (menu Go | gopls | Show Log); big") }
                row { checkBox("Serve the debug pages of gopls").bindSelected(settings::goplsDebugPages).comment("Sessions, memory, metrics and the RPC log of the server in a browser (menu Go | gopls | Open Debug Pages)") }
                row { comment("Every other setting of the server: the <b>gopls</b> page below this one. What is set there wins over these switches") }
            }
            group("Debugger (delve)") {
                row { checkBox("Show global variables of the package").bindSelected(settings::debugShowGlobalVariables) }
                row { checkBox("Hide system goroutines").bindSelected(settings::debugHideSystemGoroutines).comment("The ones of the runtime: garbage collector, finalizers, the scheduler") }
                row("Stack trace depth:") { intTextField(1..1000).bindIntText(settings::debugStackTraceDepth) }
                row {
                    checkBox("Debug programs of a Go version this delve does not support").bindSelected(settings::debugAnyGoVersion)
                        .comment("<code>--check-go-version=false</code>. Usually works; the dependable fix is a delve that matches the toolchain")
                }
                row { checkBox("Write the log of delve for every debug session").bindSelected(settings::debugAdapterLog).comment("Menu Go | Show Debugger Logs") }
            }
            group("Editor") {
                row {
                    checkBox("Start a doc comment with the name of the declaration").bindSelected(settings::docCommentNames)
                        .comment("<code>//</code> typed on an empty line right above <code>func</code>, <code>type</code>, <code>var</code> or <code>const</code> becomes <code>// Name </code>")
                }
                row {
                    checkBox("Show the actions of gopls in the list of Alt+Enter").bindSelected(settings::goplsActionsInMenu)
                        .comment("Extract variable, Inline call, Invert if, Add test and others, for the caret or the selection. Off: they are behind <b>Refactorings and actions of gopls...</b>")
                }
                row {
                    checkBox("Type Latin characters in code when the keyboard layout is Russian").bindSelected(settings::latinInCode)
                        .comment("<code>аьеюЗкштедт</code> is typed as <code>fmt.Println</code>. Strings, runes and comments keep what is typed")
                }
                row {
                    checkBox("Suggest the idiomatic next line as grey text (Tab to accept)").bindSelected(settings::inlineIdioms)
                        .comment("<code>if err != nil { return ... }</code> after an assigned error, with the return values of the function and the error wrapped where the file wraps its errors; <code>defer cancel()</code>, <code>defer mu.Unlock()</code>, <code>defer f.Close()</code>, <code>defer span.End()</code> after what needs them; the loop of a scanner and the error after it")
                }
            }
            group("Completion") {
                row {
                    checkBox("Offer the values of a return statement as one item").bindSelected(settings::completeReturnValues)
                        .comment("<code>nil, err</code> after <code>return</code> inside <code>if err != nil</code>: the zero values of the results of the function, and the error")
                }
                row {
                    checkBox("Names that begin with what is typed go first").bindSelected(settings::completionPrefixFirst)
                        .comment("gopls matches fuzzily and orders by its own score: <code>ni</code> gives <code>net.IP</code> above <code>nil</code>. Off: the order of gopls")
                }
                row {
                    checkBox("Show the values of the expected type in bold").bindSelected(settings::completionByType)
                        .comment("An argument of a call, a value of <code>return</code>, of a typed <code>var</code>. Smart completion (Ctrl+Shift+Space) leaves only such values. Needs gopls")
                }
                row {
                    checkBox("Offer functions and types of packages by their names").bindSelected(settings::completionCatalogue)
                        .comment("<code>Printl</code> gives <code>fmt.Println</code>, with the import: the standard library and the modules go.mod requires directly. Read once for a version, works without gopls")
                }
                row {
                    checkBox("Offer packages that are not imported").bindSelected(settings::completionUnimportedPackages)
                        .comment("By the name of the package: <code>htt</code> gives <code>http</code> of <code>net/http</code>, and the import is written when it is chosen. Needs gopls")
                }
                row {
                    checkBox("Write the braces of a literal after a struct type").bindSelected(settings::completionStructBraces)
                        .comment("Where a value is expected: <code>c := http.Client{}</code>, caret between the braces, ready for Fill All Fields. Needs gopls")
                }
                row {
                    checkBox("Offer the arguments of a completed call").bindSelected(settings::completionArguments)
                        .comment("The parameters are shown above the caret and the list opens for the first argument, then after every <code>, </code>. Needs gopls")
                }
            }
            group("Code Quality") {
                row("Reformat Code with:") { comboBox(GoFormatter.entries).bindItem(settings::formatter.toNullableProperty()) }
                row { checkBox("Format Go files on save").bindSelected(settings::formatOnSave).comment("With the formatter above; goimports also adds and removes imports. A file with a syntax error is saved as it is") }
                row {
                    checkBox("Show golangci-lint warnings in the editor").bindSelected(settings::lintOnTheFly)
                        .comment("For saved files, with the <code>.golangci.yml</code> of the repository; the linter is run for the package of the file")
                }
            }
            group("Tools") {
                for (row in toolRows.values) {
                    row(row.tool.command + ":") {
                        cell(row.path).align(AlignX.FILL).comment(row.tool.purpose)
                        cell(row.install)
                    }
                    row("") { cell(row.status) }
                }
            }
        }
    }

    private fun refreshGoStatus() {
        ApplicationManager.getApplication().executeOnPooledThread {
            GoEnvironment.reset()
            val executable = GoCli.findExecutable()
            val environment = GoEnvironment.get()
            val text = if (executable == null) "go is not found" else "$executable  —  Go ${environment.goVersion ?: "?"}, GOROOT ${environment.goRoot ?: "?"}"
            ApplicationManager.getApplication().invokeLater({
                goStatus.text = text
                toolRows.values.forEach { it.refresh() }
            }, ModalityState.any())
        }
    }

    override fun isModified(): Boolean = super.isModified() || goPath.text.trim() != settings.goPath || toolRows.values.any { it.path.text.trim() != settings.toolPath(it.tool.command) }

    override fun apply() {
        super.apply()
        settings.goPath = goPath.text
        toolRows.values.forEach { settings.setToolPath(it.tool.command, it.path.text) }
        refreshGoStatus()
        GoLanguageServerControl.restartAll(project)
    }

    override fun reset() {
        super.reset()
        goPath.text = settings.goPath
        toolRows.values.forEach { it.path.text = settings.toolPath(it.tool.command) }
        refreshGoStatus()
    }
}
