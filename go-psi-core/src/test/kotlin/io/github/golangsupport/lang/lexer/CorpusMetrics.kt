package io.github.golangsupport.lang.lexer

import junit.framework.TestCase.fail
import java.nio.file.Files
import java.nio.file.Path

/**
 * Corpus metrics file (a JSON file under `testData/metrics`): a flat object of integer values where lower is
 * better. [check] fails on any regression and rewrites the file when nothing regressed and some
 * value changed; a missing file is created. Keys in `informational` (for example the file count)
 * are recorded but never compared.
 */
object CorpusMetrics {
    private val entry = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*(-?\\d+)")

    fun read(file: Path): Map<String, Long> =
        entry.findAll(Files.readString(file)).associate { it.groupValues[1] to it.groupValues[2].toLong() }

    fun check(file: Path, actual: LinkedHashMap<String, Long>, informational: Set<String> = emptySet()) {
        if (!Files.exists(file)) {
            write(file, actual)
            println("  metrics: created $file")
            return
        }
        val accepted = read(file)
        val regressions = actual.filter { (key, value) ->
            key !in informational && accepted[key] != null && value > accepted.getValue(key)
        }
        if (regressions.isNotEmpty()) {
            fail(
                "Corpus metrics regressed in $file: " +
                    regressions.entries.joinToString { (k, v) -> "$k ${accepted[k]} -> $v" },
            )
        }
        if (actual != accepted) {
            write(file, actual)
            println("  metrics: updated $file (was $accepted)")
        } else {
            println("  metrics: unchanged $file")
        }
    }

    private fun write(file: Path, values: Map<String, Long>) {
        Files.createDirectories(file.parent)
        Files.writeString(file, values.entries.joinToString(",", "{", "}\n") { (k, v) -> "\"$k\":$v" })
    }
}
