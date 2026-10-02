package io.github.golangsupport.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.LighterASTNode
import com.intellij.lang.LighterLazyParseableNode
import com.intellij.lang.PsiBuilderFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.LazyParseableElement
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.ILightLazyParseableElementType
import com.intellij.psi.tree.IReparseableElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.util.diff.FlyweightCapableTreeStructure
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.lexer.GoLexer
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicLong

/**
 * The element type of every `Block` (`GoTypes.BLOCK`, PSI [io.github.golangsupport.lang.psi.GoBlock]).
 *
 * Function bodies (of declarations and function literals) are collapsed by
 * [GoParserUtil.lazyBlock] into a [LazyParseableElement] of this type and parsed with the `Block`
 * rule on first access; nested blocks (`if`, `for`, `{}` statements) are ordinary composite nodes
 * of the same type, created while a body is parsed. A change inside a body re-parses only that body
 * when [isReparseable] allows it; the platform then merges the new body into the old one
 * (`BlockSupportImpl.mergeTrees`), so the body node and every untouched node keep their identity.
 * See docs/GRAMMAR.md section N.
 */
@ApiStatus.Internal
class GoLazyBlockElementType(@NonNls debugName: String) : IReparseableElementType(debugName, GoLanguage), ILightLazyParseableElementType {

    override fun createNode(text: CharSequence?): ASTNode = LazyParseableElement(this, text)

    override fun doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode? {
        PARSES.incrementAndGet()
        val builder = PsiBuilderFactory.getInstance().createBuilder(psi.project, chameleon, null, GoLanguage, chameleon.chars)
        GoParserUtil.startBodyChunk(builder)
        val root = GoParser().parse(this, builder)
        // The root marker collapses with the `Block` marker (Grammar-Kit _COLLAPSE_); unwrap if not.
        val first = root.firstChildNode
        return if (first != null && first.elementType === this && first.treeNext == null) first.firstChildNode else first
    }

    /**
     * The light-tree parse of a collapsed body. The platform uses it when it diffs a re-parsed file
     * (or body) against the old tree: an edited body that was already expanded is then compared node
     * by node instead of being replaced as a whole, so the change keeps its precise PSI events.
     */
    override fun parseContents(chameleon: LighterLazyParseableNode): FlyweightCapableTreeStructure<LighterASTNode> {
        PARSES.incrementAndGet()
        val project = chameleon.containingFile?.project ?: ProjectManager.getInstance().defaultProject
        val builder = PsiBuilderFactory.getInstance().createBuilder(project, chameleon, null, GoLanguage, chameleon.text)
        GoParserUtil.startBodyChunk(builder)
        GoParser().parseLight(this, builder)
        return builder.lightTree
    }

    /**
     * True when [newText] re-parsed alone yields exactly what a full parse of the new file would give
     * for this body (docs/GRAMMAR.md section N): [currentNode] is a function body that no lookahead
     * of the enclosing code depends on, and [newText] is still one balanced `{ ... }` whose extent
     * [GoParserUtil.lazyBlock] would find again.
     */
    override fun isReparseable(currentNode: ASTNode, newText: CharSequence, fileLanguage: Language, project: Project): Boolean =
        isReparseableBody(currentNode) && isSelfContainedBody(newText)

