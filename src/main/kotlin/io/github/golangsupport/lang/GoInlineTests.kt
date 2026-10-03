package io.github.golangsupport.lang

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoSignatureType

/**
 * The rules of the catalogue past the first batch that belong to tests: the values of a test (A56, A58–A61), the table of cases
 * (A57) and the whole body of a test (G8) or a benchmark (G9), `t.Parallel()` (F14), the arguments of `assert.Equal` (C14).
 */
object GoInlineTests {
    private fun inTest(p: GoInlinePlace): Boolean = p.original.isTestFile

    /** The `*testing.T` (or B) parameter of the function around, by its kind. */
    private fun testingParameter(p: GoInlinePlace, kinds: Set<String>): GoInlineVariable? =
        p.variables.firstOrNull { v -> v.isParameter && p.standard(v)?.let { it.first == "testing" && it.third && it.second in kinds } == true }

    /** The function of the package a `TestFoo` (`BenchmarkFoo`) is named for. */
    private fun tested(p: GoInlinePlace, prefix: String): GoFunctionDeclaration? {
        val test = (p.owner as? GoFunctionDeclaration ?: PsiTreeUtil.getParentOfType(p.leaf, GoFunctionDeclaration::class.java))?.name ?: return null
        if (!test.startsWith(prefix) || test.length <= prefix.length) return null
        val name = test.removePrefix(prefix).substringBefore('_').takeIf { it.isNotEmpty() } ?: return null
        return (p.packageFunctions[name] ?: p.packageFunctions[name.replaceFirstChar(Char::lowercaseChar)]) as? GoFunctionDeclaration
    }

    /** The variable of the loop over the cases around the slot (`tt` of `for _, tt := range tests`) and the names of its fields. */
    private fun caseOfLoop(p: GoInlinePlace): Pair<String, List<String>>? {
        val loop = PsiTreeUtil.getParentOfType(p.leaf, GoForStatement::class.java) ?: return null
        val case = loop.rangeClause?.varDefinitionList?.lastOrNull() ?: return null
        val struct = p.structOf(p.typeOf(case)) ?: return null
        return (case.name ?: return null) to struct.fields.map { it.name }
    }

    // --- values (A56, A58, A59, A60, A61) ---

    private val WANT_NAMES = setOf("want", "expected", "exp", "wantErr", "expectedErr")

    /** `want :=` in the loop over the cases, which have a field `want`: `tt.want`. */
    fun a56Want(p: GoInlinePlace, name: String): String? {
        if (name !in WANT_NAMES || !inTest(p)) return null
        val (case, fields) = caseOfLoop(p) ?: return null
        return fields.singleOrNull { it.equals(name, ignoreCase = true) }?.let { "$case.$it" }
    }

    /** `srv :=` in a test with a handler in scope (what has `ServeHTTP`, an `http.HandlerFunc`): `httptest.NewServer(handler)`. */
    fun a58Server(p: GoInlinePlace, name: String): String? {
        if (name !in setOf("srv", "server", "ts", "testServer") || !inTest(p)) return null
        val handlers = p.variables.filter { v ->
            p.isStandard(v.type, "net/http", "HandlerFunc") || runCatching { p.semantic.methodsOf(v.type).any { it.name == "ServeHTTP" } }.getOrDefault(false)
        }
        val handler = handlers.singleOrNull() ?: return null
        return "${p.qualifier("net/http/httptest")}.NewServer(${handler.name})"
    }

    /** `rec :=` in a test: `httptest.NewRecorder()`; `w :=` too when it is used below as a recorder. */
    fun a59Recorder(p: GoInlinePlace, name: String): String? {
        if (!inTest(p)) return null
        val byName = name in setOf("rec", "rr", "recorder", "resp", "res")
        if (!byName) {
            if (name != "w") return null
            val uses = p.definitionOf(name)?.let(p::usesOf) ?: return null
            if (uses.selected.none { it == "Code" || it == "Body" || it == "Result" } && uses.passedAs.none { p.isStandard(it, "net/http", "ResponseWriter") }) return null
        } else if (name == "resp" || name == "res") {
            // `resp` is a response as often: a recorder only when it is read as one below
            val uses = p.definitionOf(name)?.let(p::usesOf) ?: return null
            if (uses.selected.none { it == "Code" || it == "Result" } || uses.selected.contains("StatusCode")) return null
        }
        return "${p.qualifier("net/http/httptest")}.NewRecorder()"
    }

