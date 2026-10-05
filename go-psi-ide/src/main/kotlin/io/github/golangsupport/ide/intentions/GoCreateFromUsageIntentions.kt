package io.github.golangsupport.ide.intentions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.inspections.GoDiagnosticsCache
import io.github.golangsupport.ide.refactoring.GoInlineFunction
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConstraintElem
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoImportList
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.lang.psi.GoSpecType
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoType
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.impl.GoTypeReferenceExpressionMixin
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.psi.GoPsiUtil.qualifier
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoType as SemanticType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * An identifier the checker reports as undefined at the caret: `undefined: x` / `undefined: pkg.X` ([code] `undefined`) or a member
 * the type of its operand lacks ([code] `undefined-member`). The create intentions read it, so they never offer to create what resolves.
 */
internal class GoUsage(val file: GoFile, val identifier: PsiElement, val name: String, val code: String) {
    companion object {
        fun at(file: GoFile, offset: Int): GoUsage? {
            val identifier = listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)
                .firstOrNull { it.node.elementType == GoTypes.IDENTIFIER } ?: return null
            val range = identifier.textRange
            val d = GoDiagnosticsCache.diagnostics(file).firstOrNull { it.range == range && (it.code == "undefined" || it.code == "undefined-member") } ?: return null
            return GoUsage(file, identifier, identifier.text, d.code)
        }
    }
}

/**
 * Base of the intentions that create a declaration for an undefined name at the caret (docs/FEATURES.md §11, wave 3 B1): gate
 * [GoIdeFeature.CODE_ACTIONS], a [GoCreatePlan] from the PSI and the types, applied in the target file with its imports. The preview
 * shows only plans that write into the editor's file.
 */
abstract class GoCreateFromUsageIntention : IntentionAction {
    @Volatile private var lastText: String? = null

    protected abstract val defaultText: String

    internal abstract fun plan(usage: GoUsage): GoCreatePlan?

    private fun plan(file: PsiFile?, editor: Editor?): GoCreatePlan? {
        if (editor == null || file !is GoFile) return null
        return GoUsage.at(file, editor.caretModel.offset)?.let(::plan)
    }

    override fun getText(): String = lastText ?: defaultText

    override fun getFamilyName(): String = defaultText

    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file !is GoFile || !GoIdeFeatureGate.enabled(GoIdeFeature.CODE_ACTIONS, project)) return false
        val plan = plan(file, editor) ?: return false
        lastText = plan.text
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        GoCreateEdits.apply(plan(file, editor) ?: return)
    }

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = GoCreateEdits.preview(plan(file, editor), file)

    /** The file to create a package-level [usage] in: its own file, or for `pkg.Name` a file of that package of the project. */
    internal fun targetFile(usage: GoUsage, qualifier: PsiElement?): GoFile? = when (qualifier) {
        null -> usage.file
        is GoReferenceExpression -> GoCreateText.packageFile(usage.file, qualifier, usage.name)
        else -> null
    }

    /** [declaration] after the enclosing top-level declaration in the own file, at the end of another package's file. */
    internal fun placed(usage: GoUsage, target: GoFile, declaration: String): GoEditPlan.Edit? =
        if (target == usage.file) GoCreateText.afterTopLevel(usage.identifier, declaration) else GoCreateText.atEnd(target, declaration)
}

/** `f(a, "s")` with `f` undefined: `func f(a int, s string) { panic("not implemented") }` after the enclosing declaration, results by the context. */
class GoCreateFunctionFromUsageIntention : GoCreateFromUsageIntention() {
    override val defaultText: String = "Create function"

    override fun plan(usage: GoUsage): GoCreatePlan? {
        if (usage.code != "undefined") return null
        val ref = usage.identifier.parent as? GoReferenceExpression ?: return null
        val call = ref.parent as? GoCallExpr ?: return null
        if (call.expression != ref) return null
        val target = targetFile(usage, ref.qualifier) ?: return null
        val source = GoSourceText(target)
        val signature = GoCreateText.callSignature(call, source, emptySet()) ?: return null
        if (target != usage.file && GoCreateText.importsOwnPackage(source, usage.file)) return null
        val edit = placed(usage, target, "func ${usage.name}$signature {\n${GoCreateText.BODY}\n}") ?: return null
        return GoCreatePlan(target, listOf(edit), source.imports, "Create function '${usage.name}'")
    }
}

