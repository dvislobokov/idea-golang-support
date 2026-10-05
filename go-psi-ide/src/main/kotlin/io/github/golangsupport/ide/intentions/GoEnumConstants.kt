package io.github.golangsupport.ide.intentions

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.infer.GoExpressionTyper
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * The constants of an "enum" (a named non-interface type): those of its package whose type is the named type, in declaration order,
 * with their values. One lookup for Fill Switch / the exhaustive-switch inspection ([GoSwitchCases]) and for Generate `String()` of the host.
 */
object GoEnumConstants {

    class Member(val constant: GoConstDefinition, val value: GoConstant?) {
        val name: String get() = constant.name.orEmpty()
    }

    /**
     * The constants of [type] in its package (unexported ones only when [ownPackage]); null when the type is an interface, a builtin,
     * or has no constants. Read action.
     */
    fun of(type: GoNamedType, ownPackage: Boolean): List<Member>? {
        if (type.underlying() is GoInterfaceType) return null
        val declarationFile = type.declaration.containingFile as? GoFile ?: return null
        if (declarationFile.packageName == "builtin") return null
        val project = declarationFile.project
        val service = GoSemanticService.getInstance(project)
        val constants = GoPackageModel.getInstance(project).scopeOf(declarationFile).files.flatMap { it.consts }
            .filter { it.name != null && it.name != "_" && (ownPackage || it.isPublic()) && GoTypePredicates.identical(service.declarationType(it), type) }
        if (constants.isEmpty()) return null
        val typer = GoExpressionTyper.getInstance(project)
        return constants.map { Member(it, typer.constantValueOf(it)) }
    }

    /** One member per value, the first name winning (`exhaustive` rules: equal values are one member); members without a known value stay. */
    fun distinct(members: List<Member>): List<Member> {
        val seen = HashSet<GoConstant>()
        return members.filter { it.value == null || seen.add(it.value) }
    }

    /**
     * [constant] is declared in a const block that uses `iota` in any of its specs (GoLand's `GoSwitchMissingCasesForIotaConsts`: a spec
     * without `iota` in such a block counts too). A bare `iota` reference, not resolved: a shadowed `iota` is not worth a resolve per spec.
     */
    fun inIotaBlock(constant: GoConstDefinition): Boolean {
        val declaration = PsiTreeUtil.getParentOfType(constant, GoConstDeclaration::class.java) ?: return false
        return declaration.constSpecList.any { spec ->
            spec.expressionList.any { e ->
                (e as? GoReferenceExpression)?.let(::isIota) == true || PsiTreeUtil.findChildrenOfType(e, GoReferenceExpression::class.java).any(::isIota)
            }
        }
    }

    private fun isIota(e: GoReferenceExpression): Boolean = e.expression == null && e.identifier?.text == "iota"

    /** At least three non-zero values, every one a distinct power of two (`1 << iota`): bit flags, not an enum. */
    fun isFlags(values: List<GoConstant?>): Boolean {
        val nonZero = values.map { (it as? GoConstant.Int)?.value ?: return false }.filter { it.signum() != 0 }
        return nonZero.size >= 3 && nonZero.all { it.signum() > 0 && it.bitCount() == 1 } && nonZero.toSet().size == nonZero.size
    }
}
