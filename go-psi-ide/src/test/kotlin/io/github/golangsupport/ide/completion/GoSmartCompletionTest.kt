package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionType

/** Smart completion (A1) and chains `x.F.M` (A2) in smart and basic completion. */
class GoSmartCompletionTest : GoCompletionTestBase() {

    private val types = """
            package main

            type Profile struct {
                Email string
                Age   int
            }

            func (p Profile) Display() string { return p.Email }

            type User struct {
                Profile Profile
                Name    string
            }

            func makeUser() User              { return User{} }
            func loadUser() (User, error)     { return User{}, nil }
            func count() int                  { return 0 }

    """

    private fun smart(text: String): List<String> {
        myFixture.configureByText("main.go", go(text))
        val items = myFixture.complete(CompletionType.SMART)
        assertNotNull("a single candidate was inserted directly:\n${myFixture.editor.document.text}", items)
        return myFixture.lookupElementStrings.orEmpty()
    }

    // --- A1: filter by the expected type ---

    fun testSmartOffersOnlyValuesOfTheExpectedType() {
        val items = smart(types + """
            func main() {
                var u User
                var n int
                var s string
                var x User = <caret>
                _, _, _ = n, s, x
            }
        """)
        assertContainsAll(items, "u", "makeUser", "loadUser", "User{}")
        assertContainsNone(items, "n", "s", "count", "nil", "for", "Profile", "main")
    }

    fun testSmartIdenticalBeforeAssignable() {
        val items = smart("""
            package main

            type Shape interface{ Area() float64 }
            type Square struct{}

            func (Square) Area() float64 { return 0 }

            func main() {
                var sq Square
                var sh Shape
                var t Shape = <caret>
                _, _, _ = sq, sh, t
            }
        """)
        assertContainsAll(items, "sq", "sh", "nil")
        assertTrue(items.toString(), items.indexOf("sh") < items.indexOf("sq"))
    }

    private val shapes = """
            package main

            type Shape interface{ Area() float64 }
            type Circle struct{ R float64 }
            type Square struct{ S float64 }
            type Plain struct{}

            func (c Circle) Area() float64  { return c.R }
            func (s *Square) Area() float64 { return s.S }

    """

    /** GoLand's probe 13: implementations of the expected interface as literals, `&` where the pointer has the methods. */
    fun testSmartImplementationsOfTheExpectedInterface() {
        val items = smart(shapes + """
            func main() {
                var c Circle
                var sq Square
                var s Shape = <caret>
                _, _, _ = c, sq, s
            }
        """)
        assertContainsAll(items, "Circle{}", "&Square{}", "&sq", "c", "nil")
        assertContainsNone(items, "Square{}", "&Circle{}", "Plain{}", "&Plain{}", "sq", "&c")
    }

    fun testSmartPointerImplementationIsWrittenWithTheCaretInsideBraces() {
        myFixture.configureByText("main.go", go(shapes + "func main() {\n    var s Shape = Squ<caret>\n    _ = s\n}\n"))
        val items = myFixture.complete(CompletionType.SMART)
        if (items != null) select("&Square{}")
        myFixture.checkResult(go(shapes + "func main() {\n    var s Shape = &Square{<caret>}\n    _ = s\n}\n"))
    }

