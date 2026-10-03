package io.github.golangsupport.lang

import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * The rules of the catalogue past the first batch that write a call of the standard library: `time` (A22–A24), `sync.Once` (A29),
 * `strconv` (A38), `encoding/json` (A40, A46, A47), `database/sql` (A45), `regexp` (A48, H4), `os/signal` (C17) and the fields of a
 * literal the library has a value for (D4, D5, D7). Each rule is a function named by its id.
 */
object GoInlineLibrary {
    // --- time (A22, A23, A24) ---

    private fun durations(p: GoInlinePlace): List<GoInlineVariable> =
        p.variables.filter { p.isStandard(it.type, "time", "Duration") || p.isStandard(it, "time", "Duration") }

    /** The `time.Duration` in scope: the only one, else the one named as one of [names]. */
    private fun duration(p: GoInlinePlace, names: Set<String>): String? {
        val all = durations(p)
        return (all.singleOrNull() ?: all.filter { it.name in names }.singleOrNull())?.name
    }

    /** `deadline :=` with a timeout in scope: `time.Now().Add(timeout)`. */
    fun a22Deadline(p: GoInlinePlace, name: String): String? {
        if (name != "deadline" && !name.endsWith("Deadline")) return null
        val timeout = duration(p, setOf("timeout", "ttl", "d")) ?: return null
        return "${p.qualifier("time")}.Now().Add($timeout)"
    }

    /** `ticker :=` with an interval in scope: `time.NewTicker(interval)`. */
    fun a23Ticker(p: GoInlinePlace, name: String): String? {
        if (name != "ticker" && !name.endsWith("Ticker")) return null
        val interval = duration(p, setOf("interval", "period", "every", "tick", "d", "freq")) ?: return null
        return "${p.qualifier("time")}.NewTicker($interval)"
    }

    /** `timer :=` with a duration in scope: `time.NewTimer(d)`. */
    fun a24Timer(p: GoInlinePlace, name: String): String? {
        if (name != "timer" && !name.endsWith("Timer")) return null
        val delay = duration(p, setOf("d", "delay", "timeout", "wait", "after")) ?: return null
        return "${p.qualifier("time")}.NewTimer($delay)"
    }

    /** `var once`: ` sync.Once`. */
    fun a29Once(p: GoInlinePlace, name: String): String? =
        if (name == "once" || name.endsWith("Once")) "${p.slot.lead}${p.qualifier("sync")}.Once" else null

    // --- strconv (A38) ---

    private val NUMBER_NAMES = setOf("n", "num", "number", "i", "id", "port", "count", "age", "limit", "size", "page", "offset", "year", "code", "status")

    /** `id, err :=` with the text of it in scope (`idStr`, `s`, `raw`): `strconv.Atoi(idStr)`, `ParseInt(…, 10, 64)` where an `int64` is wanted below. */
    fun a38Atoi(p: GoInlinePlace, name: String): String? {
        if (name !in NUMBER_NAMES) return null
        val texts = p.variables.filter { v ->
            (v.type.underlying() as? GoBasicType)?.kind?.isString == true &&
                (v.name in setOf("s", "str", "raw", "text") || v.name.equals("${name}Str", true) || v.name.equals("${name}String", true) || v.name.equals("${name}Text", true) || v.name.equals("${name}Raw", true))
        }
        val text = texts.singleOrNull() ?: texts.filter { it.name.startsWith(name, true) }.singleOrNull() ?: return null
        val uses = p.definitionOf(name)?.let(p::usesOf) ?: GoInlineUses()
        val wide = (uses.passedAs + uses.returnedAs).any { (GoTypePredicates.defaultType(it) as? GoBasicType)?.kind == GoBasicKind.INT64 }
        val strconv = p.qualifier("strconv")
        return if (wide) "$strconv.ParseInt(${text.name}, 10, 64)" else "$strconv.Atoi(${text.name})"
    }

    // --- encoding/json (A40, A46, A47) ---

