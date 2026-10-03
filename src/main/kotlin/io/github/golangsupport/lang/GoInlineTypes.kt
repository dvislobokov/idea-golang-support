package io.github.golangsupport.lang

import io.github.golangsupport.lang.psi.GoStructType

/**
 * The type of a struct field or a `var` by its name, as GoLand guesses it: `Name str|` → `string`, `CreatedAt ` → `time.Time`,
 * `Timeout ` → `time.Duration`, `Users ` → `[]User`. A field of the same name elsewhere in the package wins over the dictionary:
 * the code base has already said what an `ID` is.
 */
object GoInlineTypes {
    private val STRINGS = Regex("""^(Name|Title|Email|Description|Desc|Text|Message|Msg|Host|Hostname|Address|Addr|Username|User|Login|Password|Token|Secret|Slug|Label|Comment|Summary|Content|Body|Query|Path|Dir|File|Filename|Method|Scheme|Domain|Phone|Country|City|Street|Version|Kind|Mode|Format|Language|Lang|Locale|Currency|Note|Reason|Prefix|Suffix|Key|DSN|Hash|Signature|Sha|Etag|UUID|Uuid)$|\w+(Name|Email|URL|Url|URI|Uri|Path|Dir|File|Text|Message|Token|Key|Address|Addr|Host|Title|Code|Hash|Version|Label)$""")
    private val INTS = Regex("""^(Count|Age|Size|Len|Length|Limit|Offset|Port|Index|Idx|Total|Retries|Attempts|Level|Depth|Page|PerPage|PageSize|Workers|Capacity|Cap|Width|Height|Year|Month|Day|Hour|Minute|Second|Priority|Order|Position|Pos|Line|Column|Col|Rank|Version)$|\w+(Count|Size|Len|Length|Limit|Offset|Port|Index|Total|Num|Number|Retries|Attempts|Level|Depth|Width|Height)$|^(Num|Max|Min)[A-Z]\w*$""")
    private val BOOLS = Regex("""^(Is|Has|Can|Should|Allow|Use|Enable|Disable|Skip|Need)[A-Z]\w*$|^(Enabled|Disabled|Active|Deleted|Verified|Visible|Hidden|Done|OK|Ok|Valid|Required|Optional|Debug|Verbose|Force|Public|Private|Admin|Locked|Archived|Published|Ready|Running|Closed|Insecure|ReadOnly|Readonly|DryRun)$|\w+(Enabled|Disabled|Only)$""")
    private val TIMES = Regex("""^(Time|Timestamp|Date|Deadline|Expires|Expiry|Expiration|Created|Updated|Deleted|Started|Finished|Birthday)$|\w+(At|Time|Date|Deadline)$""")
    private val DURATIONS = Regex("""^(Timeout|Interval|Duration|TTL|Ttl|Delay|Period|Backoff|Elapsed|Latency|Wait)$|\w+(Timeout|Interval|Duration|TTL|Ttl|Delay|Period|Backoff)$""")
    private val FLOATS = Regex("""^(Price|Amount|Balance|Rate|Ratio|Score|Weight|Lat|Latitude|Lon|Lng|Longitude|Percent|Percentage|Factor|Temperature|Temp|Probability|Cost|Discount|Tax)$|\w+(Price|Amount|Rate|Ratio|Score|Weight|Percent|Factor|Cost)$""")
    private val BYTES = setOf("Data", "Raw", "Payload", "Bytes", "Buf", "Buffer", "Blob", "Content")
    private val STRING_SLICES = Regex("""^(Tags|Names|Labels|Emails|Args|Arguments|Lines|Paths|Hosts|Addresses|Addrs|Roles|Scopes|Permissions|Keys|Values|Words|Aliases|Domains|Origins|Fields|Columns|Files|URLs|Urls)$|\w+(Names|Tags|Labels|Paths|Keys|Emails|URLs|Urls)$""")
    private val STRING_MAPS = setOf("Metadata", "Meta", "Attrs", "Attributes", "Extra", "Params", "Env", "Annotations", "Properties", "Props", "Vars")

