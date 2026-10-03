package io.github.golangsupport.ci

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.HighlightSeverity
import java.nio.file.Path

/** The arguments of `go-inspect`: `<projectDir> <out.sarif> [--inspections a,b] [--min-severity weak|warning|error]`. Pure, for the tests. */
data class GoInspectOptions(val projectDir: Path, val output: Path, val inspections: Set<String>? = null, val minLevel: GoSarifLevel = GoSarifLevel.NOTE) {

    class UsageException(message: String) : Exception(message)

    companion object {
        const val COMMAND = "go-inspect"
        const val USAGE = "usage: $COMMAND <projectDir> <out.sarif> [--inspections ShortName,ShortName] [--min-severity weak|warning|error]"

        /** [args] as the platform passes them: the command name may come first (it did in older platforms), it is skipped. */
        fun parse(args: List<String>): GoInspectOptions {
            val rest = if (args.firstOrNull() == COMMAND) args.drop(1) else args
            val positional = ArrayList<String>()
            var inspections: Set<String>? = null
            var minLevel = GoSarifLevel.NOTE
            var i = 0
            while (i < rest.size) {
                val arg = rest[i]
                val (name, inline) = arg.split('=', limit = 2).let { it[0] to it.getOrNull(1) }
                fun value(): String = inline ?: rest.getOrNull(++i) ?: throw UsageException("$name needs a value")
                when (name) {
                    "--inspections" -> inspections = value().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                        .ifEmpty { throw UsageException("--inspections lists no inspection") }
                    "--min-severity" -> value().let { minLevel = GoSarifLevel.ofOption(it) ?: throw UsageException("unknown severity '$it': weak, warning or error") }
                    else -> if (arg.startsWith("--")) throw UsageException("unknown option $arg") else positional += arg
                }
                i++
            }
            if (positional.size != 2) throw UsageException("expected <projectDir> and <out.sarif>, got ${positional.size} positional arguments")
            return GoInspectOptions(Path.of(positional[0]).toAbsolutePath().normalize(), Path.of(positional[1]).toAbsolutePath().normalize(), inspections, minLevel)
        }
    }
}

/** What `go-inspect` reads: the walk of the project directory mirrors what the `go` command builds, and generated code is not reviewed. */
object GoInspectFiles {
    /** `go` ignores `testdata` and directories starting with `.` or `_`; `vendor` is someone else's code. */
    fun skipDirectory(name: String): Boolean = name == "vendor" || name == "testdata" || name == "node_modules" || name.startsWith(".") || name.startsWith("_")

    fun isGoModFile(name: String): Boolean = name == "go.mod" || name == "go.work"

    fun isCandidate(name: String): Boolean = name.endsWith(".go") || isGoModFile(name)

    /** The convention of `go generate` (https://go.dev/s/generatedcode): the marker line anywhere before the package clause. */
    private val GENERATED = Regex("""^// Code generated .* DO NOT EDIT\.$""")

    fun isGenerated(text: CharSequence): Boolean {
        for (line in text.lineSequence()) {
            val trimmed = line.trimEnd('\r')
            if (GENERATED.matches(trimmed)) return true
            if (trimmed.startsWith("package ")) return false
        }
        return false
    }

    /** Forward slashes and no leading `./`: SARIF wants URI references relative to the source root. */
    fun relativeUri(root: Path, file: Path): String =
        root.relativize(file).joinToString("/") { java.net.URLEncoder.encode(it.toString(), Charsets.UTF_8).replace("+", "%20") }
}

/** SARIF `level` values the report uses; [rank] orders them for `--min-severity`. */
enum class GoSarifLevel(val sarif: String, val rank: Int) {
    NOTE("note", 1), WARNING("warning", 2), ERROR("error", 3);

    companion object {
        fun ofOption(text: String): GoSarifLevel? = when (text.lowercase()) {
            "weak", "note", "weak_warning" -> NOTE
            "warning" -> WARNING
            "error" -> ERROR
            else -> null
        }

        /** Below a weak warning (information, text attributes) nothing is shown in the editor's problems either: not a finding. */
        fun of(severity: HighlightSeverity): GoSarifLevel? = when {
            severity >= HighlightSeverity.ERROR -> ERROR
            severity >= HighlightSeverity.WARNING -> WARNING
            severity >= HighlightSeverity.WEAK_WARNING -> NOTE
            else -> null
        }

        /** As the editor does it: an explicit highlight type of the problem wins, the generic ones take the level of the profile. */
        fun of(type: ProblemHighlightType, profileSeverity: HighlightSeverity): GoSarifLevel? = when (type) {
            ProblemHighlightType.ERROR, ProblemHighlightType.GENERIC_ERROR -> ERROR
            ProblemHighlightType.WARNING -> WARNING
            ProblemHighlightType.WEAK_WARNING -> NOTE
            ProblemHighlightType.INFORMATION -> null
            else -> of(profileSeverity)
        }
    }
}
