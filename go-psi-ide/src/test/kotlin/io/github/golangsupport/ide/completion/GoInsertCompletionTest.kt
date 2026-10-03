package io.github.golangsupport.ide.completion

/** Insert handlers: call parentheses, package `.`, auto-import of unimported packages and their members. */
class GoInsertCompletionTest : GoCompletionTestBase() {

    fun testFunctionWithParametersPutsCaretInsideParentheses() {
        checkInsert("""
            package main

            func greet(name string) {}
            func greeting() {}

            func main() {
                gree<caret>
            }
        """, "greet", """
            package main

            func greet(name string) {}
            func greeting() {}

            func main() {
                greet(<caret>)
            }
        """)
    }

    fun testFunctionWithoutParametersPutsCaretAfterParentheses() {
        checkInsert("""
            package main

            func greet(name string) {}
            func greeting() {}

            func main() {
                gree<caret>
            }
        """, "greeting", """
            package main

            func greet(name string) {}
            func greeting() {}

            func main() {
                greeting()<caret>
            }
        """)
    }

    fun testExistingParenthesesAreReused() {
        checkInsert("""
            package main

            func greet(name string) {}
            func greeting() {}

            func main() {
                gree<caret>("x")
            }
        """, "greet", """
            package main

            func greet(name string) {}
            func greeting() {}

            func main() {
                greet(<caret>"x")
            }
        """)
    }

    fun testFunctionValueExpectedInsertsNoParentheses() {
        checkInsert("""
            package main

            func apply(f func(int) int) {}
            func double(x int) int { return x * 2 }
            func dump() {}

            func main() {
                apply(d<caret>)
            }
        """, "double", """
            package main

            func apply(f func(int) int) {}
            func double(x int) int { return x * 2 }
            func dump() {}

            func main() {
                apply(double<caret>)
            }
        """)
    }

    fun testMethodCallParentheses() {
        checkInsert("""
            package main

            type T struct{}

            func (T) Run(n int) {}
            func (T) Reset()    {}

            func main() {
                var t T
                t.R<caret>
            }
        """, "Run", """
            package main

            type T struct{}

            func (T) Run(n int) {}
            func (T) Reset()    {}

            func main() {
                var t T
                t.Run(<caret>)
            }
        """)
    }

    fun testImportedPackageInsertsDot() {
        checkInsert("""
            package main

            import (
                "fmt"
                "flag"
            )

            func main() {
                fm<caret>
            }
        """, "fmt", """
            package main

            import (
                "fmt"
                "flag"
            )

            func main() {
                fmt.<caret>
            }
        """)
    }

    fun testUnimportedPackageAddsImportToEmptyFile() {
        checkInsert("""
            package main

            func main() {
                strco<caret>
            }
        """, "strconv", """
            package main

            import "strconv"

            func main() {
                strconv.<caret>
            }
        """)
    }

    fun testUnimportedMemberAddsImportIntoSortedGroup() {
        checkInsert("""
            package main

            import (
                "fmt"
                "os"
            )

            func main() {
                fmt.Println(os.Args)
                strings.ToUpp<caret>
            }
        """, "ToUpper", """
            package main

            import (
                "fmt"
                "os"
                "strings"
            )

            func main() {
                fmt.Println(os.Args)
                strings.ToUpper(<caret>)
            }
        """)
    }

    fun testAutoImportInsertsInSortedPosition() {
        checkInsert("""
            package main

            import (
                "fmt"
                "os"
            )

            func main() {
                fmt.Println(os.Args)
                var b bytes.Buf<caret>
                _ = b
            }
        """, "Buffer", """
            package main

            import (
                "bytes"
                "fmt"
                "os"
            )

            func main() {
                fmt.Println(os.Args)
                var b bytes.Buffer<caret>
                _ = b
            }
        """)
    }

    fun testAutoImportTurnsSingleImportIntoGroup() {
        checkInsert("""
            package main

            import "os"

            func main() {
                _ = os.Args
                _ = unicode.IsUpp<caret>
            }
        """, "IsUpper", """
            package main

            import (
                "os"
                "unicode"
            )

            func main() {
                _ = os.Args
                _ = unicode.IsUpper(<caret>)
            }
        """)
    }

    fun testAutoImportPutsTheStandardLibraryAboveModules() {
        checkInsert("""
            package main

            import (
                mux "github.com/gorilla/mux"
            )

            var _ = mux.NewRouter

            func main() {
                strings.ToUpp<caret>
            }
        """, "ToUpper", """
            package main

            import (
                "strings"

                mux "github.com/gorilla/mux"
            )

            var _ = mux.NewRouter

            func main() {
                strings.ToUpper(<caret>)
            }
        """)
    }

    fun testAutoImportGroupsASingleModuleImport() {
        checkInsert("""
            package main

            import mux "github.com/gorilla/mux"

            var _ = mux.NewRouter

            func main() {
                strings.ToUpp<caret>
            }
        """, "ToUpper", """
            package main

            import (
                "strings"

                mux "github.com/gorilla/mux"
            )

            var _ = mux.NewRouter

            func main() {
                strings.ToUpper(<caret>)
            }
        """)
    }

    fun testUnimportedPackagesNotOfferedWhenNameIsTaken() {
        val items = lookups("""
            package main

            func main() {
                strings := []string{}
                stri<caret>
            }
        """)
        assertEquals(1, items.count { it == "strings" })
    }
}
