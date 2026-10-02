package io.github.golangsupport.ide.completion

/** Unqualified names: locals and shadowing, signatures, package scope across files, imports, universe, types. */
class GoScopeCompletionTest : GoCompletionTestBase() {

    fun testLocalsDeclaredBeforeCaretOnly() {
        val items = lookups("""
            package main

            func main() {
                alpha := 1
                alphabet := "a"
                al<caret>
                alpine := 2
            }
        """)
        assertContainsAll(items, "alpha", "alphabet")
        assertContainsNone(items, "alpine")
    }

    fun testInnerDeclarationShadowsOuter() {
        val items = lookups("""
            package main

            func main() {
                value := 1
                valid := true
                if valid {
                    value := "text"
                    _ = val<caret>
                }
            }
        """)
        // A single `value`: the inner string.
        assertEquals(1, items.count { it == "value" })
        assertEquals(" string", presentation("value").tailText)
    }

    fun testParametersResultsAndReceiver() {
        val items = lookups("""
            package main

            type Server struct{}

            func (srv *Server) handle(sreq string, sn int) (sres error) {
                s<caret>
                return nil
            }
        """)
        assertContainsAll(items, "srv", "sreq", "sn", "sres", "string")
    }

    fun testIfInitRangeAndTypeSwitchVariables() {
        val items = lookups("""
            package main

            func main() {
                var xs []int
                for xIndex, xValue := range xs {
                    if xOk := xIndex > 0; xOk {
                        var any0 interface{} = xValue
                        switch xTyped := any0.(type) {
                        case int:
                            _ = x<caret>
                        }
                    }
                }
            }
        """)
        assertContainsAll(items, "xs", "xIndex", "xValue", "xOk", "xTyped")
    }

    fun testFunctionLiteralParametersAndOuterLocals() {
        val items = lookups("""
            package main

            func main() {
                outer := 1
                f := func(inner int) {
                    _ = <caret>
                }
                _ = f
            }
        """)
        assertContainsAll(items, "outer", "inner")
        assertContainsNone(items, "f")
    }

    fun testPackageLevelAcrossFilesWithoutInitAndBlank() {
        myFixture.addFileToProject("decl.go", go("""
            package main

            type Point struct{ X, Y int }

            func NewPoint() *Point { return nil }

            func init() {}

            var _ = 1

            const Limit = 10

            var counter int
        """))
        val items = lookups("""
            package main

            func main() {
                _ = <caret>
            }
        """)
        assertContainsAll(items, "Point", "NewPoint", "Limit", "counter", "main")
        assertContainsNone(items, "init", "_")
    }

    fun testImportedPackagesAndUniverse() {
        val items = lookups("""
            package main

            import (
                "fmt"
                str "strings"
                _ "embed"
            )

            func main() {
                <caret>
            }
        """)
        assertContainsAll(items, "fmt", "str", "len", "append", "nil", "true", "string", "error")
        assertContainsNone(items, "strings", "embed", "iota")
        assertEquals(" (fmt)", presentation("fmt").tailText)
    }

    fun testDotImportedMembersAreUnqualified() {
        val items = lookups("""
            package main

            import . "strings"

            func main() {
                _ = ToU<caret>
            }
        """)
        assertContainsAll(items, "ToUpper", "ToUpperSpecial")
        assertEquals("strings", presentation("ToUpper").typeText)
    }

    fun testIotaOnlyInConstDeclarations() {
        assertTrue("iota" in lookups("""
            package main

            const (
                A = i<caret>
            )
        """))
        assertFalse("iota" in lookups("""
            package main

            var v = i<caret>
        """))
    }

    fun testTypePositionOffersTypesAndPackagesOnly() {
        myFixture.addFileToProject("decl.go", go("""
            package main

            type Point struct{}

            type Reader interface{}

            func helper() {}

            var global int
        """))
        val items = lookups("""
            package main

            import "fmt"

            func main() {
                var x <caret>
                _ = x
            }
        """)
        assertContainsAll(items, "Point", "Reader", "int", "string", "error", "any", "fmt", "map", "chan", "struct", "interface", "func")
        assertContainsNone(items, "helper", "global", "append", "nil", "main", "comparable", "if")
        assertEquals(" struct", presentation("Point").tailText)
        assertEquals(" interface", presentation("Reader").tailText)
    }

    fun testComparableInConstraintsOnly() {
        assertTrue("comparable" in lookups("""
            package main

            func Keys[K c<caret>, V any](m map[K]V) {}
        """))
    }

    fun testTypeParametersInScope() {
        val items = lookups("""
            package main

            type List[Elem any] struct{ items []<caret> }
        """)
        assertContainsAll(items, "Elem", "List")
    }

    fun testQualifiedTypePositionOffersPackageTypesOnly() {
        val items = lookups("""
            package main

            import "strings"

            var b strings.<caret>
        """)
        assertContainsAll(items, "Builder", "Reader", "Replacer")
        assertContainsNone(items, "ToUpper", "NewReader", "Map")
    }

    fun testReceiverTypeOffersPackageTypes() {
        myFixture.addFileToProject("decl.go", go("""
            package main

            type Remote struct{}
        """))
        val items = lookups("""
            package main

            type Local struct{}

            func (l *<caret>) M() {}
        """)
        assertContainsAll(items, "Local", "Remote")
        assertContainsNone(items, "int", "string")
    }

    fun testCompositeLiteralTypeIsExpressionPosition() {
        val items = lookups("""
            package main

            type pair struct{}

            func main() {
                pairs := 1
                x := pai<caret>{}
                _, _ = x, pairs
            }
        """)
        assertContainsAll(items, "pairs", "pair")
    }
}
