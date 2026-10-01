package io.github.golangsupport.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.layout.selectedValueMatches
import io.github.golangsupport.GoBundle

/**
 * A page under Settings | Tools | Go: one area each, so that a page is read in one look. The settings are application-level; the
 * language server is restarted on apply, since most of them go into its start or its configuration. The texts come from [GoBundle]:
 * the settings pages are the part of the plugin that speaks Russian when the IDE does.
 */
abstract class GoSettingsPage(protected val project: Project, titleKey: String) : BoundConfigurable(GoBundle.message(titleKey)) {
    protected val settings: GoSettings get() = GoSettings.getInstance()

    override fun apply() {
        super.apply()
        GoLanguageServerControl.restartAll(project)
    }
}

/** The switches of gopls the plugin knows what to do with; every other option of the server is on the gopls page under this one. */
class GoLanguageServerConfigurable(project: Project) : GoSettingsPage(project, "page.languageServer") {
    override fun createPanel(): DialogPanel = panel {
        row { checkBox(GoBundle.message("ls.enabled")).bindSelected(settings::languageServerEnabled) }
        row { checkBox(GoBundle.message("ls.staticcheck")).bindSelected(settings::goplsStaticcheck) }
        row { checkBox(GoBundle.message("ls.gofumpt")).bindSelected(settings::goplsGofumpt) }
        row { checkBox(GoBundle.message("ls.inlayHints")).bindSelected(settings::goplsInlayHints) }
        row { checkBox(GoBundle.message("ls.highlightUsages")).bindSelected(settings::goplsHighlightUsages).comment(GoBundle.message("ls.highlightUsages.comment")) }
        row { checkBox(GoBundle.message("ls.trace")).bindSelected(settings::goplsTrace).comment(GoBundle.message("ls.trace.comment")) }
        row { checkBox(GoBundle.message("ls.debugPages")).bindSelected(settings::goplsDebugPages).comment(GoBundle.message("ls.debugPages.comment")) }
        row { comment(GoBundle.message("ls.more")) }
    }
}

class GoDebuggerConfigurable(project: Project) : GoSettingsPage(project, "page.debugger") {
    override fun createPanel(): DialogPanel = panel {
        row { checkBox(GoBundle.message("debugger.globals")).bindSelected(settings::debugShowGlobalVariables) }
        row { checkBox(GoBundle.message("debugger.hideSystem")).bindSelected(settings::debugHideSystemGoroutines).comment(GoBundle.message("debugger.hideSystem.comment")) }
        row(GoBundle.message("debugger.stackDepth")) { intTextField(1..1000).bindIntText(settings::debugStackTraceDepth) }
        row {
            checkBox(GoBundle.message("debugger.anyGoVersion")).bindSelected(settings::debugAnyGoVersion)
                .comment(GoBundle.message("debugger.anyGoVersion.comment"))
        }
        row { checkBox(GoBundle.message("debugger.log")).bindSelected(settings::debugAdapterLog).comment(GoBundle.message("debugger.log.comment")) }
        lateinit var location: ComboBox<GoDebugBinaryLocation>
        row(GoBundle.message("debugger.binaryIn")) {
            // the renderer, not toString(): what the settings file keeps has to stay English whatever the language of the page
            location = comboBox(GoDebugBinaryLocation.entries, SimpleListCellRenderer.create("") { it.label })
                .bindItem(settings::debugBinaryLocation.toNullableProperty())
                .comment(GoBundle.message("debugger.binaryIn.comment")).component
        }
        row(GoBundle.message("debugger.binaryDirectory")) {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(GoBundle.message("debugger.binaryDirectory.chooser")), project)
                .align(AlignX.FILL).bindText(settings::debugBinaryDirectory)
                .comment(GoBundle.message("debugger.binaryDirectory.comment"))
        }.visibleIf(location.selectedValueMatches { it == GoDebugBinaryLocation.CUSTOM })
    }
}

class GoEditorConfigurable(project: Project) : GoSettingsPage(project, "page.editor") {
    override fun createPanel(): DialogPanel = panel {
        group(GoBundle.message("editor.group")) {
            row { checkBox(GoBundle.message("editor.docComments")).bindSelected(settings::docCommentNames).comment(GoBundle.message("editor.docComments.comment")) }
            row { checkBox(GoBundle.message("editor.goplsActions")).bindSelected(settings::goplsActionsInMenu).comment(GoBundle.message("editor.goplsActions.comment")) }
            row { checkBox(GoBundle.message("editor.latin")).bindSelected(settings::latinInCode).comment(GoBundle.message("editor.latin.comment")) }
            row { checkBox(GoBundle.message("editor.idioms")).bindSelected(settings::inlineIdioms).comment(GoBundle.message("editor.idioms.comment")) }
        }
        group(GoBundle.message("completion.group")) {
            row { checkBox(GoBundle.message("completion.returnValues")).bindSelected(settings::completeReturnValues).comment(GoBundle.message("completion.returnValues.comment")) }
            row { checkBox(GoBundle.message("completion.prefixFirst")).bindSelected(settings::completionPrefixFirst).comment(GoBundle.message("completion.prefixFirst.comment")) }
            row { checkBox(GoBundle.message("completion.byType")).bindSelected(settings::completionByType).comment(GoBundle.message("completion.byType.comment")) }
            row { checkBox(GoBundle.message("completion.catalogue")).bindSelected(settings::completionCatalogue).comment(GoBundle.message("completion.catalogue.comment")) }
            row { checkBox(GoBundle.message("completion.unimported")).bindSelected(settings::completionUnimportedPackages).comment(GoBundle.message("completion.unimported.comment")) }
            row { checkBox(GoBundle.message("completion.structBraces")).bindSelected(settings::completionStructBraces).comment(GoBundle.message("completion.structBraces.comment")) }
            row { checkBox(GoBundle.message("completion.keywords")).bindSelected(settings::completionKeywordTemplates).comment(GoBundle.message("completion.keywords.comment")) }
            row { checkBox(GoBundle.message("completion.values")).bindSelected(settings::completionValues).comment(GoBundle.message("completion.values.comment")) }
            row { checkBox(GoBundle.message("completion.arguments")).bindSelected(settings::completionArguments).comment(GoBundle.message("completion.arguments.comment")) }
        }
    }
}

class GoCodeQualityConfigurable(project: Project) : GoSettingsPage(project, "page.codeQuality") {
    override fun createPanel(): DialogPanel = panel {
        row(GoBundle.message("quality.formatter")) { comboBox(GoFormatter.entries).bindItem(settings::formatter.toNullableProperty()) }
        row { checkBox(GoBundle.message("quality.formatOnSave")).bindSelected(settings::formatOnSave).comment(GoBundle.message("quality.formatOnSave.comment")) }
        row { checkBox(GoBundle.message("quality.lint")).bindSelected(settings::lintOnTheFly).comment(GoBundle.message("quality.lint.comment")) }
    }
}
