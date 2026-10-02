package io.github.golangsupport.ide.completion

/** Context-sensitive keywords and statement snippets. */
class GoKeywordCompletionTest : GoCompletionTestBase() {

    fun testTopLevelKeywordsWithImportBeforeDeclarations() {
        val items = lookups("""
            package main

            <caret>
        """)
        assertContainsAll(items, "func", "type", "var", "const", "import")
        assertContainsNone(items, "package", "if", "return", "len")
    }

    fun testNoImportKeywordAfterDeclarations() {
        val items = lookups("""
            package main

            func main() {}

            <caret>
        """)
        assertContainsAll(items, "func", "type", "var", "const")
        assertContainsNone(items, "import")
    }

    fun testStatementKeywordsOutsideLoops() {
        val items = lookups("""
            package main

            func main() {
                <caret>
            }
        """)
        assertContainsAll(items, "if", "for", "switch", "select", "go", "defer", "return", "var", "const", "type", "goto")
        assertContainsNone(items, "break", "continue", "fallthrough", "case", "default", "else", "range", "package", "import")
    }

    fun testBreakAndContinueInsideLoop() {
        val items = lookups("""
            package main

            func main() {
                for {
                    <caret>
                }
            }
        """)
        assertContainsAll(items, "break", "continue")
    }

    fun testBreakButNoContinueInSwitchClause() {
        val items = lookups("""
            package main

            func main() {
                switch x := 1; x {
                case 1:
                    <caret>
                }
            }
        """)
        assertContainsAll(items, "break", "fallthrough", "case", "default")
        assertContainsNone(items, "continue")
    }

    fun testCaseAndDefaultDirectlyInSwitchBody() {
        val items = lookups("""
            package main

            func main() {
                x := 1
                switch x {
                <caret>
                }
            }
        """)
        assertEquals(listOf("case", "default"), items.sorted())
    }

    fun testCaseAndDefaultInSelectBody() {
        val items = lookups("""
            package main

            func main() {
                select {
                <caret>
                }
            }
        """)
        assertEquals(listOf("case", "default"), items.sorted())
    }

    fun testElseAfterIfBlock() {
        val items = lookups("""
            package main

            func main() {
                ok := true
                if ok {
                } e<caret>
            }
        """)
        assertContainsAll(items, "else")
    }

    fun testNoElseOnNextLine() {
        val items = lookups("""
            package main

            func main() {
                ok := true
                if ok {
                }
                <caret>
            }
        """)
        assertContainsNone(items, "else")
    }

    fun testRangeInForHeader() {
        assertTrue("range" in lookups("""
            package main

            func main() {
                xs := []int{1}
                for i := r<caret>
                _ = xs
            }
        """))
        assertTrue("range" in lookups("""
            package main

            func main() {
                ch := make(chan int)
                for r<caret>
                _ = ch
            }
        """))
    }

    fun testTypeConstructorsInTypePosition() {
        val items = lookups("""
            package main

            type T <caret>
        """)
        assertContainsAll(items, "struct", "interface", "map", "chan", "func")
        assertContainsNone(items, "if", "var", "return")
    }

    fun testKeywordInsertsTrailingSpace() {
        checkInsert("""
            package main

            func main() int {
                retu<caret>
            }
        """, "return", """
            package main

            func main() int {
                return <caret>
            }
        """)
    }

    fun testStructKeywordInTypePositionInsertsBraces() {
        checkInsert("""
            package main

            type T stru<caret>
        """, "struct", """
            package main

            type T struct{<caret>}
        """)
    }

    // --- snippets ---

    fun testIferrReturnsZeroValuesOfResults() {
        checkInsert("""
            package main

            import "bytes"

            type Point struct{ X int }

            func load(name string) (int, string, bool, *Point, Point, []byte, bytes.Buffer, error) {
                _, err := 0, error(nil)
                iferr<caret>
            }
        """, "iferr", """
            package main

            import "bytes"

            type Point struct{ X int }

            func load(name string) (int, string, bool, *Point, Point, []byte, bytes.Buffer, error) {
                _, err := 0, error(nil)
                if err != nil {
                    return 0, "", false, nil, Point{}, nil, bytes.Buffer{}, err
                }<caret>
            }
        """)
    }

    fun testIferrWithoutResults() {
        checkInsert("""
            package main

            func run() {
                var err error
                iferr<caret>
            }
        """, "iferr", """
            package main

            func run() {
                var err error
                if err != nil {
                    return
                }<caret>
            }
        """)
    }

    fun testIferrInFunctionLiteralUsesLiteralResults() {
        checkInsert("""
            package main

            func main() {
                f := func() (string, error) {
                    var err error
                    iferr<caret>
                }
                _ = f
            }
        """, "iferr", """
            package main

            func main() {
                f := func() (string, error) {
                    var err error
                    if err != nil {
                        return "", err
                    }<caret>
                }
                _ = f
            }
        """)
    }

    fun testForRangeSnippetOverVisibleVariables() {
        val items = lookups("""
            package main

            func main() {
                names := []string{"a"}
                ages := map[string]int{}
                count := 3
                fo<caret>
            }
        """)
        assertContainsAll(items, "for", "for range names", "for range ages")
        assertContainsNone(items, "for range count")
        assertEquals("  for k, v := range ages {...}", presentation("for range ages").tailText)
        select("for range names")
        myFixture.checkResult(go("""
            package main

            func main() {
                names := []string{"a"}
                ages := map[string]int{}
                count := 3
                for i, v := range names {
                    <caret>
                }
            }
        """))
    }

    fun testSwitchAndSelectSkeletons() {
        checkInsert("""
            package main

            func main() {
                sw<caret>
            }
        """, "switch {}", """
            package main

            func main() {
                switch <caret> {
                case :
                }
            }
        """)
    }

    fun testNoSnippetsOutsideStatementStart() {
        val items = lookups("""
            package main

            func main() {
                x := i<caret>
                _ = x
            }
        """)
        assertContainsNone(items, "iferr", "if")
    }
}
