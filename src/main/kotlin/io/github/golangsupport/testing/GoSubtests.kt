package io.github.golangsupport.testing

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.TokenType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.GoDeclarationInfo
import io.github.golangsupport.lang.GoDeclarationPsi
import io.github.golangsupport.lang.GoScopeInputs
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.GoTextLexer
import io.github.golangsupport.lang.GoStructure
import io.github.golangsupport.lang.GoTextTokens
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments

/**
 * A subtest a test function names in its body: `t.Run("empty", ...)`, or a case of a table, `{name: "empty", ...}`. [nameRange] is the string
 * literal with its quotes; [name] is what `go test -run` knows the subtest by (spaces become underscores, as `go test` rewrites them).
 */
class GoSubtest(val name: String, val nameRange: TextRange, val function: GoDeclarationInfo) {
    /** `TestTotal/empty`: the name of the subtest as the test tree and `-run` see it. */
    val fullName: String get() = "${function.name}/$name"
}

/**
 * The subtests of a test function: from the PSI of go-psi where there is one ([find] with a file), else by tokens without knowing types -
 * the string that follows `.Run(` or `name:` in the body of a `TestXxx` or `FuzzXxx` function.
 */
object GoSubtests {
    /** The fields a table test names its cases by, as people call them. */
    private val CASE_FIELDS = setOf("name", "Name", "desc", "description", "testName", "title", "scenario", "tc", "caseName", "label")

    fun find(text: CharSequence, function: GoDeclarationInfo): List<GoSubtest> {
        val body = function.body ?: return emptyList()
        val lexer = GoTextLexer()
        lexer.start(text, body.startOffset, body.endOffset, 0)
        val tokens = ArrayList<Pair<com.intellij.psi.tree.IElementType, TextRange>>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in GoTextTokens.COMMENTS) tokens += type to TextRange(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        val result = ArrayList<GoSubtest>()
        val seen = HashSet<String>()
        for (i in tokens.indices) {
            val (type, range) = tokens[i]
            if (type != GoTextTokens.STRING && type != GoTextTokens.RAW_STRING) continue
            val word = { j: Int -> tokens.getOrNull(j)?.takeIf { it.first == GoTextTokens.IDENTIFIER }?.let { text.subSequence(it.second.startOffset, it.second.endOffset).toString() } }
            val isRun = tokens.getOrNull(i - 1)?.first == GoTextTokens.LPAREN && word(i - 2) == "Run" && tokens.getOrNull(i - 3)?.first == GoTextTokens.DOT && word(i - 4) != null
            val isCase = tokens.getOrNull(i - 1)?.let { it.first == GoTextTokens.OPERATOR && text[it.second.startOffset] == ':' } == true && word(i - 2) in CASE_FIELDS &&
                tokens.getOrNull(i - 3)?.first.let { it == GoTextTokens.LBRACE || it == GoTextTokens.COMMA }
            if (!isRun && !isCase) continue
            val name = subtestName(text.subSequence(range.startOffset, range.endOffset).toString()) ?: continue
            if (seen.add(name)) result += GoSubtest(name, range, function)
        }
        return result
    }

    /** `"two items"` -> `two_items`; a name with an escape or a format verb is not a name `-run` can be given. */
    fun subtestName(literal: String): String? {
        val inner = when {
            literal.length >= 2 && literal.startsWith("\"") && literal.endsWith("\"") -> literal.substring(1, literal.length - 1)
            literal.length >= 2 && literal.startsWith("`") && literal.endsWith("`") -> literal.substring(1, literal.length - 1)
            else -> return null
        }
        if (inner.isEmpty() || '\\' in inner || '%' in inner) return null
        return inner.replace(' ', '_')
    }

