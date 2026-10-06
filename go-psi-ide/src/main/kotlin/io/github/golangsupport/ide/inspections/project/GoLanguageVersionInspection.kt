package io.github.golangsupport.ide.inspections.project

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.gofix.GoFixPsi
import io.github.golangsupport.ide.inspections.gofix.GoFixVersions
import io.github.golangsupport.ide.rules.builtin.vet.GoVetPsi
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeParameters
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.project.api.GoVersion
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoLookup
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoTypeParamType

/**
 * Language features newer than the file's Go version (`//go:build go1.N`, else the `go` directive of the module; unknown → silent),
 * with go/types' `versionErrorf` texts: `type parameter requires go1.18 or later` (at the first type parameter of a function or type),
 * `clear requires go1.21 or later`, `built-in min requires go1.21 or later`, and `rangeKeyVal`'s
 * `cannot range over n (variable of type int): requires go1.22 or later` / `…: requires go1.23 or later` for a function iterator,
 * and the go1.27 pair: `generic method requires go1.27 or later` (a method's own type parameters, instead of `type parameter`) and
 * `use of promoted field Bar.Baz in struct literal of type Foo requires go1.27 or later` (a struct literal key reached through embedding).
 * Each text ends with cmd/compile's hint (noder/irgen.go): `(-lang was set to go1.17; check go.mod)`, or
 * `(file declares //go:build go1.21)` when the version comes from the file. Only these four features (the rest of go/types' version
 * checks are not ported).
 */
class GoLanguageVersionInspection : GoAnalysisInspectionBase() {

    override fun isApplicable(file: GoFile): Boolean = file.packageName != "builtin" && version(file)?.let { it < GO_1_27 } == true

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        val version = version(file) ?: return
        val hint = GoVetPsi.fileGoVersion(file)?.let { " (file declares //go:build $it)" } ?: " (-lang was set to $version; check go.mod)"
        fun report(at: PsiElement, required: GoVersion, text: String) {
            if (version < required) holder.registerProblem(at, "$text requires $required or later$hint", ProblemHighlightType.GENERIC_ERROR)
        }
        when (element) {
            // go/types resolver: a method's own type parameters are "generic method" (go1.27) only, not "type parameter"
            is GoTypeParameters -> PsiTreeUtil.findChildOfType(element, GoTypeParamDefinition::class.java)?.let {
                if (element.parent is GoMethodDeclaration) report(it, GO_1_27, "generic method") else report(it, GO_1_18, "type parameter")
            }
            is GoKey -> promotedField(element)?.let { report(element, GO_1_27, it) }
            is GoCallExpr -> {
                val callee = element.expression
                when {
                    GoFixPsi.isBuiltin(callee, "clear") -> report(callee, GO_1_21, "clear")
                    GoFixPsi.isBuiltin(callee, "min") -> report(callee, GO_1_21, "built-in min")
                    GoFixPsi.isBuiltin(callee, "max") -> report(callee, GO_1_21, "built-in max")
                }
            }
            is GoRangeClause -> {
                val x = element.expression ?: return
                val type = GoSemanticService.getInstance(file.project).typeOf(x)
                if (type is GoTypeParamType) return
                val required = when (val u = type.underlying()) {
                    is GoBasicType -> if (u.kind.isInteger) GO_1_22 else return
                    is GoSignatureType -> GO_1_23
                    else -> return
                }
                if (version < required) holder.registerProblem(x, "cannot range over ${operand(x)}: requires $required or later$hint", ProblemHighlightType.GENERIC_ERROR)
            }
        }
    }

    companion object {
        private val GO_1_18 = GoVersion("1.18")
        private val GO_1_21 = GoVersion("1.21")
        private val GO_1_22 = GoVersion("1.22")
        private val GO_1_23 = GoVersion("1.23")

        private val GO_1_27 = GoVersion("1.27")

        private fun version(file: GoFile): GoVersion? = GoFixVersions.languageVersion(file)

        /**
         * go/types `exprInternal` (struct literal keys): `use of promoted field Bar.Baz in struct literal of type Foo` when the key names a
         * field reached through embedded fields (the path is `fieldPath`: the embedded names, then the field). Keys of an explicit `T{…}` only.
         */
        private fun promotedField(key: GoKey): String? {
            val name = key.expression as? GoReferenceExpression ?: return null
            if (name.expression != null) return null
            val lit = (key.parent as? GoElement)?.parent?.let { it as? GoLiteralValue }?.parent as? GoCompositeLit ?: return null
            if (lit.typeList.isEmpty() && lit.typeReferenceExpression == null) return null
            val service = GoSemanticService.getInstance(key.project)
            val type = service.typeOf(lit)
            if (type.underlying() !is GoStructType) return null
            val selection = service.lookupFieldOrMethod(type, name.identifier.text, key.containingFile as? GoFile) as? GoLookup.Selection.Field ?: return null
            if (selection.path.isEmpty()) return null
            val path = (selection.path.map { it.name } + selection.member.name).joinToString(".")
            return "use of promoted field $path in struct literal of type ${service.render(type)}"
        }

        /** go/types `operand.String`: `10 (untyped int constant)`, `n (untyped int constant 10)`, `c (constant 3 of type int)`, `n (variable of type int)`, `f() (value of type int)`. */
        fun operand(x: GoExpression): String {
            val service = GoSemanticService.getInstance(x.project)
            val text = x.text
            val type = service.typeOf(x)
            val typeText = service.render(type)
            service.constantValue(x)?.let { c ->
                val value = c.render()
                return when {
                    type is GoBasicType && type.isUntyped -> if (value == text) "$text ($typeText constant)" else "$text ($typeText constant $value)"
                    value == text -> "$text (constant of type $typeText)"
                    else -> "$text (constant $value of type $typeText)"
                }
            }
            var e: PsiElement = x
            while (e is GoParenthesesExpr) e = e.inner ?: break
            val isVar = e is GoReferenceExpression && service.resolve(e).firstOrNull().let { it is GoVarDefinition || it is GoParamDefinition || it is GoReceiver }
            return "$text (${if (isVar) "variable" else "value"} of type $typeText)"
        }
    }
}
