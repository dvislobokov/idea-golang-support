package io.github.golangsupport.ide.completion

import com.intellij.testFramework.fixtures.CompletionAutoPopupTester

/** The list that pops up by itself after `.` and narrows while typing (the live path: restarts at the prefix length, item matchers). */
class GoAutoPopupCompletionTest : GoCompletionTestBase() {
    private lateinit var tester: CompletionAutoPopupTester

    override fun setUp() {
        super.setUp()
        tester = CompletionAutoPopupTester(myFixture)
    }

    override fun runInDispatchThread(): Boolean = false

    override fun runTestRunnable(testRunnable: com.intellij.util.ThrowableRunnable<Throwable>) = tester.runWithAutoPopupEnabled(testRunnable)

    fun testLowerCaseTypedIntoThePoppedUpListKeepsTheExportedMember() {
        // seen live: after `fmt.` the list showed, `e` `r` `r` closed it — `Errorf` is wanted without Shift
        myFixture.configureByText("main.go", go("""
            package main

            type User struct{ Name string }

            func (u User) Greet() string { return u.Name }
            func (u *User) Rename(n string) { u.Name = n }

            func main() {
                var u User
                u<caret>
            }
        """))
        tester.typeWithPauses(".")
        assertNotNull("no list after the dot", tester.lookup)
        tester.typeWithPauses("gre")
        val items = tester.lookup?.items?.map { it.lookupString }
        assertNotNull("the list closed at `gre`", items)
        assertContainsAll(items!!, "Greet")
    }
}