    fun testSmartSynthesizedLiterals() {
        assertContainsAll(smart(types + "func main() {\n    var p *User = <caret>\n    _ = p\n}\n"), "&User{}", "nil")
        assertContainsAll(smart("package main\n\nfunc main() {\n    var m map[string]int = <caret>\n    _ = m\n}\n"), "make(map[string]int)", "nil")
        assertContainsAll(smart("package main\n\nfunc main() {\n    var xs []string = <caret>\n    _ = xs\n}\n"), "make([]string, 0)", "nil", "append")
        assertContainsAll(smart("package main\n\nfunc main() {\n    var c chan int = <caret>\n    _ = c\n}\n"), "make(chan int)")
        assertContainsAll(smart("package main\n\nfunc main() {\n    var f func(a int) error = <caret>\n    _ = f\n}\n"), "func(a int) error {}", "nil")
        // `""` is the only string there: inserted directly, the caret between the quotes.
        myFixture.configureByText("main.go", go("package main\n\nfunc main() {\n    var s string = <caret>\n    _ = s\n}\n"))
        assertNull(myFixture.complete(CompletionType.SMART))
        myFixture.checkResult(go("package main\n\nfunc main() {\n    var s string = \"<caret>\"\n    _ = s\n}\n"))
        assertContainsAll(smart("package main\n\nfunc main() {\n    var k int = <caret>\n    _ = k\n}\n"), "0", "len", "cap")
        val bools = smart("package main\n\nfunc main() {\n    var b bool = <caret>\n    _ = b\n}\n")
        assertContainsAll(bools, "true", "false")
        assertContainsNone(bools, "nil", "0")
    }

    fun testSmartStructLiteralPutsCaretInsideBraces() {
        myFixture.configureByText("main.go", go(types + """
            func main() {
                var x User = Us<caret>
                _ = x
            }
        """))
        val items = myFixture.complete(CompletionType.SMART)
        if (items != null) select("User{}")
        myFixture.checkResult(go(types + """
            func main() {
                var x User = User{<caret>}
                _ = x
            }
        """))
    }

    fun testSmartSelectorFiltersMembers() {
        val items = smart("""
            package main

            type Account struct {
                Name  string
                Nick  string
                Count int
            }

            func main() {
                var a Account
                var s string = a.<caret>
                _ = s
            }
        """)
        assertContainsAll(items, "Name", "Nick")
        assertContainsNone(items, "Count")
    }

    fun testSmartWithoutExpectedTypeIsBasic() {
        val items = smart(types + """
            func main() {
                var u User
                <caret>
            }
        """)
        assertContainsAll(items, "u", "makeUser", "count", "for", "Profile")
    }

    // --- A2: chains ---

    fun testSmartChainsFilteredByType() {
        val items = smart(types + """
            func main() {
                var u User
                var s string = <caret>
                _ = s
            }
        """)
        assertContainsAll(items, "u.Profile.Email", "u.Profile.Display")
        assertContainsNone(items, "u.Profile.Age")
    }

    fun testChainInsertsCallParentheses() {
        myFixture.configureByText("main.go", go(types + """
            func main() {
                var u User
                var s string = Displ<caret>
                _ = s
            }
        """))
        val items = myFixture.complete(CompletionType.SMART)
        if (items != null) select("u.Profile.Display")
        myFixture.checkResult(go(types + """
            func main() {
                var u User
                var s string = u.Profile.Display()<caret>
                _ = s
            }
        """))
    }

    fun testBasicChainsNeedTwoCharacters() {
        val chains = types + """
            type Entry struct{}
            type Event struct{}

            func main() {
                var u User
                PREFIX<caret>
            }
        """
        checkInsert(chains.replace("PREFIX", "Emai"), "u.Profile.Email", chains.replace("PREFIX<caret>", "u.Profile.Email<caret>"))
        val oneLetter = lookups(chains.replace("PREFIX", "E"))
        assertContainsAll(oneLetter, "Entry", "Event")
        assertContainsNone(oneLetter, "u.Profile.Email")
    }

    fun testChainsAreCapped() {
        val fields = (1..30).joinToString("") { "\tF$it Inner\n" }
        myFixture.configureByText(
            "main.go",
            "package main\n\ntype Inner struct {\n\tS1, S2, S3, S4, S5 string\n}\n\ntype Root struct {\n$fields}\n\n" +
                "func main() {\n\tvar r Root\n\tvar s string = <caret>\n\t_ = s\n}\n",
        )
        myFixture.complete(CompletionType.SMART)
        assertEquals(GoChainCandidates.MAX_CHAINS, myFixture.lookupElementStrings.orEmpty().count { it.startsWith("r.") })
    }
}
