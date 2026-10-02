package io.github.golangsupport.ide.completion

import io.github.golangsupport.ide.completion.api.GoCompletionCandidate
import io.github.golangsupport.ide.completion.api.GoCompletionRanker
import io.github.golangsupport.ide.completion.api.GoCompletionRankingContext

/** Members after `.`: fields, method sets (`T` vs `*T`), promotion, interfaces, packages, method expressions, struct literal keys, labels. */
class GoMemberCompletionTest : GoCompletionTestBase() {

    // Indented like the test bodies so that `go()` trims both parts alike.
    private val types = """
            package main

            type Base struct{ ID int }

            func (b Base) Describe() string { return "" }
            func (b *Base) Reset()         {}

            type User struct {
                Base
                Name  string
                email string
            }

            func (u User) Greet() string { return u.Name }
            func (u *User) Rename(n string) { u.Name = n }

            func makeUser() User { return User{} }

    """

    fun testPresentationTextsAreRenderedLazily() {
        // The ranker runs after the lookup elements are built and before the popup renders its rows.
        var renderedWhileBuilding = -1
        var candidatesSeen = 0
        val ranker = object : GoCompletionRanker {
            override fun rank(context: GoCompletionRankingContext, candidates: List<GoCompletionCandidate>): List<Double> {
                renderedWhileBuilding = GoLookupElementFactory.lazyRenderCount.get()
                candidatesSeen = candidates.size
                return candidates.map { 0.0 }
            }
        }
        GoCompletionRanker.EP_NAME.point.registerExtension(ranker, testRootDisposable)
        GoLookupElementFactory.lazyRenderCount.set(0)
        lookups(types + """
            func main() {
                var u User
                u.<caret>
            }
        """)
        assertTrue("ranker was not consulted", candidatesSeen > 0)
        assertEquals("building the candidates must not render types", 0, renderedWhileBuilding)
        GoLookupElementFactory.lazyRenderCount.set(0)
        assertEquals(" string", presentation("Name").tailText)
        assertEquals(1, GoLookupElementFactory.lazyRenderCount.get())
    }

    fun testFieldsAndBothMethodSetsOfAddressableValue() {
        val items = lookups(types + """
            func main() {
                var u User
                u.<caret>
            }
        """)
        assertContainsAll(items, "Name", "email", "Base", "ID", "Greet", "Rename", "Describe", "Reset")
        assertEquals(" string", presentation("Name").tailText)
        assertEquals("(n string)", presentation("Rename").tailText)
        assertEquals("User", presentation("Greet").typeText)
    }

    fun testNonAddressableValueHasOnlyValueReceiverMethods() {
        val items = lookups(types + """
            func main() {
                makeUser().<caret>
            }
        """)
        assertContainsAll(items, "Name", "Greet", "Describe")
        assertContainsNone(items, "Rename", "Reset")
    }

    fun testPointerQualifierHasAllMethods() {
        val items = lookups(types + """
            func main() {
                u := &User{}
                u.<caret>
            }
        """)
        assertContainsAll(items, "Greet", "Rename", "Describe", "Reset", "ID")
    }

    fun testPromotedFieldsRankAfterDirectOnes() {
        val items = lookups(types + """
            func main() {
                u := &User{}
                u.<caret>
            }
        """)
        assertTrue("direct field before promoted: $items", items.indexOf("Name") < items.indexOf("ID"))
    }

    fun testInterfaceMethodsIncludingEmbedded() {
        val items = lookups("""
            package main

            type Reader interface{ Read(p []byte) (int, error) }
            type ReadCloser interface {
                Reader
                Close() error
            }

            func use(rc ReadCloser) {
                rc.<caret>
            }
        """)
        assertContainsAll(items, "Read", "Close")
        // The type model does not keep the `byte` alias.
        assertEquals("(p []byte) (int, error)", presentation("Read").tailText)
    }

    fun testMethodExpressionOnTypeName() {
        val items = lookups(types + """
            func main() {
                f := User.<caret>
                _ = f
            }
        """)
        assertContainsAll(items, "Greet", "Describe")
        assertContainsNone(items, "Rename", "Name")
    }

    fun testPackageMembersExportedOnlyAndNoMethods() {
        val items = lookups("""
            package main

            import "strings"

            func main() {
                strings.<caret>
            }
        """)
        assertContainsAll(items, "Builder", "ToUpper", "NewReplacer", "Index")
        assertContainsNone(items, "WriteString", "Len", "indexFunc", "init")
        assertEquals("strings", presentation("ToUpper").typeText)
        assertEquals("(s string) string", presentation("ToUpper").tailText)
    }