/**
 * Members of a named type of the project that the checker misses on `x.M(...)` / `x.F`: the type, its declaration and whether it is
 * writable from here (another package of the project only for exported names; never generic, local, interface or builtin types).
 */
internal class GoMemberTarget(val ref: GoReferenceExpression, val named: GoNamedType, val spec: GoTypeSpec, val file: GoFile) {
    companion object {
        fun of(usage: GoUsage): GoMemberTarget? {
            if (usage.code != "undefined-member") return null
            val ref = usage.identifier.parent as? GoReferenceExpression ?: return null
            val operand = ref.qualifier ?: return null
            val named = when (val t = GoSemanticService.getInstance(usage.file.project).typeOf(operand)) {
                is GoNamedType -> t
                is GoPointerType -> t.elem as? GoNamedType
                else -> null
            } ?: return null
            if (named.isInstantiated || named.declaration.typeParameters != null || named.underlying() is GoInterfaceType) return null
            val spec = named.declaration
            if ((spec.parent as? GoTypeDeclaration)?.parent !is GoFile) return null
            val file = spec.containingFile as? GoFile ?: return null
            if (!GoCreateText.isProjectFile(file)) return null
            if (!GoSourceText(usage.file).isOwnPackage(file) && !Character.isUpperCase(usage.name[0])) return null
            return GoMemberTarget(ref, named, spec, file)
        }
    }
}

/** `x.M(a)` with `M` missing on the named type `T` of `x`: `func (t *T) M(a int) { panic("not implemented") }` after T's last method. */
class GoCreateMethodFromUsageIntention : GoCreateFromUsageIntention() {
    override val defaultText: String = "Create method"

    override fun plan(usage: GoUsage): GoCreatePlan? {
        val member = GoMemberTarget.of(usage) ?: return null
        val call = member.ref.parent as? GoCallExpr ?: return null
        if (call.expression != member.ref) return null
        val source = GoSourceText(member.file)
        val receiver = GoCreateText.receiverOf(member.named)
        val signature = GoCreateText.callSignature(call, source, setOf(receiver.name)) ?: return null
        val typeName = member.named.name
        val declaration = "func (${receiver.name} ${if (receiver.pointer) "*" else ""}$typeName) ${usage.name}$signature {\n${GoCreateText.BODY}\n}"
        val edit = GoCreateText.afterMethods(member.spec, declaration) ?: return null
        return GoCreatePlan(member.file, listOf(edit), source.imports, "Create method '${usage.name}' on $typeName")
    }
}

/** `x.F` with `F` missing on the struct `T` of `x`: a field of the assigned / expected type (`any` when there is none) at the end of the struct. */
class GoCreateFieldFromUsageIntention : GoCreateFromUsageIntention() {
    override val defaultText: String = "Create field"

