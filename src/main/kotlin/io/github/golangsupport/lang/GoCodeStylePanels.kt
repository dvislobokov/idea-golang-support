package io.github.golangsupport.lang

import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.ui.ComboBox
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import io.github.golangsupport.GoBundle
import io.github.golangsupport.ide.formatter.GoCodeStyleSettings
import javax.swing.JComponent

/** A tab of Code Style | Go over [GoCodeStyleSettings] without a preview (as Java's Imports tab): the options act on Optimize Imports / Reformat. */
abstract class GoCustomCodeStyleTab(settings: CodeStyleSettings) : CodeStyleAbstractPanel(settings) {
    override fun getRightMargin(): Int = -1
    override fun createHighlighter(scheme: EditorColorsScheme): EditorHighlighter? = null
    override fun getFileType(): FileType = GoFileType
    override fun getPreviewText(): String? = null

    protected fun custom(settings: CodeStyleSettings): GoCodeStyleSettings = settings.getCustomSettings(GoCodeStyleSettings::class.java)
}

/**
 * Code Style | Go | Imports, GoLand's options that gofmt leaves alone, applied by Code | Optimize Imports: the sorting (goimports groups,
 * gofmt's sort inside the hand-made groups, none), the standard library as a group, the project's packages (or `goimports -local`
 * prefixes, also given to goimports when it is the formatter) as a group, one declaration, redundant aliases removed.
 */
class GoImportsCodeStyleTab(settings: CodeStyleSettings) : GoCustomCodeStyleTab(settings) {
    private val sortings = listOf(GoCodeStyleSettings.SORT_GOIMPORTS, GoCodeStyleSettings.SORT_GOFMT, GoCodeStyleSettings.SORT_NONE)
    private val sorting = ComboBox(sortings.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { GoBundle.message("codeStyle.imports.sorting.$it") }
    }
    private val oneDeclaration = JBCheckBox(GoBundle.message("codeStyle.imports.oneDeclaration"))
    private val groupStdlib = JBCheckBox(GoBundle.message("codeStyle.imports.groupStdlib"))
    private val groupLocal = JBCheckBox(GoBundle.message("codeStyle.imports.groupLocal"))
    private val localPrefixes = JBTextField()
    private val removeAliases = JBCheckBox(GoBundle.message("codeStyle.imports.removeAliases"))

    private val component = panel {
        row(GoBundle.message("codeStyle.imports.sorting")) { cell(sorting) }
        row { cell(groupStdlib) }
        row { cell(groupLocal) }
        indent {
            row(GoBundle.message("codeStyle.imports.localPrefixes")) {
                cell(localPrefixes).align(AlignX.FILL).comment(GoBundle.message("codeStyle.imports.localPrefixes.comment"))
            }.enabledIf(groupLocal.selected)
        }
        row { cell(oneDeclaration) }
        row { cell(removeAliases) }
        row { comment(GoBundle.message("codeStyle.imports.comment")) }
    }

    init {
        addPanelToWatch(component)
    }

    override fun getTabTitle(): String = GoBundle.message("codeStyle.imports.tab")

    override fun getPanel(): JComponent = component

    override fun apply(settings: CodeStyleSettings) {
        val go = custom(settings)
        go.IMPORT_SORTING = sorting.item ?: GoCodeStyleSettings.SORT_GOIMPORTS
        go.IMPORT_ONE_DECLARATION = oneDeclaration.isSelected
        go.IMPORT_GROUP_STDLIB = groupStdlib.isSelected
        go.IMPORT_GROUP_LOCAL = groupLocal.isSelected
        go.IMPORT_LOCAL_PREFIXES = localPrefixes.text.trim()
        go.IMPORT_REMOVE_REDUNDANT_ALIASES = removeAliases.isSelected
    }

    override fun isModified(settings: CodeStyleSettings): Boolean {
        val go = custom(settings)
        return go.IMPORT_SORTING != sorting.item || go.IMPORT_ONE_DECLARATION != oneDeclaration.isSelected || go.IMPORT_GROUP_STDLIB != groupStdlib.isSelected ||
            go.IMPORT_GROUP_LOCAL != groupLocal.isSelected || go.IMPORT_LOCAL_PREFIXES != localPrefixes.text.trim() || go.IMPORT_REMOVE_REDUNDANT_ALIASES != removeAliases.isSelected
    }

