package io.github.golangsupport.ide.formatter

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Runs the real `gofmt` of a Go installation (test oracle only; the formatter never calls it). */
class GofmtRunner(val executable: Path) {

    /** `gofmt` output for [text]; fails if gofmt reports an error. */
    fun format(text: String): String {
        val errFile = java.io.File.createTempFile("gofmt", ".err")
        val process = ProcessBuilder(executable.toString()).redirectError(errFile).start()
        process.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = errFile.readText().also { errFile.delete() }
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "gofmt failed: $err" }
        return out.replace("\r\n", "\n")
    }

    /** Absolute, normalised paths of the files under [root] that `gofmt -l` reports. */
    fun listUnformatted(root: Path): Set<Path> {
        // testdata directories contain invalid files: discard gofmt's error output
        val process = ProcessBuilder(executable.toString(), "-l", root.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor(10, TimeUnit.MINUTES)
        return out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { Path.of(it).toAbsolutePath().normalize() }.toSet()
    }

    companion object {
        /** gofmt of [goroot], `$GOROOT`, or the default install path; null when none exists. */
        fun find(goroot: Path? = null): GofmtRunner? {
            val roots = listOfNotNull(
                goroot,
                System.getProperty("gopsi.goroot")?.let { Path.of(it) },
                System.getenv("GOROOT")?.takeIf { it.isNotBlank() }?.let { Path.of(it) },
                Path.of("C:\\Program Files\\Go"),
                Path.of("/usr/local/go"),
            )
            for (r in roots) {
                for (name in listOf("gofmt.exe", "gofmt")) {
                    val exe = r.resolve("bin").resolve(name)
                    if (Files.isRegularFile(exe)) return GofmtRunner(exe)
                }
            }
            return null
        }
    }
}
