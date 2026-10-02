package io.github.golangsupport.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.github.golangsupport.lang.lexer.GoLexer
import io.github.golangsupport.lang.psi.GoElementTypes
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes

class GoParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = GoLexer()

    override fun createParser(project: Project?): PsiParser = GoParser()

    override fun getFileNodeType(): IFileElementType = GoElementTypes.FILE

    override fun getWhitespaceTokens(): TokenSet = GoTokenSets.WHITESPACES

    override fun getCommentTokens(): TokenSet = GoTokenSets.COMMENTS

    override fun getStringLiteralElements(): TokenSet = GoTokenSets.STRING_LITERALS

    override fun createElement(node: ASTNode): PsiElement = GoTypes.Factory.createElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = GoFile(viewProvider)
}
