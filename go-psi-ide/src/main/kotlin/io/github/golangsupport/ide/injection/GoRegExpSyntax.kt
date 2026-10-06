package io.github.golangsupport.ide.injection

/**
 * The RE2 escapes and Unicode classes of Go's `regexp/syntax` (package doc), as the platform's RegExp completion and documentation
 * read them from [GoRegExpLanguageHost]: `\d`, `\A`, `\x{10FFFF}`, … after a backslash, `\p{Greek}` after `\p{`. Rows are
 * `[name, description]` without the backslash. With empty tables the platform's contributor offered nothing (0 rows, seen live).
 */
object GoRegExpSyntax {

    /** Escapes after `\`: Perl classes, empty-string assertions, character escapes, quoting. */
    val CHARACTER_CLASSES: Array<Array<String>> = arrayOf(
        arrayOf("d", "digits (== [0-9])"),
        arrayOf("D", "not digits (== [^0-9])"),
        arrayOf("s", "whitespace (== [\\t\\n\\f\\r ])"),
        arrayOf("S", "not whitespace (== [^\\t\\n\\f\\r ])"),
        arrayOf("w", "word characters (== [0-9A-Za-z_])"),
        arrayOf("W", "not word characters (== [^0-9A-Za-z_])"),
        arrayOf("A", "at beginning of text"),
        arrayOf("z", "at end of text"),
        arrayOf("b", "at ASCII word boundary (\\w on one side and \\W, \\A, or \\z on the other)"),
        arrayOf("B", "not at ASCII word boundary"),
        arrayOf("a", "bell (== \\007)"),
        arrayOf("f", "form feed (== \\014)"),
        arrayOf("t", "horizontal tab (== \\011)"),
        arrayOf("n", "newline (== \\012)"),
        arrayOf("r", "carriage return (== \\015)"),
        arrayOf("v", "vertical tab character (== \\013)"),
        arrayOf("x7F", "hex character code (exactly two digits)"),
        arrayOf("x{10FFFF}", "hex character code"),
        arrayOf("Q", "literal text until \\E, even if it has punctuation"),
        arrayOf("E", "nothing, but ends quoting started by \\Q"),
        arrayOf("*", "literal *, for any punctuation character *"),
        arrayOf("pN", "Unicode character class (one-letter name)"),
        arrayOf("PN", "negated Unicode character class (one-letter name)"),
        arrayOf("P{Greek}", "negated Unicode character class"),
    )

    /** Unicode general categories `\p{Lu}` (one-letter ones also as `\pL`) with their names; `Any` is RE2's own. */
    val CATEGORIES: Array<Array<String>> = arrayOf(
        arrayOf("Any", "any character"),
        arrayOf("C", "other"), arrayOf("Cc", "control"), arrayOf("Cf", "format"), arrayOf("Cn", "unassigned"), arrayOf("Co", "private use"),
        arrayOf("Cs", "surrogate"),
        arrayOf("L", "letter"), arrayOf("LC", "cased letter"), arrayOf("Ll", "lowercase letter"), arrayOf("Lm", "modifier letter"),
        arrayOf("Lo", "other letter"), arrayOf("Lt", "titlecase letter"), arrayOf("Lu", "uppercase letter"),
        arrayOf("M", "mark"), arrayOf("Mc", "spacing mark"), arrayOf("Me", "enclosing mark"), arrayOf("Mn", "nonspacing mark"),
        arrayOf("N", "number"), arrayOf("Nd", "decimal number"), arrayOf("Nl", "letter number"), arrayOf("No", "other number"),
        arrayOf("P", "punctuation"), arrayOf("Pc", "connector punctuation"), arrayOf("Pd", "dash punctuation"), arrayOf("Pe", "close punctuation"),
        arrayOf("Pf", "final punctuation"), arrayOf("Pi", "initial punctuation"), arrayOf("Po", "other punctuation"), arrayOf("Ps", "open punctuation"),
        arrayOf("S", "symbol"), arrayOf("Sc", "currency symbol"), arrayOf("Sk", "modifier symbol"), arrayOf("Sm", "math symbol"), arrayOf("So", "other symbol"),
        arrayOf("Z", "separator"), arrayOf("Zl", "line separator"), arrayOf("Zp", "paragraph separator"), arrayOf("Zs", "space separator"),
    )

