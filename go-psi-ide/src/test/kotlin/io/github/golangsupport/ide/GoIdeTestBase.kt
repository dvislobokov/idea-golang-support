package io.github.golangsupport.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Fixture base of the IDE tests; [testDataSubdir] is relative to `<repo>/testData`. A minimal copy
 * of core's `GoCodeInsightTestBase`, whose test classes are not on this module's test classpath.
 */
abstract class GoIdeTestBase : BasePlatformTestCase() {

    protected open val testDataSubdir: String = ""

    override fun getTestDataPath(): String = testDataRoot() + if (testDataSubdir.isEmpty()) "" else "/" + testDataSubdir.trim('/')

    companion object {
        fun testDataRoot(): String {
            val fromProperty = System.getProperty("gopsi.testDataPath")?.takeIf { it.isNotBlank() }
            val dir = fromProperty?.let(::File) ?: run {
                var current: File? = File(System.getProperty("user.dir")).absoluteFile
                while (current != null && !File(current, "testData").isDirectory) current = current.parentFile
                File(current ?: error("Cannot locate testData"), "testData")
            }
            return dir.absolutePath.replace(File.separatorChar, '/')
        }
    }
}
