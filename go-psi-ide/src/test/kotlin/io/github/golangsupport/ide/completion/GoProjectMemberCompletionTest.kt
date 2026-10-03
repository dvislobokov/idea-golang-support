package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.io.File

/** Exported names of unimported packages of the current module by their bare name (A3), from the stub index. */
class GoProjectMemberCompletionTest : GoCompletionTestBase() {

    private val module = mapOf(
        "go.mod" to "module example.com/app\n\ngo 1.22\n",
        "helpers.go" to "package main\n\nfunc HandleLocal() {}\n",
        "pkg/api/api.go" to """
            package api

            type Handler struct{}

            func HandleAll(n int) string { return "" }

            var HandlerCount int = 0

            const HandlerLimit = 3

            func handleHidden() {}
        """.trimIndent() + "\n",
        "pkg/api/api_test.go" to "package api\n\nfunc HandleTest() {}\n",
        "pkg/api/internal/secret/secret.go" to "package secret\n\nfunc HandleSecret() {}\n",
        "internal/shared/shared.go" to "package shared\n\nfunc HandleShared() {}\n",
        "testdata/x/x.go" to "package x\n\nfunc HandleIgnored() {}\n",
        "cmd/tool/main.go" to "package main\n\nfunc HandleMain() {}\n",
    )

    /** Writes [module] and `main.go` ([mainText], with `<caret>`) to a temporary content root, completes with [type] and runs [check]. */
    private fun inModule(mainText: String, type: CompletionType = CompletionType.BASIC, check: () -> Unit) {
        val tmp = FileUtil.createTempDirectory("gopsi-project-members", null, true)
        for ((path, text) in module) File(tmp, path).also { it.parentFile.mkdirs() }.writeText(text)
        val text = go(mainText)
        val caret = text.indexOf("<caret>")
        File(tmp, "main.go").writeText(text.replace("<caret>", ""))
        VfsRootAccess.allowRootAccess(testRootDisposable, tmp.path)
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        PsiTestUtil.addContentRoot(module(), dir)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            myFixture.configureFromExistingVirtualFile(dir.findChild("main.go")!!)
            myFixture.editor.caretModel.moveToOffset(caret)
            myFixture.complete(type)
            check()
        } finally {
            PsiTestUtil.removeContentEntry(module(), dir)
        }
    }

    private fun module() = myFixture.module

    private fun items(): List<String> = myFixture.lookupElementStrings.orEmpty()

    fun testExportedNamesOfUnimportedProjectPackages() = inModule("""
        package main

        func main() {
            Handl<caret>
        }
    """) {
        val items = items()
        assertContainsAll(items, "api.Handler", "api.HandleAll", "api.HandlerCount", "api.HandlerLimit", "shared.HandleShared", "HandleLocal")
        assertContainsNone(items, "secret.HandleSecret", "api.HandleTest", "x.HandleIgnored", "main.HandleMain", "api.handleHidden", "main.HandleLocal")
        assertEquals("(n int) string", presentation("api.HandleAll").tailText)
        assertEquals("example.com/app/pkg/api", presentation("api.HandleAll").typeText)
    }

    fun testInsertWritesQualifierAndImport() = inModule("""
        package main

        import (
            "fmt"
        )

        func main() {
            fmt.Println(HandleA<caret>)
        }
    """) {
        if (myFixture.lookup != null) select("api.HandleAll")
        myFixture.checkResult(go("""
            package main

            import (
                "fmt"

                "example.com/app/pkg/api"
            )

            func main() {
                fmt.Println(api.HandleAll(<caret>))
            }
        """))
    }

    fun testExpectedTypeRanksAndSmartFilters() {
        inModule("""
            package main

            func main() {
                var s string = Handl<caret>
                _ = s
            }
        """) {
            val items = items()
            assertTrue(items.toString(), items.indexOf("api.HandleAll") in 0 until items.indexOf("api.HandlerCount"))
        }
        inModule("""
            package main

            func main() {
                var n int = Handl<caret>
                _ = n
            }
        """, CompletionType.SMART) {
            // The only fitting item is inserted directly.
            assertNull(items().toString(), myFixture.lookup)
            assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("var n int = api.HandlerCount\n"))
        }
    }

    fun testTypePositionOffersTypesOnly() = inModule("""
        package main

        func main() {
            var h Handl<caret>
            _ = h
        }
    """) {
        val items = items()
        assertContainsNone(items, "api.HandleAll", "api.HandlerCount")
        if (myFixture.lookup != null) assertContainsAll(items, "api.Handler")
        else assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("var h api.Handler"))
    }

    fun testOneCharacterIsNotEnough() = inModule("""
        package main

        type Hx struct{}
        type Hy struct{}

        func main() {
            H<caret>
        }
    """) {
        assertContainsNone(items(), "api.Handler", "api.HandleAll")
    }

    fun testInternalVisibilityRule() {
        assertTrue(GoProjectMemberCandidates.importable("example.com/app/pkg/api", "example.com/app"))
        assertTrue(GoProjectMemberCandidates.importable("example.com/app/internal/shared", "example.com/app"))
        assertTrue(GoProjectMemberCandidates.importable("example.com/app/internal/shared", "example.com/app/cmd/tool"))
        assertFalse(GoProjectMemberCandidates.importable("example.com/app/pkg/api/internal/secret", "example.com/app"))
        assertTrue(GoProjectMemberCandidates.importable("example.com/app/pkg/api/internal/secret", "example.com/app/pkg/api/v2"))
        assertFalse(GoProjectMemberCandidates.importable("example.com/app/internal/shared", "example.com/other"))
        assertFalse(GoProjectMemberCandidates.importable("example.com/app/internal/shared", null))
    }
}
