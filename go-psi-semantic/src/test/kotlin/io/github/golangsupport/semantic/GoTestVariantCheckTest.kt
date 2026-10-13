package io.github.golangsupport.semantic

import com.intellij.psi.PsiManager
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.GoProjectModelTestBase
import io.github.golangsupport.semantic.api.GoSemanticService

/**
 * `go test` compiles the `_test.go` files of a package into it: their methods and names exist in the test variant only — for the
 * package's own test files and for its external test package, which imports that variant (`export_test.go`). Seen on prometheus
 * (`hasSynced` in `discovery/kubernetes/kubernetes_test.go`) and on GOROOT test files. Fixture `testData/project/testvariant`.
 */
class GoTestVariantCheckTest : GoProjectModelTestBase() {

    private fun messages(relative: String): List<String> {
        val file = PsiManager.getInstance(project).findFile(fixture(relative)) as GoFile
        return GoSemanticService.getInstance(project).check(file).map { it.message }
    }

    fun testInternalTestFileSeesItsMethods() = assertEquals(emptyList<String>(), messages("testvariant/tv/export_test.go"))

    fun testExternalTestPackageSeesTheTestVariant() = assertEquals(emptyList<String>(), messages("testvariant/tv/d_ext_test.go"))

    fun testRegularFileOfThePackageDoesNot() =
        assertEquals(listOf("d.synced undefined (type *D has no field or method synced)"), messages("testvariant/tv/d.go"))

    fun testAnotherPackageDoesNot() = assertEquals(
        listOf("d.Synced undefined (type *tv.D has no field or method Synced)", "undefined: tv.Hidden"),
        messages("testvariant/user/u.go"),
    )
}
