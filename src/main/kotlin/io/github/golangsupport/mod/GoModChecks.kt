package io.github.golangsupport.mod

import io.github.golangsupport.project.impl.GoModDirective
import io.github.golangsupport.project.impl.GoModFileParser

/** The checks of go.mod / go.work; [group] is the inspection that reports the rule (a user turns them off per group). */
enum class GoModRule(val group: String) {
    REPLACE_PATH("GoModPaths"), USE_DIRECTORY("GoModPaths"),
    DUPLICATE_REQUIRE("GoModRequires"), SELF_REFERENCE("GoModRequires"), VENDOR_SYNC("GoModRequires"),
    GO_VERSION("GoModVersions"), TOOLCHAIN("GoModVersions"),
}

/** What a path of a directive points at; the checks ask, the wrapper answers from the file system (tests answer from a map). */
enum class GoDirState { MISSING, NO_GO_MOD, OK }

/** [line] is zero-based; [needle] is the word of the line to underline (the whole line when null or not found). */
data class GoModProblem(val rule: GoModRule, val line: Int, val needle: String?, val message: String, val error: Boolean, val duplicateOf: String? = null)

/** What the checks may ask of the world: directories relative to the file, and `vendor/modules.txt` next to it (null when there is none). */
class GoModEnvironment(val dirState: (String) -> GoDirState, val vendorModulesTxt: String?)

/** Pure checks over the text of a go.mod / go.work: no platform, no `go` binary, so they are tested in seconds. */
object GoModChecks {
    private val GO_VERSION = Regex("""1\.\d+(\.\d+)?((alpha|beta|rc)\d+)?""")
    private val TOOLCHAIN_VERSION = Regex("""go1\.\d+(\.\d+)?((alpha|beta|rc)\d+)?""")

    fun check(text: CharSequence, isWork: Boolean, env: GoModEnvironment): List<GoModProblem> {
        val file = GoModFile.parse(text)
        val directives = GoModFileParser.directives(text)
        val result = ArrayList<GoModProblem>()
        replacePaths(directives.filter { it.verb == "replace" }.map { it.line - 1 to it.args }, result, env)
        if (isWork) usePaths(directives, result, env) else {
            duplicates(file, result)
            selfReferences(file, result)
            file.vendorProblems(env.vendorModulesTxt, result)
        }
        versions(directives, result)
        return result.sortedBy { it.line }
    }

    /** `=> ../x`: Go demands the prefix `./`, `../` or an absolute path for a directory; anything else is a module path. */
    fun isLocalPath(path: String): Boolean =
        path == "." || path == ".." || path.startsWith("./") || path.startsWith("../") || path.startsWith(".\\") || path.startsWith("..\\") ||
            path.startsWith("/") || Regex("""[A-Za-z]:[/\\].*""").matches(path)

    private fun replacePaths(replaces: List<Pair<Int, List<String>>>, out: MutableList<GoModProblem>, env: GoModEnvironment) {
        for ((line, args) in replaces) {
            val arrow = args.indexOf("=>")
            val target = args.getOrNull(arrow + 1)?.takeIf { arrow >= 0 && isLocalPath(it) } ?: continue
            val state = env.dirState(target)
            if (state == GoDirState.OK) continue
            out += GoModProblem(GoModRule.REPLACE_PATH, line, target, if (state == GoDirState.MISSING) "Replacement directory '$target' does not exist" else "Replacement directory '$target' has no go.mod", true)
        }
    }

    private fun usePaths(directives: List<GoModDirective>, out: MutableList<GoModProblem>, env: GoModEnvironment) {
        for (d in directives) {
            if (d.verb != "use") continue
            val dir = d.args.singleOrNull() ?: continue
            val state = env.dirState(dir)
            if (state == GoDirState.OK) continue
            out += GoModProblem(GoModRule.USE_DIRECTORY, d.line - 1, dir, if (state == GoDirState.MISSING) "Directory '$dir' does not exist" else "Directory '$dir' has no go.mod", true)
        }
    }

