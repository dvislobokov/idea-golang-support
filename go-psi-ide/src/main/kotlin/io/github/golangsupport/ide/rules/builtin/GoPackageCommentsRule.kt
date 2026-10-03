package io.github.golangsupport.ide.rules.builtin

import com.intellij.psi.TokenType
import io.github.golangsupport.ide.rules.GoPackageRule
import io.github.golangsupport.ide.rules.GoPackageRuleContext
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.ide.rules.GoRulePackage
import io.github.golangsupport.lang.lexer.GoLexer
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes

/**
 * revive `package-comments`: no file of the package has a package comment. Reported once, on the package clause of the first
 * non-test file by name. The comment is found by the lexer (no AST of the other files): the comment group right above `package`,
 * directives (`//go:build`, `// +build`) not counting as documentation. Off by default, like the doc-comment inspection.
 */
class GoPackageCommentsRule : GoPackageRule() {
    override val id: String get() = "revive:package-comments"
    override val linter: String get() = "revive"
    override val title: String get() = "Package without a package comment"
    override val description: String get() = "No file of the package has a <code>// Package name ...</code> comment above its package clause."
    override val defaultLevel: GoRuleLevel get() = GoRuleLevel.WEAK_WARNING
    override val enabledByDefault: Boolean get() = false
    override val needs: Set<GoRuleNeed> get() = NEEDS

    override fun checkPackage(pkg: GoRulePackage, ctx: GoPackageRuleContext) {
        val files = pkg.nonTestFiles.ifEmpty { return }
        if (files.any { hasPackageDoc(it.viewProvider.contents) }) return
        val clause = files.first().packageClause ?: return
        ctx.report(clause, "should have a package comment")
    }

    companion object {
        private val NEEDS = setOf(GoRuleNeed.PROJECT_INDEX)
        private val DIRECTIVE = Regex("""^//(go:|line |export |extern |\s*\+build)""")

        /** Whether the comments directly above the `package` keyword (no blank line between) hold more than directives. */
        fun hasPackageDoc(text: CharSequence): Boolean {
            val lexer = GoLexer()
            lexer.start(text)
            val group = ArrayList<String>()
            while (lexer.tokenType != null && lexer.tokenType !== GoTypes.PACKAGE) {
                val type = lexer.tokenType
                when {
                    GoTokenSets.COMMENTS.contains(type) -> group += lexer.tokenText
                    type === TokenType.WHITE_SPACE -> if (lexer.tokenText.count { it == '\n' } > 1) group.clear()
                    else -> group.clear()
                }
                lexer.advance()
            }
            if (lexer.tokenType !== GoTypes.PACKAGE) return false
            return group.any { comment -> comment.startsWith("/*") || !DIRECTIVE.containsMatchIn(comment) && comment.removePrefix("//").isNotBlank() }
        }
    }
}
