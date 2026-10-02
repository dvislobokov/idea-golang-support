package io.github.golangsupport.lang.lexer

import io.github.golangsupport.lang.psi.GoBuildConstraint
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.impl.GoPsiImplUtil
import com.intellij.psi.TokenType
import org.jetbrains.annotations.ApiStatus

/**
 * Lexer-only scan of a file header: build constraints, package name and imports. Used by the
 * file-based indices and the file stub, so neither needs a parse tree.
 *
 * Build constraints follow go/build `parseFileHeader`: only `//` comments in the leading comment
 * block that end before the last blank line preceding the package clause count.
 */
@ApiStatus.Internal
object GoFileHeaderScanner {

    data class Import(val path: String, val alias: String?)

    data class Header(val packageName: String?, val buildConstraint: GoBuildConstraint, val imports: List<Import>)

    fun scan(text: CharSequence, withImports: Boolean = true): Header {
        val lexer = GoLexer()
        lexer.start(text)

        // 1. Build constraints: a port of go/build parseFileHeader + shouldBuild (build.go).
        val constraint = parseBuildConstraint(text)
        while (lexer.tokenType === TokenType.WHITE_SPACE || GoTokenSets.COMMENTS.contains(lexer.tokenType)) lexer.advance()

        // 2. package Name
        if (lexer.tokenType !== GoTypes.PACKAGE) return Header(null, constraint, emptyList())
        lexer.advanceSignificant()
        val packageName = if (lexer.tokenType === GoTypes.IDENTIFIER) lexer.tokenText else null
        if (!withImports) return Header(packageName, constraint, emptyList())

        // 3. import declarations
        val imports = mutableListOf<Import>()
        lexer.advanceSignificant()
        while (true) {
            while (lexer.tokenType != null && GoTokenSets.SEMICOLONS.contains(lexer.tokenType)) lexer.advanceSignificant()
            if (lexer.tokenType !== GoTypes.IMPORT) break
            lexer.advanceSignificant()
            if (lexer.tokenType === GoTypes.LPAREN) {
                lexer.advanceSignificant()
                while (lexer.tokenType != null && lexer.tokenType !== GoTypes.RPAREN) {
                    if (GoTokenSets.SEMICOLONS.contains(lexer.tokenType)) {
                        lexer.advanceSignificant()
                        continue
                    }
                    val spec = lexer.readImportSpec() ?: return Header(packageName, constraint, imports)
                    imports += spec
                }
                lexer.advanceSignificant()
            } else {
                imports += lexer.readImportSpec() ?: break
            }
        }
        return Header(packageName, constraint, imports)
    }

    /** `[alias | .] "path"`; leaves the lexer after the path, or returns null on anything else. */
    private fun GoLexer.readImportSpec(): Import? {
        var alias: String? = null
        if (tokenType === GoTypes.IDENTIFIER || tokenType === GoTypes.PERIOD) {
            alias = tokenText
            advanceSignificant()
        }
        if (tokenType !== GoTypes.STRING && tokenType !== GoTypes.RAW_STRING) return null
        val path = GoPsiImplUtil.unquote(tokenText)
        advanceSignificant()
        return Import(path, alias)
    }

    private fun GoLexer.advanceSignificant() {
        advance()
        while (tokenType === TokenType.WHITE_SPACE || GoTokenSets.COMMENTS.contains(tokenType)) advance()
    }

    /**
     * go/build `parseFileHeader`: walks the leading run of comment and blank lines. A `//go:build`
     * line counts anywhere in that run (outside `/* */`), even directly above `package`; the first
     * one wins. `// +build` lines (go/build `shouldBuild`) count only before the last blank line
     * that precedes the first non-`//` line.
     */
    fun parseBuildConstraint(text: CharSequence): GoBuildConstraint {
        var end = 0
        var ended = false
        var inSlashStar = false
        var goBuild: String? = null
        var p = 0
        val n = text.length
        lines@ while (p < n) {
            val nl = indexOf(text, '\n', p)
            val lineStart = p
            val lineEnd = if (nl >= 0) nl else n
            p = if (nl >= 0) nl + 1 else n
            var line = text.subSequence(lineStart, lineEnd).trim()
            if (line.isEmpty() && !ended) {
                end = p
                continue@lines
            }
            if (!line.startsWith("//")) ended = true
            if (!inSlashStar && goBuild == null && isGoBuildComment(line)) {
                goBuild = line.subSequence(2, line.length).substring(8).trim()
            }
            comments@ while (line.isNotEmpty()) {
                if (inSlashStar) {
                    val i = line.indexOf("*/")
                    if (i >= 0) {
                        inSlashStar = false
                        line = line.subSequence(i + 2, line.length).trim()
                        continue@comments
                    }
                    continue@lines
                }
                if (line.startsWith("//")) continue@lines
                if (line.startsWith("/*")) {
                    inSlashStar = true
                    line = line.subSequence(2, line.length).trim()
                    continue@comments
                }
                break@lines
            }
        }
        val plusBuild = mutableListOf<String>()
        for (raw in text.subSequence(0, end).split('\n')) {
            val line = raw.trim()
            if (line.startsWith("//") && line.substring(2).trimStart().startsWith("+build")) plusBuild += line.substring(2).trim()
        }
        return GoBuildConstraint(goBuild, plusBuild)
    }

    /** go/build `isGoBuildComment`: `//go:build` followed by whitespace or end of line. */
    private fun isGoBuildComment(line: CharSequence): Boolean {
        if (!line.startsWith("//")) return false
        val rest = line.subSequence(2, line.length) // no space allowed between `//` and `go:build`
        return rest.startsWith("go:build") && (rest.length == 8 || rest[8] == ' ' || rest[8] == '	')
    }

    private fun indexOf(text: CharSequence, ch: Char, from: Int): Int {
        for (i in from until text.length) if (text[i] == ch) return i
        return -1
    }
}
