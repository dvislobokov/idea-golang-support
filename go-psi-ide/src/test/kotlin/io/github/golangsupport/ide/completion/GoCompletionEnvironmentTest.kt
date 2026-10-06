package io.github.golangsupport.ide.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.util.ThreeState
import io.github.golangsupport.lang.psi.GoFile
import java.io.File

/**
 * Import-path completion (standard library and module packages on disk), positions without
 * completion, autopopup confidence, dumb mode, stub-only candidates and a performance budget.
 */
class GoCompletionEnvironmentTest : GoCompletionTestBase() {

    // --- import paths ---

    fun testImportPathFromStandardLibrary() {
        val items = lookups("""
            package main

            import "net/ht<caret>"
        """)
        assertContainsAll(items, "net/http", "net/http/httptest", "net/http/httputil")
        assertContainsNone(items, "net/http/internal", "fmt")
        select("net/http/httptest")
        myFixture.checkResult(go("""
            package main

            import "net/http/httptest<caret>"
        """))
    }

    fun testImportPathMatchesPathElementsAndSkipsImported() {
        val items = lookups("""
            package main

            import (
                "strings"
                "str<caret>"
            )
        """)
        assertContainsAll(items, "strconv")
        assertContainsNone(items, "strings", "internal/stringslite", "cmd/go")
        val byElement = lookups("""
            package main

            import "templ<caret>"
        """)
        assertContainsAll(byElement, "text/template", "html/template")
    }

    fun testImportPathFromModulePackages() {
        val main = copyModule("""
            package main

            import (
                "fmt"
                "example.com/shop/<caret>"
            )

            func main() {
                fmt.Println("shop")
            }
        """)
        myFixture.configureFromExistingVirtualFile(main.first)
        myFixture.editor.caretModel.moveToOffset(main.second)
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertContainsAll(items, "example.com/shop/internal/store", "example.com/shop/pkg/api", "example.com/shop/cmd/tool")
        assertContainsNone(items, "example.com/shop/testdata/ignored", "fmt")
    }

    fun testUnimportedModulePackageMemberAddsImportInNewGroup() {
        val main = copyModule("""
            package main

            import (
                "fmt"
            )

            func main() {
                fmt.Println(api.Hand<caret>{})
            }
        """)
        myFixture.configureFromExistingVirtualFile(main.first)
        myFixture.editor.caretModel.moveToOffset(main.second)
        myFixture.completeBasic()
        myFixture.checkResult(go("""
            package main

            import (
                "fmt"

                "example.com/shop/pkg/api"
            )

            func main() {
                fmt.Println(api.Handler<caret>{})
            }
        """))
    }

    /** Copies `testData/completion/module` to a temporary directory; writes [mainText] (with `<caret>`) as main.go. */
    private fun copyModule(mainText: String): Pair<VirtualFile, Int> {
        val tmp = FileUtil.createTempDirectory("gopsi-completion", null, true)
        FileUtil.copyDir(File(testDataPath, "completion/module"), tmp)
        val text = go(mainText)
        val caret = text.indexOf("<caret>")
        File(tmp, "main.go").writeText(text.replace("<caret>", ""))
        VfsRootAccess.allowRootAccess(testRootDisposable, tmp.path)
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        return dir.findChild("main.go")!! to caret
    }

    // --- no completion ---

    fun testNothingInCommentsAndStrings() {
        complete("""
            package main

            // pri<caret>
            func main() {}
        """)
        assertContainsNone(myFixture.lookupElementStrings.orEmpty(), "print", "println", "func", "main")
        complete("""
            package main

            func main() {
                _ = "pri<caret>"
            }
        """)
        assertContainsNone(myFixture.lookupElementStrings.orEmpty(), "print", "println", "panic")
    }

    fun testNothingWhenDeclaringNames() {
        complete("""
            package main

            func main() {
                var pri<caret> int
            }
        """)
        assertContainsNone(myFixture.lookupElementStrings.orEmpty(), "print", "println", "private")
    }

