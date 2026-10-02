package io.github.golangsupport.ide

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.lexer.GoLexer

/**
 * Test-only highlighter over the PSI lexer, without colours: brace matching, quote handling and the TODO index read the tokens of the
 * editor highlighter. The plugin's highlighter and palette are the root module's (`lang.GoSyntaxHighlighter`, `lang.GoColors`).
 */
class GoTestSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = object : SyntaxHighlighterBase() {
        override fun getHighlightingLexer(): Lexer = GoLexer()
        override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = TextAttributesKey.EMPTY_ARRAY
    }
}
