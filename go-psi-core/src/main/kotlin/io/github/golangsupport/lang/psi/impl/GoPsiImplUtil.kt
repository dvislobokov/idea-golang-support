package io.github.golangsupport.lang.psi.impl

import com.intellij.openapi.util.text.StringUtil
import io.github.golangsupport.lang.psi.GoParType
import io.github.golangsupport.lang.psi.GoPointerType
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.lang.psi.GoType
import io.github.golangsupport.lang.psi.GoTypes
import org.jetbrains.annotations.ApiStatus

/** Small PSI helpers shared by mixins and stub element types. */
@ApiStatus.Internal
object GoPsiImplUtil {

    /** Go's exported-name rule: the first character is a Unicode upper-case letter (Lu). */
    @JvmStatic
    fun isExported(name: String?): Boolean =
        !name.isNullOrEmpty() && Character.isUpperCase(name.codePointAt(0))

    /** Unquotes an interpreted (`"..."`) or raw (`` `...` ``) string literal; tolerates unterminated literals. */
    @JvmStatic
    fun unquote(literal: String?): String {
        if (literal.isNullOrEmpty()) return ""
        val quote = literal[0]
        if (quote != '"' && quote != '`') return literal
        val end = if (literal.length > 1 && literal.last() == quote) literal.length - 1 else literal.length
        val body = literal.substring(1, end)
        return if (quote == '`') body.replace("\r", "") else StringUtil.unescapeStringCharacters(body)
    }

    /** The local name of an import: the alias, or the last segment of the path. */
    @JvmStatic
    fun importName(alias: String?, path: String): String = alias ?: path.substringAfterLast('/')

    /** The receiver's base type: `T` for `T`, `*T`, `(*T)`, `T[K]`, `pkg.T` (invalid but parsable). */
    data class ReceiverType(val name: String, val pointer: Boolean)

    @JvmStatic
    fun receiverBaseType(receiver: GoReceiver?): ReceiverType? {
        var type: GoType? = receiver?.type
        var pointer = false
        while (type != null) {
            type = when (type) {
                is GoParType -> type.type
                is GoPointerType -> {
                    pointer = true
                    type.type
                }
                else -> break
            }
        }
        if (type == null || type.node.elementType !== GoTypes.TYPE) return null
        val name = type.typeReferenceExpression?.identifier?.text ?: return null
        return ReceiverType(name, pointer)
    }

    /** Number of parameters: one per name, or one per unnamed parameter declaration. */
    @JvmStatic
    fun arity(signature: GoSignature?): Int =
        signature?.parameters?.parameterDeclarationList?.sumOf { maxOf(1, it.paramDefinitionList.size) } ?: 0
}
