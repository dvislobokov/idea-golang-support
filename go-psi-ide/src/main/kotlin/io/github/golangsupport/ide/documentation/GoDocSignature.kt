package io.github.golangsupport.ide.documentation

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.navigation.GoItemPresentation
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoLabelDefinition
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeParameterDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.impl.GoUseScopes
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.infer.GoExpressionTyper
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoTypeRenderer

/**
 * gopls-style one-line (or, for struct/interface types, source-shaped) declarations shown at the
 * top of the documentation popup:
 *
 * ```
 * func Println(a ...any) (n int, err error)
 * func (b *Buffer) Write(p []byte) (n int, err error)
 * type Reader interface{...}                     (hint; the full popup shows the source)
 * var x int
 * const Pi untyped float = 3.14
 * field X int
 * ```
 */
object GoDocSignature {

    /** The definition text of [element]; [short] collapses struct and interface bodies to `{...}`. */
    @JvmStatic
    fun definition(element: PsiElement, short: Boolean): String? {
        val named = element as? GoNamedElement ?: return null
        val name = named.name ?: return null
        val semantic = GoSemanticService.getInstance(element.project)
        fun render(type: GoType) = renderType(type)
        return when (named) {
            is GoFunctionDeclaration -> "func $name" + signatureTail(named, semantic.declarationType(named))
            is GoMethodDeclaration -> {
                val receiver = named.receiver?.text?.let(GoItemPresentation::normalize) ?: "()"
                "func $receiver $name" + signatureTail(named, semantic.declarationType(named))
            }
            is GoMethodSpec -> {
                val iface = PsiTreeUtil.getParentOfType(named, GoTypeSpec::class.java)?.name
                (if (iface != null) "func ($iface) " else "func ") + name + signatureTail(named, semantic.declarationType(named))
            }
            is GoTypeSpec -> typeDefinition(named, name, short)
            is GoConstDefinition -> {
                val type = render(semantic.declarationType(named))
                val value = GoExpressionTyper.getInstance(element.project).constantValueOf(named)?.let(::constantText)
                "const $name $type" + (value?.let { " = $it" } ?: "")
            }
            is GoVarDefinition -> "var $name " + render(GoTypePredicates.defaultType(semantic.declarationType(named)))
            is GoParamDefinition -> "var $name " + render(semantic.declarationType(named))
            is GoReceiver -> "var $name " + render(semantic.declarationType(named))
            is GoFieldDefinition -> "field $name " + render(semantic.declarationType(named))
            is GoAnonymousFieldDefinition -> "field " + GoItemPresentation.normalize(named.text)
            is GoTypeParamDefinition -> {
                val constraint = (named.parent as? GoTypeParameterDeclaration)?.constraintElem?.text?.let(GoItemPresentation::normalize)
                "type $name" + (constraint?.let { " $it" } ?: "")
            }
            is GoLabelDefinition -> "label $name"
            is GoPackageClause -> "package $name"
            is GoImportSpec -> "package ${packageNameOf(named) ?: name}"
            else -> null
        }
    }

    /**
     * `(a int) error` part of a rendered signature (`func` and the name stripped), with type
     * parameters. Falls back to the source text when the type is unknown (builtins such as `len`
     * are typed by the checker, not by their `builtin.go` declarations).
     */
    private fun signatureTail(declaration: PsiElement, type: GoType): String {
        val sig = type as? GoSignatureType
        if (sig != null) return renderType(sig).removePrefix("func")
        val typeParams = (declaration as? io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration)?.typeParameters
        val signature = when (declaration) {
            is io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration -> declaration.signature
            is GoMethodSpec -> declaration.signature
            else -> null
        }
        return GoItemPresentation.typeParametersText(typeParams) + GoItemPresentation.signatureText(signature)
    }

    /** A constant value as Go source: `1024`, `3.14`, `"text"`, `(1 + 2i)`, `true`. */
    @JvmStatic
    fun constantText(value: GoConstant): String = when (value) {
        is GoConstant.Bool -> value.value.toString()
        is GoConstant.Int -> value.value.toString()
        is GoConstant.Float -> value.value.stripTrailingZeros().toPlainString()
        is GoConstant.Complex -> "(${value.re.stripTrailingZeros().toPlainString()} + ${value.im.stripTrailingZeros().toPlainString()}i)"
        is GoConstant.Str -> "\"" + com.intellij.openapi.util.text.StringUtil.escapeStringCharacters(value.value) + "\""
    }

    /** gopls-style type text: like [GoTypeRenderer], with the empty interface printed as `any`. */
    @JvmStatic
    fun renderType(type: GoType): String = GoTypeRenderer.render(type).replace("interface{}", "any")

    private fun typeDefinition(spec: GoTypeSpec, name: String, short: Boolean): String {
        val typeParams = GoItemPresentation.typeParametersText(spec.typeParameters)
        val assign = if (spec.isAlias) " =" else ""
        val type = spec.type
        val body = when {
            type == null -> ""
            type is GoStructType || type is GoInterfaceType -> {
                val keyword = if (type is GoStructType) "struct" else "interface"
                if (short) "$keyword{...}" else sourceOf(type)
            }
            else -> GoItemPresentation.normalize(type.text)
        }
        return "type $name$typeParams$assign $body"
    }

    /** Source text of a struct/interface type with comments removed and indentation normalized to tabs. */
    private fun sourceOf(type: PsiElement): String {
        val text = type.text.replace(COMMENT, "")
        val lines = text.split('\n').map { it.trimEnd() }.filter { it.isNotEmpty() }
        if (lines.size <= 1) return lines.joinToString("")
        val indent = lines.drop(1).dropLast(1).minOfOrNull { l -> l.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        return buildString {
            append(lines.first().trim())
            for (l in lines.drop(1).dropLast(1)) append("\n\t").append(if (l.length >= indent) l.substring(indent) else l.trim())
            append('\n').append(lines.last().trim())
        }
    }

    /** Exported methods declared on [spec]'s type in its package, rendered like functions (for type docs). */
    @JvmStatic
    fun methodLines(spec: GoTypeSpec): List<String> {
        val name = spec.name ?: return emptyList()
        val file = spec.containingFile as? GoFile ?: return emptyList()
        if (GoUseScopes.isInsideFunctionBody(spec)) return emptyList() // local types have no methods
        return GoPackageModel.getInstance(spec.project).scopeOf(file).methodsOf(name)
            .filter { it.isPublic() }
            .mapNotNull { definition(it, true) }
    }

    /** The package name an import brings in (the resolved package's name). */
    private fun packageNameOf(spec: GoImportSpec): String? =
        io.github.golangsupport.semantic.scope.GoScopes.importName(spec).takeIf { !spec.isDot && !spec.isBlank }

    private val COMMENT = Regex("//[^\\n]*|/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
}