    /**
     * The same from the PSI of go-psi: `t.Run("name", ...)` where `t` is a `*testing.T` (a `Run` of anything else is no subtest), a run
     * inside the function literal of another named by the path of both (`outer/inner`, as `go test` names it), and the cases of a table
     * (`{name: "x", ...}`) under the runs around them. A run whose name is not a literal hides the runs inside it: their path is not
     * known. Falls back to [find] by tokens when the function is not found in the PSI or in dumb mode. Read action.
     */
    fun find(file: GoFile, function: GoDeclarationInfo): List<GoSubtest> {
        val declaration = GoDeclarationPsi.psiOf(file, function) as? GoFunctionOrMethodDeclaration
        if (declaration == null || DumbService.isDumb(file.project)) return find(file.viewProvider.contents, function)
        val block = declaration.block ?: return emptyList()
        val semantic = GoSemanticService.getInstance(file.project)
        val result = ArrayList<GoSubtest>()
        val seen = HashSet<String>()
        fun add(path: String?, literal: GoStringLiteral) {
            if (path != null && seen.add(path)) result += GoSubtest(path, literal.textRange, function)
        }
        SyntaxTraverser.psiTraverser(block).traverse().forEach { element ->
            when (element) {
                is GoCallExpr -> runName(element, semantic)?.let { literal -> add(path(element, semantic)?.let { prefix -> join(prefix, subtestName(literal.text)) }, literal) }
                is GoElement -> {
                    val field = element.key?.expression as? GoReferenceExpression ?: return@forEach
                    val literal = element.value?.expression as? GoStringLiteral ?: return@forEach
                    if (field.expression != null || field.identifier?.text !in CASE_FIELDS) return@forEach
                    add(path(element, semantic)?.let { prefix -> join(prefix, subtestName(literal.text)) }, literal)
                }
            }
        }
        return result
    }

    private fun join(prefix: String, name: String?): String? = name?.let { if (prefix.isEmpty()) it else "$prefix/$it" }

    /** The path of the runs around [element] inside the test function: "" at its top, null under a run whose name is not a literal. */
    private fun path(element: PsiElement, semantic: GoSemanticService): String? {
        val names = ArrayList<String>()
        var current: PsiElement = element
        while (true) {
            val literal = PsiTreeUtil.getParentOfType(current, GoFunctionLit::class.java) ?: break
            val call = (literal.parent as? GoArgumentList)?.parent as? GoCallExpr ?: break
            current = call
            if (!isRun(call, semantic)) continue
            val name = runName(call, semantic)?.let { subtestName(it.text) } ?: return null
            names += name
        }
        return names.asReversed().joinToString("/")
    }

    /** The literal that names the subtest of `t.Run("name", ...)`, null for any other call. */
    private fun runName(call: GoCallExpr, semantic: GoSemanticService): GoStringLiteral? =
        if (isRun(call, semantic)) call.arguments.firstOrNull() as? GoStringLiteral else null

    /** `t.Run(...)` with `t` a `*testing.T`: a `Run` of a server or of a `*testing.B` is no subtest of a test. */
    private fun isRun(call: GoCallExpr, semantic: GoSemanticService): Boolean {
        val callee = call.expression as? GoReferenceExpression ?: return false
        if (callee.identifier?.text != "Run") return false
        val receiver = callee.expression as? GoReferenceExpression ?: return false
        if (receiver.expression != null) return false
        val variable = runCatching { semantic.resolve(receiver) }.getOrDefault(emptyList()).firstOrNull { it is GoParamDefinition || it is GoVarDefinition } as? GoNamedElement
            ?: return false
        return GoScopeInputs.standardTypeOf(variable, semantic) == Triple("testing", "T", true)
    }

    /** The subtests of every test function of the file, by the offset of their name: for the gutter and the run producer. */
    fun ofFile(file: GoFile): Map<Int, GoSubtest> = CachedValuesManager.getCachedValue(file) {
        val structure = GoStructure.of(file)
        val all = GoTests.find(structure, file.name).filter { it.second == GoTestKind.TEST || it.second == GoTestKind.FUZZ }
            .flatMap { (function, _) -> find(file, function) }
        CachedValueProvider.Result.create(all.associateBy { it.nameRange.startOffset }, file, DumbService.getInstance(file.project).modificationTracker)
    }
}
