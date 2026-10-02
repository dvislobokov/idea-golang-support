package io.github.golangsupport.ide.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.highlighting.GoHighlightingColors as C
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoKey
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoLabelDefinition
import io.github.golangsupport.lang.psi.GoLabelRef
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.resolve.GoResolver
import io.github.golangsupport.semantic.scope.GoUniverse

/**
 * Semantic highlighting: colours identifiers by what they declare or resolve to (types, type
 * parameters, functions, methods, fields, parameters, local and package variables, constants,
 * packages, labels and builtins). Uses only the cached resolve of each reference (never
 * expression typing directly), so it stays cheap; not dumb-aware because resolve uses indices.
 * Unresolved references are left to the lexer colours (the unresolved-reference inspection marks them).
 */
class GoSemanticHighlightingAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element.containingFile as? GoFile ?: return
        when (element) {
            is GoReferenceExpression -> referenceKey(element, file)?.let { highlight(holder, element.identifier, it) }
            is GoTypeReferenceExpression -> typeReferenceKey(element)?.let { highlight(holder, element.identifier, it) }
            is GoLabelRef -> highlight(holder, element.identifier, C.LABEL)
            is GoNamedElement -> declarationKey(element)?.let { highlight(holder, element.nameIdentifier, it) }
        }
    }

    private fun highlight(holder: AnnotationHolder, identifier: PsiElement?, key: TextAttributesKey) {
        if (identifier == null || identifier.textLength == 0) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(identifier).textAttributes(key).create()
    }

    private fun declarationKey(e: GoNamedElement): TextAttributesKey? = when (e) {
        is GoTypeSpec -> C.TYPE
        is GoTypeParamDefinition -> C.TYPE_PARAMETER
        is GoFunctionDeclaration -> C.FUNCTION_DECLARATION
        is GoMethodDeclaration, is GoMethodSpec -> C.METHOD_DECLARATION
        is GoFieldDefinition -> C.FIELD
        is GoAnonymousFieldDefinition -> null // its identifier is the embedded type's reference
        is GoParamDefinition, is GoReceiver -> C.PARAMETER
        is GoVarDefinition -> if (GoPsiUtil.isInsideFunctionBody(e)) C.LOCAL_VARIABLE else C.PACKAGE_VARIABLE
        is GoConstDefinition -> C.CONSTANT
        is GoLabelDefinition -> C.LABEL
        is GoImportSpec, is GoPackageClause -> C.PACKAGE
        else -> null
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
            is GoResolver.Result.ReceiverTypeParam -> C.TYPE_PARAMETER
            is GoResolver.Result.Cgo -> null
            else -> targetKey(result.element ?: return null, file)
        }
    }

    private fun typeReferenceKey(ref: GoTypeReferenceExpression): TextAttributesKey? {
        val target = GoResolver.getInstance(ref.project).resolveTypeReference(ref) ?: return null
        return when {
            target is GoTypeSpec -> if (GoUniverse.isBuiltinDeclaration(target)) C.BUILTIN_TYPE else C.TYPE
            target is GoTypeParamDefinition -> C.TYPE_PARAMETER
            // Receiver type parameters (`func (l *List[T])`) resolve to their name in the receiver.
            PsiTreeUtil.getParentOfType(target, GoReceiver::class.java, false) != null -> C.TYPE_PARAMETER
            else -> null
        }
    }

    /** The colour of a reference to [target]; declarations of other files are classified from their stubs. */
    private fun targetKey(target: PsiElement, file: GoFile): TextAttributesKey? {
        if (target is GoNamedElement && GoUniverse.isBuiltinDeclaration(target)) {
            return when (target) {
                is GoTypeSpec -> C.BUILTIN_TYPE
                is GoFunctionDeclaration -> C.BUILTIN_FUNCTION
                else -> C.BUILTIN_CONSTANT
            }
        }
        return when (target) {
            is GoTypeSpec -> C.TYPE
            is GoTypeParamDefinition -> C.TYPE_PARAMETER
            is GoFunctionDeclaration -> C.FUNCTION_CALL
            is GoMethodDeclaration, is GoMethodSpec -> C.METHOD_CALL
            is GoFieldDefinition, is GoAnonymousFieldDefinition -> C.FIELD
            is GoParamDefinition, is GoReceiver -> C.PARAMETER
            // Locals are only visible in their own file; anything else is package level (stub-backed).
            is GoVarDefinition -> if (target.containingFile == file && GoPsiUtil.isInsideFunctionBody(target)) C.LOCAL_VARIABLE else C.PACKAGE_VARIABLE
            is GoConstDefinition -> C.CONSTANT
            is GoLabelDefinition -> C.LABEL
            is GoImportSpec -> C.PACKAGE
            else -> null
        }
    }
}
