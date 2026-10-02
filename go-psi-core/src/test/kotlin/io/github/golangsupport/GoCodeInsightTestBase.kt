package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Base class for fixture-based tests; [testDataSubdir] is relative to `<repo>/testData`. */
abstract class GoCodeInsightTestBase : BasePlatformTestCase() {

    protected open val testDataSubdir: String = ""

    override fun getTestDataPath(): String =
        if (testDataSubdir.isEmpty()) GoTestUtil.testDataPath() else GoTestUtil.testDataPath(testDataSubdir)
}
