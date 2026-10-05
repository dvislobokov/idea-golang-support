package io.github.golangsupport.lang

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.settings.GoSettings

/**
 * Enter right after a bare `//` on the line above a declaration writes the stub of its doc comment instead of a new line: `// Name `, the
 * caret after it (GoLand's "Insert documentation comment stub"). Typing the second `/` does the same already ([GoDocCommentTypedHandler]);
 * this is for a `//` that is there without the name. The switches are the platform's (Settings | Editor | General | Smart Keys, Insert
 * documentation comment stub) and the plugin's "Start a doc comment with the name of the declaration".
 */
class GoDocCommentEnterHandler : EnterHandlerDelegateAdapter() {
    override fun preprocessEnter(
        file: PsiFile, editor: Editor, caretOffset: Ref<Int>, caretAdvance: Ref<Int>, dataContext: DataContext, originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        if (file !is GoFile || editor.caretModel.caretCount > 1 || editor.selectionModel.hasSelection()) return EnterHandlerDelegate.Result.Continue
        if (!CodeInsightSettings.getInstance().JAVADOC_STUB_ON_ENTER || !GoSettings.getInstance().docCommentNames) return EnterHandlerDelegate.Result.Continue
        val offset = caretOffset.get()
        val name = GoDocComments.nameToComment(editor.document.immutableCharSequence, offset) ?: return EnterHandlerDelegate.Result.Continue
        editor.document.insertString(offset, " $name ")
        editor.caretModel.moveToOffset(offset + name.length + 2)
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        return EnterHandlerDelegate.Result.Stop
    }
}
