package io.github.golangsupport

import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.stubs.SerializationManagerEx
import com.intellij.psi.stubs.Stub
import com.intellij.psi.stubs.StubTreeBuilder
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.indexing.FileContentImpl
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.stubs.GoFileElementType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Base class for stub tests: builds stub trees from the AST (the [GoFileElementType] builder and the
 * platform's indexing entry point [StubTreeBuilder]), round-trips them through the platform
 * serializer, and compares golden dumps under `testData/<testDataSubdir>`.
 */
abstract class GoStubTestCase : GoCodeInsightTestBase() {

    /** A non-physical Go file parsed from [text]. */
    protected fun createLightGoFile(name: String, text: String): GoFile =
        PsiFileFactory.getInstance(project).createFileFromText(name, GoLanguage, text) as GoFile

    /** Stub tree built by the Go stub builder from a parsed file. */
    protected fun buildFromPsi(file: GoFile): Stub = GoFileElementType.INSTANCE.builder.buildStubTree(file)

    /** Stub tree built the way the indexer does it, from file content. */
    protected fun buildFromContent(name: String, text: String): Stub {
        val file = LightVirtualFile(name, GoFileType, text)
        return StubTreeBuilder.buildStubTree(FileContentImpl.createByText(file, text, project))
            ?: error("no stub tree for $name")
    }

    protected fun serialize(stub: Stub): ByteArray =
        ByteArrayOutputStream().also { SerializationManagerEx.getInstanceEx().serialize(stub, it) }.toByteArray()

    protected fun deserialize(bytes: ByteArray): Stub =
        SerializationManagerEx.getInstanceEx().deserialize(ByteArrayInputStream(bytes))

    protected fun roundTrip(stub: Stub): Stub = deserialize(serialize(stub))

    protected fun dump(stub: Stub): String = DebugUtil.stubTreeToString(stub).trim()

    /** Number of stubs in the tree (including the file stub). */
    protected fun countStubs(stub: Stub): Int = 1 + stub.childrenStubs.sumOf { countStubs(it) }

    /** Compares [actual] with `<golden>`; `-Dgopsi.updateGoldens=true` rewrites it, a missing golden is created and fails. */
    protected fun assertGolden(golden: File, actual: String) {
        if (GoTestUtil.updateGoldens || !golden.exists()) {
            val existed = golden.exists()
            FileUtil.writeToFile(golden, actual)
            if (!GoTestUtil.updateGoldens && !existed) {
                fail("Golden file did not exist and was created: ${golden.path}. Review it and re-run.")
            }
        } else {
            assertSameLinesWithFile(golden.path, actual)
        }
    }
}