    /** A struct of the program (not of the standard library), or a pointer to one: what is marshalled. */
    private fun isOwnStruct(p: GoInlinePlace, type: GoType): Boolean {
        val named = (if (type is GoPointerType) type.elem else type) as? GoNamedType ?: return false
        if (named.underlying() !is GoStructType) return false
        val own = p.isOwn(named)
        return own || named.pkgPath?.substringBefore('/')?.contains('.') == true
    }

    /** `data, err :=` written or returned as bytes below, with one value of the program in scope: `json.Marshal(v)`. */
    fun a40Marshal(p: GoInlinePlace, name: String): String? {
        if (name !in setOf("data", "b", "body", "payload", "raw", "out", "buf", "bs", "bytes", "js", "encoded")) return null
        val uses = p.definitionOf(name)?.let(p::usesOf) ?: return null
        val asBytes = (uses.passedAs + uses.returnedAs).any { (it.underlying() as? GoSliceType)?.elem?.let { e -> (e.underlying() as? GoBasicType)?.kind == GoBasicKind.UINT8 } == true }
        if (!asBytes) return null
        val value = p.variables.filter { it.element !is GoReceiver && isOwnStruct(p, it.type) }.singleOrNull() ?: return null
        return "${p.qualifier("encoding/json")}.Marshal(${value.name})"
    }

    /** `dec :=` with a request in scope: `json.NewDecoder(r.Body)`; with a reader: `json.NewDecoder(r)`. */
    fun a46Decoder(p: GoInlinePlace, name: String): String? {
        if (name !in setOf("dec", "decoder")) return null
        val request = p.variables.filter { p.isStandard(it, "net/http", "Request", true) }
        val source = request.singleOrNull()?.let { "${it.name}.Body" }
            ?: p.variables.filter { p.isStandard(it, "io", "Reader") }.singleOrNull()?.takeIf { request.isEmpty() }?.name ?: return null
        return "${p.qualifier("encoding/json")}.NewDecoder($source)"
    }

    /** `enc :=` with a response writer (or an `io.Writer`) in scope: `json.NewEncoder(w)`. */
    fun a47Encoder(p: GoInlinePlace, name: String): String? {
        if (name !in setOf("enc", "encoder")) return null
        val writers = p.variables.filter { p.isStandard(it, "net/http", "ResponseWriter") }
        val target = writers.singleOrNull()?.name
            ?: p.variables.filter { p.isStandard(it, "io", "Writer") }.singleOrNull()?.takeIf { writers.isEmpty() }?.name ?: return null
        return "${p.qualifier("encoding/json")}.NewEncoder($target)"
    }

    // --- database/sql (A45) ---

    private val QUERY_NAMES = Regex("""^(query|q|stmt|sqlQuery|sqlStr|statement)$|.Query$|.SQL$""")

    /** `rows, err :=` with a database, a query text and maybe a context in scope: `db.QueryContext(ctx, query)`. */
    fun a45Query(p: GoInlinePlace, name: String): String? {
        if (name != "rows") return null
        val handles = setOf("DB", "Tx", "Conn")
        val databases = p.variables.filter { v -> handles.any { p.isStandard(v, "database/sql", it, true) } }.map { it.name } +
            p.variables.filter { it.element is GoReceiver }.flatMap { r ->
                p.structOf(r.type)?.fields.orEmpty().filter { f ->
                    val pointer = f.type as? GoPointerType ?: return@filter false
                    handles.any { p.isStandard(pointer.elem, "database/sql", it) }
                }.map { "${r.name}.${it.name}" }
            }
        val database = databases.singleOrNull() ?: return null
        val query = p.variables.filter { QUERY_NAMES.containsMatchIn(it.name) && (it.type.underlying() as? GoBasicType)?.kind?.isString == true }.singleOrNull() ?: return null
        val context = GoInlineDeclarations.parentContext(p)
        return if (context != null) "$database.QueryContext($context, ${query.name})" else "$database.Query(${query.name})"
    }

