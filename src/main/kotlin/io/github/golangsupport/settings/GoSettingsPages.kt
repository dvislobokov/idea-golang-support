package io.github.golangsupport.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selectedValueMatches
import com.intellij.ui.dsl.builder.toNullableProperty

/**
 * A page under Settings | Tools | Go: one area each, so that a page is read in one look. The settings are application-level; the
 * language server is restarted on apply, since most of them go into its start or its configuration.
 */
abstract class GoSettingsPage(protected val project: Project, title: String) : BoundConfigurable(title) {
    protected val settings: GoSettings get() = GoSettings.getInstance()

    override fun apply() {
        super.apply()
        GoLanguageServerControl.restartAll(project)
    }
}

/** The switches of gopls the plugin knows what to do with; every other option of the server is on the gopls page under this one. */
class GoLanguageServerConfigurable(project: Project) : GoSettingsPage(project, "Language Server") {
    override fun createPanel(): DialogPanel = panel {
        row { checkBox("Use gopls for errors, completion, navigation and refactorings").bindSelected(settings::languageServerEnabled) }
        row { checkBox("Staticcheck analyzers").bindSelected(settings::goplsStaticcheck) }
        row { checkBox("Format with gofumpt, a stricter gofmt").bindSelected(settings::goplsGofumpt) }
        row { checkBox("Inlay hints: parameter names, types of variables, values of constants").bindSelected(settings::goplsInlayHints) }
        row { checkBox("Highlight the usages of the name at the caret and the exit points of a function").bindSelected(settings::goplsHighlightUsages).comment("Reads and writes in their colours; on <code>func</code> or <code>return</code> — every <code>return</code> of the function") }
        row { checkBox("Log every message of the protocol").bindSelected(settings::goplsTrace).comment("<code>-rpc.trace</code> in the log window of gopls (menu Go | gopls | Show Log); big") }
        row { checkBox("Serve the debug pages of gopls").bindSelected(settings::goplsDebugPages).comment("Sessions, memory, metrics and the RPC log of the server in a browser (menu Go | gopls | Open Debug Pages)") }
        row { comment("Every other setting of the server: the <b>gopls</b> page under this one. What is set there wins over these switches") }
    }
}

class GoDebuggerConfigurable(project: Project) : GoSettingsPage(project, "Debugger") {
    override fun createPanel(): DialogPanel = panel {
        row { checkBox("Show global variables of the package").bindSelected(settings::debugShowGlobalVariables) }
        row { checkBox("Hide system goroutines").bindSelected(settings::debugHideSystemGoroutines).comment("The ones of the runtime: garbage collector, finalizers, the scheduler") }
        row("Stack trace depth:") { intTextField(1..1000).bindIntText(settings::debugStackTraceDepth) }
        row {
            checkBox("Debug programs of a Go version this delve does not support").bindSelected(settings::debugAnyGoVersion)
                .comment("<code>--check-go-version=false</code>. Usually works; the dependable fix is a delve that matches the toolchain")
        }
        row { checkBox("Write the log of delve for every debug session").bindSelected(settings::debugAdapterLog).comment("Menu Go | Show Debugger Logs") }
        lateinit var location: ComboBox<GoDebugBinaryLocation>
        row("Build debug binaries in:") {
            location = comboBox(GoDebugBinaryLocation.entries).bindItem(settings::debugBinaryLocation.toNullableProperty())
                .comment("The <code>__debug_bin…</code> delve builds for a session: in the temp directory of the system (nothing is left in the project), next to the sources as <code>dlv debug</code> does, or in a directory of your own. Deleted when the session ends").component
        }
        row("Directory:") {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Directory of Debug Binaries"), project)
                .align(AlignX.FILL).bindText(settings::debugBinaryDirectory)
                .comment("A relative path is from the directory of the package: <code>build</code>, <code>../bin</code>")
        }.visibleIf(location.selectedValueMatches { it == GoDebugBinaryLocation.CUSTOM })
    }
}

class GoEditorConfigurable(project: Project) : GoSettingsPage(project, "Editor and Completion") {
    override fun createPanel(): DialogPanel = panel {
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
                    .comment("<code>if err != nil { return ... }</code> after an assigned error, with the return values of the function and the error wrapped where the file wraps its errors; <code>defer cancel()</code>, <code>defer mu.Unlock()</code>, <code>defer f.Close()</code>, <code>defer span.End()</code> after what needs them; the loop of a scanner and the error after it; the answer of an HTTP handler and the status of a gRPC method; the receiver after <code>func (</code>; the tag of a field as the fields above have it")
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
                checkBox("Offer what a keyword can begin").bindSelected(settings::completionKeywordTemplates)
                    .comment("<code>ty</code> at the top of a file gives <code>type Name struct {...}</code>, <code>fo</code> in a body gives <code>for i, x := range xs</code> for the slices in sight; Tab walks the stops. Works without gopls")
            }
            row {
                checkBox("Offer HTTP status constants and time layouts").bindSelected(settings::completionValues)
                    .comment("<code>404</code> in <code>WriteHeader</code>, <code>http.Error</code> or a comparison with <code>StatusCode</code> gives <code>http.StatusNotFound</code>; inside the string of <code>time.Parse</code> or <code>Format</code> — the layouts and their parts")
            }
            row {
                checkBox("Offer the arguments of a completed call").bindSelected(settings::completionArguments)
                    .comment("The parameters are shown above the caret and the list opens for the first argument, then after every <code>, </code>. Needs gopls")
            }
        }
    }
}

class GoCodeQualityConfigurable(project: Project) : GoSettingsPage(project, "Code Quality") {
    override fun createPanel(): DialogPanel = panel {
        row("Reformat Code with:") { comboBox(GoFormatter.entries).bindItem(settings::formatter.toNullableProperty()) }
        row { checkBox("Format Go files on save").bindSelected(settings::formatOnSave).comment("With the formatter above; goimports also adds and removes imports. A file with a syntax error is saved as it is") }
        row {
            checkBox("Show golangci-lint warnings in the editor").bindSelected(settings::lintOnTheFly)
                .comment("For saved files, with the <code>.golangci.yml</code> of the repository; the linter is run for the package of the file")
        }
    }
}