    /** `req :=` (no error) in a test: `httptest.NewRequest(http.MethodGet, "/", nil)`. */
    fun a60Request(p: GoInlinePlace, name: String): String? {
        if (name != "req" && name != "request" || !inTest(p) || p.slot.names.size != 1) return null
        return "${p.qualifier("net/http/httptest")}.NewRequest(${p.qualifier("net/http")}.MethodGet, \"/\", nil)"
    }

    /** `dir :=` in a test: `t.TempDir()`. */
    fun a61TempDir(p: GoInlinePlace, name: String): String? {
        if (name !in setOf("dir", "tmp", "tmpDir", "tmpdir", "tempDir") || !inTest(p)) return null
        val t = testingParameter(p, setOf("T", "B", "F")) ?: return null
        return "${t.name}.TempDir()"
    }

    // --- the table of cases (A57) and the test (G8) ---

    /** The fields of the table of cases for [signature]: the name, the parameters, `want` and `wantErr`; null when it cannot be one. */
    private fun tableFields(p: GoInlinePlace, signature: GoSignatureType): List<Pair<String, String>>? {
        val fields = ArrayList<Pair<String, String>>()
        fields += "name" to "string"
        if (signature.variadic) return null
        for (parameter in signature.params) {
            val name = parameter.name?.takeIf { it.isNotEmpty() && it != "_" } ?: return null
            if (name in setOf("name", "want", "wantErr", "tt", "tests", "got", "err", "t")) return null
            fields += name to (p.typeText(parameter.type) ?: return null)
        }
        val results = signature.results.map { it.type }
        val error = results.lastOrNull()?.let(GoReturnValues::isError) == true
        val values = if (error) results.dropLast(1) else results
        if (values.size > 1) return null
        values.singleOrNull()?.let { fields += "want" to (p.typeText(it) ?: return null) }
        if (error) fields += "wantErr" to "bool"
        return fields
    }

    /** The table: `[]struct {` + the fields aligned as gofmt has them + `}{` + one case + `}`; [indent] is that of its first line. */
    private fun table(p: GoInlinePlace, fields: List<Pair<String, String>>, indent: String): String {
        val width = fields.maxOf { it.first.length }
        val inner = indent + p.unit
        val lines = fields.joinToString("\n") { (name, type) -> "$inner${name.padEnd(width)} $type" }
        return "[]struct {\n$lines\n$indent}{\n$inner{name: \"\"},\n$indent}"
    }

    /** `tests :=` at the start of `TestFoo`: the table of cases for `Foo`. */
    fun a57Table(p: GoInlinePlace, name: String): String? {
        if (name !in setOf("tests", "cases", "testCases", "tcs", "tt") || !inTest(p) || p.slot.names.size != 1) return null
        val function = tested(p, "Test") ?: return null
        val signature = p.typeOf(function) as? GoSignatureType ?: return null
        val fields = tableFields(p, signature) ?: return null
        return table(p, fields, p.blockIndent)
    }

