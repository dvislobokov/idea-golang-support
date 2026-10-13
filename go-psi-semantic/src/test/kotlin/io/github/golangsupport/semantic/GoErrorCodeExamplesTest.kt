package io.github.golangsupport.semantic

import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.GoProjectModelTestBase
import io.github.golangsupport.project.ProjectTestUtil
import io.github.golangsupport.semantic.api.GoSemanticService
import java.nio.file.Files

/**
 * The examples of `$GOROOT/src/internal/types/errors/codes.go`, read as `codes_test.go` (TestErrorCodeExamples) reads them: the doc
 * comment of a code split at "Example:", `package p` prepended when missing. go/types reports an error with that code on each; the
 * checker must report something on each (a syntax error counts: `BadRecv`), except the examples of [NOT_CAUGHT] (it may only shrink: a
 * caught example fails the test until it is removed from the list). Coverage report: docs/compare/go-error-codes-*.md.
 */
class GoErrorCodeExamplesTest : GoProjectModelTestBase() {

    private companion object {
        /** Examples (`<code>_<n>`) the checker misses. */
        val NOT_CAUGHT = setOf(
            "InvalidDeclCycle_2", "InvalidInstanceCycle_1", "InvalidUnsafeSlice_4", "InvalidUntypedConversion_1",
            "TypeTooLarge_1", "TypeTooLarge_2",
        )
    }

    override fun setUp() {
        super.setUp()
        com.intellij.openapi.util.RecursionManager.disableAssertOnRecursionPrevention(testRootDisposable)
        com.intellij.openapi.util.RecursionManager.disableMissedCacheAssertions(testRootDisposable)
    }

    /** (`<code>_<n>`, example) in the order of codes.go. */
    private fun examples(): List<Pair<String, String>> {
        val src = Files.readString(ProjectTestUtil.goroot().resolve("src/internal/types/errors/codes.go"))
        val body = src.substring(src.indexOf("// The zero Code value"))
        val out = ArrayList<Pair<String, String>>()
        val comment = ArrayList<String>()
        for (raw in body.lines().drop(1)) {
            val line = raw.trim()
            if (line == ")") break
            if (line.startsWith("//")) { comment += line.removePrefix("//").removePrefix(" "); continue }
            if (line.isEmpty()) { if (comment.isNotEmpty()) comment += ""; continue }
            val name = Regex("""^(\w+)(\s+Code\s*=\s*iota)?$""").find(line)?.groupValues?.get(1)
            if (name != null && name != "_") {
                comment.joinToString("\n").split("Example:").drop(1).forEachIndexed { i, part -> out += "${name}_${i + 1}" to part.trimIndent().trim() }
            }
            comment.clear()
        }
        return out
    }

    fun testEveryCodeWithAnExampleIsCaught() {
        val semantic = GoSemanticService.getInstance(project)
        val missed = sortedSetOf<String>()
        examples().forEachIndexed { i, (example, text0) ->
            val text = if (text0.startsWith("package")) text0 else "package p\n\n$text0"
            val file = myFixture.addFileToProject("codes/ex$i/example.go", text + "\n") as GoFile
            val diagnostics = semantic.check(file).map { "[${it.code}] ${it.message.lineSequence().first()}" } +
                com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, com.intellij.psi.PsiErrorElement::class.java).map { "[syntax] ${it.errorDescription}" }
            println("$example: ${diagnostics.joinToString(" | ").ifEmpty { "-" }}")
            if (diagnostics.isEmpty()) missed += example
        }
        assertEquals("examples not caught (NOT_CAUGHT may only shrink)", NOT_CAUGHT.toSortedSet(), missed)
    }
}
