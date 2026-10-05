package io.github.golangsupport.mod

import io.github.golangsupport.project.impl.GoModFileParser

/**
 * Pure planners behind `VgoMigrateFromReplacesToWorkspace` and `VgoUnresolvedIgnorePath`: over the text of a go.mod, with the file system
 * asked through functions, so the tests need no platform and no `go`.
 */
object GoModLayout {
    /** The lowest Go a go.work may name: workspaces came with 1.18. */
    private const val WORKSPACE_GO = "1.18"

    /** A `replace` of the module to a local directory with a go.mod ([hasGoMod] answers for the path as written): what a go.work `use` can carry instead. */
    fun workspaceReplaces(text: CharSequence, hasGoMod: (String) -> Boolean): List<GoReplace> =
        GoModFile.parse(text).replaces.filter { it.isLocal && GoModChecks.isLocalPath(it.newPath) && it.newPath != "." && hasGoMod(it.newPath) }

    /** The text of a go.work next to the go.mod: `go` of the module (1.18 at least), `use .` and a `use` for every directory. */
    fun goWork(goVersion: String?, directories: List<String>): String {
        val go = goVersion?.takeIf { GoModChecks.compareVersions(it, WORKSPACE_GO) >= 0 } ?: WORKSPACE_GO
        val uses = (listOf(".") + directories.map { it.replace('\\', '/').trimEnd('/') }).distinct()
        return "go $go\n\nuse (\n" + uses.joinToString("") { "\t$it\n" } + ")\n"
    }

    /** "Create go.work": the go.work text and the go.mod lines without the replaces it takes over; null when there is none. */
    fun migrateToWorkspace(text: String, hasGoMod: (String) -> Boolean): Pair<String, List<String>>? {
        val replaces = workspaceReplaces(text, hasGoMod).takeIf { it.isNotEmpty() } ?: return null
        val work = goWork(GoModFile.parse(text).goVersion, replaces.map { it.newPath })
        return work to GoModDirectiveEdits.removeEntries(text.split('\n'), replaces.mapTo(HashSet()) { it.line })
    }

    /** A path of an `ignore` directive; [line] is zero-based. */
    data class IgnorePath(val path: String, val line: Int)

    /** Every path of `ignore` / `ignore ( … )` (Go 1.25). */
    fun ignorePaths(text: CharSequence): List<IgnorePath> =
        GoModFileParser.directives(text).filter { it.verb == "ignore" }.mapNotNull { d -> d.args.singleOrNull()?.let { IgnorePath(it, d.line - 1) } }

    /**
     * The `ignore` paths that point at nothing. `./x` (and `../x`, an absolute path) is a directory from the module root; a path without
     * `./` matches a directory of that relative path at any depth of the module, as `go` reads it ([existsAnywhere] answers for those,
     * null when it cannot tell — then the path is not reported).
     */
    fun unresolvedIgnores(text: CharSequence, existsFromRoot: (String) -> Boolean, existsAnywhere: (String) -> Boolean?): List<IgnorePath> =
        ignorePaths(text).filter { p ->
            val path = p.path.replace('\\', '/').trimEnd('/')
            when {
                path.isEmpty() || path == "." -> false
                existsFromRoot(path) -> false
                GoModChecks.isLocalPath(path) -> true
                else -> existsAnywhere(path) == false
            }
        }

    /** "Remove the path": the go.mod lines without the `ignore` entry on [line]. */
    fun removeIgnore(lines: List<String>, line: Int): List<String>? =
        lines.takeIf { line in it.indices }?.let { GoModDirectiveEdits.removeEntries(it, setOf(line)) }
}
