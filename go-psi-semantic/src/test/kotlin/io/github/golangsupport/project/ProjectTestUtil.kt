package io.github.golangsupport.project

import junit.framework.TestCase.fail
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test configuration for go-psi-semantic (mirrors core's `GoTestUtil`, which is not on this
 * module's test classpath). Values come from system properties set by the Gradle build.
 */
object ProjectTestUtil {
    private const val DEFAULT_GOROOT = "C:\\Program Files\\Go"
    private val DEFAULT_GOMODCACHE = System.getProperty("user.home") + "\\go\\pkg\\mod"

    fun testDataPath(): Path {
        System.getProperty("gopsi.testDataPath")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testData")
            if (candidate.isDirectory) return candidate.toPath()
            dir = dir.parentFile
        }
        error("Cannot locate testData; set -Dgopsi.testDataPath")
    }

    fun projectData(relative: String): Path = testDataPath().resolve("project").resolve(relative)

    val updateGoldens: Boolean
        get() = System.getProperty("gopsi.updateGoldens")?.let { it.isEmpty() || it.toBoolean() } ?: false

    fun goroot(): Path = Paths.get(System.getProperty("gopsi.goroot")?.takeIf { it.isNotBlank() } ?: DEFAULT_GOROOT)

    fun gomodcache(): Path = Paths.get(System.getProperty("gopsi.gomodcache")?.takeIf { it.isNotBlank() } ?: DEFAULT_GOMODCACHE)

    fun goBinary(): Path? = goroot().resolve("bin").resolve(if (File.separatorChar == '\\') "go.exe" else "go").takeIf { Files.isRegularFile(it) }

    /** Compares [actual] with the golden [file]; writes it when missing or when updating goldens. */
    fun assertGolden(file: Path, actual: String) {
        val normalized = actual.replace("\r\n", "\n")
        if (!Files.exists(file) || updateGoldens) {
            Files.createDirectories(file.parent)
            Files.writeString(file, normalized)
            if (!updateGoldens) fail("Golden $file was missing and has been created; rerun to accept")
            return
        }
        junit.framework.TestCase.assertEquals("golden $file", Files.readString(file).replace("\r\n", "\n"), normalized)
    }

    /**
     * Corpus metrics file: a flat JSON object of integers where lower is better (same contract as
     * core's `CorpusMetrics`): fails on regressions, rewrites on improvement, creates when missing.
     */
    fun checkMetrics(file: Path, actual: LinkedHashMap<String, Long>, informational: Set<String> = emptySet()) {
        val entry = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*(-?\\d+)")
        fun write() {
            Files.createDirectories(file.parent)
            Files.writeString(file, actual.entries.joinToString(",", "{", "}\n") { (k, v) -> "\"$k\":$v" })
        }
        if (!Files.exists(file)) {
            write()
            println("  metrics: created $file")
            return
        }
        val accepted = entry.findAll(Files.readString(file)).associate { it.groupValues[1] to it.groupValues[2].toLong() }
        val regressions = actual.filter { (k, v) -> k !in informational && accepted[k] != null && v > accepted.getValue(k) }
        if (regressions.isNotEmpty()) {
            fail("Corpus metrics regressed in $file: " + regressions.entries.joinToString { (k, v) -> "$k ${accepted[k]} -> $v" })
        }
        if (actual != accepted) {
            write()
            println("  metrics: updated $file (was $accepted)")
        } else {
            println("  metrics: unchanged $file")
        }
    }
}