    /** Script names of Go's `unicode.Scripts` (Go 1.27); GoLand shows them without a description. */
    val SCRIPTS: List<String> = listOf(
        "Adlam", "Ahom", "Anatolian_Hieroglyphs", "Arabic", "Armenian", "Avestan", "Balinese", "Bamum",
        "Bassa_Vah", "Batak", "Bengali", "Beria_Erfe", "Bhaiksuki", "Bopomofo", "Brahmi", "Braille",
        "Buginese", "Buhid", "Canadian_Aboriginal", "Carian", "Caucasian_Albanian", "Chakma", "Cham", "Cherokee",
        "Chorasmian", "Common", "Coptic", "Cuneiform", "Cypriot", "Cypro_Minoan", "Cyrillic", "Deseret",
        "Devanagari", "Dives_Akuru", "Dogra", "Duployan", "Egyptian_Hieroglyphs", "Elbasan", "Elymaic", "Ethiopic",
        "Garay", "Georgian", "Glagolitic", "Gothic", "Grantha", "Greek", "Gujarati", "Gunjala_Gondi",
        "Gurmukhi", "Gurung_Khema", "Han", "Hangul", "Hanifi_Rohingya", "Hanunoo", "Hatran", "Hebrew",
        "Hiragana", "Imperial_Aramaic", "Inherited", "Inscriptional_Pahlavi", "Inscriptional_Parthian", "Javanese", "Kaithi", "Kannada",
        "Katakana", "Kawi", "Kayah_Li", "Kharoshthi", "Khitan_Small_Script", "Khmer", "Khojki", "Khudawadi",
        "Kirat_Rai", "Lao", "Latin", "Lepcha", "Limbu", "Linear_A", "Linear_B", "Lisu",
        "Lycian", "Lydian", "Mahajani", "Makasar", "Malayalam", "Mandaic", "Manichaean", "Marchen",
        "Masaram_Gondi", "Medefaidrin", "Meetei_Mayek", "Mende_Kikakui", "Meroitic_Cursive", "Meroitic_Hieroglyphs", "Miao", "Modi",
        "Mongolian", "Mro", "Multani", "Myanmar", "Nabataean", "Nag_Mundari", "Nandinagari", "New_Tai_Lue",
        "Newa", "Nko", "Nushu", "Nyiakeng_Puachue_Hmong", "Ogham", "Ol_Chiki", "Ol_Onal", "Old_Hungarian",
        "Old_Italic", "Old_North_Arabian", "Old_Permic", "Old_Persian", "Old_Sogdian", "Old_South_Arabian", "Old_Turkic", "Old_Uyghur",
        "Oriya", "Osage", "Osmanya", "Pahawh_Hmong", "Palmyrene", "Pau_Cin_Hau", "Phags_Pa", "Phoenician",
        "Psalter_Pahlavi", "Rejang", "Runic", "Samaritan", "Saurashtra", "Sharada", "Shavian", "Siddham",
        "Sidetic", "SignWriting", "Sinhala", "Sogdian", "Sora_Sompeng", "Soyombo", "Sundanese", "Sunuwar",
        "Syloti_Nagri", "Syriac", "Tagalog", "Tagbanwa", "Tai_Le", "Tai_Tham", "Tai_Viet", "Tai_Yo",
        "Takri", "Tamil", "Tangsa", "Tangut", "Telugu", "Thaana", "Thai", "Tibetan",
        "Tifinagh", "Tirhuta", "Todhri", "Tolong_Siki", "Toto", "Tulu_Tigalari", "Ugaritic", "Vai",
        "Vithkuqi", "Wancho", "Warang_Citi", "Yezidi", "Yi", "Zanabazar_Square",
    )

    /** `\p{…}` names: the categories with a description, then the scripts with an empty one, as GoLand (the platform renders them as `p{name}` rows). */
    // Always two elements: the platform's `\p{` reference reads the description unchecked (a one-element row failed with an index error).
    val PROPERTIES: Array<Array<String>> = CATEGORIES + SCRIPTS.map { arrayOf(it, "") }.toTypedArray()

    private val DESCRIPTIONS: Map<String, String> =
        CATEGORIES.associate { it[0] to "Unicode category ${it[0]}: ${it[1]}" } + SCRIPTS.associateWith { "Unicode script $it" }

    /** What [name] (`Lu`, `Greek`, `^Lu`) is, for the platform's documentation of `\p{name}`; null when RE2 has no such class. */
    fun propertyDescription(name: String?): String? = name?.removePrefix("^")?.let(DESCRIPTIONS::get)
}
