package io.github.golangsupport.semantic.flow

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.project.GoProjectModelTestBase

/** Helpers of the flow tests: a file `p/a.go` with the given declarations, the graph of a function by name. */
abstract class GoFlowTestBase : GoProjectModelTestBase() {

    private var files = 0

    protected fun file(code: String, imports: String = ""): GoFile =
        myFixture.addFileToProject("p${files++}/a.go", "package p\n\n" + (if (imports.isEmpty()) "" else "$imports\n\n") + code.trimIndent() + "\n") as GoFile

    protected fun function(file: GoFile, name: String = "f"): GoFunctionOrMethodDeclaration =
        PsiTreeUtil.findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java).first { it.name == name }

    protected fun flow(code: String, name: String = "f", imports: String = ""): GoControlFlow? = GoControlFlow.of(function(file(code, imports), name))

    protected fun dump(code: String, name: String = "f", imports: String = ""): String =
        flow(code, name, imports)?.let(GoFlowDump::dump) ?: "<no graph>"

    protected fun literalFlow(file: GoFile, index: Int = 0): GoControlFlow? =
        GoControlFlow.of(PsiTreeUtil.findChildrenOfType(file, GoFunctionLit::class.java).toList()[index])

    protected fun assertDump(expected: String, code: String, name: String = "f", imports: String = "") =
        assertEquals(expected.trimIndent() + "\n", dump(code, name, imports))
}