    // --- regexp (A48, H4) ---

    private val REGEXP_NAMES = Regex("""^(re|rx|regex)$|.(Re|RE|Rx|Regex|Regexp)$""")

    /** `var re =` / `re :=`: ``regexp.MustCompile(`…`)`` with the caret inside the quotes once it is accepted. */
    fun a48Regexp(p: GoInlinePlace, name: String): String? {
        if (!REGEXP_NAMES.containsMatchIn(name) || p.slot.names.size != 1) return null
        return "${p.qualifier("regexp")}.MustCompile(`${GoInlineSuggestions.CARET}`)"
    }

    // --- os/signal (C17) ---

    /** `signal.NotifyContext(|`: `ctx, os.Interrupt, syscall.SIGTERM` (`context.Background()` without a context in scope). */
    fun c17NotifyContext(p: GoInlinePlace, call: GoCallExpr, index: Int, signature: GoSignatureType?): String? {
        val callee = call.expression as? GoReferenceExpression ?: return null
        if (callee.identifier?.text != "NotifyContext" || index != 0 || call.arguments.size != 1) return null
        if (signature != null && (signature.params.size != 2 || !p.isStandard(signature.params[0].type, "context", "Context"))) return null
        val qualifier = callee.expression?.text ?: return null
        // the import may not be written yet: then `signal.` is the package by its name, and accepting adds it
        val imported = GoReturnValues.importName(p.original, "os/signal")
        if (imported != qualifier && !(imported == null && qualifier == "signal")) return null
        p.qualifier("os/signal")
        val context = GoInlineDeclarations.parentContext(p) ?: "${p.qualifier("context")}.Background()"
        return "$context, ${p.qualifier("os")}.Interrupt, ${p.qualifier("syscall")}.SIGTERM"
    }

    // --- fields of a literal (D4, D5, D7) ---

    private val CREATED = setOf("CreatedAt", "UpdatedAt", "ModifiedAt", "Created", "Updated", "Modified", "Timestamp")

    /** `T{CreatedAt: |` of a `time.Time`: `time.Now()`. */
    fun d4Now(p: GoInlinePlace, field: String, type: GoType): String? =
        if (field in CREATED && p.isStandard(type, "time", "Time")) "${p.qualifier("time")}.Now()" else null

    /** The services a struct is given rather than made: the context, the loggers, the clients. */
    private val SERVICES = listOf("context" to "Context", "log/slog" to "Logger", "log" to "Logger", "net/http" to "Client", "database/sql" to "DB")

    /** `T{Logger: |` of a `*slog.Logger` (a context, an `*http.Client`, an `*sql.DB`): the only variable of that type in scope. */
    fun d5Service(p: GoInlinePlace, type: GoType): String? {
        val named = (if (type is GoPointerType) type.elem else type) as? GoNamedType ?: return null
        if (SERVICES.none { (path, name) -> p.isStandard(named, path, name) }) return null
        return p.variables.filter { GoTypePredicates.identical(it.type, type) }.singleOrNull()?.name
    }

    private val ADDRESS_NAMES = Regex("""^(addr|address|listen|listenAddr|bind|hostPort)$|.Addr$""")

    /** `&http.Server{|` with an address and a handler in scope: `Addr: addr, Handler: mux`. */
    fun d7Server(p: GoInlinePlace, type: GoType): String? {
        if (!p.isStandard(type, "net/http", "Server")) return null
        val struct = type.underlying() as? GoStructType ?: return null
        val handlerType = struct.fields.firstOrNull { it.name == "Handler" }?.type ?: return null
        val address = p.variables.filter { ADDRESS_NAMES.containsMatchIn(it.name) && (it.type.underlying() as? GoBasicType)?.kind?.isString == true }.singleOrNull() ?: return null
        val handler = p.variables.filter { it.name != address.name && GoInlineValues.fits(p, it.type, handlerType) }.singleOrNull() ?: return null
        return "Addr: ${address.name}, Handler: ${handler.name}"
    }
}