    /** The empty body of `TestFoo`: the table of cases for `Foo` and the loop that runs them. */
    fun g8TestBody(p: GoInlinePlace): String? {
        if (!inTest(p)) return null
        val t = testingParameter(p, setOf("T"))?.name ?: return null
        val function = tested(p, "Test") ?: return null
        val name = function.name ?: return null
        val signature = p.typeOf(function) as? GoSignatureType ?: return null
        val fields = tableFields(p, signature) ?: return null
        val indent = p.blockIndent
        val u = p.unit
        val call = "$name(${signature.params.joinToString(", ") { "tt.${it.name}" }})"
        val want = fields.any { it.first == "want" }
        val wantErr = fields.any { it.first == "wantErr" }
        val body = ArrayList<String>()
        body += when {
            want && wantErr -> "got, err := $call"
            want -> "got := $call"
            wantErr -> "err := $call"
            else -> call
        }
        if (wantErr) {
            body += "if (err != nil) != tt.wantErr {"
            body += "$u$t.Fatalf(\"$name() error = %v, wantErr %v\", err, tt.wantErr)"
            body += "}"
        }
        if (want) {
            val result = signature.results.first().type
            body += if (result.underlying() is GoBasicType) "if got != tt.want {" else "if !${p.qualifier("reflect")}.DeepEqual(got, tt.want) {"
            body += "$u$t.Errorf(\"$name() = %v, want %v\", got, tt.want)"
            body += "}"
        }
        val testing = p.qualifier("testing")
        val run = body.joinToString("") { "\n$indent$u$u$it" }
        return "tests := ${table(p, fields, indent)}\n${indent}for _, tt := range tests {\n$indent$u$t.Run(tt.name, func($t *$testing.T) {$run\n$indent$u})\n$indent}"
    }

    /** The empty body of `BenchmarkFoo`: `for b.Loop() {` (Go 1.24 and later; the loop to `b.N` before) with the call of `Foo` when it takes nothing. */
    fun g9BenchmarkBody(p: GoInlinePlace): String? {
        if (!inTest(p)) return null
        val b = testingParameter(p, setOf("B"))?.name ?: return null
        val benchmark = (p.owner as? GoFunctionDeclaration)?.name?.takeIf { it.startsWith("Benchmark") } ?: return null
        val indent = p.blockIndent
        val function = tested(p, "Benchmark")
        val call = function?.takeIf { (p.typeOf(it) as? GoSignatureType)?.params?.isEmpty() == true }?.name?.let { "$it()" } ?: ""
        if (benchmark == "Benchmark") return null
        val loop = if (p.goVersionAtLeast(24)) "for $b.Loop() {" else "for i := 0; i < $b.N; i++ {"
        return "$loop\n$indent${p.unit}$call\n$indent}"
    }

    // --- t.Parallel (F14) ---

    /** The first line of `TestX` when every other test of the file starts with `t.Parallel()`: `t.Parallel()`. */
    fun f14Parallel(p: GoInlinePlace): String? {
        if (!inTest(p)) return null
        val function = p.owner as? GoFunctionDeclaration ?: return null
        if (function.name?.startsWith("Test") != true || function.name == "TestMain") return null
        val t = testingParameter(p, setOf("T"))?.name ?: return null
        if (function.block?.text?.contains(".Parallel()") == true) return null
        val others = p.file.functions.filter { it != function && it.name?.startsWith("Test") == true && it.name != "TestMain" }
        if (others.isEmpty()) return null
        if (!others.all { it.block?.statementList?.firstOrNull()?.text?.endsWith(".Parallel()") == true }) return null
        return "$t.Parallel()"
    }

    // --- assert.Equal (C14) ---

    /** `assert.Equal(t, |` (or `require`) with `want` and `got` in scope: `tt.want, got`, expected first. */
    fun c14AssertEqual(p: GoInlinePlace, call: GoCallExpr, index: Int): String? {
        val callee = call.expression as? GoReferenceExpression ?: return null
        if (callee.identifier?.text !in setOf("Equal", "EqualValues", "Exactly") || index != 1 || call.arguments.size != 2) return null
        if (callee.expression?.text !in setOf("assert", "require")) return null
        val got = p.variables.filter { it.name in setOf("got", "actual") }.singleOrNull()?.name ?: return null
        val case = caseOfLoop(p)
        val want = case?.let { (name, fields) -> fields.singleOrNull { it.equals("want", true) || it.equals("expected", true) }?.let { "$name.$it" } }
            ?: p.variables.filter { it.name in setOf("want", "expected") }.singleOrNull()?.name ?: return null
        return "$want, $got"
    }
}
