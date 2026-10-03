package io.github.golangsupport.ide.directives

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import io.github.golangsupport.lang.psi.GoNamedElement
import java.io.File

/** References of `//go:embed`, `//go:linkname` and `//go:generate` comments: their targets, and none in ordinary comments. */
class GoDirectiveReferencesTest : GoSemanticIdeTestBase() {

    private fun comments(): List<PsiComment> = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiComment::class.java).toList()

    /** `text of the reference -> sorted targets` for every directive reference of the file. */
    private fun references(): List<String> = comments().flatMap { c ->
        c.references.filterIsInstance<GoDirectiveReference>().map { r ->
            val targets = r.multiResolve(false).mapNotNull { it.element }.map(::describe).sorted()
            r.rangeInElement.substring(c.text) + " -> " + targets
        }
    }

    private fun describe(e: PsiElement): String = when (e) {
        is PsiDirectory -> "dir ${e.name}"
        is PsiFile -> e.name
        is GoNamedElement -> "${e.name} in ${e.containingFile.name}"
        else -> e.toString()
    }

    private fun embedFixture() {
        myFixture.addFileToProject("hello.txt", "hi")
        myFixture.addFileToProject("static/a.css", "a")
        myFixture.addFileToProject("static/b.css", "b")
        myFixture.addFileToProject("static/js/app.js", "j")
        myFixture.addFileToProject("static/.hidden", "h")
        myFixture.addFileToProject("static/_skip", "s")
        myFixture.addFileToProject("my file.txt", "s")
    }

    fun testEmbedFile() {
        embedFixture()
        myFixture.configureByText("a.go", "package p\n\nimport _ \"embed\"\n\n//go:embed hello.txt\nvar s string\n")
        assertEquals(listOf("hello.txt -> [hello.txt]"), references())
    }

    fun testEmbedGlobAndDirectoryAndAll() {
        embedFixture()
        myFixture.configureByText(
            "a.go",
            "package p\n\nimport \"embed\"\n\n//go:embed static/*.css static/js all:static/_skip\nvar f embed.FS\n",
        )
        assertEquals(
            listOf("static/*.css -> [a.css, b.css]", "static/js -> [dir js]", "static/_skip -> [_skip]"),
            references(),
        )
    }

    fun testEmbedQuotedPatternAndMissing() {
        embedFixture()
        myFixture.configureByText("a.go", "package p\n\nimport _ \"embed\"\n\n//go:embed \"my file.txt\" `hello.txt` nothing.txt ../x\nvar s string\n")
        assertEquals(listOf("my file.txt -> [my file.txt]", "hello.txt -> [hello.txt]", "nothing.txt -> []"), references())
    }

    fun testEmbedSingleTargetResolves() {
        embedFixture()
        myFixture.configureByText("a.go", "package p\n\n//go:embed hello.txt\nvar s string\n")
        val ref = comments().single().references.filterIsInstance<GoDirectiveReference>().single()
        assertEquals("hello.txt", (ref.resolve() as PsiFile).name)
    }

    fun testLinknameToStdlibFunctionAndMethod() {
        myFixture.configureByText(
            "a.go",
            "package p\n\nimport _ \"unsafe\"\n\n//go:linkname nanotime runtime.nanotime\nfunc nanotime() int64\n\n//go:linkname builderLen strings.Builder.Len\nfunc builderLen() int\n",
        )
        val refs = references()
        // the runtime file holding nanotime varies by Go version: only its name and that it is not the local stub are fixed
        assertEquals(4, refs.size)
        assertEquals("nanotime -> [nanotime in a.go]", refs[0])
        assertTrue(refs[1], refs[1].startsWith("nanotime -> [nanotime in ") && !refs[1].endsWith("in a.go]"))
        assertEquals("builderLen -> [builderLen in a.go]", refs[2])
        assertEquals("Builder.Len -> [Len in builder.go]", refs[3])
    }

    fun testLinknameToProjectPackage() {
        val tmp = FileUtil.createTempDirectory("gopsi-linkname", null, true)
        File(tmp, "go.mod").writeText("module example.com/m\n\ngo 1.22\n")
        File(tmp, "sub").mkdirs()
        File(tmp, "sub/sub.go").writeText("package sub\n\nfunc Hidden() int { return 1 }\n\ntype T struct{}\n\nfunc (t *T) Run() {}\n")
        File(tmp, "a.go").writeText(
            "package p\n\nimport _ \"unsafe\"\n\n//go:linkname hidden example.com/m/sub.Hidden\nfunc hidden() int\n\n//go:linkname run example.com/m/sub.(*T).Run\nfunc run()\n",
        )
        VfsRootAccess.allowRootAccess(testRootDisposable, tmp.path)
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        PsiTestUtil.addContentRoot(myFixture.module, dir)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            myFixture.configureFromExistingVirtualFile(dir.findChild("a.go")!!)
            assertEquals(
                listOf("hidden -> [hidden in a.go]", "Hidden -> [Hidden in sub.go]", "run -> [run in a.go]", "(*T).Run -> [Run in sub.go]"),
                references(),
            )
        } finally {
            PsiTestUtil.removeContentEntry(myFixture.module, dir)
        }
    }

    fun testLinknameUnresolvedTargetHasNoReference() {
        myFixture.configureByText("a.go", "package p\n\n//go:linkname f nowhere/pkg.f\nfunc f()\n")
        assertEquals(listOf("f -> [f in a.go]"), references())
    }

    fun testGenerateGoRunDirectoryAndFileArgument() {
        myFixture.addFileToProject("cmd/x/main.go", "package main\n\nfunc main() {}\n")
        myFixture.addFileToProject("schema/api.yaml", "a: 1\n")
        myFixture.configureByText(
            "a.go",
            "package p\n\n//go:generate go run ./cmd/x -in schema/api.yaml -out=schema/api.yaml gen.go missing/file.go\n//go:generate stringer -type=Kind\nvar x int\n",
        )
        assertEquals(
            listOf("./cmd/x -> [dir x]", "schema/api.yaml -> [api.yaml]", "schema/api.yaml -> [api.yaml]"),
            references(),
        )
    }

    fun testNoReferencesInOrdinaryComments() {
        embedFixture()
        myFixture.configureByText(
            "a.go",
            "package p\n\n// go:embed hello.txt\n// see hello.txt and ./cmd/x\n//go:embedded hello.txt\n//go:noinline\nfunc f() {\n\t//go:embed hello.txt\n}\n",
        )
        // the in-body directive is still a directive comment to the reference provider; the others must stay silent
        assertEquals(listOf("hello.txt -> [hello.txt]"), references())
    }
}
