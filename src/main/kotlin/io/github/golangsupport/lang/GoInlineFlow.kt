package io.github.golangsupport.lang

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanDir
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * Section E of the catalogue past the first batch: what a `for` and a `switch` go over (E3, E4, E10, E11), and the conditions of an
 * `if` that do not follow from the statement above it (E6, E7, E9). Each rule is a function named by its id.
 */
object GoInlineFlow {
    private fun brace(p: GoInlinePlace): String = if (p.slot.rest.isEmpty()) " {" else ""

    // --- for (E3, E4) ---

    fun loop(p: GoInlinePlace): String? {
        val found = if (p.slot.names.isEmpty()) e3RangeChannel(p) else e4CountedLoop(p, p.slot.names[0])
        return found?.let { it + brace(p) }
    }

    private fun receivable(p: GoInlinePlace, variable: GoInlineVariable): Boolean = (variable.type.underlying() as? GoChanType)?.dir?.let { it != GoChanDir.SEND } == true

    /**
     * `for |` right after the channel is made (or after the goroutines started with it): `range ch`. Only a channel the statements
     * between mention, and only one.
     */
    fun e3RangeChannel(p: GoInlinePlace): String? {
        var statement = p.previousStatement ?: return null
        val between = ArrayList<GoStatement>()
        repeat(4) {
            val short = statement as? GoShortVarDeclaration ?: PsiTreeUtil.getChildOfType(statement, GoShortVarDeclaration::class.java)
            val declared = short?.varDefinitionList.orEmpty().mapNotNull { d -> p.variables.firstOrNull { it.element == d && receivable(p, it) } }
            if (declared.isNotEmpty()) {
                val channel = declared.singleOrNull() ?: return null
                return channel.name.takeIf { name -> between.all { Regex("""\b$name\b""").containsMatchIn(it.text) } }?.let { "range $it" }
            }
            if (statement !is GoGoStatement && statement !is GoDeferStatement) return null
            between += statement
            statement = PsiTreeUtil.getPrevSiblingOfType(statement, GoStatement::class.java) ?: return null
        }
        return null
    }

    /** `for i := |` with one slice in scope: `0; i < len(s); i++`. */
    fun e4CountedLoop(p: GoInlinePlace, index: String): String? {
        val slices = p.variables.filter { it.name != index && (it.type.underlying() is GoSliceType || it.type.underlying() is GoArrayType) }
        val slice = slices.singleOrNull() ?: return null
        return "0; $index < len(${slice.name}); $index++"
    }

    // --- switch (E10, E11) ---

    fun switch(p: GoInlinePlace): String? {
        val found = if (p.slot.names.isEmpty()) e10Enum(p) else e11TypeSwitch(p, p.slot.names[0])
        return found?.let { it + brace(p) }
    }

    /** The constants of the package of [type], when it is a type of the package that has at least two. */
    fun constantsOf(p: GoInlinePlace, type: GoNamedType): List<String> {
        if (type.underlying() !is GoBasicType) return emptyList()
        if (!p.isOwn(type)) return emptyList()
        val constants = p.packageConsts.values.filter { c -> p.same(p.typeOf(c), type) }.mapNotNull { it.name }.filter { it != "_" }
        return if (constants.size >= 2) constants else emptyList()
    }

    /** `switch |` with one variable of an enumeration of the package (a type with its constants) in scope: `x`. */
    fun e10Enum(p: GoInlinePlace): String? {
        val enums = p.variables.filter { v -> (v.type as? GoNamedType)?.let { constantsOf(p, it).isNotEmpty() } == true }
        return enums.singleOrNull()?.name
    }

    /** `switch v := |` with one variable of an interface type in scope (a context aside): `x.(type)`. */
    fun e11TypeSwitch(p: GoInlinePlace, name: String): String? {
        val interfaces = p.variables.filter { v ->
            v.name != name && v.type.underlying() is GoInterfaceType && !p.isStandard(v.type, "context", "Context") && !p.isStandard(v, "context", "Context")
        }
        return interfaces.singleOrNull()?.name?.let { "$it.(type)" }
    }

    // --- if (E6, E7, E9) ---

    /** The condition of an `if` that opens the body of a function: a nil pointer or an empty slice among the parameters (E6, E7). */
    fun firstCondition(p: GoInlinePlace, statement: GoIfStatement): String? {
        if (statement.parent !== p.body) return null
        val found = listOfNotNull(e6NilParameter(p), e7EmptyParameter(p))
        return found.singleOrNull()
    }

    private fun parameters(p: GoInlinePlace): List<GoInlineVariable> =
        p.variables.filter { v ->
            v.element is io.github.golangsupport.lang.psi.GoParamDefinition && p.owner?.let { PsiTreeUtil.isAncestor(it, v.element, true) } == true &&
                p.body?.textRange?.contains(v.element.textRange) != true
        }

    /** The pointer parameter the function dereferences below, the only one: `p == nil`. Not a pointer of the standard library (`*http.Request`). */
    fun e6NilParameter(p: GoInlinePlace): String? {
        val pointers = parameters(p).filter { v ->
            val pointer = v.type as? GoPointerType ?: return@filter false
            val named = pointer.elem as? GoNamedType ?: return@filter false
            val own = p.isOwn(named)
            if (!own && named.pkgPath?.substringBefore('/')?.contains('.') != true) return@filter false
            val uses = p.usesOf(v.element)
            uses.selected.isNotEmpty()
        }
        return pointers.singleOrNull()?.let { "${it.name} == nil" }
    }

    /** The slice parameter of a function, the only one, that it reads below: `len(s) == 0`. */
    fun e7EmptyParameter(p: GoInlinePlace): String? {
        val slices = parameters(p).filter { it.type.underlying() is GoSliceType && p.usesOf(it.element).count > 0 }
        return slices.singleOrNull()?.let { "len(${it.name}) == 0" }
    }

    private val SENTINEL = Regex("""^Err[A-Z0-9]\w*$""")

    /** `if errors.|` after `err := …`: `errors.Is(err, ErrNotFound)` with the sentinel error of the package the function mentions, or the only one. */
    fun e9ErrorsIs(p: GoInlinePlace, error: String): String? {
        val sentinels = p.packageVars.values.filter { v -> v.name?.let(SENTINEL::matches) == true && GoReturnValues.isError(p.typeOf(v)) }.mapNotNull { it.name }
        val body = p.body?.text.orEmpty()
        val mentioned = sentinels.filter { Regex("""\b$it\b""").containsMatchIn(body) }
        val sentinel = mentioned.singleOrNull() ?: sentinels.singleOrNull()?.takeIf { mentioned.isEmpty() } ?: return null
        val errors = p.qualifier("errors")
        return "$errors.Is($error, $sentinel)"
    }
}
