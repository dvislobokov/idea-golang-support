package io.github.golangsupport.ide.rules.builtin.staticcheck

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoCallRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.ide.rules.GoRuleSet
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.types.GoConstant
import java.math.BigInteger

/**
 * Base of the staticcheck call checks (`SA1xxx`): the callee's simple name is matched against [calleeNames] before anything is
 * resolved, then [checkCallee] gets the resolved callee as `path.Name` (functions) or `path.Type.Name` (methods), so aliased and
 * dot imports work and a local function of the same name does not.
 */
abstract class GoStaticcheckCallRule : GoCallRule() {
    override val linter: String get() = "staticcheck"
    override val needs: Set<GoRuleNeed> get() = TYPES

    /** Simple names of the functions / methods this rule looks at. */
    protected abstract val calleeNames: Set<String>

    final override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val ref = GoLintPsi.calleeReference(call) ?: return
        val name = ref.identifier?.text ?: return
        if (name !in calleeNames) return
        val callee = GoStaticcheckPsi.calleeKey(ref, ctx) ?: return
        checkCallee(call, callee, GoStaticcheckPsi.arguments(call) ?: return, ctx)
    }

    /** [arguments] are the call's argument expressions (calls with `xs...` are skipped). */
    protected abstract fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext)

    protected companion object {
        val TYPES: Set<GoRuleNeed> = setOf(GoRuleNeed.TYPES)
    }
}

/** PSI and constant helpers of the staticcheck call checks. */
internal object GoStaticcheckPsi {

    /** `path.Name` of a function, `path.Type.Name` of a method declaration [ref] resolves to; null for anything else. */
    fun calleeKey(ref: GoReferenceExpression, ctx: GoRuleContext): String? {
        val target = ctx.resolve(ref).singleOrNull() ?: return null
        return when (target) {
            is GoMethodDeclaration -> "${GoAnalysisPsi.packagePath(target) ?: return null}.${target.receiverTypeName ?: return null}.${target.name}"
            is GoFunctionDeclaration -> "${GoAnalysisPsi.packagePath(target) ?: return null}.${target.name}"
            else -> null
        }
    }

    /** `path.Name` of the declaration [e] (a name or `pkg.Name`, parentheses allowed) refers to, or null. */
    fun referenceKey(e: GoExpression?, ctx: GoRuleContext): String? {
        val ref = GoLintPsi.unparen(e) as? GoReferenceExpression ?: return null
        val target = ctx.resolve(ref).singleOrNull() as? GoNamedElement ?: return null
        val path = GoAnalysisPsi.packagePath(target) ?: return null
        return "$path.${target.name}"
    }

    /** The argument expressions of [call]; null for `f(xs...)`. */
    fun arguments(call: GoCallExpr): List<GoExpression>? {
        val list = call.argumentList ?: return null
        if (list.hasEllipsis) return null
        return list.expressions
    }

    fun stringConstant(e: GoExpression, ctx: GoRuleContext): String? = (ctx.semantic.constantValue(e) as? GoConstant.Str)?.value

    fun intConstant(e: GoExpression, ctx: GoRuleContext): BigInteger? = when (val c = ctx.semantic.constantValue(e)) {
        is GoConstant.Int -> c.value
        is GoConstant.Float -> c.toBigInteger()
        else -> null
    }

    /** Whether the rule [id] is enabled for [ctx]'s file: an alias rule (`govet:unmarshal`) stands down for its staticcheck twin. */
    fun enabled(id: String, ctx: GoRuleContext): Boolean = GoRuleSet.getInstance(ctx.project).snapshot(ctx.file).find(id) != null

    /** Go's `strconv.Quote`. */
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            i += Character.charCount(c)
            when {
                c == '"'.code -> sb.append("\\\"")
                c == '\\'.code -> sb.append("\\\\")
                c == 7 -> sb.append("\\a")
                c == 8 -> sb.append("\\b")
                c == 12 -> sb.append("\\f")
                c == 10 -> sb.append("\\n")
                c == 13 -> sb.append("\\r")
                c == 9 -> sb.append("\\t")
                c == 11 -> sb.append("\\v")
                isPrint(c) -> sb.appendCodePoint(c)
                c < 0x80 -> sb.append("\\x").append(String.format("%02x", c))
                c < 0x10000 -> sb.append("\\u").append(String.format("%04x", c))
                else -> sb.append("\\U").append(String.format("%08x", c))
            }
        }
        return sb.append('"').toString()
    }

    private fun isPrint(c: Int): Boolean {
        if (c == ' '.code) return true
        if (c < 0x20 || c == 0x7f) return false
        return when (Character.getType(c).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.UNASSIGNED, Character.SURROGATE, Character.PRIVATE_USE,
            Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false
            else -> true
        }
    }

    /** The source text of [e] usable as the receiver of a method call (`a.Equal(b)`), parenthesized when needed. */
    fun operandText(e: GoExpression): String {
        val inner = GoLintPsi.unparen(e)
        return if (inner is GoReferenceExpression || inner is GoCallExpr || inner !== e) e.text else "(${e.text})"
    }
}

// ---- quick fixes: text edits around the problem element

