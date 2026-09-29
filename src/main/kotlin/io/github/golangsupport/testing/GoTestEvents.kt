package io.github.golangsupport.testing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import io.github.golangsupport.lang.GoDeclarationInfo
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoFile
import io.github.golangsupport.lang.GoFileStructure

enum class GoTestKind(val prefix: String) { TEST("Test"), BENCHMARK("Benchmark"), FUZZ("Fuzz"), EXAMPLE("Example") }

/** Which functions `go test` runs: `TestXxx`, `BenchmarkXxx`, `FuzzXxx`, `ExampleXxx` of a `_test.go` file, where `Xxx` does not start with a lower-case letter. */
object GoTests {
    fun kindOf(name: String): GoTestKind? = GoTestKind.entries.firstOrNull { kind ->
        name.startsWith(kind.prefix) && name.getOrNull(kind.prefix.length)?.isLowerCase() != true && (kind != GoTestKind.TEST || name != "TestMain")
    }

    fun kindOf(declaration: GoDeclarationInfo, fileName: String): GoTestKind? =
        if (declaration.kind == GoDeclarationKind.FUNCTION && fileName.endsWith(GoFile.TEST_SUFFIX)) kindOf(declaration.name) else null

    fun find(structure: GoFileStructure, fileName: String): List<Pair<GoDeclarationInfo, GoTestKind>> =
        structure.declarations.mapNotNull { declaration -> kindOf(declaration, fileName)?.let { declaration to it } }

    /** `-run` for exactly these functions; a subtest `TestA/case_1` is `^TestA$/^case_1$`, a level of the pattern per level of the name. */
    fun pattern(names: List<String>): String {
        if (names.size == 1) return names[0].split('/').joinToString("/") { "^" + escape(it) + "$" }
        return "^(" + names.map { it.substringBefore('/') }.distinct().joinToString("|", transform = ::escape) + ")$"
    }

    /** Not [Regex.escape]: its `\Q...\E` is not RE2. Function names need nothing, the names of subtests now and then do. */
    private fun escape(name: String): String = name.replace(SPECIAL) { "\\" + it.value }

    private val SPECIAL = Regex("""[.+*?()\[\]{}|^$\\]""")
}

/**
 * The stream of `go test -json` as TeamCity service messages, for the test tree of the platform. Ids make the tree: a package is a
 * suite, a test a node under its package, a subtest (`TestA/case`) a node under its parent test, which Go finishes after its children.
 */
