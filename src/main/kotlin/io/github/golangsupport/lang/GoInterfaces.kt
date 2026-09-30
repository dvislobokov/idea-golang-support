package io.github.golangsupport.lang

/** The package an interface is declared in: what qualifying the types of its methods in another package takes. */
class GoInterfaceOrigin(val importPath: String, val packageName: String, val exportedTypes: Set<String>, val imports: List<GoImport>)

/** A method of an interface, its signature as the package of the interface writes it. */
class GoInterfaceMethod(val name: String, val signature: String, val origin: GoInterfaceOrigin)

/** The members of an interface body: methods with their full signatures, and the names of the embedded interfaces (`Reader`, `io.Reader`). */
class GoInterfaceBody(val methods: List<Pair<String, String>>, val embedded: List<String>)

/**
 * Interfaces by their text, for Implement Interface: the scanner keeps a signature of one line and cuts it for the Structure view,
 * here a method is read whole from the body of the interface, and made to compile in the file that implements it.
 */
object GoInterfaces {
    class Rewritten(val name: String, val signature: String, val imports: List<String>)

    private val METHOD = Regex("""^([A-Za-z_]\w*)\s*(\(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val EMBEDDED = Regex("""^\*?([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)?)(?:\[.*])?$""", RegexOption.DOT_MATCHES_ALL)
    private val QUALIFIED = Regex("""(?<![\w.])([A-Za-z_]\w*)\.([A-Z]\w*)""")
    private val EXPORTED = Regex("""(?<![\w.])([A-Z]\w*)""")
    private val WHITESPACE = Regex("""\s+""")

    /** The predeclared interfaces: `error` has a method, the others constrain type parameters and give nothing to implement. */
    private val PREDECLARED = setOf("any", "comparable")

    /** [body] with or without its braces; comments are dropped, a signature written over several lines is put on one. */
    fun parseBody(body: CharSequence): GoInterfaceBody {
        val text = stripComments(body).trim().removePrefix("{").removeSuffix("}")
        val methods = ArrayList<Pair<String, String>>()
        val embedded = ArrayList<String>()
        for (member in members(text)) {
            val method = METHOD.find(member)
            if (method != null) {
                methods += method.groupValues[1] to tidy(method.groupValues[2])
                continue
            }
            val name = EMBEDDED.find(member)?.groupValues?.get(1) ?: continue
            if (name == "error") methods += "Error" to "() string" else if (name !in PREDECLARED) embedded += name
        }
        return GoInterfaceBody(methods, embedded)
    }

    /**
     * The signature of [method] as the file of [targetPath] with [targetImports] has to write it: the types of the package of the
     * interface get its name in front of them, the ones it takes from other packages are called by the names those packages have in the
     * target file; [Rewritten.imports] are the paths the file does not import yet.
     */
    fun rewrite(method: GoInterfaceMethod, targetPath: String?, targetImports: List<GoImport>): Rewritten {
        val origin = method.origin
        val needed = LinkedHashSet<String>()
        fun nameIn(path: String): String = targetImports.firstOrNull { it.path == path }?.let { GoImports.nameOf(it) } ?: run { needed += path; GoSemanticColors.packageName(path) }
        var signature = QUALIFIED.replace(method.signature) { match ->
            val (qualifier, name) = match.destructured
            val path = origin.imports.firstOrNull { GoImports.nameOf(it) == qualifier }?.path ?: return@replace match.value
            if (path == targetPath) name else "${nameIn(path)}.$name"
        }
        if (origin.importPath != targetPath) {
            var packageName: String? = null
            signature = EXPORTED.replace(signature) { match ->
                if (match.value !in origin.exportedTypes) match.value
                else "${packageName ?: nameIn(origin.importPath).also { packageName = it }}.${match.value}"
            }
        }
        return Rewritten(method.name, signature, needed.toList())
    }

    /** The members of a body, one per line or `;`, a member going on through the brackets it opens. */
    private fun members(text: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in text) {
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            if ((c == '\n' || c == ';') && depth <= 0) {
                current.toString().trim().takeIf { it.isNotEmpty() }?.let { result += it }
                current.setLength(0)
            } else current.append(c)
        }
        current.toString().trim().takeIf { it.isNotEmpty() }?.let { result += it }
        return result
    }

    /** `(\n\tctx context.Context,\n\tid int,\n) error` -> `(ctx context.Context, id int) error`. */
    private fun tidy(signature: String): String = signature.replace(WHITESPACE, " ").replace(Regex("""\(\s+"""), "(").replace(Regex("""(,)?\s+\)"""), ")").trim()

    private fun stripComments(text: CharSequence): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && text.getOrNull(i + 1) == '/' -> { while (i < text.length && text[i] != '\n') i++; continue }
                c == '/' && text.getOrNull(i + 1) == '*' -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    // a line break inside the comment still ends the member
                    if (text.subSequence(i, end).contains('\n')) out.append('\n')
                    i = end
                    continue
                }
                c == '"' || c == '`' -> {
                    val end = text.indexOf(c, i + 1).let { if (it < 0) text.length else it + 1 }
                    out.append(text, i, end)
                    i = end
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