/** Replaces the problem element with [replacement]. */
internal class GoReplaceWithTextFix(private val family: String, private val replacement: String) : LocalQuickFix {
    override fun getFamilyName(): String = family

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        replace(element, replacement)
    }

    companion object {
        fun replace(element: PsiElement, text: String) {
            val file = element.containingFile
            val document = GoImportEdits.document(file) ?: return
            document.replaceString(element.textRange.startOffset, element.textRange.endOffset, text)
            GoImportEdits.commit(file, document)
        }
    }
}

/** Swaps the first two arguments of the call the problem is on. */
internal class GoSwapArgumentsFix(private val family: String) : LocalQuickFix {
    override fun getFamilyName(): String = family

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val call = descriptor.psiElement as? GoCallExpr ?: return
        val args = GoStaticcheckPsi.arguments(call) ?: return
        if (args.size < 2) return
        val (a, b) = args
        val file = call.containingFile
        val document = GoImportEdits.document(file) ?: return
        val aText = a.text
        val bText = b.text
        document.replaceString(b.textRange.startOffset, b.textRange.endOffset, aText)
        document.replaceString(a.textRange.startOffset, a.textRange.endOffset, bText)
        GoImportEdits.commit(file, document)
    }
}

/** Removes the problem element (an argument) with the comma next to it. */
internal class GoRemoveArgumentFix(private val family: String) : LocalQuickFix {
    override fun getFamilyName(): String = family

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val argument = descriptor.psiElement ?: return
        val call = PsiTreeUtil.getParentOfType(argument, GoCallExpr::class.java) ?: return
        val args = GoStaticcheckPsi.arguments(call) ?: return
        val index = args.indexOf(argument)
        if (index < 0) return
        val range = when {
            args.size == 1 -> argument.textRange
            index == args.lastIndex -> TextRange(args[index - 1].textRange.endOffset, argument.textRange.endOffset)
            else -> TextRange(argument.textRange.startOffset, args[index + 1].textRange.startOffset)
        }
        val file = argument.containingFile
        val document = GoImportEdits.document(file) ?: return
        document.deleteString(range.startOffset, range.endOffset)
        GoImportEdits.commit(file, document)
    }
}

/** Renames the callee of the call the problem is on (`fmt.Printf` -> `fmt.Print`). */
internal class GoRenameCalleeFix(private val family: String, private val newName: String) : LocalQuickFix {
    override fun getFamilyName(): String = family

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val call = descriptor.psiElement as? GoCallExpr ?: return
        val identifier = GoLintPsi.calleeReference(call)?.identifier ?: return
        GoReplaceWithTextFix.replace(identifier, newName)
    }
}

/** Replaces the problem element with `context.<function>()`, importing `context` when the file does not. */
internal class GoUseContextFix(private val function: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Use context.$function"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val file = element.containingFile as? GoFile ?: return
        val document = GoImportEdits.document(file) ?: return
        val spec = file.imports.firstOrNull { it.path == "context" && it.alias != "_" }
        val alias = spec?.alias
        val qualifier = when {
            spec == null || alias == null -> "context."
            alias == "." -> ""
            else -> "$alias."
        }
        document.replaceString(element.textRange.startOffset, element.textRange.endOffset, "$qualifier$function()")
        GoImportEdits.commit(file, document)
        if (spec == null) {
            GoImportInserter.addImport(file, document, "context")
            GoImportEdits.commit(file, document)
        }
    }
}

/**
 * Replaces the problem element with [build] applied to the qualifier of package [importPath] in the file (`math.`, the alias, nothing
 * for a dot import), importing the package when the file does not. [build] must not capture PSI.
 */
internal class GoReplaceWithImportFix(private val family: String, private val importPath: String, private val build: (String) -> String) : LocalQuickFix {
    override fun getFamilyName(): String = family

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val file = element.containingFile as? GoFile ?: return
        val document = GoImportEdits.document(file) ?: return
        val spec = file.imports.firstOrNull { it.path == importPath && it.alias != "_" }
        val alias = spec?.alias
        val qualifier = when {
            spec == null || alias == null -> importPath.substringAfterLast('/') + "."
            alias == "." -> ""
            else -> "$alias."
        }
        document.replaceString(element.textRange.startOffset, element.textRange.endOffset, build(qualifier))
        GoImportEdits.commit(file, document)
        if (spec == null) {
            GoImportInserter.addImport(file, document, importPath)
            GoImportEdits.commit(file, document)
        }
    }
}

/** Adds a buffer of one to a `make(chan T)` call (held by a smart pointer: it is usually on another line than the problem). */
internal class GoBufferChannelFix(make: GoCallExpr) : LocalQuickFix {
    private val pointer: SmartPsiElementPointer<GoCallExpr> = SmartPointerManager.createPointer(make)

    override fun getFamilyName(): String = "Change to buffered channel"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val make = pointer.element ?: return
        val rparen = make.argumentList?.rparen ?: return
        val file = make.containingFile
        val document = GoImportEdits.document(file) ?: return
        document.insertString(rparen.textRange.startOffset, ", 1")
        GoImportEdits.commit(file, document)
    }
}
