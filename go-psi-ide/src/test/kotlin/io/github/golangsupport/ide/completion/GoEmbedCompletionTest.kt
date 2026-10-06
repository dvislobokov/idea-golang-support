package io.github.golangsupport.ide.completion

import io.github.golangsupport.ide.directives.GoEmbedCompletion

/** Files and directories after `//go:embed` ([io.github.golangsupport.ide.directives.GoEmbedCompletionContributor]). */
class GoEmbedCompletionTest : GoCompletionTestBase() {

    private fun files() {
        myFixture.addFileToProject("hello.txt", "hi")
        myFixture.addFileToProject("static/a.css", "a")
        myFixture.addFileToProject("static/b.css", "b")
        myFixture.addFileToProject("static/.hidden", "h")
        myFixture.addFileToProject(".git/config", "c")
        myFixture.addFileToProject("_skip.txt", "s")
        myFixture.addFileToProject("my file.txt", "s")
        myFixture.addFileToProject("nested/go.mod", "module nested\n")
        myFixture.addFileToProject("nested/x.go", "package nested\n")
    }

    private val head = "package main\n\nimport \"embed\"\n\n"

    fun testFilesAndDirectoriesOfThePackage() {
        files()
        val items = lookups(head + "//go:embed <caret>\nvar content embed.FS\n")
        assertContainsAll(items, "hello.txt", "main.go", "static")
        assertContainsNone(items, ".git", "_skip.txt", "my file.txt", "nested", "static/a.css")
        // files before directories
        assertTrue(items.indexOf("hello.txt") < items.indexOf("static"))
    }

    fun testPrefixAndSubdirectory() {
        files()
        assertEquals(listOf("static/a.css", "static/b.css"), lookups(head + "//go:embed hello.txt static/<caret>\nvar content embed.FS\n"))
        checkInsert(head + "//go:embed hel<caret>\nvar s string\n", null, head + "//go:embed hello.txt\nvar s string\n")
    }

    fun testQuotedPatternAndAllPrefix() {
        files()
        checkInsert(head + "//go:embed \"my<caret>\nvar s string\n", null, head + "//go:embed \"my file.txt\nvar s string\n")
        assertContainsAll(lookups(head + "//go:embed all:static/<caret>\nvar content embed.FS\n"), "static/.hidden", "static/a.css")
    }

    fun testWordRules() {
        assertEquals("", GoEmbedCompletion.wordAt("//go:embed ")?.path)
        assertEquals("static/", GoEmbedCompletion.wordAt("//go:embed a.txt static/")?.path)
        assertTrue(GoEmbedCompletion.wordAt("//go:embed all:x")!!.all)
        assertTrue(GoEmbedCompletion.wordAt("//go:embed `x y")!!.quoted)
        assertNull(GoEmbedCompletion.wordAt("//go:embed"))
        assertNull(GoEmbedCompletion.wordAt("//go:embedx "))
        assertNull(GoEmbedCompletion.wordAt("//go:embed *.txt"))
        assertNull(GoEmbedCompletion.wordAt("//go:embed ../x"))
        assertNull(GoEmbedCompletion.wordAt("//go:embed /etc"))
        assertNull(GoEmbedCompletion.wordAt("// go:embed x"))
    }
}
