package io.github.golangsupport.ide.formatter

import com.intellij.psi.PsiFile
import com.intellij.application.options.CodeStyle
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CustomCodeStyleSettings

/**
 * Code Style | Go beyond the indents: how Optimize Imports lays the imports out (the Imports tab, as GoLand's) and which long lists the
 * Built-in formatter chops down (Wrapping and Braces). gofmt has no options, so everything here is what gofmt leaves alone: the
 * grouping of imports (`goimports -local`) and line breaks.
 */
@Suppress("PropertyName")
class GoCodeStyleSettings(container: CodeStyleSettings) : CustomCodeStyleSettings("GoCodeStyleSettings", container) {
    /** [SORT_GOIMPORTS]: groups like goimports; [SORT_GOFMT]: sorted inside the blank-line groups written by hand; [SORT_NONE]: left as they are. */
    @JvmField var IMPORT_SORTING: Int = SORT_GOIMPORTS

    /** Several `import` declarations are merged into one parenthesised declaration. */
    @JvmField var IMPORT_ONE_DECLARATION: Boolean = false

    /** The standard library is a group of its own (goimports); off: one group with the third-party packages. */
    @JvmField var IMPORT_GROUP_STDLIB: Boolean = true

    /** The packages of the project (or of [IMPORT_LOCAL_PREFIXES]) are a group after the third-party ones. */
    @JvmField var IMPORT_GROUP_LOCAL: Boolean = true

    /** Comma-separated prefixes of the local group, as `goimports -local`; empty: the main modules of the file. */
    @JvmField var IMPORT_LOCAL_PREFIXES: String = ""

    /** `fmt "fmt"` -> `"fmt"`: an alias equal to the name the package is imported under anyway. */
    @JvmField var IMPORT_REMOVE_REDUNDANT_ALIASES: Boolean = false

    /** Reformat Code (Built-in formatter) puts every argument of a call that does not fit into the right margin on its own line. */
    @JvmField var CHOP_DOWN_CALL_ARGUMENTS: Boolean = false

    /** The same for the elements of a composite literal. */
    @JvmField var CHOP_DOWN_COMPOSITE_LITERALS: Boolean = false

    /** The same for the parameters of a function declaration. */
    @JvmField var CHOP_DOWN_PARAMETERS: Boolean = false

    /** Other tab, GoLand's "Add a leading space to comments": Reformat Code adds the space (`GoCommentSpacePostFormatProcessor`) and the inspection "Comment has no leading space" reports `//text` only when on. */
    @JvmField var ADD_LEADING_SPACE_TO_COMMENTS: Boolean = false

    /** The local prefixes typed by the user, or empty. */
    fun localPrefixes(): List<String> = IMPORT_LOCAL_PREFIXES.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val SORT_GOIMPORTS = 0
        const val SORT_GOFMT = 1
        const val SORT_NONE = 2

        fun of(file: PsiFile): GoCodeStyleSettings = CodeStyle.getCustomSettings(file, GoCodeStyleSettings::class.java)

        /** What the local group of [file] is: the typed prefixes, else the main modules; empty when the group is off. Read action. */
        fun localGroup(file: PsiFile): List<String> {
            val settings = of(file)
            if (!settings.IMPORT_GROUP_LOCAL) return emptyList()
            return settings.localPrefixes().ifEmpty { GoImportGroups.localPrefixes(file) }
        }
    }
}
