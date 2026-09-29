package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoSyntaxHighlighter
import io.github.golangsupport.settings.GoSettings

/** The names of packages are coloured by the plugin: gopls is told not to, for it colours a part of an import path as well. */
class GoPackageColoursTest : BasePlatformTestCase() {
    private var languageServer = true

    // a file opened in an editor starts gopls, where it is installed: not in a test
    override fun setUp() {
        super.setUp()
        languageServer = GoSettings.getInstance().languageServerEnabled
        GoSettings.getInstance().languageServerEnabled = false
    }

    override fun tearDown() {
        try {
            GoSettings.getInstance().languageServerEnabled = languageServer
        } finally {
            super.tearDown()
        }
    }

    fun testPackagesInTheCodeAndNotInThePathsOfImports() {
        val code = """
            package store

            import (
            	"fmt"
            	f "os"

            	"github.com/google/uuid"
            )

            func id() string {
            	fmt.Println(f.Args)
            	return uuid.NewString()
            }
        """.trimIndent()
        myFixture.configureByText("store.go", code)
        val coloured = myFixture.doHighlighting().filter { it.forcedTextAttributesKey == GoSyntaxHighlighter.PACKAGE }
            .map { code.substring(it.startOffset, it.endOffset) to code.substring(0, it.startOffset).count { c -> c == '\n' } + 1 }
        // the clause, the name given to an import, and the packages the code speaks of; nothing inside the quotes of an import
        assertEquals(listOf("store" to 1, "f" to 5, "fmt" to 11, "f" to 11, "uuid" to 12), coloured.sortedWith(compareBy({ it.second }, { code.indexOf(it.first) })))
    }
}
