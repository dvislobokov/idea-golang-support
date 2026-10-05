package io.github.golangsupport.ide.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.documentation.GoDocLinks
import io.github.golangsupport.lang.GoColors as C
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoLabelDefinition
import io.github.golangsupport.lang.psi.GoLabelRef
import io.github.golangsupport.lang.psi.GoLabeledStatement
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoSwitchStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.resolve.GoResolver
import io.github.golangsupport.semantic.scope.GoUniverse

/**
 * Semantic highlighting: colours identifiers by what they declare or resolve to, with the keys of GoLand (`color-keys-go.txt`):
 * exported / package-local functions, types (struct, interface, other), constants and variables; local, scope (declared in an
 * `if` / `for` / `switch` header or a case clause) and reassigned-in-`:=` variables; receivers apart from parameters; fields;
 * calls of functions, of variables and fields holding a func; builtins (`nil` is a variable); packages and labels; the names a
 * doc comment refers to (its first word naming the declaration, and `[Name]` doc links). Uses only the cached resolve of each
 * reference (never expression typing directly), so it stays cheap; declarations of other files are classified from their stubs.
 * Not dumb-aware because resolve uses indices. Unresolved references are left to the lexer colours (the unresolved-reference
 * inspection marks them). Stands down while [GoIdeFeature.SEMANTIC_COLORS] is off at the host's gate (another source colours then).
 */
class GoSemanticHighlightingAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element.containingFile as? GoFile ?: return
        if (element !is GoReferenceExpression && element !is GoTypeReferenceExpression && element !is GoLabelRef && element !is GoNamedElement && element !is PsiComment) return
        // a settings read per coloured element: the gate is cheap, and the host switch may flip between two highlighting passes
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.SEMANTIC_COLORS, element.project)) return
        when (element) {
            is GoReferenceExpression -> referenceKey(element, file)?.let { highlight(holder, element.identifier, it) }
            is GoTypeReferenceExpression -> typeReferenceKey(element)?.let { highlight(holder, element.identifier, it) }
            is GoLabelRef -> highlight(holder, element.identifier, C.LABEL)
            is GoNamedElement -> declarationKey(element)?.let { highlight(holder, element.nameIdentifier, it) }
            is PsiComment -> commentReferences(element, holder)
        }
    }

    private fun highlight(holder: AnnotationHolder, identifier: PsiElement?, key: TextAttributesKey) {
        if (identifier == null || identifier.textLength == 0) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(identifier).textAttributes(key).create()
    }

    private fun declarationKey(e: GoNamedElement): TextAttributesKey? {
        if (GoUniverse.isBuiltinDeclaration(e)) return builtinKey(e)
        return when (e) {
            is GoTypeSpec -> typeSpecKey(e, declaration = true)
            is GoTypeParamDefinition -> C.TYPE_REFERENCE
            is GoFunctionDeclaration, is GoMethodDeclaration, is GoMethodSpec -> if (exported(e)) C.EXPORTED_FUNCTION else C.LOCAL_FUNCTION
            is GoFieldDefinition -> memberKey(e, call = false)
            is GoAnonymousFieldDefinition -> null // its identifier is the embedded type's reference
            is GoReceiver -> C.METHOD_RECEIVER
            is GoParamDefinition -> C.FUNCTION_PARAMETER
            is GoVarDefinition -> when {
                !GoPsiUtil.isInsideFunctionBody(e) -> if (exported(e)) C.PACKAGE_EXPORTED_VARIABLE else C.PACKAGE_LOCAL_VARIABLE
                isReassignment(e) -> C.REASSIGNMENT_IN_SHORT_VAR_DECLARATION
                isScopeVariable(e) -> C.SCOPE_VARIABLE
                else -> C.LOCAL_VARIABLE
            }
            is GoConstDefinition -> constKey(e, local = GoPsiUtil.isInsideFunctionBody(e))
            is GoLabelDefinition -> C.LABEL
            is GoImportSpec, is GoPackageClause -> C.PACKAGE
            else -> null
        }
    }

    private fun referenceKey(ref: GoReferenceExpression, file: GoFile): TextAttributesKey? {
        val resolver = GoResolver.getInstance(ref.project)
        // `T{Name: v}`: a struct literal key names a field; other keys (maps) are expressions.
        val key = ref.parent as? GoKey
        val result = (if (key != null) resolver.resolveFieldKey(key).firstOrNull() else null)
            ?: resolver.resolveReferenceExpression(ref).firstOrNull() ?: return null
        return when (result) {
            is GoResolver.Result.Import, is GoResolver.Result.Package -> C.PACKAGE
            is GoResolver.Result.Label -> C.LABEL
            is GoResolver.Result.ReceiverTypeParam -> C.TYPE_REFERENCE
            is GoResolver.Result.Cgo -> null
            else -> targetKey(result.element ?: return null, file, isCallee(ref))
        }
    }

    private fun typeReferenceKey(ref: GoTypeReferenceExpression): TextAttributesKey? {
        val target = GoResolver.getInstance(ref.project).resolveTypeReference(ref) ?: return null
        return when {
            target is GoTypeSpec -> if (GoUniverse.isBuiltinDeclaration(target)) C.BUILTIN_TYPE_REFERENCE else typeSpecKey(target, declaration = false)
            target is GoTypeParamDefinition -> C.TYPE_REFERENCE
            // Receiver type parameters (`func (l *List[T])`) resolve to their name in the receiver.
            PsiTreeUtil.getParentOfType(target, GoReceiver::class.java, false) != null -> C.TYPE_REFERENCE
            else -> null
        }
    }

    /** The colour of a reference to [target] ([call]: the reference is the callee of a call); declarations of other files are classified from their stubs. */
    private fun targetKey(target: PsiElement, file: GoFile, call: Boolean): TextAttributesKey? {
        if (target is GoNamedElement && GoUniverse.isBuiltinDeclaration(target)) {
            return if (target is GoFunctionDeclaration) C.BUILTIN_FUNCTION_CALL else builtinKey(target)
        }
        // Locals are only visible in their own file; anything else is package level (stub-backed, no AST of other files).
        val local = target.containingFile == file && GoPsiUtil.isInsideFunctionBody(target)
        return when (target) {
            is GoTypeSpec -> typeSpecKey(target, declaration = false)
            is GoTypeParamDefinition -> C.TYPE_REFERENCE
            is GoFunctionDeclaration, is GoMethodDeclaration, is GoMethodSpec -> if (exported(target as GoNamedElement)) C.EXPORTED_FUNCTION_CALL else C.LOCAL_FUNCTION_CALL
            is GoFieldDefinition, is GoAnonymousFieldDefinition -> memberKey(target as GoNamedElement, call)
            is GoReceiver -> if (call) C.LOCAL_VARIABLE_CALL else C.METHOD_RECEIVER
            is GoParamDefinition -> if (call) C.LOCAL_VARIABLE_CALL else C.FUNCTION_PARAMETER
            is GoVarDefinition -> when {
                !local -> if (exported(target)) {
                    if (call) C.PACKAGE_EXPORTED_VARIABLE_CALL else C.PACKAGE_EXPORTED_VARIABLE
                } else {
                    if (call) C.PACKAGE_LOCAL_VARIABLE_CALL else C.PACKAGE_LOCAL_VARIABLE
                }
                call -> C.LOCAL_VARIABLE_CALL
                isScopeVariable(target) -> C.SCOPE_VARIABLE
                else -> C.LOCAL_VARIABLE
            }
            is GoConstDefinition -> constKey(target, local)
            is GoLabelDefinition -> C.LABEL
            is GoImportSpec -> C.PACKAGE
            else -> null
        }
    }

    private fun builtinKey(e: GoNamedElement): TextAttributesKey = when (e) {
        is GoTypeSpec -> C.BUILTIN_TYPE_REFERENCE
        is GoFunctionDeclaration -> C.BUILTIN_FUNCTION
        is GoVarDefinition -> C.BUILTIN_VARIABLE // nil
        else -> C.BUILTIN_CONSTANT // true, false, iota
    }

    private fun constKey(e: GoConstDefinition, local: Boolean): TextAttributesKey = when {
        local -> C.LOCAL_CONSTANT
        exported(e) -> C.PACKAGE_EXPORTED_CONSTANT
        else -> C.PACKAGE_LOCAL_CONSTANT
    }

    private fun memberKey(e: GoNamedElement, call: Boolean): TextAttributesKey =
        if (exported(e)) (if (call) C.STRUCT_EXPORTED_MEMBER_CALL else C.STRUCT_EXPORTED_MEMBER) else if (call) C.STRUCT_LOCAL_MEMBER_CALL else C.STRUCT_LOCAL_MEMBER

    /** Struct, interface or any other type, by the type literal of the spec (a stub child: no AST of other files). */
    private fun typeSpecKey(spec: GoTypeSpec, declaration: Boolean): TextAttributesKey {
        val exported = exported(spec)
        return when (spec.type) {
            is GoStructType -> if (declaration) (if (exported) C.PACKAGE_EXPORTED_STRUCT else C.PACKAGE_LOCAL_STRUCT) else if (exported) C.EXPORTED_STRUCT_REFERENCE else C.LOCAL_STRUCT_REFERENCE
            is GoInterfaceType -> if (declaration) (if (exported) C.PACKAGE_EXPORTED_INTERFACE else C.PACKAGE_LOCAL_INTERFACE) else if (exported) C.EXPORTED_INTERFACE_REFERENCE else C.LOCAL_INTERFACE_REFERENCE
            else -> if (declaration) C.TYPE_SPECIFICATION else C.TYPE_REFERENCE
        }
    }

    private fun exported(e: GoNamedElement): Boolean = e.name?.let { it.isNotEmpty() && Character.isUpperCase(it.codePointAt(0)) } == true

    /** `f()` / `(f)()` / `x.f()`: [ref] is what the call invokes. */
    private fun isCallee(ref: GoReferenceExpression): Boolean {
        var e: PsiElement = ref
        while (e.parent is GoParenthesesExpr) e = e.parent
        return (e.parent as? GoCallExpr)?.expression === e
    }

    /**
     * A variable of an implicit block of the spec: declared in the header of an `if` / `for` / `switch` / `select` or in one of
     * the case clauses, the nearest block of the function body not reached first.
     */
    private fun isScopeVariable(v: GoVarDefinition): Boolean {
        var e: PsiElement? = v.parent
        while (e != null && e !is GoBlock && e !is GoFile) {
            when (e) {
                is GoIfStatement, is GoForStatement, is GoSwitchStatement, is GoExprSwitchStatement, is GoTypeSwitchStatement, is GoSelectStatement,
                is GoExprCaseClause, is GoTypeCaseClause, is GoCommClause -> return true
            }
            e = e.parent
        }
        return false
    }

    /** `a, err := f()` where `err` is already declared in the same scope: the `:=` assigns it, as go/types reads it. */
    private fun isReassignment(v: GoVarDefinition): Boolean {
        val decl = v.parent as? GoShortVarDeclaration ?: return false
        val name = v.name?.takeIf { it != "_" } ?: return false
        var holder: PsiElement = decl
        while (holder.parent is GoSimpleStatement || holder.parent is GoLabeledStatement) holder = holder.parent
        val container = holder.parent ?: return false
        val earlier = when (container) {
            is GoBlock -> container.statementList
            is GoExprCaseClause -> container.statementList
            is GoTypeCaseClause -> container.statementList
            is GoCommClause -> container.statementList
            else -> return false // a header: its scope is new
        }.takeWhile { it !== holder }
        if (earlier.any { s -> GoPsiUtil.declarationsOf(s).any { it.name == name } }) return true
        // Parameters, results and the receiver share the function's outermost block.
        val owner = (container as? GoBlock)?.parent ?: return false
        val signature = when (owner) {
            is GoFunctionDeclaration -> owner.signature
            is GoMethodDeclaration -> owner.signature
            is GoFunctionLit -> owner.signature
            else -> null
        } ?: return false
        if (owner is GoMethodDeclaration && owner.receiver?.name == name) return true
        val params = signature.parameters?.parameterDeclarationList.orEmpty() + signature.result?.parameters?.parameterDeclarationList.orEmpty()
        return params.any { d -> d.paramDefinitionList.any { it.name == name } }
    }

    /**
     * Names a comment refers to: the first word of a doc comment when it is the name of the declaration (`// Run starts ...`), and the
     * names of `[Name]` doc links that resolve. Only comments outside function bodies; links are resolved only in lines with `[`.
     */
    private fun commentReferences(comment: PsiComment, holder: AnnotationHolder) {
        val text = comment.text
        if (!text.startsWith("//") || PsiTreeUtil.getParentOfType(comment, GoBlock::class.java) != null) return
        val start = comment.textRange.startOffset
        documentedName(comment)?.let { name ->
            val content = if (text.startsWith("// ")) 3 else 2
            if (text.startsWith(name, content) && text.getOrNull(content + name.length)?.let { Character.isLetterOrDigit(it) || it == '_' } != true) {
                annotate(holder, TextRange(start + content, start + content + name.length))
            }
        }
        if ('[' !in text) return
        for (link in GoDocLinks.linksIn(comment)) {
            val targets = GoDocLinks.resolve(comment, link)
            link.nameRanges.forEachIndexed { i, range -> if (targets.getOrNull(i) != null) annotate(holder, range.shiftRight(start)) }
        }
    }

    private fun annotate(holder: AnnotationHolder, range: TextRange) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(C.COMMENT_REFERENCE).create()
    }

    /** The name of the declaration whose doc comment starts with [comment] (comments are bound into the declaration they precede). */
    private fun documentedName(comment: PsiComment): String? {
        val candidate: GoNamedElement? = when (val holder = comment.parent) {
            is GoNamedElement -> holder
            is GoFieldDeclaration -> holder.fieldDefinitionList.firstOrNull()
            is GoVarSpec -> holder.varDefinitionList.firstOrNull()
            is GoConstSpec -> holder.constDefinitionList.firstOrNull()
            is GoTypeDeclaration -> holder.typeSpecList.singleOrNull()
            is GoVarDeclaration -> holder.varSpecList.singleOrNull()?.varDefinitionList?.firstOrNull()
            is GoConstDeclaration -> holder.constSpecList.singleOrNull()?.constDefinitionList?.firstOrNull()
            else -> null
        }
        return candidate?.takeIf { it.docComment === comment }?.name
    }
}