class GoTestEvents(
    private val locationHint: (packagePath: String, test: String?) -> String? = { _, _ -> null },
    /** Every line a test or a package prints: the results of benchmarks are among them ([GoBenchmarks.parseLine]). */
    private val onOutput: (packagePath: String, line: String) -> Unit = { _, _ -> },
) {
    private val startedPackages = LinkedHashSet<String>()
    private val startedTests = HashSet<String>()

    /** Started and not finished yet, by package: a benchmark gets no `pass` of its own (seen live), the package ends it. */
    private val openTests = HashMap<String, LinkedHashMap<String, String>>()
    private val failedPackages = HashSet<String>()
    private val packageOutput = HashMap<String, StringBuilder>()

    /** The messages for one line of the output; null when the line is not an event of `go test -json` and belongs to the console as it is. */
    fun convert(line: String): List<String>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        val event = runCatching { JsonParser.parseString(trimmed) as? JsonObject }.getOrNull() ?: return null
        val action = event.string("Action") ?: return null
        val packagePath = event.string("Package") ?: event.string("ImportPath")?.substringBefore(' ') ?: return emptyList()
        val test = event.string("Test")
        val result = ArrayList<String>()
        if (startedPackages.add(packagePath)) {
            result += ServiceMessageBuilder.testSuiteStarted(packagePath).id(packagePath, ROOT).hint(locationHint(packagePath, null)).toString()
        }
        if (test == null) packageEvent(action, packagePath, event, result) else testEvent(action, packagePath, test, event, result)
        return result
    }

    private fun testEvent(action: String, packagePath: String, test: String, event: JsonObject, result: MutableList<String>) {
        val id = "$packagePath|$test"
        fun ensureStarted() {
            if (!startedTests.add(id)) return
            val parent = if ('/' in test) "$packagePath|${test.substringBeforeLast('/')}" else packagePath
            result += ServiceMessageBuilder.testStarted(test.substringAfterLast('/')).id(id, parent).hint(locationHint(packagePath, test)).toString()
            openTests.getOrPut(packagePath, ::LinkedHashMap)[id] = test.substringAfterLast('/')
        }
        if (action == "pass" || action == "fail" || action == "skip" || action == "bench") openTests[packagePath]?.remove(id)
        when (action) {
            "run" -> ensureStarted()
            "output" -> {
                val output = event.string("Output").orEmpty()
                onOutput(packagePath, output)
                // the framing of the verbose mode repeats what the tree shows
                if (FRAMING.any { output.trimStart().startsWith(it) }) return
                ensureStarted()
                result += ServiceMessageBuilder.testStdOut(test.substringAfterLast('/')).addAttribute("nodeId", id).addAttribute("out", output).toString()
            }
            "pass", "fail", "skip" -> {
                ensureStarted()
                val name = test.substringAfterLast('/')
                if (action == "fail") {
                    failedPackages += packagePath
                    result += ServiceMessageBuilder.testFailed(name).addAttribute("nodeId", id).addAttribute("message", "").toString()
                }
                if (action == "skip") result += ServiceMessageBuilder.testIgnored(name).addAttribute("nodeId", id).addAttribute("message", "").toString()
                result += ServiceMessageBuilder.testFinished(name).addAttribute("nodeId", id).addAttribute("duration", millis(event)).toString()
            }
            // a benchmark reports no `run`: its result line comes as output, then `bench`
            "bench" -> {
                ensureStarted()
                result += ServiceMessageBuilder.testFinished(test.substringAfterLast('/')).addAttribute("nodeId", id).addAttribute("duration", millis(event)).toString()
            }
        }
    }

    private fun packageEvent(action: String, packagePath: String, event: JsonObject, result: MutableList<String>) {
        when (action) {
            "output", "build-output" -> {
                val output = event.string("Output").orEmpty()
                onOutput(packagePath, output)
                packageOutput.getOrPut(packagePath, ::StringBuilder).append(output)
            }
            "pass", "fail", "skip", "build-fail" -> {
                // what is still open when the package ends (a benchmark, a test the package died in) ends with it: passed with the package, failed otherwise
                openTests.remove(packagePath)?.forEach { (id, name) ->
                    if (action != "pass") result += ServiceMessageBuilder.testFailed(name).addAttribute("nodeId", id).addAttribute("message", "").toString()
                    result += ServiceMessageBuilder.testFinished(name).addAttribute("nodeId", id).toString()
                }
                // a package that fails without a failed test did not compile, or died in TestMain or in a panic: what it printed is the reason
                if ((action == "fail" || action == "build-fail") && packagePath !in failedPackages) {
                    val id = "$packagePath|$PACKAGE_FAILURE"
                    result += ServiceMessageBuilder.testStarted(PACKAGE_FAILURE).id(id, packagePath).toString()
                    result += ServiceMessageBuilder.testFailed(PACKAGE_FAILURE).addAttribute("nodeId", id).addAttribute("message", "The package has failed")
                        .addAttribute("details", packageOutput[packagePath]?.toString().orEmpty()).toString()
                    result += ServiceMessageBuilder.testFinished(PACKAGE_FAILURE).addAttribute("nodeId", id).toString()
                }
                if (action != "build-fail") result += ServiceMessageBuilder.testSuiteFinished(packagePath).addAttribute("nodeId", packagePath).toString()
            }
        }
    }

    private fun ServiceMessageBuilder.id(id: String, parent: String): ServiceMessageBuilder = addAttribute("nodeId", id).addAttribute("parentNodeId", parent)
    private fun ServiceMessageBuilder.hint(hint: String?): ServiceMessageBuilder = if (hint == null) this else addAttribute("locationHint", hint)
    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun millis(event: JsonObject): String = ((event.get("Elapsed")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0) * 1000).toLong().toString()

    companion object {
        const val ROOT = "0"
        const val PACKAGE_FAILURE = "(package)"
        private val FRAMING = listOf("=== RUN", "=== PAUSE", "=== CONT", "=== NAME", "--- PASS", "--- FAIL", "--- SKIP", "--- BENCH")
    }
}
