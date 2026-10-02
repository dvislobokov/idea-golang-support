package io.github.golangsupport.lang.stubs

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.FileContentImpl
import io.github.golangsupport.GoCodeInsightTestBase
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.lang.index.GoBuildTagsIndex
import io.github.golangsupport.lang.index.GoFileImportsIndex
import io.github.golangsupport.lang.index.GoIndexedFileHeader
import io.github.golangsupport.lang.lexer.GoFileHeaderScanner
import io.github.golangsupport.lang.psi.GoBuildConstraint
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.stubs.index.GoAllPrivateNamesIndex
import io.github.golangsupport.lang.stubs.index.GoAllPublicNamesIndex
import io.github.golangsupport.lang.stubs.index.GoFunctionIndex
import io.github.golangsupport.lang.stubs.index.GoMethodFingerprintIndex
import io.github.golangsupport.lang.stubs.index.GoMethodIndex
import io.github.golangsupport.lang.stubs.index.GoMethodSpecFingerprintIndex
import io.github.golangsupport.lang.stubs.index.GoPackagesIndex
import io.github.golangsupport.lang.stubs.index.GoStringStubIndex
import io.github.golangsupport.lang.stubs.index.GoTypesIndex

/** Queries every Go stub index and file-based index over a small fixture project. */
class GoIndicesTest : GoCodeInsightTestBase() {

    private lateinit var alpha: GoFile
    private lateinit var alphaTest: GoFile
    private lateinit var beta: GoFile

    override fun setUp() {
        super.setUp()
        alpha = myFixture.addFileToProject(
            "alpha/alpha.go",
            """
            package alpha

            import (
            	"fmt"
            	str "strings"
            )

            type T struct{ n int }
            type hidden = T
            type Reader interface {
            	Read(p []byte) (int, error)
            	Close() error
            }

            func Foo(a, b int) int { var local = 1; return a + b + local }
            func bar() { fmt.Println(str.ToUpper("x")) }
            func (t *T) Read(p []byte) (int, error) { return 0, nil }
            func (T) Close() error { return nil }

            var V, w = 1, 2
            const C, d = 3, 4
            """.trimIndent(),
        ) as GoFile
        alphaTest = myFixture.addFileToProject(
            "alpha/alpha_test.go",
            """
            //go:build linux && !race
            // +build linux,!race

            package alpha

            import "testing"

            func TestFoo(t *testing.T) {}
            """.trimIndent(),
        ) as GoFile
        beta = myFixture.addFileToProject(
            "beta/beta.go",
            """
            package beta

            import "fmt"

            func Foo() { fmt.Println() }
            func (r *Reader) Read(p []byte) (int, error) { return 0, nil }

            type Reader struct{}
            """.trimIndent(),
        ) as GoFile
    }

    private val scope: GlobalSearchScope get() = GlobalSearchScope.allScope(project)

    private fun keys(index: GoStringStubIndex<*>): Set<String> =
        StubIndex.getInstance().getAllKeys(index.key, project).filter { index.find(it, project, scope).isNotEmpty() }.toSet()

    private fun Collection<PsiElement>.describe(): List<String> =
        map { "${it.containingFile.virtualFile.parent.name}/${(it as? PsiNamedElement)?.name ?: it.javaClass.simpleName}" }.sorted()

    fun testPackagesIndex() {
        assertEquals(setOf("alpha", "beta"), keys(GoPackagesIndex()))
        val files = GoPackagesIndex().find("alpha", project, scope).map { it.name }.sorted()
        assertEquals(listOf("alpha.go", "alpha_test.go"), files)
    }

    fun testFunctionIndex() {
        assertEquals(setOf("Foo", "bar", "TestFoo"), keys(GoFunctionIndex()))
        val foos: Collection<GoFunctionDeclaration> = GoFunctionIndex().find("Foo", project, scope)
        assertEquals(listOf("alpha/Foo", "beta/Foo"), foos.describe())
    }

    fun testMethodIndex() {
        assertEquals(setOf("T", "Reader"), keys(GoMethodIndex()))
        val methods: Collection<GoMethodDeclaration> = GoMethodIndex().find("T", project, scope)
        assertEquals(listOf("alpha/Close", "alpha/Read"), methods.describe())
        assertEquals(listOf("beta/Read"), GoMethodIndex().find("Reader", project, scope).describe())
    }

