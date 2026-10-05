package io.github.golangsupport.ide.inspections

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** "Shadowing variable" (GoLand's `GoShadowedVar`): what is reported, what is a reuse or an idiom, the message line, both fixes. */
class GoShadowingVariableInspectionTest : GoSemanticIdeTestBase() {

    private fun doHighlight(text: String, fileName: String = "a.go") {
        myFixture.enableInspections(GoShadowingVariableInspection())
        myFixture.configureByText(fileName, text.trimIndent() + "\n")
        myFixture.checkHighlighting(false, false, true)
    }

    // GoLand's text names the file only (seen live 2026-10-05: "... at style.go" for a declaration of the same file)
    private fun shadows(name: String, file: String = "a.go") = "Declaration of '$name' shadows declaration at $file"

    fun testShortVarInIfBodyShadowsFunctionVariable() = doHighlight(
        """
        package p

        func load(s string) (string, error) { return s, nil }

        func f(name string) (string, error) {
        	value, err := load(name)
        	if err != nil {
        		return "", err
        	}
        	if value != "" {
        		<weak_warning descr="${shadows("value")}">value</weak_warning>, <weak_warning descr="${shadows("err")}">err</weak_warning> := load(value + "x")
        		if err != nil {
        			return "", err
        		}
        		_ = value
        	}
        	return value, nil
        }
        """
    )

    fun testRangeAndForAndIfInitAndSwitchAndSelect() = doHighlight(
        """
        package p

        func f(ch chan int) {
        	i, n := 0, 1
        	for <weak_warning descr="${shadows("i")}">i</weak_warning> := range 3 {
        		_ = i
        	}
        	for <weak_warning descr="${shadows("n")}">n</weak_warning> := 0; n < 2; n++ {
        	}
        	if <weak_warning descr="${shadows("i")}">i</weak_warning> := 2; i > 0 {
        	}
        	switch <weak_warning descr="${shadows("n")}">n</weak_warning> := 3; n {
        	}
        	select {
        	case <weak_warning descr="${shadows("i")}">i</weak_warning> := <-ch:
        		_ = i
        	}
        	var x any = i + n
        	switch <weak_warning descr="${shadows("n")}">n</weak_warning> := x.(type) {
        	default:
        		_ = n
        	}
        }
        """
    )

    fun testOnlyTheFreshNameShadows() = doHighlight(
        """
        package p

        func load() (int, error) { return 0, nil }

        func f() error {
        	x, err := load()
        	if x > 0 {
        		var y int
        		y, <weak_warning descr="${shadows("err")}">err</weak_warning> := load()
        		_ = y
        		return err
        	}
        	_ = x
        	return err
        }
        """
    )

    fun testRedeclarationInTheSameScopeIsNotShadowing() = doHighlight(
        """
        package p

        func load() (int, error) { return 0, nil }

        func f() error {
        	x, err := load()
        	y, err := load()
        	_, _ = x, y
        	return err
        }

        func g() (n int, err error) {
        	n, err = load()
        	m, err := load()
        	_ = m
        	return
        }

        func h(v any) {
        	switch t := v.(type) {
        	case int:
        		t, u := 1, 2
        		_, _ = t, u
        	}
        }
        """
    )

    fun testPackageLevelVariableAndConstant() = doHighlight(
        """
        package p

        var counter = 0

        const limit = 10

        func f() int {
        	<weak_warning descr="${shadows("counter")}">counter</weak_warning> := 1
        	var <weak_warning descr="${shadows("limit")}">limit</weak_warning> = 2
        	return counter + limit
        }
        """
    )

    fun testPackageLevelInAnotherFile() {
        myFixture.addFileToProject("b.go", "package p\n\nvar shared = 1\n")
        doHighlight(
            """
            package p

            func f() int {
            	<weak_warning descr="Declaration of 'shared' shadows declaration at b.go">shared</weak_warning> := 2
            	return shared
            }
            """
        )
    }

    fun testParameterAndReceiverShadowedInNestedScope() = doHighlight(
        """
        package p

        type T struct{}

        func (t T) m(a int) (r int) {
        	if a > 0 {
        		<weak_warning descr="${shadows("a")}">a</weak_warning> := 1
        		<weak_warning descr="${shadows("r")}">r</weak_warning> := a
        		<weak_warning descr="${shadows("t")}">t</weak_warning> := T{}
        		_, _ = r, t
        	}
        	return a
        }
        """
    )

    fun testClosureShadowsEnclosingFunctionVariable() = doHighlight(
        """
        package p

        func f() {
        	v := 1
        	g := func() {
        		<weak_warning descr="${shadows("v")}">v</weak_warning> := 2
        		_ = v
        	}
        	g()
        	_ = v
        }
        """
    )

    fun testQuietCases() = doHighlight(
        """
        package p

        import "fmt"

        var global = 1

        func f(items []int, v any, global2 int) {
        	fmt.Println()
        	_, x := 1, 2
        	if true {
        		_, y := 3, 4
        		_, _ = x, y
        	}
        	for _, it := range items {
        		it := it
        		_ = it
        	}
        	switch v := v.(type) {
        	default:
        		_ = v
        	}
        	fmt := "shadowing an import is another inspection"
        	_ = fmt
        	other := 1
        	_ = other
        }

        func g(global int) int { return global }
        """
    )

    // G10, seen live on GoLand 2026.2.3: `new := 2` → "Declaration of 'new' shadows declaration at builtin.go"
    fun testPredeclaredNamesAreShadowedAtBuiltinGo() = doHighlight(
        """
        package p

        func f() {
        	<weak_warning descr="${shadows("new", "builtin.go")}">new</weak_warning> := 2
        	_ = new
        	<weak_warning descr="${shadows("nil", "builtin.go")}">nil</weak_warning> := 0
        	_ = nil
        }
        """
    )

    fun testReportedInTestFiles() = doHighlight(
        """
        package p

        import "testing"

        func TestX(t *testing.T) {
        	got := 1
        	t.Run("sub", func(t *testing.T) {
        		<weak_warning descr="${shadows("got", "a_test.go")}">got</weak_warning> := 2
        		_ = got
        	})
        	_ = got
        }
        """,
        "a_test.go",
    )

    fun testNamePaintedWithShadowingVariableKey() {
        myFixture.enableInspections(GoShadowingVariableInspection())
        myFixture.configureByText("a.go", "package p\n\nfunc f() {\n\tv := 1\n\t{\n\t\tv := 2\n\t\t_ = v\n\t}\n\t_ = v\n}\n")
        val info = myFixture.doHighlighting().single { it.description?.startsWith("Declaration of 'v'") == true }
        assertEquals("GO_SHADOWING_VARIABLE", info.forcedTextAttributesKey?.externalName)
    }

    fun testNavigateFixMovesCaretToShadowedDeclaration() {
        myFixture.enableInspections(GoShadowingVariableInspection())
        myFixture.configureByText(
            "a.go",
            """
            package p

            func f() {
            	value := 1
            	if value > 0 {
            		val<caret>ue := 2
            		_ = value
            	}
            }
            """.trimIndent() + "\n"
        )
        val offered = myFixture.availableIntentions.map { it.text }
        assertTrue(offered.toString(), "Rename variable" in offered)
        myFixture.launchAction(myFixture.findSingleIntention("Navigate to shadowed declaration"))
        assertEquals(myFixture.file.text.indexOf("value := 1"), myFixture.editor.caretModel.offset)
    }
}
