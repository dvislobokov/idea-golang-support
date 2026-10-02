package io.github.golangsupport.benchmark

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.lang.GoFileType

/**
 * Reformat Code (through `CodeStyleManager`) of `net/http/server.go` and `go/printer/nodes.go`,
 * both gofmt-clean. Every iteration reformats freshly parsed files (parsing is untimed). Unit: ms per MB.
 */
class GoFormatterBenchmark : GoIdeTestBase() {

    fun testReformat() {
        val sources = listOf("net/http/server.go", "go/printer/nodes.go").map { it.substringAfterLast('/') to BenchmarkSupport.readGoroot(it) }
        val megabytes = sources.sumOf { it.second.length } / 1_000_000.0
        var files: List<PsiFile> = emptyList()
        BenchmarkSupport.run(
            "GoFormatterBenchmark.reformat",
            "ms/MB",
            megabytes,
            prepare = {
                files = sources.map { (name, text) ->
                    PsiFileFactory.getInstance(project).createFileFromText(name, GoFileType, text, System.currentTimeMillis(), true).also { it.node }
                }
            },
        ) {
            WriteCommandAction.runWriteCommandAction(project) {
                for (file in files) CodeStyleManager.getInstance(project).reformat(file)
            }
        }
        // gofmt-clean input stays unchanged (the corpus gate owns exact parity; this guards the benchmark's input).
        for ((i, file) in files.withIndex()) assertEquals(sources[i].first, sources[i].second, file.text)
    }
}
