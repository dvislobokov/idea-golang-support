package io.github.golangsupport.ide.editor.paste

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.ExtensionTestUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoFile
import java.awt.datatransfer.StringSelection

/** Imports that follow pasted Go code: the copy record between files, aliases, duplicates, the setting, and text from outside. */
class GoPasteImportsTest : GoSemanticIdeTestBase() {

    private var savedSetting = CodeInsightSettings.YES

    override fun setUp() {
        super.setUp()
        savedSetting = CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE
        CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE = CodeInsightSettings.YES
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE = savedSetting
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun copyPaste(source: String, target: String): String {
        myFixture.configureByText("src.go", source)
        myFixture.performEditorAction(IdeActions.ACTION_COPY)
        myFixture.configureByText("dst.go", target)
        myFixture.performEditorAction(IdeActions.ACTION_PASTE)
        return myFixture.editor.document.text
    }

    /** The import paths of the target file, as written (`str "strings"`). */
    private fun imports(): List<String> = (myFixture.file as GoFile).imports.map { it.text }

    fun testAddsTheImportsOfCopiedQualifiers() {
        copyPaste(
            "package p\n\nimport (\n\t\"fmt\"\n\t\"strings\"\n)\n\nfunc f() {\n\t<selection>fmt.Println(strings.ToUpper(\"x\"))</selection>\n}\n",
            "package q\n\nimport \"os\"\n\nfunc g() {\n\t_ = os.Args\n\t<caret>\n}\n",
        )
        assertEquals(listOf("\"fmt\"", "\"os\"", "\"strings\""), imports())
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("package q\n\nimport (\n\t\"fmt\"\n\t\"os\"\n\t\"strings\"\n)\n"))
    }

    fun testDoesNotDuplicateAnExistingImport() {
        copyPaste(
            "package p\n\nimport (\n\t\"fmt\"\n\t\"strings\"\n)\n\nfunc f() {\n\t<selection>fmt.Println(strings.ToUpper(\"x\"))</selection>\n}\n",
            "package q\n\nimport \"fmt\"\n\nfunc g() {\n\tfmt.Println()\n\t<caret>\n}\n",
        )
        assertEquals(listOf("\"fmt\"", "\"strings\""), imports())
    }

    fun testKeepsTheAliasOfTheSource() {
        copyPaste(
            "package p\n\nimport str \"strings\"\n\nfunc f() string {\n\treturn <selection>str.ToUpper(\"x\")</selection>\n}\n",
            "package q\n\nfunc g() string {\n\treturn <caret>\n}\n",
        )
        assertEquals(listOf("str \"strings\""), imports())
    }

    fun testTypeQualifierAndNoImportForATakenName() {
        // `bytes` is imported for the type; `strings` is a variable of the target, so no import makes it a package.
        copyPaste(
            "package p\n\nimport (\n\t\"bytes\"\n\t\"strings\"\n)\n\n<selection>var b bytes.Buffer\nvar s = strings.ToUpper(\"x\")</selection>\n",
            "package q\n\nvar strings struct{ ToUpper func(string) string }\n\n<caret>\n",
        )
        assertEquals(listOf("\"bytes\""), imports())
    }

    fun testPathImportedUnderAnotherNameIsLeftAlone() {
        copyPaste(
            "package p\n\nimport \"strings\"\n\nvar x = <selection>strings.ToUpper(\"x\")</selection>\n",
            "package q\n\nimport s \"strings\"\n\nvar _ = s.ToLower(\"y\")\nvar y = <caret>\n",
        )
        assertEquals(listOf("s \"strings\""), imports())
    }

    fun testSettingNoAddsNothing() {
        CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE = CodeInsightSettings.NO
        copyPaste(
            "package p\n\nimport \"strings\"\n\nvar x = <selection>strings.ToUpper(\"x\")</selection>\n",
            "package q\n\nvar y = <caret>\n",
        )
        assertEquals(emptyList<String>(), imports())
    }

    fun testCopyWithinTheSameFileChangesNoImports() {
        myFixture.configureByText("same.go", "package p\n\nimport \"strings\"\n\nvar x = <selection>strings.ToUpper(\"x\")</selection>\nvar y = 1\n")
        myFixture.performEditorAction(IdeActions.ACTION_COPY)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
        myFixture.performEditorAction(IdeActions.ACTION_PASTE)
        assertEquals(listOf("\"strings\""), imports())
    }

    fun testExternalTextUsesTheResolvers() {
        val asked = ArrayList<Pair<String, Set<String>>>()
        val resolver = object : GoPasteImportResolver {
            override fun importPathFor(file: GoFile, packageName: String, members: Set<String>): String? {
                asked += packageName to members
                return if (packageName == "json" && members == setOf("Marshal", "Unmarshal")) "encoding/json" else null
            }
        }
        ExtensionTestUtil.maskExtensions(GoPasteImportResolver.EP_NAME, listOf(resolver), testRootDisposable)
        myFixture.configureByText("ext.go", "package q\n\nimport \"fmt\"\n\nfunc g(v any) {\n\t<caret>\n}\n")
        CopyPasteManager.getInstance().setContents(StringSelection("b, _ := json.Marshal(v)\n\t_ = json.Unmarshal(b, &v)\n\tfmt.Println(template.New(\"x\"))\n"))
        myFixture.performEditorAction(IdeActions.ACTION_PASTE)
        assertEquals(listOf("\"encoding/json\"", "\"fmt\""), imports())
        // `fmt` resolves already: only the unresolved qualifiers are asked about.
        assertEquals(listOf("json" to setOf("Marshal", "Unmarshal"), "template" to setOf("New")), asked)
    }
}
