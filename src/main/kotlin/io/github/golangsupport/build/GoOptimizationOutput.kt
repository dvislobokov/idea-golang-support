package io.github.golangsupport.build

import java.io.File

/** The groups of the Go Optimization window: what `-m` and `check_bce` of the compiler report. */
enum class GoOptimizationKind(val title: String) {
    INLINING("Inlining"),
    ESCAPE("Escape analysis"),
    BOUNDS("Bounds checks"),
}

/** One decision of the compiler: [file] is absolute with `/`, [line] and [column] are 1-based. */
data class GoOptimizationDecision(val file: String, val line: Int, val column: Int, val kind: GoOptimizationKind, val text: String)

/**
 * The diagnostics of `go build -gcflags='-m=2 -d=ssa/check_bce/debug=1'`: `path.go:12:6: can inline f with cost 4 as: ...`,
 * `inlining call to f`, `cannot inline g: ...`, `x escapes to heap`, `leaking param: p`, `moved to heap: v`, `Found IsInBounds`.
 * The explanations `-m=2` prints under a decision (indented `flow:` / `from` lines) and the `# package` headers are skipped.
 */
object GoOptimizationOutput {
    /** The compiler's position: a drive letter may precede the path on Windows, so the path ends at the first `.go:`. */
    private val POSITION = Regex("""^(.+?\.go):(\d+):(\d+): (.*)$""")

    /** `-gcflags` of a run: decisions of inlining and escape analysis, and the bounds checks the compiler kept when [boundsChecks]. */
    fun gcflags(boundsChecks: Boolean): String = "-gcflags=-m=2" + if (boundsChecks) " -d=ssa/check_bce/debug=1" else ""

    fun arguments(buildTags: List<String>, boundsChecks: Boolean): List<String> = listOf("build") + buildTags + gcflags(boundsChecks) + "./..."

    fun parse(output: String, workDirectory: String): List<GoOptimizationDecision> = output.lineSequence().mapNotNull { line ->
        val match = POSITION.matchEntire(line.trimEnd('\r')) ?: return@mapNotNull null
        val (path, lineNumber, column, message) = match.destructured
        // the reasons of -m=2 are indented under their decision
        if (message.startsWith(" ") || message.startsWith("\t")) return@mapNotNull null
        val kind = kindOf(message) ?: return@mapNotNull null
        GoOptimizationDecision(resolve(path, workDirectory), lineNumber.toInt(), column.toInt(), kind, shorten(message))
    }.distinct().toList()

    fun kindOf(message: String): GoOptimizationKind? = when {
        message.startsWith("Found IsInBounds") || message.startsWith("Found IsSliceInBounds") -> GoOptimizationKind.BOUNDS
        message.startsWith("can inline") || message.startsWith("cannot inline") || message.startsWith("inlining call to") -> GoOptimizationKind.INLINING
        "escape" in message || message.startsWith("leaking param") || message.startsWith("moved to heap") || "leaks to" in message -> GoOptimizationKind.ESCAPE
        else -> null
    }

    /** The compiler's own complaints in a failed run (`x.go:6:2: "net/url" imported and not used`, `too many errors`): everything that is
     *  neither a decision nor its indented reason, so a notification shows what broke the build, not the thousands of `-m` lines (seen live). */
    fun errors(output: String): List<String> = output.lineSequence().map { it.trimEnd('\r') }.filter { line ->
        val match = POSITION.matchEntire(line)
        if (match == null) line.startsWith("too many errors") else match.groupValues[4].let { !it.startsWith(" ") && !it.startsWith("\t") && kindOf(it) == null }
    }.distinct().toList()

    /** `can inline f with cost 4 as: func() { ... }`: the body the compiler repeats is left out. */
    fun shorten(message: String): String = message.substringBefore(" as: ").trimEnd(':', ' ')

    /** The compiler writes paths relative to the directory of the command (`./x.go`, `.\x.go`, `internal/y.go`) or absolute ones. */
    fun resolve(path: String, workDirectory: String): String {
        val file = File(path).let { if (it.isAbsolute || path.startsWith("/")) it else File(workDirectory, path) }
        return file.toPath().normalize().toString().replace('\\', '/')
    }
}
