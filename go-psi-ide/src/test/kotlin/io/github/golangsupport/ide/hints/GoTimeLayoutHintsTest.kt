package io.github.golangsupport.ide.hints

import com.intellij.codeInsight.hints.InlayDumpUtil
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayProviderPassInfo
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPass
import com.intellij.codeInsight.hints.declarative.impl.util.DeclarativeHintsDumpUtil
import com.intellij.codeInsight.hints.declarative.impl.views.TextInlayPresentationEntry
import com.intellij.codeInsight.multiverse.codeInsightContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.progress.EmptyProgressIndicator
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** The time layout hint: the rendered example after the layout argument of the `time` functions, and nothing after anything else. */
class GoTimeLayoutHintsTest : GoSemanticIdeTestBase() {

    private fun doTest(expected: String, provider: InlayHintsProvider, options: Map<String, Boolean> = emptyMap()) {
        val expectedText = expected.trimIndent()
        val source = InlayDumpUtil.removeInlays(expectedText)
        myFixture.configureByText("hints.go", source)
        assertEquals(expectedText.trim(), dump(source, provider, options).trim())
    }

    private fun dump(source: String, provider: InlayHintsProvider, options: Map<String, Boolean>): String {
        val pass = ActionUtil.underModalProgress(project, "") {
            DeclarativeInlayHintsPass(myFixture.file, myFixture.editor, listOf(InlayProviderPassInfo(provider, "provider.id", options)), isPreview = false)
        }
        pass.setContext(myFixture.file.codeInsightContext)
        ActionUtil.underModalProgress(project, "") { pass.doCollectInformation(EmptyProgressIndicator()) }
        pass.applyInformationToEditor()
        return DeclarativeHintsDumpUtil.dumpHints(source, editor = myFixture.editor, renderer = { list ->
            list.getEntries().joinToString("") { (it as TextInlayPresentationEntry).text }
        })
    }

    fun testFormatParseAndConstants() = doTest("""
        package p

        import (
        	"fmt"
        	"time"
        )

        const layout = "15:04"

        func f(t time.Time, s string, b []byte) {
        	_ = t.Format("2006-01-02 15:04"/*<# → 2026-03-07 15:09 #>*/)
        	_ = t.Format(layout/*<# → 15:09 #>*/)
        	_ = t.Format(time.RFC3339/*<# → 2026-03-07T15:09:08+03:00 #>*/)
        	_, _ = time.Parse("Jan _2"/*<# → Mar  7 #>*/, s)
        	_, _ = time.ParseInLocation("3:04PM"/*<# → 3:09PM #>*/, s, time.UTC)
        	_ = t.AppendFormat(b, "05.000"/*<# → 08.123 #>*/)
        	fmt.Println("2006-01-02", s)
        	_ = t.Format(s)
        }
    """, GoTimeLayoutHintsProvider())

    fun testOtherFormatMethodsAndUnresolved() = doTest("""
        package p

        type T struct{}

        func (T) Format(layout string) string { return layout }

        func f(x Unknown, t T) {
        	_ = t.Format("2006-01-02")
        	_ = x.Format("2006-01-02")
        }
    """, GoTimeLayoutHintsProvider())
}
