package io.github.golangsupport.lang.stubs

import com.intellij.psi.impl.source.PsiFileImpl
import io.github.golangsupport.GoStubTestCase
import io.github.golangsupport.GoTestUtil
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypes
import java.io.File

/**
 * Stub trees for every parser case and every Go file in `testData/stubs`: built from the AST (stub
 * builder and indexer entry point) and after a serialization round trip must be identical; the
 * stub-backed `GoFile` API must agree with the AST-backed one. Golden stub dumps for
 * the files in `testData/stubs`.
 */
class GoStubsTest : GoStubTestCase() {

    private fun sourceFiles(): List<File> =
        listOf("parser/cases", "stubs").flatMap { dir ->
            File(GoTestUtil.testDataPath(dir)).listFiles { f -> f.extension == "go" }!!.sortedBy { it.name }
        }

    fun testStubTreesAreConsistent() {
        val files = sourceFiles()
        assertTrue(files.size >= 20)
        for (source in files) {
            val text = source.readText()
            val fromPsi = buildFromPsi(createLightGoFile(source.name, text))
            val fromContent = buildFromContent(source.name, text)
            val expected = dump(fromPsi)
            assertEquals("builder vs indexer stub tree for ${source.name}", expected, dump(fromContent))
            val restored = roundTrip(fromPsi)
            assertEquals("serialization round trip for ${source.name}", expected, dump(restored))
            assertEquals("stub count for ${source.name}", countStubs(fromPsi), countStubs(restored))
        }
    }

    fun testStubApiMatchesAstApi() {
        for (source in sourceFiles()) {
            val text = source.readText()
            val astFile = createLightGoFile(source.name, text)
            val stubFile = myFixture.addFileToProject("${source.parentFile.name}/${source.name}", text) as GoFile
            assertNotNull("expected a stub for ${source.name}", (stubFile as PsiFileImpl).stub)

            val fromStub = describe(stubFile, stub = true)
            assertFalse("AST loaded while reading ${source.name} from stubs", stubFile.isContentsLoaded)
            assertEquals("GoFile API for ${source.name}", describe(astFile, stub = false), fromStub)

            // Loading the AST binds it to the existing stub PSI; a mismatch fails with a platform error.
            assertNotNull(stubFile.node)
            assertEquals("GoFile API after AST load for ${source.name}", fromStub, describe(stubFile, stub = true))
        }
    }

    fun testGenerics() = doGoldenTest()
    fun testInterfaces() = doGoldenTest()
    fun testStructs() = doGoldenTest()
    fun testConsts() = doGoldenTest()
    fun testImports() = doGoldenTest()

    /** `testData/stubs/<TestName>.go` -> stub dump in `<TestName>.txt`. */
    private fun doGoldenTest() {
        val name = getTestName(false)
        val source = File(GoTestUtil.testDataPath("stubs/$name.go"))
        val stub = buildFromPsi(createLightGoFile(source.name, source.readText()))
        assertGolden(File(source.parentFile, "$name.txt"), dump(stub))
    }

    private fun describe(file: GoFile, stub: Boolean): String = buildString {
        fun names(label: String, elements: List<GoNamedElement>) {
            append(label).append(": ").append(elements.joinToString { "${it.name}${if (it.isPublic()) "+" else ""}" })
            append('\n')
        }
        append("package: ").append(file.packageName).append('\n')
        append("test: ").append(file.isTestFile).append(", cgo: ").append(file.isCgo).append('\n')
        append("constraint: ").append(file.buildConstraint).append('\n')
        append("imports: ").append(file.imports.joinToString { "${it.alias ?: ""}:${it.path}=${it.name}" }).append('\n')
        val functions: List<GoFunctionOrMethodDeclaration> =
            if (stub) file.functions + file.methods else file.topLevelFromAst<GoFunctionOrMethodDeclaration>(GoTypes.FUNCTION_DECLARATION) +
                file.topLevelFromAst(GoTypes.METHOD_DECLARATION)
        append("functions: ")
        append(
            functions.joinToString { fn ->
                val params = fn.signature?.parameters?.parameterDeclarationList.orEmpty()
                    .joinToString(",") { p -> p.paramDefinitionList.joinToString("|") { it.name.orEmpty() } }
                "${fn.name}($params)"
            },
        )
        append('\n')
        names("methods", file.methods)
        names("types", if (stub) file.types else file.topLevelFromAst(GoTypes.TYPE_SPEC))
        names("vars", file.vars)
        names("consts", file.consts)
        append("aliases: ").append(file.types.filter { it.isAlias }.joinToString { it.name.orEmpty() }).append('\n')
        append("receivers: ").append(file.methods.joinToString { "${if (it.isPointerReceiver) "*" else ""}${it.receiverTypeName}" })
    }
}