    fun testUnexportedFieldsOfOtherPackagesHidden() {
        val items = lookups("""
            package main

            import "strings"

            func main() {
                var b strings.Builder
                b.<caret>
            }
        """)
        assertContainsAll(items, "Len", "WriteString", "String", "Grow")
        assertContainsNone(items, "buf", "addr", "copyCheck")
    }

    fun testMembersThroughLocalVariableOfOtherFileType() {
        myFixture.addFileToProject("decl.go", go("""
            package main

            type Config struct{ Port int; Host string }

            func Load() *Config { return nil }
        """))
        val items = lookups("""
            package main

            func main() {
                cfg := Load()
                cfg.<caret>
            }
        """)
        assertContainsAll(items, "Port", "Host")
    }

    fun testNoKeywordsAfterDot() {
        val items = lookups(types + """
            func main() {
                var u User
                u.<caret>
            }
        """)
        assertContainsNone(items, "func", "return", "if", "map", "nil", "len")
    }

    // --- struct literal keys ---

    fun testStructLiteralKeysSkipUsedOnes() {
        val items = lookups(types + """
            func main() {
                _ = User{Name: "x", <caret>}
            }
        """)
        assertContainsAll(items, "email", "Base")
        assertContainsNone(items, "Name")
        assertTrue("keys first: $items", items.indexOf("email") < items.indexOf("makeUser"))
    }

    fun testStructLiteralPromotedKeys() {
        val items = lookups(types + """
            func main() {
                _ = User{<caret>: 1}
            }
        """)
        assertContainsAll(items, "Name", "email", "Base", "ID")
        // A key position offers keys only.
        assertContainsNone(items, "makeUser", "len")
    }

    fun testStructLiteralKeyInsertsColon() {
        checkInsert(types + """
            func main() {
                _ = User{ema<caret>}
            }
        """, "email", types + """
            func main() {
                _ = User{email: <caret>}
            }
        """)
    }

    fun testPositionalLiteralOffersNoKeys() {
        val items = lookups("""
            package main

            type P struct{ X, Y int }

            func main() {
                xv := 1
                _ = P{1, <caret>}
                _ = xv
            }
        """)
        assertContainsAll(items, "xv")
        assertContainsNone(items, "X", "Y")
    }

    fun testNestedElidedLiteralKeys() {
        val items = lookups("""
            package main

            type P struct{ Left, Right int }

            func main() {
                _ = []P{{<caret>}}
            }
        """)
        assertContainsAll(items, "Left", "Right")
    }

    fun testMapLiteralKeyIsAnExpression() {
        val items = lookups("""
            package main

            func main() {
                keyName := "k"
                _ = map[string]int{<caret>: 1}
                _ = keyName
            }
        """)
        assertContainsAll(items, "keyName", "len")
    }

    // --- labels ---

    fun testGotoOffersFunctionLabels() {
        val items = lookups("""
            package main

            func main() {
            outer:
                for {
                    goto <caret>
                }
            done:
                return
                goto outer
                goto done
            }

            func other() {
            foreign:
                goto foreign
            }
        """)
        assertContainsAll(items, "outer", "done")
        assertContainsNone(items, "foreign")
    }

    fun testBreakAndContinueOfferEnclosingLabels() {
        val code = """
            package main

            func main() {
            outerLoop:
                for {
                outerSwitch:
                    switch {
                    default:
                        KEYWORD <caret>
                    }
                    break outerSwitch
                }
            later:
                for {
                }
                goto later
                break outerLoop
            }
        """
        val breakItems = lookups(code.replace("KEYWORD", "break"))
        assertContainsAll(breakItems, "outerLoop", "outerSwitch")
        assertContainsNone(breakItems, "later")
        myFixture.configureByText("main.go", go(code.replace("KEYWORD", "continue")))
        myFixture.completeBasic()
        // Only the loop label fits `continue`.
        assertEquals(listOf("outerLoop"), myFixture.lookupElementStrings)
    }

    fun testPackageClauseSuggestsDirectoryAndSiblingNames() {
        myFixture.addFileToProject("server/a.go", "package server\n")
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("server/b.go", "package <caret>\n").virtualFile)
        myFixture.completeBasic()
        assertContainsAll(myFixture.lookupElementStrings.orEmpty(), "server", "main")
    }
}
