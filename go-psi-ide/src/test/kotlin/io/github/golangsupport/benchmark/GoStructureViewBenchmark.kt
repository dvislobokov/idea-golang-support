package io.github.golangsupport.benchmark

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.openapi.util.Disposer
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.ide.structure.GoStructureViewModel
import io.github.golangsupport.lang.psi.GoFile

/**
 * Builds the structure view model of `go/types/api.go` and materializes the whole tree (children
 * and presentable text of every node), ten times per iteration. Unit: ms.
 */
class GoStructureViewBenchmark : GoIdeTestBase() {

    fun testApi() {
        val file = myFixture.configureByText("api.go", BenchmarkSupport.readGoroot("go/types/api.go")) as GoFile
        var nodes = 0
        BenchmarkSupport.run("GoStructureViewBenchmark.api", "ms", 1.0) {
            // One build is a few milliseconds: ten builds per iteration.
            repeat(10) {
                val model = GoStructureViewModel(file, myFixture.editor)
                try {
                    nodes = count(model.root)
                } finally {
                    Disposer.dispose(model)
                }
            }
        }
        assertTrue("structure view is empty", nodes > 50)
    }

    private fun count(element: StructureViewTreeElement): Int {
        element.presentation.presentableText
        return 1 + element.children.sumOf { count(it as StructureViewTreeElement) }
    }
}
