package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoSourceText
import io.github.golangsupport.lang.psi.GoAddExpr
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoConstant

/**
 * modernize / vet `hostport`: an address built as `fmt.Sprintf("%s:%d", host, port)` (or `"%s:%s"`, or `host + ":" + port`) and passed
 * to `net.Dial`-like functions (directly, or through a local `addr := …` used only there) does not work with IPv6 literals:
 * → `net.JoinHostPort(host, strconv.Itoa(port))` / `net.JoinHostPort(host, port)`. Nothing is reported for addresses that never reach
 * a dial / listen call (`"%s:%d"` is also the usual `file:line`).
 */
class GoFixHostPortInspection : GoFix2InspectionBase() {
    override val minVersion = "1.0"

    override fun detect(element: PsiElement, file: GoFile): GoFixFinding? {
        val (host, port, format) = parts(element) ?: return null
        if (!reachesDial(element as GoExpression)) return null
        val service = GoSemanticService.getInstance(file.project)
        if (!isKind(service.typeOf(host)) { it.isString }) return null
        val portType = service.typeOf(port)
        val portText: (GoSourceText) -> String = when {
            format.endsWith("%s") && isKind(portType) { it.isString } -> { _ -> port.text }
            format.endsWith("%d") && portType.underlying() == GoBasicType.INT -> { s -> "${s.prefix("strconv", "strconv")}Itoa(${port.text})" }
            format.endsWith("%d") && isKind(portType) { it.isInteger } -> { s -> "${s.prefix("fmt", "fmt")}Sprint(${port.text})" }
            else -> return null
        }
        return GoFixFinding(element, "address format \"$format\" does not work with IPv6", listOf("Replace with net.JoinHostPort" to { s ->
            listOf(GoFixEdit.replace(element, "${s.prefix("net", "net")}JoinHostPort(${host.text}, ${portText(s)})"))
        }))
    }

    /** (host, port, format) of `fmt.Sprintf("%s:%d", host, port)` or `host + ":" + port`. */
    private fun parts(element: PsiElement): Triple<GoExpression, GoExpression, String>? {
        if (element is GoCallExpr) {
            val args = GoFixPsi.args(element)
            if (args.size != 3 || GoFixPsi.hasEllipsis(element)) return null
            val format = (GoSemanticService.getInstance(element.project).constantValue(args[0]) as? GoConstant.Str)?.value ?: return null
            if (format != "%s:%d" && format != "%s:%s") return null
            if (!GoFixPsi.isCallTo(element, "fmt.Sprintf")) return null
            return Triple(args[1], args[2], format)
        }
        if (element is GoAddExpr && element.add != null && element.parent !is GoAddExpr) {
            val left = element.left as? GoAddExpr ?: return null
            if (left.add == null || left.left is GoAddExpr) return null
            val colon = left.right ?: return null
            val value = (GoSemanticService.getInstance(element.project).constantValue(colon) as? GoConstant.Str)?.value
            if (value != ":") return null
            return Triple(left.left ?: return null, element.right ?: return null, "%s:%s")
        }
        return null
    }

    /** Whether [address] is the address argument of a dial / listen call, or the value of a local used only as one. */
    private fun reachesDial(address: GoExpression): Boolean {
        if (isDialAddress(address)) return true
        val declaration = address.parent as? GoShortVarDeclaration ?: return false
        val index = declaration.expressionList.indexOf(address)
        if (declaration.expressionList.size != declaration.varDefinitionList.size) return false
        val variable = declaration.varDefinitionList.getOrNull(index) as? GoVarDefinition ?: return false
        val body = GoFixPsi.enclosingBody(declaration) ?: return false
        val uses = GoFixPsi.references(body, variable)
        return uses.size == 1 && isDialAddress(uses.single())
    }

    private fun isDialAddress(e: GoExpression): Boolean {
        val list = e.parent as? GoArgumentList ?: return false
        val call = list.parent as? GoCallExpr ?: return false
        val index = GoFixPsi.args(call).indexOf(e)
        val key = GoFixPsi.callee(call, *DIAL.keys.map { it.substringAfterLast('.') }.toTypedArray()) ?: return false
        return DIAL[key] == index
    }

    private fun isKind(type: io.github.golangsupport.semantic.types.GoType, test: (GoBasicKind) -> Boolean): Boolean =
        (type.underlying() as? GoBasicType)?.kind?.let(test) == true

    companion object {
        /** Functions taking an address, with the index of that argument. */
        private val DIAL = mapOf(
            "net.Dial" to 1, "net.DialTimeout" to 1, "net.Listen" to 1, "net.ListenPacket" to 1,
            "net.Dialer.Dial" to 1, "net.Dialer.DialContext" to 2, "net.ListenConfig.Listen" to 2, "net.ListenConfig.ListenPacket" to 2,
            "net.ResolveTCPAddr" to 1, "net.ResolveUDPAddr" to 1,
        )
    }
}
