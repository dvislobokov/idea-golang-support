package io.github.golangsupport.ide.completion

/** A struct, map, slice or array type picked where a value goes becomes a composite literal, as in GoLand (dump probe 19: `Circle{<caret>}`). */
class GoTypeLiteralCompletionTest : GoCompletionTestBase() {

    private val types = """
        package main

        type Circle struct{ Radius float64 }
        type Names []string
        type Index map[string]int
        type Grid [3]int
        type MyInt int
        type Shape interface{ Area() float64 }
        type Set[T comparable] map[T]struct{}

        func use(c Circle) {}
    """.trimIndent()

    private fun body(line: String) = "$types\n\nfunc main() {\n    $line\n}"

    private fun returning(line: String) = "$types\n\nfunc make2() Circle {\n    $line\n}"

    fun testAssignment() = checkInsert(body("x := Cir<caret>"), "Circle", body("x := Circle{<caret>}"))

    fun testReturn() = checkInsert(returning("return Cir<caret>"), "Circle", returning("return Circle{<caret>}"))

    fun testArgument() = checkInsert(body("use(Cir<caret>)"), "Circle", body("use(Circle{<caret>})"))

    fun testAddressOf() = checkInsert(body("x := &Cir<caret>"), "Circle", body("x := &Circle{<caret>}"))

    fun testSliceMapAndArrayTypes() {
        checkInsert(body("x := Nam<caret>"), "Names", body("x := Names{<caret>}"))
        checkInsert(body("x := Inde<caret>"), "Index", body("x := Index{<caret>}"))
        checkInsert(body("x := Gri<caret>"), "Grid", body("x := Grid{<caret>}"))
    }

    fun testOtherTypesStayBareNames() {
        // GoLand's dump shows braces for struct types only; a conversion target, an interface or a generic type keeps the bare name
        checkInsert(body("x := MyI<caret>"), "MyInt", body("x := MyInt<caret>"))
        checkInsert(body("var s Shape = Shap<caret>"), "Shape", body("var s Shape = Shape<caret>"))
        checkInsert(body("x := Se<caret>"), "Set", body("x := Set<caret>"))
    }

    fun testTypePositionsStayBareNames() {
        checkInsert(body("var c Cir<caret>"), "Circle", body("var c Circle<caret>"))
        checkInsert(body("x := []Cir<caret>{}"), "Circle", body("x := []Circle<caret>{}"))
        checkInsert("$types\n\nfunc g(c Cir<caret>) {}", "Circle", "$types\n\nfunc g(c Circle<caret>) {}")
    }

    fun testNoBracesBeforeAnExistingBraceOrParenthesisAndNotInMake() {
        checkInsert(body("x := Cir<caret>(c)"), "Circle", body("x := Circle<caret>(c)"))
        checkInsert(body("x := make(Nam<caret>)"), "Names", body("x := make(Names<caret>)"))
        checkInsert(body("x := new(Cir<caret>)"), "Circle", body("x := new(Circle<caret>)"))
    }

    fun testAStatementStartStaysABareName() = checkInsert(body("Cir<caret>"), "Circle", body("Circle<caret>"))

    fun testATypeOfAnUnimportedPackageGetsBracesAndTheImport() = checkInsert(
        "package main\n\nfunc main() {\n    b := strings.Build<caret>\n    _ = b\n}",
        "Builder",
        "package main\n\nimport \"strings\"\n\nfunc main() {\n    b := strings.Builder{<caret>}\n    _ = b\n}",
    )

    fun testATypeOfAnImportedPackage() = checkInsert(
        "package main\n\nimport \"strings\"\n\nfunc main() {\n    b := strings.Build<caret>\n    _ = b\n}",
        "Builder",
        "package main\n\nimport \"strings\"\n\nfunc main() {\n    b := strings.Builder{<caret>}\n    _ = b\n}",
    )
}