    fun testConfidenceSkipsAutopopupInCommentsStringsAndNumbers() {
        myFixture.configureByText("main.go", go("""
            package main

            import "fmt"

            // comment
            var s = "text"
            var f = 1.5
        """))
        val confidence = GoCompletionConfidence()
        val file = myFixture.file
        fun at(marker: String, delta: Int = 1): ThreeState {
            val offset = file.text.indexOf(marker) + delta
            return confidence.shouldSkipAutopopup(myFixture.editor, file.findElementAt(offset)!!, file, offset)
        }
        assertEquals(ThreeState.YES, at("comment"))
        assertEquals(ThreeState.YES, at("\"text\""))
        assertEquals(ThreeState.YES, at("1.5", 2))
        assertEquals(ThreeState.NO, at("\"fmt\""))
        assertEquals(ThreeState.UNSURE, at("var s", 1))
    }

    // --- environment ---

    fun testCompletionWorksInDumbMode() {
        myFixture.addFileToProject("decl.go", go("""
            package main

            type remote struct{ Field int }
        """))
        myFixture.configureByText("main.go", go("""
            package main

            func main() {
                rLocal := remote{}
                r<caret>
            }
        """))
        var items: List<String> = emptyList()
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(com.intellij.openapi.project.DumbService.isDumb(project))
            myFixture.completeBasic()
            items = myFixture.lookupElementStrings.orEmpty()
        }
        assertContainsAll(items, "rLocal", "remote", "return")
    }

    fun testCandidatesFromOtherFilesComeFromStubs() {
        val decl = myFixture.addFileToProject("decl.go", go("""
            package main

            type Account struct{ Balance int }

            func Open(name string) *Account { return nil }

            var Accounts = map[string]*Account{}

            var defaultName string = "x"

            const Limit = 10
        """)) as GoFile
        (decl as PsiFileImpl).let { f ->
            ApplicationManager.getApplication().runWriteAction { f.onContentReload() }
            assertNotNull(f.stub)
            assertNull(f.treeElement)
        }
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(VirtualFileFilter { it == decl.virtualFile }, testRootDisposable)
        val items = lookups("""
            package main

            func main() {
                var n int
                n = <caret>
            }
        """)
        assertContainsAll(items, "Account", "Open", "Accounts", "defaultName", "Limit")
        assertEquals("(name string)" to "*Account", presentation("Open").let { it.tailText to it.typeText })
        assertEquals("string", presentation("defaultName").typeText)
        assertNull("completion loaded decl.go's AST", (decl as PsiFileImpl).treeElement)
    }

    fun testCompletionInLargeFileIsFast() {
        val text = StringBuilder("package main\n\n")
        for (i in 0 until 500) {
            when (i % 4) {
                0 -> text.append("func helper$i(a int, b string) (int, error) {\n\treturn a, nil\n}\n\n")
                1 -> text.append("type Type$i struct {\n\tField$i int\n\tName string\n}\n\n")
                2 -> text.append("var value$i = helper${i - 2}\n\n")
                else -> text.append("const limit$i = $i\n\n")
            }
        }
        text.append("func main() {\n")
        while (text.count { it == '\n' } < 3010) text.append("\tx := 1\n\t_ = x\n")
        text.append("\tv<caret>\n}\n")
        myFixture.configureByText("big.go", text.toString())
        assertTrue(myFixture.editor.document.lineCount >= 3000)
        val times = ArrayList<Long>()
        repeat(6) {
            val start = System.nanoTime()
            myFixture.completeBasic()
            times += (System.nanoTime() - start) / 1_000_000
            assertTrue(myFixture.lookupElementStrings.orEmpty().contains("value2"))
            myFixture.lookup?.hideLookup(true)
        }
        // The first runs warm up caches and class loading; the budget applies to the best of the rest.
        val best = times.drop(2).min()
        println("completion in a ${myFixture.editor.document.lineCount}-line file: $times ms")
        assertTrue("completion took $best ms (runs: $times)", best < 300)
    }
}
