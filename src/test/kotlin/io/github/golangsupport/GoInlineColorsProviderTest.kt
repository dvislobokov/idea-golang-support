package io.github.golangsupport

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionTextElement
import com.intellij.codeInsight.inline.completion.testInlineCompletion
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.DefaultGoToolchainProvider
import io.github.golangsupport.settings.GoSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * The provider of the grey text through the inline completion of the platform: the idiom after Enter is shown as several elements of
 * different colours, and accepting it (whole, or a word at a time) writes the same text the grey one would.
 */
class GoInlineColorsProviderTest : BasePlatformTestCase() {
    private var languageServer = true
    private var colors = true

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        languageServer = GoSettings.getInstance().languageServerEnabled
        colors = GoSettings.getInstance().inlineSuggestionColors
        GoSettings.getInstance().languageServerEnabled = false
        val goroot = Paths.get(System.getProperty("gopsi.goroot")?.takeIf { it.isNotBlank() } ?: "C:\\Program Files\\Go")
        val toolchain = GoToolchainInfo(
            goroot = goroot, version = DefaultGoToolchainProvider.readVersion(goroot), gopath = emptyList(),
            gomodcache = Paths.get(System.getProperty("user.home"), "go", "pkg", "mod"), goos = "linux", goarch = "amd64", cgoEnabled = true,
        )
        val fixed = object : GoToolchainProvider {
            override fun toolchainFor(project: com.intellij.openapi.project.Project?): GoToolchainInfo = toolchain
        }
        ApplicationManager.getApplication().replaceService(GoToolchainProvider::class.java, fixed, testRootDisposable)
        VfsRootAccess.allowRootAccess(testRootDisposable, goroot.toString())
    }

    override fun tearDown() {
        try {
            GoSettings.getInstance().languageServerEnabled = languageServer
            GoSettings.getInstance().inlineSuggestionColors = colors
        } finally {
            super.tearDown()
        }
    }

    private val source = "package a\n\nfunc g() (int, error) { return 0, nil }\n\nfunc f() error {\n\tn, err := g()<caret>\n\t_ = n\n\treturn nil\n}\n"

    fun testTheIdiomIsShownInSeveralColours() = myFixture.testInlineCompletion {
        GoSettings.getInstance().inlineSuggestionColors = true
        init(GoFileType, source)
        typeChar('\n')
        delay()
        val context = assertContextExists()
        val shown = context.textToInsert()
        assertTrue(shown, shown.contains("if err != nil {"))
        val elements = context.state.elements.map { it.element }
        assertTrue("one element per colour: ${elements.map { it.text }}", elements.size > 3)
        assertTrue(elements.all { it is InlineCompletionTextElement })
        val colours = withContext(Dispatchers.EDT) { elements.map { (it as InlineCompletionTextElement).getAttributes(myFixture.editor).foregroundColor }.toSet() }
        assertTrue("several colours: $colours", colours.size >= 2)
        insert()
        withContext(Dispatchers.EDT) { assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("n, err := g()\n\t" + shown.trimStart())) }
    }

    fun testAWordAtATime() = myFixture.testInlineCompletion {
        GoSettings.getInstance().inlineSuggestionColors = true
        init(GoFileType, source)
        typeChar('\n')
        delay()
        val whole = assertContextExists().textToInsert()
        callAction(IdeActions.ACTION_INSERT_INLINE_COMPLETION_WORD)
        delay()
        val rest = assertContextExists().textToInsert()
        assertTrue("'$rest' is the end of '$whole'", rest.isNotEmpty() && rest.length < whole.length && whole.endsWith(rest))
        insert()
        withContext(Dispatchers.EDT) { assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains(whole.trimStart())) }
    }

    fun testTheCaretGoesInsideTheQuotesAndTheImportIsAdded() = myFixture.testInlineCompletion {
        init(GoFileType, "package a\n\nvar emailRe =<caret>\n")
        typeChar(' ')
        delay()
        assertEquals("regexp.MustCompile(``)", assertContextExists().textToInsert())
        insert()
        withContext(Dispatchers.EDT) {
            val text = myFixture.editor.document.text
            assertTrue(text, text.contains("import \"regexp\"") && text.contains("var emailRe = regexp.MustCompile(``)"))
            val caret = myFixture.editor.caretModel.offset
            assertEquals("`)", text.substring(caret, caret + 2))
        }
    }

    fun testPlainGreyWhenTurnedOff() = myFixture.testInlineCompletion {
        GoSettings.getInstance().inlineSuggestionColors = false
        init(GoFileType, source)
        typeChar('\n')
        delay()
        val elements = assertContextExists().state.elements
        assertEquals(1, elements.size)
        assertTrue(elements[0].element is com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement)
    }
}
