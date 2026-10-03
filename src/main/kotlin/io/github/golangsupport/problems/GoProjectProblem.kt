package io.github.golangsupport.problems

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.ProblemsProvider
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.golangsupport.ci.GoSarifLevel
import javax.swing.Icon

/** One finding of a file, without the platform: [line] and [column] from 0, [inspection] the short name, [name] the display name. */
data class GoFinding(val line: Int, val column: Int, val level: GoSarifLevel, val inspection: String, val name: String, val message: String) {
    /** "Include warnings" off: errors only. */
    fun shown(includeWarnings: Boolean): Boolean = includeWarnings || level == GoSarifLevel.ERROR

    /** The text of the row: the message, then which inspection said it (the tab mixes every inspection of the project). */
    val text: String get() = "$message ($name)"
}

/** The provider of the plugin's problems in the Project Errors tab; the collector ignores problems of another project's provider. */
class GoProblemsProvider(override val project: Project) : ProblemsProvider

/**
 * A row of the Project Errors tab. The tab takes the severity from the icon (the platform reads a severity only from its own highlighting
 * problems, which need a range highlighter of an open editor). Equal by file and finding: a file analysed again replaces only what changed.
 */
class GoProjectProblem(override val provider: ProblemsProvider, override val file: VirtualFile, val finding: GoFinding) : FileProblem {
    override val text: String get() = finding.text
    override val group: String get() = finding.name
    override val description: String get() = "${finding.name}: ${finding.message}"
    override val line: Int get() = finding.line
    override val column: Int get() = finding.column
    override val icon: Icon get() = when (finding.level) {
        GoSarifLevel.ERROR -> HighlightDisplayLevel.ERROR.icon
        GoSarifLevel.WARNING -> HighlightDisplayLevel.WARNING.icon
        GoSarifLevel.NOTE -> HighlightDisplayLevel.WEAK_WARNING.icon
    }

    override fun equals(other: Any?): Boolean = other is GoProjectProblem && other.file == file && other.finding == finding
    override fun hashCode(): Int = 31 * file.hashCode() + finding.hashCode()
    override fun toString(): String = "${file.name}:${line + 1}:${column + 1}: ${finding.level.sarif}: $text"
}