    override fun plan(usage: GoUsage): GoCreatePlan? {
        val member = GoMemberTarget.of(usage) ?: return null
        val ref = member.ref
        if ((ref.parent as? GoCallExpr)?.expression == ref) return null
        val struct = structOf(member.spec) ?: return null
        val rbrace = struct.rbrace ?: return null
        val source = GoSourceText(member.file)
        val type = GoCreateText.valueType(GoCreateUsages.valueTypeFor(ref)).takeUnless(GoCreateText::mentionsTypeParam)
        val text = member.file.viewProvider.contents
        val indent = GoIntentionText.indentAt(text, struct.textRange.startOffset)
        val edits = ArrayList<GoEditPlan.Edit>()
        val keyword = struct.struct
        val lbrace = struct.lbrace ?: return null
        if (lbrace.textRange.startOffset == keyword.textRange.endOffset) edits += GoEditPlan.Edit(keyword.textRange.endOffset, keyword.textRange.endOffset, " ")
        val field = "${usage.name} ${GoCreateText.type(source, type)}"
        val fields = struct.fieldDeclarationList
        if (fields.isNotEmpty() && !text.subSequence(lbrace.textRange.endOffset, rbrace.textRange.startOffset).contains('\n')) {
            // `struct{ A int }` is spread over lines, one field per line, as gofmt keeps it (seen live: the new field went after `A int` on its line)
            val body = (fields.map { it.text } + field).joinToString("") { "$indent\t$it\n" }
            edits += GoEditPlan.Edit(lbrace.textRange.endOffset, rbrace.textRange.startOffset, "\n$body$indent")
        } else {
            edits += GoIntentionText.insertBefore(text, rbrace.textRange.startOffset, indent, listOf("\t$field"))
        }
        return GoCreatePlan(member.file, edits, source.imports, "Create field '${usage.name}' in ${member.named.name}")
    }

    private fun structOf(spec: GoTypeSpec): GoStructType? {
        var t: PsiElement? = spec.type
        while (t is GoSpecType) t = PsiTreeUtil.getChildOfType(t, GoType::class.java)
        return t as? GoStructType
    }
}

/**
 * `x` undefined where a value is used: `x := <zero>` (or `var x T` when the zero literal would have another type) before the statement
 * inside a function, `var x T` after the enclosing declaration at package level; `T` from the assignment or the expected type, else `any`.
 */
class GoCreateVariableFromUsageIntention : GoCreateFromUsageIntention() {
    override val defaultText: String = "Create variable"

    override fun plan(usage: GoUsage): GoCreatePlan? {
        if (usage.code != "undefined") return null
        val ref = usage.identifier.parent as? GoReferenceExpression ?: return null
        val parent = ref.parent
        if (parent is GoCallExpr && parent.expression == ref) return null
        // `x.F` with `x` undefined: rather a missing import than a variable
        if (parent is GoReferenceExpression && parent.qualifier == ref) return null
        // `time` of `time.Time`: a package name in a type, never a value (seen live without the import)
        if (PsiTreeUtil.getParentOfType(ref, GoTypeReferenceExpression::class.java, GoType::class.java) != null) return null
        val qualifier = ref.qualifier
        val target = targetFile(usage, qualifier) ?: return null
        val source = GoSourceText(target)
        val local = qualifier == null && GoPsiUtil.isInsideFunctionBody(ref)
        val type = GoCreateText.valueType(GoCreateUsages.valueTypeFor(ref)).takeUnless { !local && GoCreateText.mentionsTypeParam(it) }
        val name = usage.name
        if (!local) {
            val edit = placed(usage, target, "var $name ${GoCreateText.type(source, type)}") ?: return null
            if (target != usage.file && GoCreateText.importsOwnPackage(source, usage.file)) return null
            return GoCreatePlan(target, listOf(edit), source.imports, "Create variable '$name'")
        }
        val statement = GoIntentionText.statementAt(ref) ?: return null
        val declaration = if (type != null && shortForm(type)) "$name := ${source.zero(type)}" else "var $name ${GoCreateText.type(source, type)}"
        val indent = GoIntentionText.indentAt(target.viewProvider.contents, statement.textRange.startOffset)
        val start = statement.textRange.startOffset
        return GoCreatePlan(target, listOf(GoEditPlan.Edit(start, start, "$declaration\n$indent")), source.imports, "Create variable '$name'")
    }

    /** Whether the zero value written as a literal has exactly [type]: `0`, `""`, `false`, `T{}`. */
    private fun shortForm(type: SemanticType): Boolean =
        type == GoBasicType.INT || type == GoBasicType.STRING || type == GoBasicType.BOOL || (type.underlying().let { it is io.github.golangsupport.semantic.types.GoStructType || it is io.github.golangsupport.semantic.types.GoArrayType })
}

