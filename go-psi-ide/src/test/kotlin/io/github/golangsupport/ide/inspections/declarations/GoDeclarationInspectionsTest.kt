package io.github.golangsupport.ide.inspections.declarations

import com.intellij.codeInspection.LocalInspectionTool
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** GoLand parity G7: `GoExportedFuncWithUnexportedType` and `GoRedundantConversion` with its fix. */
class GoDeclarationInspectionsTest : GoSemanticIdeTestBase() {

    private fun doHighlight(name: String, text: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools)
        myFixture.configureByText(name, text.trimIndent())
        myFixture.checkHighlighting(true, false, true)
    }

    private fun doFix(before: String, after: String) {
        myFixture.enableInspections(GoRedundantConversionInspection())
        myFixture.configureByText("fix.go", before.trimIndent())
        myFixture.launchAction(myFixture.findSingleIntention("Remove redundant type conversion"))
        myFixture.checkResult(after.trimIndent())
    }

    fun testExportedFunctionWithUnexportedResult() = doHighlight(
        "store.go",
        """
        package store

        type store struct{}

        type Store struct{}

        func NewStore() <warning descr="Exported function with the unexported return type 'store'">*store</warning> { return &store{} }

        func (Store) Inner() <warning descr="Exported method with the unexported return type 'store'">(store, error)</warning> { return store{}, nil }

        func (store) Self() store { return store{} }

        func newStore() *store { return &store{} }

        func Open() (*Store, error) { return &Store{}, nil }

        func Count() int { return 0 }
        """,
        GoExportedFuncWithUnexportedTypeInspection(),
    )

    fun testMainPackageIsSkipped() = doHighlight(
        "main.go",
        """
        package main

        type app struct{}

        func New() *app { return &app{} }

        func main() { _ = New() }
        """,
        GoExportedFuncWithUnexportedTypeInspection(),
    )

    fun testRedundantConversion() = doHighlight(
        "conv.go",
        """
        package conv

        type ID int

        func f(n int, id ID, b []byte, u uint8) {
        	_ = <weak_warning descr="Redundant type conversion">int(n)</weak_warning>
        	_ = <weak_warning descr="Redundant type conversion">ID(id)</weak_warning>
        	_ = <weak_warning descr="Redundant type conversion">[]byte(b)</weak_warning>
        	_ = <weak_warning descr="Redundant type conversion">byte(u)</weak_warning>
        	_ = int(id)
        	_ = ID(n)
        	_ = float64(1)
        	_ = string(b)
        	_ = int64(n)
        }
        """,
        GoRedundantConversionInspection(),
    )

    fun testRemoveConversion() = doFix(
        """
        package conv

        func f(n int) int {
        	return <caret>int(n) + 1
        }
        """,
        """
        package conv

        func f(n int) int {
        	return n + 1
        }
        """,
    )

    fun testRemoveConversionKeepsPrecedence() = doFix(
        """
        package conv

        func f(a, b int) int {
        	return <caret>int(a+b) * 2
        }
        """,
        """
        package conv

        func f(a, b int) int {
        	return (a+b) * 2
        }
        """,
    )
}
