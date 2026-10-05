package io.github.golangsupport.ide.inspections.unused

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.inspections.declarations.GoExportedFuncWithUnexportedTypeInspection
import io.github.golangsupport.ide.inspections.declarations.GoRedundantConversionInspection
import io.github.golangsupport.ide.inspections.flow.GoConstantConditionInspection
import io.github.golangsupport.ide.inspections.flow.GoDivisionByZeroInspection
import io.github.golangsupport.ide.inspections.lint.GoUnusedParameterInspection
import java.io.File

/**
 * The G7 inspections (data flow, unused) over the GoLand probe files laid out as GoLand saw them (`internal/probe`, `internal/probeerr` of an
 * application module): exactly GoLand's findings of the same ids (`docs/goland-analysis/dumps/highlight-internal-*.txt`), and the exported
 * declarations of an internal package checked the way GoLand checks them.
 */
class GoG7ProbeTest : GoSemanticIdeTestBase() {

    private lateinit var root: VirtualFile

    private val tools: List<LocalInspectionTool>
        get() = listOf(
            GoUnusedFunctionInspection(), GoUnusedExportedFunctionInspection(), GoUnusedTypeInspection(), GoUnusedExportedTypeInspection(),
            GoUnusedConstInspection(), GoUnusedGlobalVariableInspection(), GoUnusedParameterInspection(), GoConstantConditionInspection(),
            GoDivisionByZeroInspection(), GoExportedFuncWithUnexportedTypeInspection(), GoRedundantConversionInspection(),
        )

    private fun probe(relative: String): String = File(File(testDataRoot()).parentFile, "tools/ui-robot/goland/probe/$relative").readText().replace("\r\n", "\n")

    private fun inProject(files: Map<String, String>, body: () -> Unit) {
        val tmp = FileUtil.createTempDirectory("gopsi-g7", null, true)
        for ((path, text) in files) File(tmp, path).also { it.parentFile.mkdirs() }.writeText(text)
        VfsRootAccess.allowRootAccess(testRootDisposable, tmp.path)
        root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(myFixture.module, root)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            body()
        } finally {
            FileDocumentManager.getInstance().saveAllDocuments()
            PsiTestUtil.removeContentEntry(myFixture.module, root)
        }
    }

    /** `line: id: message «text»` of every problem of [tools] in [path], sorted by position. */
    private fun problems(path: String, tools: List<LocalInspectionTool> = this.tools): List<String> {
        myFixture.enableInspections(*tools.toTypedArray())
        myFixture.configureFromExistingVirtualFile(root.findFileByRelativePath(path) ?: error("no $path"))
        val document = myFixture.editor.document
        val ids = tools.map { it.shortName }.toSet()
        return myFixture.doHighlighting(HighlightSeverity.INFORMATION).filter { it.inspectionToolId in ids }.sortedBy { it.startOffset }
            .map { "${document.getLineNumber(it.startOffset) + 1}: ${it.inspectionToolId}: ${it.description} «${it.text}»" }
    }

    private val playground = mapOf(
        "go.mod" to "module example.com/playground\n\ngo 1.24\n",
        "cmd/check/main.go" to "package main\n\nfunc main() {}\n",
    )

    fun testProbeFilesGiveGoLandsFindings() = inProject(playground + mapOf(
        "internal/probe/analysis.go" to probe("analysis.go"),
        "internal/probe/analysis_test.go" to probe("analysis_test.go"),
        "internal/probeerr/broken.go" to probe("probeerr/broken.go"),
    )) {
        assertEquals(
            listOf(
                "23: GoUnusedConst: Unused constant 'Debug' «Debug»",
                "24: GoUnusedConst: Unused constant 'Info' «Info»",
                "25: GoUnusedConst: Unused constant 'Warn' «Warn»",
                "29: GoUnusedConst: Unused constant 'MaxItems' «MaxItems»",
                "161: GoUnusedFunction: Unused function 'Analysis' «Analysis»",
                "200: GoUnusedFunction: Unused function 'Probes' «Probes»",
                "200: GoUnusedParameter: Unused parameter 'c Circle' «c Circle»",
                "200: GoUnusedParameter: Unused parameter 'sq *Square' «sq *Square»",
                "200: GoUnusedParameter: Unused parameter 'shapes []Shape' «shapes []Shape»",
                "200: GoUnusedParameter: Unused parameter 'names []string' «names []string»",
                "200: GoUnusedParameter: Unused parameter 'ch chan int' «ch chan int»",
                "200: GoUnusedParameter: Unused parameter 'err error' «err error»",
                "200: GoUnusedParameter: Unused parameter 'lvl Level' «lvl Level»",
                "211: GoUnusedFunction: Unused function 'Returns' «Returns»",
                "211: GoUnusedParameter: Unused parameter 'name string' «name string»",
            ),
            problems("internal/probe/analysis.go"),
        )
        assertEquals(emptyList<String>(), problems("internal/probe/analysis_test.go"))
        assertEquals(listOf("23: GoUnusedFunction: Unused function 'Broken' «Broken»"), problems("internal/probeerr/broken.go"))
    }

    fun testLibraryPackageExportsStayQuiet() = inProject(mapOf(
        "go.mod" to "module example.com/g7lib\n\ngo 1.24\n",
        "api/api.go" to "package api\n\nconst Max = 1\n\nvar Global = 2\n\nfunc Public(x int) {}\n\nfunc private() {}\n",
    )) {
        assertEquals(listOf("9: GoUnusedFunction: Unused function 'private' «private»"), problems("api/api.go"))
    }
}
