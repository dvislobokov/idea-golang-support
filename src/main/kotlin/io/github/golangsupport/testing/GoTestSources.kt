package io.github.golangsupport.testing

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.openapi.vfs.VirtualFile
import io.github.golangsupport.lang.GoFile

/**
 * Go keeps tests next to the code, so there is no test source root to mark: a test is a file named `*_test.go`. Telling the platform so
 * puts these files into its "Tests" scope, and with it come the green background in the Project view, the editor tabs and the search
 * results (Settings | Appearance | File Colors), "Tests" as a scope of Find in Files, and test files after production code in navigation.
 */
class GoTestSourcesFilter : TestSourcesFilter() {
    override fun isTestSource(file: VirtualFile, project: Project): Boolean = !file.isDirectory && file.name.endsWith(GoFile.TEST_SUFFIX)
}