    /** The type for the field or variable [name], with the lead ([GoInlineSuggestions.Slot.lead]) of the slot; null when no rule knows it. */
    fun byName(p: GoInlinePlace, name: String): String? {
        val type = samePackageField(p, name) ?: dictionary(p, name) ?: return null
        return p.slot.lead + type
    }

    private fun dictionary(p: GoInlinePlace, name: String): String? {
        val upper = name.replaceFirstChar { it.uppercaseChar() }
        typeNamed(p, upper)?.let { return it }
        return when {
            upper == "ID" || upper.endsWith("ID") || upper.endsWith("Id") -> null
            upper == "Ctx" || upper == "Context" -> "${p.qualifier("context")}.Context"
            upper == "Err" || upper == "Error" || upper.endsWith("Err") -> "error"
            upper in setOf("Mu", "Mutex", "Lock") -> "${p.qualifier("sync")}.Mutex"
            upper in setOf("Wg", "WG", "WaitGroup") -> "${p.qualifier("sync")}.WaitGroup"
            upper in setOf("Logger", "Log") -> "*${p.qualifier("log/slog")}.Logger"
            upper == "DB" || upper == "Db" -> "*${p.qualifier("database/sql")}.DB"
            upper == "Client" || upper == "HTTPClient" || upper == "HttpClient" -> "*${p.qualifier("net/http")}.Client"
            upper in setOf("Headers", "Header") -> "${p.qualifier("net/http")}.Header"
            DURATIONS.matches(upper) -> "${p.qualifier("time")}.Duration"
            TIMES.matches(upper) -> "${p.qualifier("time")}.Time"
            BOOLS.matches(upper) -> "bool"
            FLOATS.matches(upper) -> "float64"
            upper in BYTES -> "[]byte"
            upper in STRING_MAPS -> "map[string]string"
            STRING_SLICES.matches(upper) -> "[]string"
            INTS.matches(upper) -> "int"
            STRINGS.matches(upper) -> "string"
            GoInlineSuggestions.isPlural(upper) -> typeNamed(p, GoInlineDeclarations.singular(upper))?.let { "[]$it" }
            else -> null
        }
    }

    /** A type of the package with exactly that name: a field `Address` of type `Address`. */
    private fun typeNamed(p: GoInlinePlace, name: String): String? = p.packageTypes.keys.firstOrNull { it == name }

    /**
     * The type written for a field of the same name in a struct of the package (not the one at the caret). One answer only: two
     * different types for `ID` say nothing. A qualified type is taken when this file imports its package under the same name.
     */
    private fun samePackageField(p: GoInlinePlace, name: String): String? {
        val caret = p.slot.start
        val imported = p.original.imports.mapNotNullTo(HashSet()) { spec -> spec.alias ?: spec.path.substringAfterLast('/') }
        val found = LinkedHashSet<String>()
        for (spec in p.packageTypes.values) {
            val struct = spec.type as? GoStructType ?: continue
            if ((spec.containingFile == p.original || spec.containingFile == p.file) && struct.textRange.contains(caret)) continue
            for (declaration in struct.fieldDeclarationList) {
                if (declaration.fieldDefinitionList.none { it.name == name }) continue
                val text = declaration.type?.text?.takeIf { it.isNotBlank() && '\n' !in it } ?: continue
                val qualifier = Regex("""([A-Za-z_]\w*)\.""").find(text)?.groupValues?.get(1)
                if (qualifier != null && qualifier !in imported) continue
                found += text
            }
        }
        return found.singleOrNull()
    }

    /** Whether the `{` that encloses [lineStart] is the brace of a `struct`: a field is being declared on the line. */
    fun insideStruct(text: CharSequence, lineStart: Int): Boolean {
        var depth = 0
        var i = lineStart - 1
        var looked = 0
        while (i >= 0 && looked++ < 20_000) {
            when (text[i]) {
                '}' -> depth++
                '{' -> if (depth == 0) {
                    var end = i
                    while (end > 0 && text[end - 1].isWhitespace()) end--
                    return end >= 6 && text.subSequence(end - 6, end).toString() == "struct" && (end == 6 || !text[end - 7].let { it.isLetterOrDigit() || it == '_' })
                } else depth--
            }
            i--
        }
        return false
    }
}
