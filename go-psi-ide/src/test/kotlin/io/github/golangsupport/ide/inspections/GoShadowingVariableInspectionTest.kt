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
        		<text_attr descr="${shadows("value")}">value</text_attr>, <text_attr descr="${shadows("err")}">err</text_attr> := load(value + "x")
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
        	for <text_attr descr="${shadows("i")}">i</text_attr> := range 3 {
        		_ = i
        	}
        	for <text_attr descr="${shadows("n")}">n</text_attr> := 0; n < 2; n++ {
        	}
        	if <text_attr descr="${shadows("i")}">i</text_attr> := 2; i > 0 {
        	}
        	switch <text_attr descr="${shadows("n")}">n</text_attr> := 3; n {
        	}
        	select {
        	case <text_attr descr="${shadows("i")}">i</text_attr> := <-ch:
        		_ = i
        	}
        	var x any = i + n
        	switch <text_attr descr="${shadows("n")}">n</text_attr> := x.(type) {
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
        		y, <text_attr descr="${shadows("err")}">err</text_attr> := load()
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
        	<text_attr descr="${shadows("counter")}">counter</text_attr> := 1
        	var <text_attr descr="${shadows("limit")}">limit</text_attr> = 2
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
            	<text_attr descr="Declaration of 'shared' shadows declaration at b.go">shared</text_attr> := 2
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
        		<text_attr descr="${shadows("a")}">a</text_attr> := 1
        		<text_attr descr="${shadows("r")}">r</text_attr> := a
        		<text_attr descr="${shadows("t")}">t</text_attr> := T{}
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
        		<text_attr descr="${shadows("v")}">v</text_attr> := 2
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
        	<text_attr descr="${shadows("new", "builtin.go")}">new</text_attr> := 2
        	_ = new
        	<text_attr descr="${shadows("nil", "builtin.go")}">nil</text_attr> := 0
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
        		<text_attr descr="${shadows("got", "a_test.go")}">got</text_attr> := 2
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
