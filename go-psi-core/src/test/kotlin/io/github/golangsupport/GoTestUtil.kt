package io.github.golangsupport

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/** Shared test configuration. All values come from system properties set by the Gradle build. */
object GoTestUtil {
    private const val TEST_DATA_PATH = "gopsi.testDataPath"
    private const val UPDATE_GOLDENS = "gopsi.updateGoldens"
    private const val GOROOT = "gopsi.goroot"
    private const val GOMODCACHE = "gopsi.gomodcache"

    private const val DEFAULT_GOROOT = "C:\\Program Files\\Go"
    private val DEFAULT_GOMODCACHE = System.getProperty("user.home") + "\\go\\pkg\\mod"

    /** Absolute path of `<repo>/testData`, with forward slashes (as the platform test framework expects). */
    @JvmStatic
    fun testDataPath(): String {
        val fromProperty = System.getProperty(TEST_DATA_PATH)?.takeIf { it.isNotBlank() }
        val dir = fromProperty?.let(::File) ?: findTestDataUpwards()
        return dir.absolutePath.replace(File.separatorChar, '/')
    }

    /** `<repo>/testData/<relative>` with forward slashes. */
    @JvmStatic
    fun testDataPath(relative: String): String = testDataPath() + "/" + relative.trim('/')

    /** True when goldens should be (re)written: `-Dgopsi.updateGoldens=true`. */
    @JvmStatic
    val updateGoldens: Boolean
        get() = System.getProperty(UPDATE_GOLDENS)?.let { it.isEmpty() || it.toBoolean() } ?: false

    @JvmStatic
    fun goroot(): Path = Paths.get(System.getProperty(GOROOT)?.takeIf { it.isNotBlank() } ?: DEFAULT_GOROOT)

    @JvmStatic
    fun gomodcache(): Path = Paths.get(System.getProperty(GOMODCACHE)?.takeIf { it.isNotBlank() } ?: DEFAULT_GOMODCACHE)

    private fun findTestDataUpwards(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testData")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("Cannot locate testData; set -D$TEST_DATA_PATH")
    }
}
