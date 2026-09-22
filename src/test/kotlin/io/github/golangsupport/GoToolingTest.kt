package io.github.golangsupport

import io.github.golangsupport.build.GoBuildOutputParser
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.lint.GoLintIssue
import io.github.golangsupport.lint.GoLintOutput
import io.github.golangsupport.mod.GoModFile
import io.github.golangsupport.run.GoEvaluate
import io.github.golangsupport.run.GoOutputLocations
import io.github.golangsupport.run.DlvDap
import io.github.golangsupport.run.GoLaunchArguments
import io.github.golangsupport.run.HitCondition
import io.github.golangsupport.testing.GoTestEvents
import io.github.golangsupport.testing.GoTestKind
import io.github.golangsupport.testing.GoTests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoToolingTest {
    @Test fun goMod() {
        val mod = GoModFile.parse(
            """
            module example.com/app // the app

            go 1.24
            toolchain go1.24.7

            require (
                github.com/BurntSushi/toml v1.3.2
                golang.org/x/text v0.14.0 // indirect
            )
            require github.com/x/y v0.1.0

            replace github.com/x/y => ../y
            replace golang.org/x/text v0.14.0 => github.com/fork/text v0.14.1
            tool golang.org/x/tools/cmd/stringer
            """.trimIndent(),
        )
        assertEquals("example.com/app", mod.modulePath)
        assertEquals("1.24", mod.goVersion)
        assertEquals("go1.24.7", mod.toolchain)
        assertEquals(listOf("github.com/BurntSushi/toml", "github.com/x/y"), mod.directRequires.map { it.path })
        assertEquals(listOf("golang.org/x/text"), mod.indirectRequires.map { it.path })
        assertEquals(6, mod.requires[0].line)
        assertTrue(mod.replacementOf(mod.requires[2])!!.isLocal)
        assertEquals("github.com/fork/text", mod.replacementOf(mod.requires[1])!!.newPath)
        assertEquals(listOf("golang.org/x/tools/cmd/stringer"), mod.tools)
        assertEquals("github.com/!burnt!sushi/toml@v1.3.2", GoModFile.cachePath("github.com/BurntSushi/toml", "v1.3.2"))
    }

    @Test fun goWork() = assertEquals(listOf("./api", "./worker"), GoModFile.parse("go 1.24\n\nuse (\n\t./api\n\t./worker\n)\n").uses)

    @Test fun buildOutput() {
        val message = GoBuildOutputParser.parseLine("store\\order.go:12:5: undefined: foo")!!
        assertEquals(listOf("store\\order.go", 12, 5, "undefined: foo"), listOf(message.file, message.line, message.column, message.text))
        assertEquals("C:\\src\\main.go", GoBuildOutputParser.parseLine("C:\\src\\main.go:7:2: \"os\" imported and not used")!!.file)
        assertEquals(1, GoBuildOutputParser.parseLine("vet: ./a.go:3: oops")!!.column)
        assertNull(GoBuildOutputParser.parseLine("# example.com/app/store"))
        assertEquals("example.com/app/store", GoBuildOutputParser.packageOf("# example.com/app/store [example.com/app/store.test]"))
    }

    @Test fun goEnv() {
        val environment = GoEnvironment.parse("""{"GOPATH":"C:\\Users\\me\\go","GOBIN":"","GOROOT":"C:\\Go","GOVERSION":"go1.24.7","GOMODCACHE":"D:\\mod"}""")
        assertEquals("1.24.7", environment.goVersion)
        assertEquals("D:\\mod", environment.goModCache)
        assertTrue(environment.binDirectory!!.path.replace('\\', '/').endsWith("me/go/bin"))
    }

    @Test fun testFunctions() {
        assertEquals(GoTestKind.TEST, GoTests.kindOf("TestTotal"))
        assertEquals(GoTestKind.EXAMPLE, GoTests.kindOf("Example_suffix"))
        assertEquals(GoTestKind.BENCHMARK, GoTests.kindOf("BenchmarkX"))
        assertNull(GoTests.kindOf("Testing"))
        assertNull(GoTests.kindOf("TestMain"))
        assertEquals("^TestA$/^two_items\\.x$", GoTests.pattern(listOf("TestA/two_items.x")))
        assertEquals("^(TestA|TestB)$", GoTests.pattern(listOf("TestA/x", "TestB", "TestA/y")))
    }

    @Test fun testEvents() {
        val events = GoTestEvents { packagePath, test -> "hint:$packagePath|${test.orEmpty()}" }
        fun event(action: String, test: String? = null, extra: String = "") =
            events.convert("""{"Action":"$action","Package":"example.com/p"${if (test == null) "" else ""","Test":"$test""""}$extra}""")!!
        assertNull(events.convert("FAIL\texample.com/p [build failed]"))
        assertTrue(event("start").single().startsWith("##teamcity[testSuiteStarted name='example.com/p' nodeId='example.com/p' parentNodeId='0'"))
        // `|` is the escape character of service messages, hence `||`
        assertTrue(event("run", "TestA").single().contains("testStarted name='TestA' nodeId='example.com/p||TestA' parentNodeId='example.com/p'"))
        assertTrue(event("run", "TestA/sub").single().contains("name='sub' nodeId='example.com/p||TestA/sub' parentNodeId='example.com/p||TestA'"))
        assertTrue(event("output", "TestA/sub", ""","Output":"=== RUN   TestA/sub\n"""").isEmpty())
        assertTrue(event("output", "TestA/sub", ""","Output":"    a_test.go:5: boom\n"""").single().contains("testStdOut"))
        val failed = event("fail", "TestA/sub", ""","Elapsed":0.25""")
        assertTrue(failed[0].contains("testFailed") && failed[1].contains("testFinished") && failed[1].contains("duration='250'"))
        assertTrue(event("skip", "TestB").any { it.contains("testIgnored") })
        // the package has a failed test: its own failure adds nothing
        assertEquals(1, event("fail").size)
    }

    @Test fun packageThatDoesNotCompile() {
        val events = GoTestEvents()
        events.convert("""{"Action":"start","Package":"p"}""")
        events.convert("""{"Action":"output","Package":"p","Output":"./a.go:3:2: undefined: x\n"}""")
        val failed = events.convert("""{"Action":"fail","Package":"p"}""")!!
        assertTrue(failed.any { it.contains("testFailed") && it.contains("undefined: x") })
        assertTrue(failed.last().contains("testSuiteFinished"))
    }

    @Test fun launchArguments() {
        val run = GoLaunchArguments.build(false, "C:/app/cmd", listOf("-v"), null, false, null, mapOf("A" to "1"), listOf("-tags=x", "-race"))
        assertEquals("debug", run["mode"])
        assertEquals(listOf("-v"), run["args"])
        assertEquals("-tags=x -race", run["buildFlags"])
        assertEquals("remote", run["outputMode"])
        assertFalse("request" in run)
        // delve builds into a temp binary, not into the package directory, so nothing is left in the project
        val output = run["output"] as String
        assertTrue("__debug_bin" in output)
        assertFalse(output.startsWith("C:/app/cmd"))
        val test = GoLaunchArguments.build(true, "C:/app/store", emptyList(), "^TestA$", false, null, emptyMap(), emptyList())
        assertEquals("test", test["mode"])
        assertEquals(listOf("-test.v", "-test.run", "^TestA$"), test["args"])
    }

    @Test fun delve() {
        assertEquals("127.0.0.1" to 63940, DlvDap.listeningAt("DAP server listening at: 127.0.0.1:63940"))
        assertNull(DlvDap.listeningAt("2026-09-21T17:39:33+03:00 debug layer=dap DAP server pid = 36168"))
        assertTrue("--check-go-version=false" in DlvDap.arguments(log = false, anyGoVersion = true))
        assertTrue(DlvDap.arguments(log = true, anyGoVersion = false).none { it.startsWith("--log-dest") })
    }

    @Test fun hitConditions() {
        assertTrue(listOf("", "5", ">= 3", "%10", "!=2").all(HitCondition::isValid))
        assertFalse(HitCondition.isValid("abc") || HitCondition.isValid("0") || HitCondition.isValid("=> 3"))
        assertEquals(">= 3", HitCondition.normalize(">=3"))
        assertEquals("5", HitCondition.normalize(" 5 "))
        assertNull(HitCondition.normalize(""))
    }

    @Test fun lint() {
        assertEquals(2, GoLintOutput.majorVersion("golangci-lint has version 2.13.2 built with go1.26.8"))
        assertEquals(1, GoLintOutput.majorVersion("golangci-lint has version v1.64.8 built with go1.24"))
        assertTrue("--output.json.path=stdout" in GoLintOutput.arguments(2, listOf("a", "b"), "./store"))
        assertEquals(listOf("run", "--out-format=json", "--issues-exit-code=0", "--build-tags=a,b", "./store"), GoLintOutput.arguments(1, listOf("a", "b"), "./store"))
        val report = """level=warning msg="something"
{"Issues":[{"FromLinter":"errcheck","Text":"Error return value of `os.Open` is not checked","Severity":"","Pos":{"Filename":"store\\lint.go","Offset":50,"Line":6,"Column":9}},{"FromLinter":"typecheck","Text":"boom","Severity":"error","Pos":{"Filename":"a.go","Line":1,"Column":0}}],"Report":{}}"""
        val issues = GoLintOutput.parse(report)
        assertEquals(GoLintIssue("store\\lint.go", 6, 9, "Error return value of `os.Open` is not checked", "errcheck", false), issues[0])
        assertTrue(issues[1].isError)
        assertTrue(GoLintOutput.parse("not json").isEmpty())
        // the word at the column, or the line without its indent
        assertEquals(1..7, GoLintOutput.rangeInLine("\tos.Open(\"x\")", 2))
        assertEquals(1..12, GoLintOutput.rangeInLine("\tos.Open(\"x\")  ", 0))
        // errcheck names the parenthesis of the call: the call is underlined from its name
        assertEquals(1..12, GoLintOutput.rangeInLine("\tos.Open(\"x\")", 9))
    }

    @Test fun outputLocations() {
        fun paths(line: String) = GoOutputLocations.find(line).map { Triple(it.path, it.line, it.column) }
        assertEquals(listOf(Triple("order_test.go", 39, 1)), paths("    order_test.go:39: about to fail"))
        assertEquals(listOf(Triple("C:/app/store/order.go", 41, 1)), paths("\tC:/app/store/order.go:41 +0x1d"))
        assertEquals(listOf(Triple("./cmd/shop/main.go", 7, 2)), paths("./cmd/shop/main.go:7:2: \"os\" imported and not used"))
        assertEquals(listOf(Triple("/home/me/app/main.go", 12, 1)), paths("panic at (/home/me/app/main.go:12)"))
        assertTrue(paths("nothing here: go:12").isEmpty())
        val location = GoOutputLocations.find("    order_test.go:39: x").single()
        assertEquals("order_test.go:39", "    order_test.go:39: x".substring(location.start, location.end))
    }

    @Test fun functionCallsAreEvaluatedWithCall() {
        assertEquals("call order.Total()", GoEvaluate.expression("order.Total()"))
        assertEquals("call f(a, g(b))", GoEvaluate.expression(" f(a, g(b)) "))
        assertEquals("len(items)", GoEvaluate.expression("len(items)"))
        assertEquals("int64(x)", GoEvaluate.expression("int64(x)"))
        assertEquals("call f()", GoEvaluate.expression("call f()"))
        assertEquals("order.Currency", GoEvaluate.expression("order.Currency"))
        assertEquals("a + f(x)", GoEvaluate.expression("a + f(x)"))
    }
}
