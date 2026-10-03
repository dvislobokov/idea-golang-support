package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.formatter.FormatterCorpusMetrics
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator
import io.github.golangsupport.project.api.GoBuildContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * False-positive gate of the data-flow inspections: runs the seven of them over the buildable non-test files of `$GOROOT/src`
 * (no `testdata`, files up to 1 MB). GOROOT is reviewed code, so the counts are mostly false positives: they are recorded in
 * `testData/metrics/goroot-src-flow.json` and may only decrease (`files`, `crashed` and `millis` aside). The first 20 reports of
 * each inspection go to `build/flow-corpus/goroot-src-flow-reports.txt` for review.
 *
 * `-Dgopsi.flow.corpus.filter=net/http` limits the walk to paths containing the text (no metrics are written then).
 */
class FlowCorpusTest : GoSemanticIdeTestBase() {

    override fun setUp() {
        super.setUp()
        com.intellij.openapi.util.RecursionManager.disableAssertOnRecursionPrevention(testRootDisposable)
        com.intellij.openapi.util.RecursionManager.disableMissedCacheAssertions(testRootDisposable)
    }

    fun testFlowInspectionsOverGorootSources() {
        val root = goroot().resolve("src")
        assertTrue("GOROOT/src not found: $root", Files.isDirectory(root))
        val filter = System.getProperty("gopsi.flow.corpus.filter")?.takeIf { it.isNotBlank() }
        val result = GoFlowCorpus.run(project, root, toolchain.buildContext) { rel -> filter == null || rel.contains(filter) }
        println(result.summary())
        val out = Path.of("build", "flow-corpus").also { Files.createDirectories(it) }
        Files.writeString(out.resolve("goroot-src-flow-reports.txt"), result.samples())
        if (filter == null) {
            val metrics = linkedMapOf("files" to result.files, "crashed" to result.crashed, "millis" to result.millis)
            for ((name, count) in result.counts) metrics[name] = count
            FormatterCorpusMetrics.check(Path.of(testDataRoot(), "metrics", "goroot-src-flow.json"), metrics, setOf("files", "millis"))
        }
    }
}

/** Runs the flow inspections over a source tree; shared by [FlowCorpusTest] and ad-hoc samples. */
internal object GoFlowCorpus {

    fun inspections(): List<LocalInspectionTool> = listOf(
        GoErrorOverwrittenInspection(), GoWrongErrorCheckedInspection(), GoNilErrorReturnInspection(), GoDeferBeforeErrorCheckInspection(),
        GoNilDereferenceInspection(), GoIneffectualAssignmentInspection(), GoLostCancelInspection(),
        GoBodyNotClosedInspection(), GoRowsNotClosedInspection(), GoLockNotReleasedInspection(), GoSendAfterCloseInspection(),
        GoWaitGroupAddInGoroutineInspection(), GoContextNotPropagatedInspection(),
        GoErrNilReturnedInspection(), GoShadowedErrorInspection(), GoResultUsedBeforeErrorCheckInspection(), GoImpossibleNilCheckInspection(),
        GoNilValueNilErrorInspection(), GoUnreachableCodeInspection(),
        // wave 4 checks without data flow (ide.inspections.lint): the same noise review
        io.github.golangsupport.ide.inspections.lint.GoSelfAssignmentInspection(), io.github.golangsupport.ide.inspections.lint.GoUnusedResultInspection(),
        io.github.golangsupport.ide.inspections.lint.GoDeferInLoopInspection(), io.github.golangsupport.ide.inspections.lint.GoCopyLocksInspection(),
        io.github.golangsupport.ide.inspections.lint.GoLoopClosureInspection(), io.github.golangsupport.ide.inspections.lint.GoTestingGoroutineInspection(),
        io.github.golangsupport.ide.inspections.lint.GoUnusedParameterInspection(),
    )

    class Result(val files: Long, val crashed: Long, val millis: Long, val counts: Map<String, Long>, val reports: Map<String, List<String>>, val crashes: List<String>) {
        fun summary(): String = buildString {
            append("GOROOT/src flow corpus\n")
            append("  files:   $files\n  crashed: $crashed ${crashes.joinToString(" | ") { it.take(200) }}\n  time:    $millis ms\n")
            for ((name, count) in counts) append("  ${name.padEnd(24)} $count\n")
        }

        fun samples(): String = buildString {
            for ((name, list) in reports) {
                append("== $name (${counts[name]})\n")
                list.forEach { append("  ").append(it).append('\n') }
            }
        }
    }

    fun run(project: Project, root: Path, context: GoBuildContext, include: (String) -> Boolean): Result {
        val tools = inspections()
        val counts = LinkedHashMap<String, Long>().apply { tools.forEach { put(it.shortName, 0L) } }
        val reports = LinkedHashMap<String, MutableList<String>>().apply { tools.forEach { put(it.shortName, ArrayList()) } }
        val crashes = ArrayList<String>()
        var files = 0L
        var crashed = 0L
        val start = System.currentTimeMillis()
        val paths = Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) && it.toString().endsWith(".go") && !it.toString().endsWith("_test.go") && Files.size(it) <= 1024 * 1024 }.sorted().toList()
        }
        for (path in paths) {
            val rel = root.relativize(path).toString().replace('\\', '/')
            if (rel.split('/').any { it == "testdata" || it.startsWith(".") || it.startsWith("_") } || !include(rel)) continue
            val vf = LocalFileSystem.getInstance().findFileByNioFile(path) ?: continue
            val text = String(vf.contentsToByteArray(), vf.charset)
            if (!GoBuildConstraintEvaluator.matchFile(vf.name, text, context)) continue
            val psi = PsiManager.getInstance(project).findFile(vf) as? GoFile ?: continue
            files++
            val document = psi.viewProvider.document
            for (tool in tools) {
                val holder = ProblemsHolder(InspectionManager.getInstance(project), psi, false)
                try {
                    val visitor = tool.buildVisitor(holder, false)
                    psi.accept(object : PsiRecursiveElementWalkingVisitor() {
                        override fun visitElement(element: PsiElement) {
                            element.accept(visitor)
                            super.visitElement(element)
                        }
                    })
                } catch (e: Throwable) {
                    if (e is ProcessCanceledException) throw e
                    crashed++
                    if (crashes.size < 10) crashes += "$rel ${tool.shortName}: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}"
                    continue
                }
                for (p in holder.results) {
                    counts.merge(tool.shortName, 1, Long::plus)
                    val list = reports.getValue(tool.shortName)
                    if (list.size < 20) {
                        val offset = p.psiElement?.textRange?.startOffset ?: 0
                        val line = (document?.getLineNumber(offset) ?: -1) + 1
                        val source = document?.let { d -> d.charsSequence.subSequence(d.getLineStartOffset(line - 1), d.getLineEndOffset(line - 1)).toString().trim() } ?: ""
                        list += "$rel:$line ${p.descriptionTemplate} | $source"
                    }
                }
            }
        }
        return Result(files, crashed, System.currentTimeMillis() - start, counts, reports, crashes)
    }
}
