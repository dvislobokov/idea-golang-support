package io.github.golangsupport.ide

import com.intellij.openapi.application.ApplicationManager

/** What to do with a linked rename (the test file of a file, the tag of a field, the package of a directory): as GoLand's Go settings page. */
enum class GoRenameChoice {
    /** A check box in the rename dialog, on by default; without a dialog (in-place rename, tests) the last choice holds. */
    ASK,
    ALWAYS,
    NEVER,
}

/**
 * The user's options of go-psi-ide features a host keeps in its own settings: auto-import (Settings | Editor | General | Auto Import, Go)
 * and the linked renames. In a standalone go-psi they live in memory ([DefaultGoIdeOptions]); the host plugin overrides the service with
 * one over its settings, the same way as [GoIdeFeatureGate].
 */
interface GoIdeOptions {
    /** An unresolved `pkg.Name` with exactly one package that fits gets its import from the daemon, without Alt+Enter. */
    var importUnambiguousOnTheFly: Boolean

    /** Unused imports are removed after the highlighting when nothing else in the file is wrong. */
    var importOptimizeOnTheFly: Boolean

    /** The "? Import "fmt" Alt+Enter" hint over an unresolved package name. */
    var importShowPopup: Boolean

    /** Import paths (`example.com/x`, `example.com/x/...`, `example.com/x/` + star) never offered for import or by completion of unimported packages. */
    var importExcluded: List<String>

    var renameTestFiles: GoRenameChoice
    var renameStructTags: GoRenameChoice
    var renameDirectoryPackage: GoRenameChoice
    var renamePackageDirectory: GoRenameChoice

    companion object {
        fun getInstance(): GoIdeOptions = ApplicationManager.getApplication().getService(GoIdeOptions::class.java)
    }
}

/** GoLand's defaults, in memory: the options of a go-psi without a host (and of the tests of go-psi-ide). */
class DefaultGoIdeOptions : GoIdeOptions {
    override var importUnambiguousOnTheFly = true
    override var importOptimizeOnTheFly = false
    override var importShowPopup = true
    override var importExcluded: List<String> = emptyList()
    override var renameTestFiles = GoRenameChoice.ASK
    override var renameStructTags = GoRenameChoice.ASK
    override var renameDirectoryPackage = GoRenameChoice.ASK
    override var renamePackageDirectory = GoRenameChoice.ASK
}

/** "Exclude from import and completion": the patterns are import paths; `/...` or a slash and a star at the end takes the subpackages too. */
object GoImportExclusions {
    fun excluded(path: String, patterns: Collection<String>): Boolean = patterns.any { matches(path, it.trim()) }

    fun excluded(path: String): Boolean = GoIdeOptions.getInstance().importExcluded.let { it.isNotEmpty() && excluded(path, it) }

    private fun matches(path: String, pattern: String): Boolean = when {
        pattern.isEmpty() -> false
        pattern.endsWith("/...") || pattern.endsWith("/*") -> pattern.substringBeforeLast('/').let { path == it || path.startsWith("$it/") }
        // `github.com/foo*`: a plain prefix of the path
        pattern.endsWith("...") || pattern.endsWith("*") -> path.startsWith(pattern.removeSuffix("...").removeSuffix("*"))
        else -> path == pattern
    }
}