/** `T` undefined in a type position: `type T struct{}` (`interface{}` in a constraint or an interface) after the enclosing declaration. */
class GoCreateTypeFromUsageIntention : GoCreateFromUsageIntention() {
    override val defaultText: String = "Create type"

    override fun plan(usage: GoUsage): GoCreatePlan? {
        if (usage.code != "undefined") return null
        val ref = usage.identifier.parent as? GoTypeReferenceExpression ?: return null
        if ((ref.parent as? GoType)?.typeArguments != null) return null
        val literal = ref.parent as? GoCompositeLit
        if (literal != null && literal.typeList.isNotEmpty()) return null
        val qualifier = if ((ref as? GoTypeReferenceExpressionMixin)?.qualifierName != null) ref.referenceExpression ?: return null else null
        val target = targetFile(usage, qualifier) ?: return null
        val constraint = PsiTreeUtil.getParentOfType(ref, GoConstraintElem::class.java) != null
        val declaration = "type ${usage.name} ${if (constraint) "interface{}" else "struct{}"}"
        val edit = placed(usage, target, declaration) ?: return null
        return GoCreatePlan(target, listOf(edit), emptyList(), "Create type '${usage.name}'")
    }
}

/**
 * An undefined plain name used as a value inside a function body, for the global variable and parameter intentions: its semantic type
 * (assigned or expected) and the type text; the callee of a call `x(a)` gets the function type of the call (`func(a int)`).
 */
internal class GoValueUsage(val ref: GoReferenceExpression, val type: SemanticType?, val typeText: String) {
    companion object {
        fun of(usage: GoUsage, source: GoSourceText, typeParams: Boolean): GoValueUsage? {
            if (usage.code != "undefined") return null
            val ref = usage.identifier.parent as? GoReferenceExpression ?: return null
            if (ref.qualifier != null || !GoPsiUtil.isInsideFunctionBody(ref)) return null
            val parent = ref.parent
            if (parent is GoReferenceExpression && parent.qualifier == ref) return null
            if (PsiTreeUtil.getParentOfType(ref, GoTypeReferenceExpression::class.java, GoType::class.java) != null) return null
            if (parent is GoCallExpr && parent.expression == ref) {
                val signature = GoCreateText.callSignature(parent, source, emptySet()) ?: return null
                return GoValueUsage(ref, null, "func$signature")
            }
            val type = GoCreateText.valueType(GoCreateUsages.valueTypeFor(ref)).takeUnless { !typeParams && GoCreateText.mentionsTypeParam(it) }
            return GoValueUsage(ref, type, GoCreateText.type(source, type))
        }
    }
}

/** `x` undefined inside a function: `var x T` at package level, after the imports (also for the callee of a call: `var x func(a int)`). */
class GoCreateGlobalVariableFromUsageIntention : GoCreateFromUsageIntention() {
    override val defaultText: String = "Create global variable"

    override fun plan(usage: GoUsage): GoCreatePlan? {
        val source = GoSourceText(usage.file)
        val value = GoValueUsage.of(usage, source, typeParams = false) ?: return null
        val file = usage.file
        val anchor = PsiTreeUtil.getChildOfType(file, GoImportList::class.java)?.importDeclarationList?.lastOrNull()
            ?: PsiTreeUtil.getChildOfType(file, GoPackageClause::class.java) ?: return null
        val end = anchor.textRange.endOffset
        val edit = GoEditPlan.Edit(end, end, "\n\nvar ${usage.name} ${value.typeText}")
        return GoCreatePlan(file, listOf(edit), source.imports, "Create global variable '${usage.name}'")
    }
}

/**
 * `x` undefined inside a function or function literal: `x T` appended to its parameters (before a variadic one); the calls of a
 * declared function in the project pass the zero value of `T` for it, so they keep compiling.
 */
class GoCreateParameterFromUsageIntention : GoCreateFromUsageIntention() {
    override val defaultText: String = "Create parameter"

