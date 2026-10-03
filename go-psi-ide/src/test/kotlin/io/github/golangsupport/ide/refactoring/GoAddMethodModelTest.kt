package io.github.golangsupport.ide.refactoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure model of the Add Method to Interface dialog ([GoAddMethodModel]) and the one-line form of [GoAddMethodOptions]. */
class GoAddMethodModelTest {

    private val model = GoAddMethodModel("Store", setOf("Get", "Close"))

    private fun p(name: String, type: String) = GoChangeParameter(name, type)

    private fun r(name: String, type: String) = GoChangeResult(name, type)

    private fun problem(name: String, params: List<GoChangeParameter> = emptyList(), results: List<GoChangeResult> = emptyList()) =
        model.problem(GoAddMethodOptions(name, params, results))

    @Test
    fun validMethod() {
        assertNull(problem("Delete", listOf(p("ctx", "context.Context"), p("id", "string")), listOf(r("", "error"))))
        assertNull(problem("Delete", listOf(p("", "string"), p("", "...int"))))
        assertNull(problem("Load", results = listOf(r("n", "int"), r("err", "error"))))
        assertNull(problem("Load", listOf(p("_", "int"), p("x", "int"))))
    }

    @Test
    fun name() {
        assertEquals(GoAddMethodProblem("Enter the method name", GoAddMethodField.NAME, silent = true), problem("  "))
        assertEquals(GoAddMethodProblem("'1x' is not a valid method name", GoAddMethodField.NAME), problem("1x"))
        assertEquals("'_' is not a valid method name", problem("_")?.message)
        assertEquals("'func' is not a valid method name", problem("func")?.message)
        assertEquals(GoAddMethodProblem("Interface Store already has a method Get", GoAddMethodField.NAME), problem("Get"))
    }

    @Test
    fun parameters() {
        assertEquals(GoAddMethodProblem("Only the last parameter can be variadic", GoAddMethodField.PARAMETERS), problem("M", listOf(p("a", "...int"), p("b", "int"))))
        assertEquals("Either every parameter has a name or none has", problem("M", listOf(p("a", "int"), p("", "string")))?.message)
        assertEquals("'a-b' is not a valid parameter name", problem("M", listOf(p("a-b", "int")))?.message)
        assertEquals("Parameter a has no type", problem("M", listOf(p("a", " ")))?.message)
        assertEquals("Parameter #2 has no type", problem("M", listOf(p("", "int"), p("", "...")))?.message)
        assertEquals("Parameter a is declared twice", problem("M", listOf(p("a", "int"), p("a", "string")))?.message)
    }

    @Test
    fun results() {
        assertEquals(GoAddMethodProblem("A result cannot be variadic", GoAddMethodField.RESULTS), problem("M", results = listOf(r("", "...int"))))
        assertEquals("Either every result has a name or none has", problem("M", results = listOf(r("n", "int"), r("", "error")))?.message)
        assertEquals("Result #1 has no type", problem("M", results = listOf(r("", "")))?.message)
        assertEquals(GoAddMethodProblem("x is declared twice", GoAddMethodField.RESULTS), problem("M", listOf(p("x", "int")), listOf(r("x", "int"))))
    }

    @Test
    fun exportedHint() {
        assertEquals("delete is unexported: types outside the package will no longer be able to implement Store", model.hint("delete"))
        assertNull(model.hint("Delete"))
        assertNull(model.hint(""))
        assertNull(GoAddMethodModel("store", emptySet()).hint("delete"))
    }

    @Test
    fun preview() {
        assertEquals("Delete(ctx context.Context, id string) error", model.preview(GoAddMethodOptions("Delete", listOf(p("ctx", "context.Context"), p("id", "string")), listOf(r("", "error")))))
        assertEquals("Load() (n int, err error)", model.preview(GoAddMethodOptions("Load", results = listOf(r("n", "int"), r("err", "error")))))
        assertEquals("?(int)", model.preview(GoAddMethodOptions("", listOf(p("", "int")))))
    }

    @Test
    fun optionsFromOneLine() {
        assertEquals(
            GoAddMethodOptions("Delete", listOf(p("ctx", "context.Context"), p("id", "string")), listOf(r("", "error")), delegate = false),
            GoAddMethodOptions.of("Delete(ctx context.Context, id string) error", delegate = false),
        )
        assertEquals(GoAddMethodOptions("M", listOf(p("a", "int"), p("b", "int"), p("rest", "...string")), listOf(r("n", "int"), r("err", "error"))),
            GoAddMethodOptions.of("M(a, b int, rest ...string) (n int, err error)"))
        assertEquals(GoAddMethodOptions("M", listOf(p("", "func(int) error"), p("", "map[string]int")), listOf(r("", "int"), r("", "error"))),
            GoAddMethodOptions.of("M(func(int) error, map[string]int) (int, error)"))
        assertEquals("Delete(ctx context.Context, id string) error", GoAddMethodOptions.of("Delete(ctx context.Context, id string) error").signature)
        assertEquals(GoAddMethodOptions("not a method"), GoAddMethodOptions.of("not a method"))
    }

    @Test
    fun newRows() {
        assertEquals(0 to p("p1", "string"), GoAddMethodModel.newParameter(emptyList()))
        assertEquals(1 to p("p2", "string"), GoAddMethodModel.newParameter(listOf(p("p1", "int"), p("xs", "...int"))))
        assertEquals(1 to p("", "string"), GoAddMethodModel.newParameter(listOf(p("", "int"))))
        assertEquals(0 to r("", "error"), GoAddMethodModel.newResult(emptyList()))
        assertEquals(1 to r("err", "error"), GoAddMethodModel.newResult(listOf(r("n", "int"))))
        assertEquals(1 to r("", "error"), GoAddMethodModel.newResult(listOf(r("", "int"))))
    }
}
