package io.github.golangsupport.ide.codevision

import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoNamedElement

/**
 * The code vision providers over the PSI: which declarations get a hint, the counts and their wording, the anchor below the doc comment,
 * the closed gate, and that counting implementations does not load the AST of the implementing file.
 */
class GoCodeVisionTest : GoSemanticIdeTestBase() {

    private val shapes = """
        package p

        // Shape is anything with an area.
        type Shape interface {
            Area() float64
            Name() string
        }

        type Circle struct{ R float64 }

        func (c *Circle) Area() float64 { return c.R }
        func (c *Circle) Name() string  { return "circle" }

        type Square struct{ S float64 }

        func (s Square) Area() float64 { return s.S }
        func (s Square) Name() string  { return "square" }

        type Point struct{ X, Y int }

        // helper is used twice.
        func helper() int { return 1 }

        func unused() {}

        func main() { _ = helper() + helper() }

        func init() {}
    """.trimIndent()

    private fun usages(): DaemonBoundCodeVisionProvider = GoUsagesCodeVisionProvider()
    private fun implementations(): DaemonBoundCodeVisionProvider = GoImplementationsCodeVisionProvider()

    /** `name: text` per entry, in file order; the name is the declaration the entry's range starts in. */
    private fun entries(provider: DaemonBoundCodeVisionProvider): List<String> =
        provider.computeForEditor(myFixture.editor, myFixture.file).map { (range, entry) ->
            val owner = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(range.startOffset), GoNamedElement::class.java, false)
            "${owner?.name}: ${(entry as com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry).text}"
        }

    fun testUsagesOfFunctionsMethodsAndTypes() {
        myFixture.configureByText("shapes.go", shapes)
        assertEquals(
            listOf(
                // the receivers reference their type; nothing calls through the interface, so its specs have no usages
                "Shape: no usages", "Area: no usages", "Name: no usages",
                "Circle: 2 usages", "Area: no usages", "Name: no usages", "Square: 2 usages", "Area: no usages", "Name: no usages",
                "Point: no usages", "helper: 2 usages", "unused: no usages",
            ),
            entries(usages()),
        )
    }

    fun testImplementationsOnTheInterfaceSideOnly() {
        myFixture.configureByText("shapes.go", shapes)
        assertEquals(listOf("Shape: 2 implementations", "Area: 2 implementations", "Name: 2 implementations"), entries(implementations()))
    }

    fun testEntryPointsAndTestsAreNotCounted() {
        myFixture.configureByText("x_test.go", "package p\n\nfunc TestX(t int) {}\nfunc BenchmarkX(b int) {}\nfunc Testable() {}\nfunc TestMain(m int) {}\nfunc helper() {}\n")
        assertEquals(listOf("Testable: no usages", "TestMain: no usages", "helper: no usages"), entries(usages()))
        myFixture.configureByText("main.go", "package main\n\nfunc main() {}\nfunc init() {}\nfunc TestX() {}\n")
        assertEquals(listOf("TestX: no usages"), entries(usages()))
    }

    fun testTheAnchorIsBelowTheDocComment() {
        myFixture.configureByText("shapes.go", shapes)
        val starts = usages().computeForEditor(myFixture.editor, myFixture.file).map { it.first.startOffset }
        assertTrue(starts.contains(shapes.indexOf("func helper()")))
        assertTrue(starts.contains(shapes.indexOf("Shape interface")))
        assertFalse(starts.contains(shapes.indexOf("// helper is used twice.")))
    }

    fun testTheCountStopsAtTheCap() {
        val calls = (1..GoCodeVision.MAX_COUNT + 5).joinToString("\n") { "\t_ = helper()" }
        myFixture.configureByText("many.go", "package p\n\nfunc helper() int { return 1 }\n\nfunc use() {\n$calls\n}\n")
        assertEquals(listOf("helper: 100+ usages", "use: no usages"), entries(usages()))
        assertEquals("1 usage", GoCodeVision.usagesText(1))
        assertEquals("1 implementation", GoCodeVision.implementationsText(1))
        assertEquals("100+ implementations", GoCodeVision.implementationsText(101))
    }

    fun testClosedGate() {
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = feature != GoIdeFeature.IMPLEMENTATION_MARKERS
        }, testRootDisposable)
        myFixture.configureByText("shapes.go", shapes)
        assertEmpty(entries(usages()))
        assertEmpty(entries(implementations()))
    }

    /** Counting implementations works from stubs: the file declaring them keeps its AST unloaded. */
    fun testImplementationsDoNotLoadTheImplementingFile() {
        val impl = myFixture.addFileToProject("p/impl.go", """
            package p

            type File struct{ name string }

            func (f *File) Read(b []byte) (int, error) { return 0, nil }
            func (f *File) Close() error { return nil }

            type Half struct{}

            func (Half) Read(b []byte) (int, error) { return 0, nil }
        """.trimIndent()) as PsiFileImpl
        ApplicationManager.getApplication().runWriteAction { impl.onContentReload() }
        assertNull(impl.treeElement)
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(VirtualFileFilter { it == impl.virtualFile }, testRootDisposable)
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("p/iface.go", """
            package p

            type ReadCloser interface {
                Read(b []byte) (int, error)
                Close() error
            }
        """.trimIndent()).virtualFile)
        assertTrue(myFixture.file is GoFile)
        assertEquals(listOf("ReadCloser: 1 implementation", "Read: 1 implementation", "Close: 1 implementation"), entries(implementations()))
        assertNull("counting implementations loaded impl.go's AST", impl.treeElement)
    }
}