    fun testTypesIndex() {
        assertEquals(setOf("T", "hidden", "Reader"), keys(GoTypesIndex()))
        val readers: Collection<GoTypeSpec> = GoTypesIndex().find("Reader", project, scope)
        assertEquals(listOf("alpha/Reader", "beta/Reader"), readers.describe())
        assertTrue(GoTypesIndex().find("hidden", project, scope).single().isAlias)
    }

    fun testAllNamesIndices() {
        assertEquals(setOf("T", "Reader", "Foo", "Read", "Close", "V", "C", "TestFoo"), keys(GoAllPublicNamesIndex()))
        assertEquals(setOf("hidden", "bar", "w", "d"), keys(GoAllPrivateNamesIndex()))
        assertEquals(listOf("alpha/Read", "beta/Read"), GoAllPublicNamesIndex().find("Read", project, scope).describe())
    }

    fun testFingerprintIndices() {
        val methods: Collection<GoMethodDeclaration> = GoMethodFingerprintIndex().find("Read/1", project, scope)
        assertEquals(listOf("alpha/Read", "beta/Read"), methods.describe())
        assertEquals(listOf("alpha/Close"), GoMethodFingerprintIndex().find("Close/0", project, scope).describe())
        val specs: Collection<GoMethodSpec> = GoMethodSpecFingerprintIndex().find("Read/1", project, scope)
        assertEquals(listOf("alpha/Read"), specs.describe())
        assertEquals(setOf("Read/1", "Close/0"), keys(GoMethodSpecFingerprintIndex()))
    }

    fun testFileImportsIndex() {
        val index = FileBasedIndex.getInstance()
        assertEquals(
            setOf("fmt", "strings", "testing"),
            index.getAllKeys(GoFileImportsIndex.NAME, project).filter { index.getContainingFiles(GoFileImportsIndex.NAME, it, scope).isNotEmpty() }.toSet(),
        )
        val fmtUsers = GoFileImportsIndex.filesImporting("fmt", project).map { "${it.parent.name}/${it.name}" }.sorted()
        assertEquals(listOf("alpha/alpha.go", "beta/beta.go"), fmtUsers)
    }

    fun testIndicesShareOneHeaderScanPerFileContent() {
        val text = "//go:build linux\n\npackage p\n\nimport (\n\t\"fmt\"\n\tx \"strings\"\n)\n"
        val file = LightVirtualFile("h.go", GoFileType, text)
        @Suppress("UNCHECKED_CAST")
        fun <V> mapOf(ext: FileBasedIndexExtension<*, V>, content: FileContent): Map<*, V> =
            (ext.indexer as DataIndexer<Any?, V, FileContent>).map(content)

        // A fresh content: both indexers see the real header, scanned once and kept on the content.
        val content = FileContentImpl.createByText(file, text, project)
        assertEquals(setOf("fmt", "strings"), mapOf(GoFileImportsIndex(), content).keys)
        val scanned = content.getUserData(GoIndexedFileHeader.HEADER)
        assertNotNull("header cached on the file content", scanned)
        assertEquals(GoBuildConstraint("linux", emptyList()), mapOf(GoBuildTagsIndex(), content).values.single())
        assertSame("the second indexer reused the scan", scanned, content.getUserData(GoIndexedFileHeader.HEADER))

        // The cached header is what both indexers read (in either order).
        val seeded = FileContentImpl.createByText(file, text, project)
        seeded.putUserData(
            GoIndexedFileHeader.HEADER,
            GoFileHeaderScanner.Header("p", GoBuildConstraint("seeded", emptyList()), listOf(GoFileHeaderScanner.Import("seeded/path", null))),
        )
        assertEquals(GoBuildConstraint("seeded", emptyList()), mapOf(GoBuildTagsIndex(), seeded).values.single())
        assertEquals(setOf("seeded/path"), mapOf(GoFileImportsIndex(), seeded).keys)
    }

    fun testBuildTagsIndex() {
        assertEquals(
            GoBuildConstraint("linux && !race", listOf("+build linux,!race")),
            GoBuildTagsIndex.constraintOf(alphaTest.virtualFile, project),
        )
        assertNull(GoBuildTagsIndex.constraintOf(alpha.virtualFile, project))
        assertTrue(alphaTest.isTestFile)
        assertFalse(beta.isTestFile)
    }
}
