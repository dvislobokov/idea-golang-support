package io.github.golangsupport.testing

/**
 * A cover profile of `go test -coverprofile`:
 *
 * ```
 * mode: count
 * example.com/playground/store/order.go:38.29,40.32 2 5
 * ```
 * a block of a file from line.column to line.column, the number of statements in it, how many times it ran (0/1 in `set` mode).
 * The same block is listed once per package that was tested with it (`-coverpkg`): the counts are added up.
 */
class GoCoverProfile(val mode: String, val blocks: List<Block>) {
    class Block(val file: String, val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int, val statements: Int, val count: Long)

    /** `example.com/playground/store/order.go` -> its blocks, the same block merged. */
    val byFile: Map<String, List<Block>> by lazy {
        blocks.groupBy { it.file }.mapValues { (_, list) ->
            list.groupBy { listOf(it.startLine, it.startColumn, it.endLine, it.endColumn) }
                .map { (_, same) -> Block(same[0].file, same[0].startLine, same[0].startColumn, same[0].endLine, same[0].endColumn, same[0].statements, same.sumOf { it.count }) }
        }
    }

    companion object {
        private val LINE = Regex("""^(.+):(\d+)\.(\d+),(\d+)\.(\d+) (\d+) (\d+)$""")

        fun parse(text: String): GoCoverProfile {
            var mode = "set"
            val blocks = ArrayList<Block>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("mode:")) { mode = line.substringAfter(':').trim(); continue }
                val m = LINE.matchEntire(line) ?: continue
                val g = m.groupValues
                blocks += Block(g[1], g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), g[7].toLong())
            }
            return GoCoverProfile(mode, blocks)
        }
    }
}

/** How a line ran: every block on it ran, none did, or some did. */
enum class LineCoverage { COVERED, UNCOVERED, PARTIAL }

/** The coverage of one file: by line (1-based, as the profile counts), and by statements for the percentages. */
class GoFileCoverage(val lines: Map<Int, LineCoverage>, val hits: Map<Int, Long>, val statements: Int, val coveredStatements: Int) {
    val percent: Double get() = if (statements == 0) 100.0 else coveredStatements * 100.0 / statements

    companion object {
        /** [lines]: the text of the file, to tell a line that only closes a block (`}`) from code; without it such lines count as code. */
        fun of(blocks: List<GoCoverProfile.Block>, lines: List<String>? = null): GoFileCoverage {
            val covered = HashMap<Int, Boolean>()
            val uncovered = HashMap<Int, Boolean>()
            val hits = HashMap<Int, Long>()
            for (block in blocks) {
                // a block starts after the `{` of its line and ends at the `}` that closes it: the line of the brace alone is not code
                val first = block.startLine
                val endText = lines?.getOrNull(block.endLine - 1)?.take((block.endColumn - 1).coerceAtLeast(0))?.trim()
                val last = if (block.endLine > block.startLine && endText == "}") block.endLine - 1 else block.endLine
                for (line in first..last.coerceAtLeast(first)) {
                    if (block.count > 0) covered[line] = true else uncovered[line] = true
                    hits[line] = maxOf(hits[line] ?: 0L, block.count)
                }
            }
            val lines = (covered.keys + uncovered.keys).associateWith { line ->
                when {
                    covered[line] == true && uncovered[line] == true -> LineCoverage.PARTIAL
                    covered[line] == true -> LineCoverage.COVERED
                    else -> LineCoverage.UNCOVERED
                }
            }
            return GoFileCoverage(lines, hits, blocks.sumOf { it.statements }, blocks.filter { it.count > 0 }.sumOf { it.statements })
        }
    }
}

object GoCoverageFormat {
    /** `82.4%`, `100%`, `0%`. */
    fun percent(value: Double): String = if (value == Math.floor(value)) "${value.toInt()}%" else String.format(java.util.Locale.ROOT, "%.1f%%", value)

    /** `-covermode=count` counts the runs of a block; the race detector needs `atomic` for that. */
    fun modeArguments(otherArguments: List<String>): List<String> =
        if (otherArguments.any { it == "-race" || it.startsWith("-covermode") }) (if (otherArguments.any { it.startsWith("-covermode") }) emptyList() else listOf("-covermode=atomic"))
        else listOf("-covermode=count")

    /**
     * The local path of a file of the profile: `example.com/playground/store/order.go` under the module `example.com/playground` rooted
     * at `C:/p` is `C:/p/store/order.go`. The longest module path wins (nested modules); null for a file of no module of the project.
     */
    fun localPath(profilePath: String, modules: Map<String, String>): String? {
        val module = modules.keys.filter { profilePath == it || profilePath.startsWith("$it/") }.maxByOrNull { it.length } ?: return null
        return modules.getValue(module).trimEnd('/', '\\') + "/" + profilePath.removePrefix(module).trimStart('/')
    }
}
