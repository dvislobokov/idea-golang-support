package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.settings.GoMlPreloadActivity
import kotlinx.coroutines.runBlocking

/** The models are preloaded at project open only when the project has Go files: a Java or Python project pays nothing. */
class GoMlPreloadActivityTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testNoGoFilesNoPreloadAndAGoFileStartsIt() {
        var loads = 0
        val activity = GoMlPreloadActivity { loads++ }
        myFixture.addFileToProject("README.md", "# not Go")
        runBlocking { activity.execute(project) }
        assertEquals(0, loads)

        myFixture.addFileToProject("cmd/app/main.go", "package main\n\nfunc main() {}\n")
        runBlocking { activity.execute(project) }
        assertEquals(1, loads)
    }
}