    override fun resetImpl(settings: CodeStyleSettings) {
        val go = custom(settings)
        sorting.item = go.IMPORT_SORTING.takeIf { it in sortings } ?: GoCodeStyleSettings.SORT_GOIMPORTS
        oneDeclaration.isSelected = go.IMPORT_ONE_DECLARATION
        groupStdlib.isSelected = go.IMPORT_GROUP_STDLIB
        groupLocal.isSelected = go.IMPORT_GROUP_LOCAL
        localPrefixes.text = go.IMPORT_LOCAL_PREFIXES
        removeAliases.isSelected = go.IMPORT_REMOVE_REDUNDANT_ALIASES
    }
}

/**
 * Code Style | Go | Other, as GoLand's: "Add a leading space to comments" (off by default) turns on the inspection "Comment has no leading
 * space" (GoLand's inspection works only with this option, seen live).
 */
class GoOtherCodeStyleTab(settings: CodeStyleSettings) : GoCustomCodeStyleTab(settings) {
    private val leadingSpace = JBCheckBox(GoBundle.message("codeStyle.other.leadingSpace"))

    private val component = panel {
        row { cell(leadingSpace).comment(GoBundle.message("codeStyle.other.leadingSpace.comment")) }
    }

    init {
        addPanelToWatch(component)
    }

    override fun getTabTitle(): String = GoBundle.message("codeStyle.other.tab")

    override fun getPanel(): JComponent = component

    override fun apply(settings: CodeStyleSettings) {
        custom(settings).ADD_LEADING_SPACE_TO_COMMENTS = leadingSpace.isSelected
    }

    override fun isModified(settings: CodeStyleSettings): Boolean = custom(settings).ADD_LEADING_SPACE_TO_COMMENTS != leadingSpace.isSelected

    override fun resetImpl(settings: CodeStyleSettings) {
        leadingSpace.isSelected = custom(settings).ADD_LEADING_SPACE_TO_COMMENTS
    }
}

/**
 * Code Style | Go | Wrapping and Braces: only what gofmt keeps, chopping a long one-line list down to one item per line (call arguments,
 * composite literal elements, function parameters). Applied by Reformat Code with the Built-in formatter; gofmt and goimports as the
 * formatter never break lines, so the options have no effect with them.
 */
class GoWrappingCodeStyleTab(settings: CodeStyleSettings) : GoCustomCodeStyleTab(settings) {
    private val arguments = JBCheckBox(GoBundle.message("codeStyle.wrapping.arguments"))
    private val literals = JBCheckBox(GoBundle.message("codeStyle.wrapping.literals"))
    private val parameters = JBCheckBox(GoBundle.message("codeStyle.wrapping.parameters"))

    private val component = panel {
        row { cell(arguments) }
        row { cell(literals) }
        row { cell(parameters) }
        row { comment(GoBundle.message("codeStyle.wrapping.comment")) }
    }

    init {
        addPanelToWatch(component)
    }

    override fun getTabTitle(): String = GoBundle.message("codeStyle.wrapping.tab")

    override fun getPanel(): JComponent = component

    override fun apply(settings: CodeStyleSettings) {
        val go = custom(settings)
        go.CHOP_DOWN_CALL_ARGUMENTS = arguments.isSelected
        go.CHOP_DOWN_COMPOSITE_LITERALS = literals.isSelected
        go.CHOP_DOWN_PARAMETERS = parameters.isSelected
    }

    override fun isModified(settings: CodeStyleSettings): Boolean {
        val go = custom(settings)
        return go.CHOP_DOWN_CALL_ARGUMENTS != arguments.isSelected || go.CHOP_DOWN_COMPOSITE_LITERALS != literals.isSelected || go.CHOP_DOWN_PARAMETERS != parameters.isSelected
    }

    override fun resetImpl(settings: CodeStyleSettings) {
        val go = custom(settings)
        arguments.isSelected = go.CHOP_DOWN_CALL_ARGUMENTS
        literals.isSelected = go.CHOP_DOWN_COMPOSITE_LITERALS
        parameters.isSelected = go.CHOP_DOWN_PARAMETERS
    }
}
