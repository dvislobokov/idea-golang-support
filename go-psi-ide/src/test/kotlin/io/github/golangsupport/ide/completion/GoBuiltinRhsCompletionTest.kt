package io.github.golangsupport.ide.completion

/** Builtins on the right of `:=` (seen live: `tables := ma` offered bytes.Map but not make). */
class GoBuiltinRhsCompletionTest : GoCompletionTestBase() {
    fun testMakeAfterShortVarDecl() {
        val items = lookups(
            """
            package main

            import "os"

            func f() error {
                fs, err := os.Open("x")
                if err != nil {
                    return err
                }
                defer fs.Close()

                tables := ma<caret>

                return nil
            }
            """,
        )
        assertContainsAll(items, "make")
    }
}