    /** The second and later requires of a path: `go mod tidy` would keep one, and Go picks the highest version anyway. */
    private fun duplicates(file: GoModFile, out: MutableList<GoModProblem>) {
        val seen = HashSet<String>()
        for (r in file.requires) if (!seen.add(r.path)) out += GoModProblem(GoModRule.DUPLICATE_REQUIRE, r.line, r.path, "Duplicate require of '${r.path}'", false, duplicateOf = r.path)
    }

    private fun selfReferences(file: GoModFile, out: MutableList<GoModProblem>) {
        val self = file.modulePath ?: return
        for (r in file.requires) if (r.path == self) out += GoModProblem(GoModRule.SELF_REFERENCE, r.line, r.path, "The module requires itself ('$self')", false)
        for (r in file.replaces) if (r.oldPath == self) out += GoModProblem(GoModRule.SELF_REFERENCE, r.line, r.oldPath, "The module replaces itself ('$self')", false)
    }

    /** As `modload.checkVendorConsistency`: every requirement is vendored at its version and, since Go 1.14, marked `## explicit`. */
    private fun GoModFile.vendorProblems(vendor: String?, out: MutableList<GoModProblem>) {
        if (vendor == null) return
        val modules = GoModFileParser.parseVendorModulesTxt(vendor)
        val explicitNeeded = goVersion?.let { compareVersions(it, "1.14") >= 0 } ?: true
        for (r in requires) {
            val v = modules.firstOrNull { it.path == r.path && (it.version == r.version || (it.version == null && it.replacement != null)) }
            val reason = when {
                v != null -> if (explicitNeeded && !v.explicit) "is not marked '## explicit' in vendor/modules.txt" else continue
                modules.any { it.path == r.path } -> "is vendored at ${modules.first { it.path == r.path }.version}, go.mod requires ${r.version}"
                else -> "is missing from vendor/modules.txt"
            }
            out += GoModProblem(GoModRule.VENDOR_SYNC, r.line, r.path, "'${r.path}' $reason; run 'go mod vendor'", false)
        }
    }

    private fun versions(directives: List<GoModDirective>, out: MutableList<GoModProblem>) {
        val go = directives.firstOrNull { it.verb == "go" && it.args.size == 1 }
        if (go != null && !GO_VERSION.matches(go.args[0])) out += GoModProblem(GoModRule.GO_VERSION, go.line - 1, go.args[0], "Malformed Go version '${go.args[0]}': expected 1.N, 1.N.P or 1.NrcM", true)
        val toolchain = directives.firstOrNull { it.verb == "toolchain" && it.args.size == 1 } ?: return
        val t = toolchain.args[0]
        if (go == null || !GO_VERSION.matches(go.args[0]) || !TOOLCHAIN_VERSION.matches(t)) return // `default` / `local` and the like are not versions
        if (compareVersions(t.removePrefix("go"), go.args[0]) < 0) out += GoModProblem(GoModRule.TOOLCHAIN, toolchain.line - 1, t, "Toolchain $t is older than the go directive ${go.args[0]}", false)
    }

    /** Go's order: 1.21 < 1.21rc1 < 1.21.0 < 1.21.1 (a language version is below any release of it). */
    fun compareVersions(a: String, b: String): Int = key(a).zip(key(b)).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0

    private fun key(v: String): List<Int> {
        val m = Regex("""(\d+)\.(\d+)(?:\.(\d+))?(?:(alpha|beta|rc)(\d+))?""").matchEntire(v) ?: return listOf(0, 0, 0, 0, 0)
        val (major, minor, patch, pre, n) = m.destructured
        val stage = when { pre == "alpha" -> 1; pre == "beta" -> 2; pre == "rc" -> 3; patch.isNotEmpty() -> 4; else -> 0 }
        return listOf(major.toInt(), minor.toInt(), stage, if (pre.isNotEmpty()) n.toInt() else patch.ifEmpty { "0" }.toInt())
    }
}