    companion object {
        private val PARSES = AtomicLong()

        /** Number of lazy body parses so far (tests: stub building and indexing must not parse bodies). */
        @TestOnly
        @JvmStatic
        fun parseCount(): Long = PARSES.get()

        /**
         * [node] is the body of a top-level function or method whose `func` keyword is at column 0,
         * or of a function literal inside such a declaration that no lookahead of the enclosing code
         * scans: a bounded lookahead (`SCAN_LIMIT` tokens) over a literal in a control clause header,
         * a literal value, an index or a type may change its result with the literal's length. The
         * column-0 condition keeps the body out of reach of the column-0 recovery scan of an earlier
         * unclosed body ([GoParserUtil.columnZeroDeclaration] stops at a column-0 `func`).
         */
        @JvmStatic
        fun isReparseableBody(node: ASTNode): Boolean {
            var child = node
            var e: ASTNode = node.treeParent ?: return false
            val parentType = e.elementType
            if (parentType !== GoTypes.FUNCTION_DECLARATION && parentType !== GoTypes.METHOD_DECLARATION && parentType !== GoTypes.FUNCTION_LIT) {
                return false
            }
            while (true) {
                val type = e.elementType
                if (type === GoTypes.FUNCTION_DECLARATION || type === GoTypes.METHOD_DECLARATION) {
                    return e.treeParent?.elementType is IFileElementType && startsLine(e)
                }
                if (Sets.LOOKAHEAD_CONTEXTS.contains(type)) return false
                if (Sets.HEADER_STATEMENTS.contains(type) && !Sets.NOT_HEADER.contains(child.elementType)) return false
                child = e
                e = e.treeParent ?: return false
                // A literal outside functions (a package-level initializer).
                if (e.elementType is IFileElementType) return false
            }
        }

        /** The `func` keyword of the declaration [decl] starts a line. */
        private fun startsLine(decl: ASTNode): Boolean {
            val func = decl.findChildByType(GoTypes.FUNC) ?: return false
            val prev = TreeUtil.prevLeaf(func) ?: return true
            val chars = prev.chars
            return chars.isNotEmpty() && chars[chars.length - 1] == '\n'
        }

        /**
         * [text] lexes (from the initial lexer state, as after `{` in any file) to `{`, tokens, `}`,
         * with the first `{` matched by the last `}` (which ends the text), and contains nothing at
         * which [GoParserUtil.lazyBlock] would end the body early: no `func IDENT` and no declaration
         * keyword at column 0. An unterminated raw string or block comment swallows the final `}`, a
         * stray `}` closes the body early, a missing one leaves it open: all rejected. The lexer state
         * after the body is unchanged: a `}` always leaves the lexer in its semicolon-insertion state.
         */
        @JvmStatic
        fun isSelfContainedBody(text: CharSequence): Boolean {
            if (text.isEmpty() || text[0] != '{' || text[text.length - 1] != '}') return false
            val lexer = GoLexer()
            lexer.start(text)
            var depth = 0
            var afterFunc = false
            while (true) {
                val t: IElementType = lexer.tokenType ?: return false
                if (t === TokenType.WHITE_SPACE || GoTokenSets.COMMENTS.contains(t)) {
                    lexer.advance()
                    continue
                }
                if (afterFunc && t === GoTypes.IDENTIFIER) return false
                afterFunc = t === GoTypes.FUNC
                val start = lexer.tokenStart
                when {
                    t === GoTypes.LBRACE -> depth++
                    t === GoTypes.RBRACE -> if (--depth == 0) return lexer.tokenEnd == text.length
                    Sets.DECLARATION_KEYWORDS.contains(t) -> if (start > 0 && text[start - 1] == '\n') return false
                }
                if (depth <= 0) return false
                lexer.advance()
            }
        }
    }
}

/**
 * Token sets over `GoTypes`, in their own class: [GoLazyBlockElementType] is created while `GoTypes`
 * is being initialized, so its own static state must not touch `GoTypes`.
 */
private object Sets {
    val DECLARATION_KEYWORDS: TokenSet = TokenSet.create(GoTypes.CONST, GoTypes.TYPE_, GoTypes.VAR, GoTypes.IMPORT, GoTypes.FUNC)

    /** Nodes whose parse runs a bounded lookahead over any function literal inside them (GoParserUtil sections C-G). */
    val LOOKAHEAD_CONTEXTS: TokenSet = TokenSet.create(
        GoTypes.COMM_CASE, GoTypes.LITERAL_VALUE, GoTypes.INDEX_OR_SLICE_EXPR, GoTypes.ARRAY_OR_SLICE_TYPE,
        GoTypes.PARAMETERS, GoTypes.TYPE_PARAMETERS, GoTypes.FIELD_DECLARATION, GoTypes.RECEIVER, GoTypes.TYPE_SPEC,
    )

    /** Statements whose header is scanned by a lookahead (`hasInitStatement`, `isRangeClause`, `isTypeSwitch`). */
    val HEADER_STATEMENTS: TokenSet = TokenSet.create(
        GoTypes.IF_STATEMENT, GoTypes.FOR_STATEMENT, GoTypes.EXPR_SWITCH_STATEMENT, GoTypes.TYPE_SWITCH_STATEMENT,
    )

    /** Children of [HEADER_STATEMENTS] outside the header (a lookahead over them cannot change its result). */
    val NOT_HEADER: TokenSet = TokenSet.create(GoTypes.BLOCK, GoTypes.ELSE_STATEMENT, GoTypes.EXPR_CASE_CLAUSE, GoTypes.TYPE_CASE_CLAUSE)
}
