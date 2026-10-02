package io.github.golangsupport.ide.inspections.printf

/** What a verb accepts: a bit set over argument classes, as vet's `printfArgType`. */
object GoPrintfArg {
    const val INT = 1
    const val RUNE = 2
    const val STRING = 4
    const val FLOAT = 8
    const val COMPLEX = 16
    const val POINTER = 32
    const val BOOL = 64
    const val ERROR = 128
    const val ANY = INT or RUNE or STRING or FLOAT or COMPLEX or POINTER or BOOL
}

/** The `fmt` verbs with their accepted flags and argument classes (vet's `printVerbs` table). */
object GoPrintfVerbs {
    class Verb(val verb: Char, val flags: String, val args: Int)

    private const val NO_FLAG = ""
    private const val NUM_FLAG = " -+.0"
    private const val SHARP_NUM_FLAG = " -+.0#"
    private const val ALL_FLAGS = " -+.0#"

    private val TABLE: Map<Char, Verb> = listOf(
        Verb('%', NO_FLAG, 0),
        Verb('b', SHARP_NUM_FLAG, GoPrintfArg.INT or GoPrintfArg.FLOAT or GoPrintfArg.COMPLEX or GoPrintfArg.POINTER),
        Verb('c', "-", GoPrintfArg.RUNE or GoPrintfArg.INT),
        Verb('d', NUM_FLAG, GoPrintfArg.INT or GoPrintfArg.POINTER),
        Verb('e', SHARP_NUM_FLAG, GoPrintfArg.FLOAT or GoPrintfArg.COMPLEX),
        Verb('E', SHARP_NUM_FLAG, GoPrintfArg.FLOAT or GoPrintfArg.COMPLEX),
        Verb('f', SHARP_NUM_FLAG, GoPrintfArg.FLOAT or GoPrintfArg.COMPLEX),
        Verb('F', SHARP_NUM_FLAG, GoPrintfArg.FLOAT or GoPrintfArg.COMPLEX),
        Verb('g', SHARP_NUM_FLAG, GoPrintfArg.FLOAT or GoPrintfArg.COMPLEX),
        Verb('G', SHARP_NUM_FLAG, GoPrintfArg.FLOAT or GoPrintfArg.COMPLEX),
        Verb('o', SHARP_NUM_FLAG, GoPrintfArg.INT or GoPrintfArg.POINTER),
        Verb('O', SHARP_NUM_FLAG, GoPrintfArg.INT or GoPrintfArg.POINTER),
        Verb('p', "-#", GoPrintfArg.POINTER),
        Verb('q', " -+.0#", GoPrintfArg.RUNE or GoPrintfArg.INT or GoPrintfArg.STRING),
        Verb('s', " -+.0", GoPrintfArg.STRING),
        Verb('t', "-", GoPrintfArg.BOOL),
        Verb('T', "-", GoPrintfArg.ANY),
        Verb('U', "-#", GoPrintfArg.RUNE or GoPrintfArg.INT),
        Verb('v', ALL_FLAGS, GoPrintfArg.ANY),
        Verb('w', ALL_FLAGS, GoPrintfArg.ERROR),
        Verb('x', SHARP_NUM_FLAG, GoPrintfArg.RUNE or GoPrintfArg.INT or GoPrintfArg.STRING or GoPrintfArg.POINTER or GoPrintfArg.FLOAT or GoPrintfArg.COMPLEX),
        Verb('X', SHARP_NUM_FLAG, GoPrintfArg.RUNE or GoPrintfArg.INT or GoPrintfArg.STRING or GoPrintfArg.POINTER or GoPrintfArg.FLOAT or GoPrintfArg.COMPLEX),
    ).associateBy { it.verb }

    fun of(verb: Char): Verb? = TABLE[verb]

    /** The first flag of [flags] that [verb] does not accept, or null. */
    fun unsupportedFlag(verb: Verb, flags: String): Char? = flags.firstOrNull { it !in verb.flags }

    /** `fmt.Println("%d", x)`: a Printf directive in a Print argument (vet's `printFormatRE`). `%XX` with two hex digits is a URL escape. */
    private val PRINT_FORMAT = Regex("%[+\\-#]*([0-9]+|(\\[[0-9]+])?\\*)?\\.?([0-9]+|(\\[[0-9]+])?\\*)?(\\[[0-9]+])?[bcdefgopqstvxEFGTUX]")

    /** The first Printf-looking directive in [text] as (start, end), ignoring a trailing `%`; null when none. */
    fun possibleDirective(text: String): IntRange? {
        val trimmed = text.removeSuffix("%")
        if ('%' !in trimmed) return null
        for (m in PRINT_FORMAT.findAll(trimmed)) {
            val s = m.value
            if (s.length >= 3 && s[1].isHex() && s[2].isHex()) continue
            return m.range
        }
        return null
    }

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** `1 arg`, `2 args`. */
    fun count(n: Int, what: String): String = if (n == 1) "$n $what" else "$n ${what}s"
}
