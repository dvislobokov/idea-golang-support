package io.github.golangsupport.ide.structure

import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.testFramework.PlatformTestUtil
import io.github.golangsupport.ide.GoIdeTestBase
import java.io.File

class GoStructureViewTest : GoIdeTestBase() {

    override val testDataSubdir: String = "structure"

    fun testStructure() = doTest("Structure")

    fun testStructureSortedAlphabetically() = doTest("Structure", "StructureAlpha", Sorter.ALPHA_SORTER.name)

    fun testStructureSortedByVisibility() = doTest("Structure", "StructureVisibility", GoVisibilitySorter.ID)

    fun testStructureSortedByKind() = doTest("Structure", "StructureKind", GoKindSorter.ID)

    fun testLeafInfo() {
        myFixture.configureByText("a.go", "package p\n\ntype T struct{ A int }\n\nfunc f() {}\n")
        myFixture.testStructureView { component ->
            val model = component.treeModel as GoStructureViewModel
            val children = model.root.children.map { it as GoStructureViewElement }
            assertFalse(model.isAlwaysLeaf(children[0]))
            assertTrue(model.isAlwaysLeaf(children[1]))
            assertFalse(model.isAlwaysShowsPlus(children[0]))
        }
    }

    private fun doTest(source: String, golden: String = source, sorter: String? = null) {
        myFixture.configureByFile("$source.go")
        myFixture.testStructureView { component ->
            if (sorter != null) component.setActionActive(sorter, true)
            PlatformTestUtil.expandAll(component.tree)
            val actual = PlatformTestUtil.print(component.tree, false)
            val file = File("$testDataPath/$golden.txt")
            if (!file.exists()) {
                file.writeText(actual)
                fail("Created golden ${file.path}; rerun to accept")
            }
            assertEquals(file.readText().replace("\r\n", "\n").trimEnd(), actual.trimEnd())
        }
    }
}