    override fun plan(usage: GoUsage): GoCreatePlan? {
        val source = GoSourceText(usage.file)
        val value = GoValueUsage.of(usage, source, typeParams = true) ?: return null
        val parameters = signatureOf(value.ref)?.parameters ?: return null
        val list = parameters.parameterDeclarationList
        val param = "${usage.name} ${value.typeText}"
        val last = list.lastOrNull()
        val edit = when {
            last == null -> GoEditPlan.Edit(parameters.lparen.textRange.endOffset, parameters.lparen.textRange.endOffset, param)
            last.text.contains("...") -> GoEditPlan.Edit(last.textRange.startOffset, last.textRange.startOffset, "$param, ")
            else -> GoEditPlan.Edit(last.textRange.endOffset, last.textRange.endOffset, ", $param")
        }
        return GoCreatePlan(usage.file, listOf(edit), source.imports, "Create parameter '${usage.name}'")
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is GoFile) return
        val usage = GoUsage.at(file, editor.caretModel.offset) ?: return
        val plan = plan(usage) ?: return
        val value = GoValueUsage.of(usage, GoSourceText(file), typeParams = true) ?: return
        val calls = callEdits(value)
        GoCreateEdits.apply(GoCreatePlan(plan.target, plan.edits + calls[file]?.first.orEmpty(), plan.imports + calls[file]?.second.orEmpty(), plan.text))
        for ((target, edits) in calls) if (target != file) GoCreateEdits.apply(GoCreatePlan(target, edits.first, edits.second, plan.text))
    }

    private fun signatureOf(ref: PsiElement): GoSignature? = when (val owner = GoPsiUtil.functionOwner(ref)) {
        is GoFunctionOrMethodDeclaration -> owner.signature
        is GoFunctionLit -> owner.signature
        else -> null
    }

    /** The zero value appended to every call of the declared function around [value], per file with the imports it needs. */
    private fun callEdits(value: GoValueUsage): Map<GoFile, Pair<List<GoEditPlan.Edit>, Set<String>>> {
        val function = GoPsiUtil.functionOwner(value.ref) as? GoFunctionOrMethodDeclaration ?: return emptyMap()
        val out = LinkedHashMap<GoFile, Pair<MutableList<GoEditPlan.Edit>, GoSourceText>>()
        for (reference in ReferencesSearch.search(function, GlobalSearchScope.projectScope(function.project)).findAll()) {
            val ref = reference.element as? GoReferenceExpression ?: continue
            val call = GoInlineFunction.callOf(ref) ?: continue
            val list = call.argumentList ?: continue
            if (list.hasEllipsis) continue
            val file = call.containingFile as? GoFile ?: continue
            val entry = out.getOrPut(file) { ArrayList<GoEditPlan.Edit>() to GoSourceText(file) }
            val zero = value.type?.let { entry.second.zero(it) } ?: "nil"
            val rparen = list.rparen ?: continue
            val args = list.expressions
            val offset = args.lastOrNull()?.textRange?.endOffset ?: rparen.textRange.startOffset
            entry.first += GoEditPlan.Edit(offset, offset, if (args.isEmpty()) zero else ", $zero")
        }
        return out.mapValues { (_, v) -> v.first to v.second.imports }
    }
}

/** Where a value is assigned or expected. */
internal object GoCreateUsages {

    /** The type a value at [ref] takes: the right side of `ref = v`, else the type expected at [ref]. */
    fun valueTypeFor(ref: GoExpression): SemanticType? {
        val service = GoSemanticService.getInstance(ref.project)
        val list = ref.parent as? GoLeftHandExprList
        val assignment = list?.parent as? GoAssignmentStatement
        if (assignment != null && assignment.assignOp.assign != null) {
            val lhs = list.expressionList
            val rhs = assignment.expressionList
            val index = lhs.indexOf(ref)
            if (lhs.size == rhs.size) return service.typeOf(rhs[index])
            val tuple = rhs.singleOrNull()?.let(service::typeOf) as? io.github.golangsupport.semantic.types.GoTupleType
            return tuple?.types?.getOrNull(index)
        }
        return service.expectedTypeAt(ref)?.takeIf { GoTypePredicates.isKnown(it) }
    }
}
