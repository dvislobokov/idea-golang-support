package io.github.golangsupport

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.settings.GoSettings

/**
 * Typing help of GoLand that the platform's Smart Keys switch: `}` that closes a block reformats it (the built-in formatter through
 * the platform's TypedHandler), Enter after a bare `//` above a declaration writes `// Name ` ([io.github.golangsupport.lang.GoDocCommentEnterHandler]).
 */
class GoTypingTest : BasePlatformTestCase() {
    private val smartKeys get() = CodeInsightSettings.getInstance()
    private var reformatBlock = true
    private var docStub = true
    private var docNames = true

    override fun setUp() {
        super.setUp()
        reformatBlock = smartKeys.REFORMAT_BLOCK_ON_RBRACE
        docStub = smartKeys.JAVADOC_STUB_ON_ENTER
        docNames = GoSettings.getInstance().docCommentNames
    }

    override fun tearDown() {
        try {
            smartKeys.REFORMAT_BLOCK_ON_RBRACE = reformatBlock
            smartKeys.JAVADOC_STUB_ON_ENTER = docStub
            GoSettings.getInstance().docCommentNames = docNames
        } finally {
            super.tearDown()
        }
    }

    private val unformatted = "package a\n\nfunc f(x int) {\n\tif x > 0 {\n      y :=   1\n  z:=2\n\t\tprintln(y,z)\n<caret>\n}\n"

    fun testTheClosingBraceOfABlockReformatsTheBlock() {
        smartKeys.REFORMAT_BLOCK_ON_RBRACE = true
        myFixture.configureByText("a.go", unformatted)
        myFixture.type('}')
        myFixture.checkResult("package a\n\nfunc f(x int) {\n\tif x > 0 {\n\t\ty := 1\n\t\tz := 2\n\t\tprintln(y, z)\n\t}<caret>\n}\n")
    }

    fun testWithoutTheSmartKeyOnlyTheBraceIsIndented() {
        smartKeys.REFORMAT_BLOCK_ON_RBRACE = false
        myFixture.configureByText("a.go", unformatted)
        myFixture.type('}')
        myFixture.checkResult("package a\n\nfunc f(x int) {\n\tif x > 0 {\n      y :=   1\n  z:=2\n\t\tprintln(y,z)\n\t}<caret>\n}\n")
    }

    fun testTheFieldsOfAStructAreAlignedLikeGofmt() {
        smartKeys.REFORMAT_BLOCK_ON_RBRACE = true
        myFixture.configureByText("a.go", "package a\n\ntype T struct {\n\tName string\n\tAge int // years\n\tID   int64\n<caret>\n")
        myFixture.type('}')
        myFixture.checkResult("package a\n\ntype T struct {\n\tName string\n\tAge  int // years\n\tID   int64\n}<caret>\n")
    }

    fun testEnterAfterBareSlashesAboveAFunctionWritesItsName() {
        myFixture.configureByText("a.go", "package a\n\n//<caret>\nfunc (s *Server) Start() {}\n\ntype Server struct{}\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        myFixture.checkResult("package a\n\n// Start <caret>\nfunc (s *Server) Start() {}\n\ntype Server struct{}\n")
    }

    fun testEnterAfterSlashesAboveATypeWritesItsName() {
        myFixture.configureByText("a.go", "package a\n\n\t//<caret>\ntype Server struct{}\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        myFixture.checkResult("package a\n\n\t// Server <caret>\ntype Server struct{}\n")
    }

    fun testEnterIsAnEnterWhereNoDeclarationFollows() {
        myFixture.configureByText("a.go", "package a\n\n//<caret>\n\nfunc f() {}\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        assertFalse(myFixture.editor.document.text.contains("// f"))
        assertTrue(myFixture.editor.document.text.startsWith("package a\n\n//\n"))
    }

    fun testTheStubFollowsTheSmartKeyAndTheGoSetting() {
        smartKeys.JAVADOC_STUB_ON_ENTER = false
        myFixture.configureByText("a.go", "package a\n\n//<caret>\nfunc f() {}\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        assertFalse(myFixture.editor.document.text.contains("// f"))

        smartKeys.JAVADOC_STUB_ON_ENTER = true
        GoSettings.getInstance().docCommentNames = false
        myFixture.configureByText("b.go", "package a\n\n//<caret>\nfunc g() {}\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        assertFalse(myFixture.editor.document.text.contains("// g"))
    }
}
