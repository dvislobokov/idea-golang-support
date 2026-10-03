package io.github.golangsupport.lang

import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanDir
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType
import com.intellij.psi.util.PsiTreeUtil

/**
 * Section A (and H2) of the catalogue: the right side of `x :=`, `var x =`, `x =`, and the type after `var x`. Each rule is a function
 * named by its id; the first that is sure wins, in the order of [declaration] (what the uses below say before what the name says).
 */
object GoInlineDeclarations {
    // --- the names (section 0 of the catalogue) ---

    private val SLICE_NAMES = setOf("arr", "list", "items", "values", "res", "result", "results", "out", "ids", "names", "keys", "xs", "vals", "elems", "parts", "lines")
    private val MAP_NAMES = Regex("""^(m|cache|lookup|index|counts|byID|by[A-Z]\w*|\w+Map|\w+Index|\w+By[A-Z]\w*)$""")
    private val SET_NAMES = Regex("""^(seen|set|visited|uniq|unique|\w+Set)$""")
    private val DONE_NAMES = setOf("done", "quit", "stop", "finished", "closing")
    private val ERROR_CHANNEL_NAMES = Regex("""^(errCh|errc|errChan|errorCh|errorsCh|errs?Ch\w*)$""")
    private val START_NAMES = setOf("start", "now", "begin", "started", "t0", "startTime", "startedAt")
    private val ELAPSED_NAMES = setOf("elapsed", "took", "dur", "duration", "latency", "spent")
    private val CANCEL_NAMES = setOf("cancel", "cancelFn", "cancelFunc")
    private val ERROR_NAMES = Regex("""^\w*[eE]rr\w*$""")
    private val OK_NAMES = setOf("ok", "found", "exists", "has", "present")
    private val PATH_NAMES = Regex("""^(path|filename|fileName|fname|file|filePath|filepath|fp|p|name)$|.(Path|File|Filename|FileName)$""")
    private val URL_NAMES = Regex("""^(url|u|uri|endpoint|target|link|href)$|.(URL|Url|Endpoint)$""")
    private val BODY_NAMES = setOf("body", "payload", "reader", "r")

    fun isSliceName(name: String): Boolean = name in SLICE_NAMES || GoInlineSuggestions.isPlural(name) && !MAP_NAMES.matches(name) && !SET_NAMES.matches(name)

    fun isMapName(name: String): Boolean = MAP_NAMES.matches(name)

    fun isSetName(name: String): Boolean = SET_NAMES.matches(name)

    // --- the right side ---

    fun declaration(p: GoInlinePlace): String? {
        val names = p.slot.names
        if (p.owner == null) return h2ErrorVariable(p, names) ?: names.singleOrNull()?.takeIf { p.slot.isVar }?.let { GoInlineLibrary.a48Regexp(p, it) }
        return when (names.size) {
            1 -> single(p, names[0])
            2 -> pair(p, names[0], names[1])
            else -> null
        }
    }

    private fun single(p: GoInlinePlace, name: String): String? {
        h2ErrorVariable(p, listOf(name))?.let { return it }
        val definition = p.definitionOf(name)
        val uses = definition?.let(p::usesOf) ?: GoInlineUses()
        // an assignment to a variable whose type is known is not guessed by its name
        if (!p.slot.define && !p.slot.isVar && definition != null && p.typeOf(definition) !is GoUnknownType && p.declared.isEmpty()) return null
        return a6MakeCopy(p, uses) ?: a11MakeCounter(p, uses) ?: a9MakeSet(p, name, uses) ?: a7MakeMap(p, name, uses) ?: a8MakeMapWithLen(p, name, uses)
            ?: a10MapByField(p, name, uses) ?: a65Keys(p, name, uses)
            ?: a1MakeSliceWithLen(p, name, uses) ?: a5MakeSliceForRange(p, name, uses) ?: a2MakeSliceWithCap(p, name, uses) ?: a3MakeSliceOfAppended(p, uses)
            ?: a12MakeDoneChannel(p, name, uses) ?: a13MakeErrorChannel(p, name, uses) ?: a14ChannelForJobs(p, name, uses) ?: a15MakeChannel(p, name, uses)
            ?: a16Context(p, name) ?: a20Now(p, name, uses) ?: a21Since(p, name) ?: library(p, name) ?: tests(p, name)
            ?: a36Zero(p, name, uses) ?: a35Length(p, name, uses)
            ?: a55TestedCall(p, listOf(name)) ?: a34ParameterField(p, name) ?: a30Constructor(p, listOf(name)) ?: a32DefaultConfig(p, name) ?: a31Literal(p, name)
    }

    private fun library(p: GoInlinePlace, name: String): String? =
        GoInlineLibrary.a22Deadline(p, name) ?: GoInlineLibrary.a23Ticker(p, name) ?: GoInlineLibrary.a24Timer(p, name) ?: GoInlineLibrary.a46Decoder(p, name)
            ?: GoInlineLibrary.a47Encoder(p, name) ?: GoInlineLibrary.a48Regexp(p, name)

    private fun tests(p: GoInlinePlace, name: String): String? =
        GoInlineTests.a56Want(p, name) ?: GoInlineTests.a57Table(p, name) ?: GoInlineTests.a58Server(p, name) ?: GoInlineTests.a59Recorder(p, name)
            ?: GoInlineTests.a60Request(p, name) ?: GoInlineTests.a61TempDir(p, name)

    private fun pair(p: GoInlinePlace, first: String, second: String): String? {
        if (!p.slot.define) return null
        return a17WithCancel(p, first, second) ?: a18WithTimeout(p, first, second) ?: a51MapLookup(p, first, second) ?: a52Receive(p, first, second)
            ?: a55TestedCall(p, listOf(first, second)) ?: errorPair(p, first, second) ?: a30Constructor(p, listOf(first, second))
    }

    /** The calls of `x, err :=` by the names around: one of them, and only when a single one fits. */
    private fun errorPair(p: GoInlinePlace, first: String, second: String): String? {
        if (!ERROR_NAMES.matches(second)) return null
        val candidates = listOfNotNull(
            a39ReadFile(p, first), a41OpenFile(p, first), a42ReadBody(p, first), a43NewRequest(p, first), a44Do(p, first),
            GoInlineLibrary.a38Atoi(p, first), GoInlineLibrary.a40Marshal(p, first), GoInlineLibrary.a45Query(p, first),
        )
        return candidates.singleOrNull()
    }

    // --- slices (A1, A2, A5, A6) ---

    /** What a slice is made from: its element type and the collection whose length it takes (null when none in scope fits). */
    private class SliceSource(val element: GoType, val collection: String?)

    private fun sliceSource(p: GoInlinePlace, name: String, uses: GoInlineUses): SliceSource? {
        val byUse = uses.appended.firstOrNull() ?: uses.indexAssigned.firstOrNull()
        if (byUse == null && !isSliceName(name)) return null
        // the loop that fills it: `for _, k := range keys { arr = append(arr, k) }`
        val ranged = (uses.appended.map { it.second } + uses.indexAssigned.map { it.second }).filterNotNull().map { it.text }.distinct()
        val element = byUse?.first?.takeIf { it !is GoUnknownType }
        if (ranged.size == 1) {
            val expression = (uses.appended.map { it.second } + uses.indexAssigned.map { it.second }).filterNotNull().first()
            val collectionType = p.typeOf(expression)
            val collectionElement = p.elementOf(collectionType) ?: (collectionType.underlying() as? GoMapType)?.let { element }
            return SliceSource(element ?: collectionElement ?: return null, ranged[0].takeIf { expression is GoReferenceExpression || it.length < 40 })
        }
        // one collection in scope of that element type (or of any, while nothing below tells the element)
        val collections = p.variables.filter { v -> v.name != name && p.elementOf(v.type)?.let { e -> element == null || GoTypePredicates.identical(e, element) } == true }
        val only = collections.singleOrNull()
        if (only != null) return SliceSource(element ?: p.elementOf(only.type)!!, only.name)
        if (collections.size > 1) return null
        return element?.let { SliceSource(it, null) }
    }

    /** `arr :=` filled below by position, `arr[i] = …`: `make([]T, len(s))`. */
    fun a1MakeSliceWithLen(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        if (uses.indexAssigned.isEmpty() || uses.appended.isNotEmpty() || isMapName(name) || isSetName(name)) return null
        val source = sliceSource(p, name, uses) ?: return null
        val collection = source.collection ?: return null
        return "make([]${p.typeText(source.element) ?: return null}, len($collection))"
    }

    /** `arr :=` appended to below, or nothing below yet, with a collection in scope: `make([]T, 0, len(s))`. */
    fun a2MakeSliceWithCap(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        if (uses.indexAssigned.isNotEmpty() || isMapName(name) || isSetName(name)) return null
        if (uses.count > 0 && uses.appended.isEmpty()) return null
        val source = sliceSource(p, name, uses) ?: return null
        val collection = source.collection ?: return null
        return "make([]${p.typeText(source.element) ?: return null}, 0, len($collection))"
    }

    /** `out :=` with `for _, x := range in { out = append(out, f(x)) }` below: the capacity of the ranged collection. */
    fun a5MakeSliceForRange(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        val append = uses.appended.firstOrNull() ?: return null
        val ranged = append.second ?: return null
        if (uses.appended.any { it.second?.text != ranged.text } || uses.indexAssigned.isNotEmpty()) return null
        val element = p.typeText(append.first) ?: return null
        return "make([]$element, 0, len(${ranged.text}))"
    }

    /** `cp :=` with `copy(cp, s)` below: `make([]T, len(s))`. */
    fun a6MakeCopy(p: GoInlinePlace, uses: GoInlineUses): String? {
        val source = uses.copiedFrom ?: return null
        val element = p.elementOf(p.typeOf(source)) ?: return null
        return "make([]${p.typeText(element) ?: return null}, len(${source.text}))"
    }

    /** The one element type of what is appended below; null when nothing is, or when two types are. */
    private fun appendedElement(p: GoInlinePlace, uses: GoInlineUses): GoType? {
        if (uses.appended.isEmpty() || uses.indexAssigned.isNotEmpty() || uses.keyAssigned.isNotEmpty()) return null
        val types = uses.appended.map { it.first }
        val first = types.first().takeIf { it !is GoUnknownType && GoTypePredicates.isKnown(it) && !GoTypePredicates.isUntyped(it) } ?: return null
        return first.takeIf { types.all { t -> GoTypePredicates.identical(t, first) } }
    }

    /** `arr :=` appended to below with values of one type and no collection to size it by: `make([]T, 0)`. */
    fun a3MakeSliceOfAppended(p: GoInlinePlace, uses: GoInlineUses): String? {
        val element = appendedElement(p, uses) ?: return null
        return "make([]${p.typeText(element) ?: return null}, 0)"
    }

    /** `var arr` appended to below with values of one type: ` []T`. */
    fun a4SliceType(p: GoInlinePlace, uses: GoInlineUses): String? {
        val element = appendedElement(p, uses) ?: return null
        return "${p.slot.lead}[]${p.typeText(element) ?: return null}"
    }

    /** `keys :=` (`values :=`) with one map in scope: `slices.Collect(maps.Keys(m))` from Go 1.23, `make([]K, 0, len(m))` before. */
    fun a65Keys(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        val keys = name == "keys" || name.endsWith("Keys")
        val values = name == "values" || name == "vals" || name.endsWith("Values")
        if (!keys && !values || uses.indexAssigned.isNotEmpty() || uses.keyAssigned.isNotEmpty()) return null
        val map = p.variables.filter { it.type.underlying() is GoMapType }.singleOrNull() ?: return null
        val type = map.type.underlying() as GoMapType
        val element = if (keys) type.key else type.value
        if (uses.appended.any { !GoTypePredicates.identical(it.first, element) }) return null
        if (uses.appended.isEmpty() && p.goVersionAtLeast(23)) {
            return "${p.qualifier("slices")}.Collect(${p.qualifier("maps")}.${if (keys) "Keys" else "Values"}(${map.name}))"
        }
        return "make([]${p.typeText(element) ?: return null}, 0, len(${map.name}))"
    }

    // --- maps (A7, A8, A9, A10, A11) ---

    private fun mapAssignments(name: String, uses: GoInlineUses): List<Triple<GoType, GoType, GoExpression?>> =
        if (uses.keyAssigned.isNotEmpty()) uses.keyAssigned + uses.intKeyAssigned else if (isMapName(name)) uses.intKeyAssigned else emptyList()

    /** `m :=` with `m[k] = v` below: `make(map[K]V)`. */
    fun a7MakeMap(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        val assigned = mapAssignments(name, uses)
        val first = assigned.firstOrNull() ?: return null
        // filled in a loop over a collection: A8
        if (first.third?.let { ranged -> p.elementOf(p.typeOf(ranged)) } != null) return null
        return "make(map[${p.typeText(first.first) ?: return null}]${p.typeText(first.second) ?: return null})"
    }

    /** The same map filled in a loop over a slice `items`: `make(map[K]V, len(items))`. */
    fun a8MakeMapWithLen(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        val assigned = mapAssignments(name, uses)
        val first = assigned.firstOrNull() ?: return null
        val ranged = first.third ?: return null
        if (p.elementOf(p.typeOf(ranged)) == null || assigned.any { it.third?.text != ranged.text }) return null
        return "make(map[${p.typeText(first.first) ?: return null}]${p.typeText(first.second) ?: return null}, len(${ranged.text}))"
    }

    /** `seen :=`: `make(map[K]struct{})`, `make(map[K]bool)` when `seen[x] = true` is below. */
    fun a9MakeSet(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        if (!isSetName(name)) return null
        val assigned = uses.keyAssigned + uses.intKeyAssigned
        val keyType = assigned.firstOrNull()?.first ?: uses.keyRead.firstOrNull()
            ?: p.variables.mapNotNull { p.elementOf(it.type) }.distinctBy { p.typeText(it) }.singleOrNull() ?: return null
        val value = assigned.firstOrNull()?.second
        val valueText = when {
            value == null -> "struct{}"
            (GoTypePredicates.defaultType(value).underlying() as? GoBasicType)?.kind?.isBoolean == true -> "bool"
            (value.underlying() as? GoStructType)?.fields?.isEmpty() == true -> "struct{}"
            else -> return null
        }
        return "make(map[${p.typeText(keyType) ?: return null}]$valueText)"
    }

    /** `counts :=` with `counts[k]++` below: `make(map[K]int)`. */
    fun a11MakeCounter(p: GoInlinePlace, uses: GoInlineUses): String? {
        val (key, value) = uses.counted.firstOrNull() ?: return null
        val valueText = if (value is GoUnknownType) "int" else p.typeText(value) ?: return null
        return "make(map[${p.typeText(key) ?: return null}]$valueText)"
    }

    private val BY_FIELD = Regex("""^by([A-Z]\w*)$""")

    /** `byID :=` with one slice of structs in scope whose element has the field `ID`: `make(map[int]*User, len(users))`. */
    fun a10MapByField(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        val field = BY_FIELD.matchEntire(name)?.groupValues?.get(1) ?: return null
        if (uses.keyAssigned.isNotEmpty() || uses.intKeyAssigned.isNotEmpty()) return null
        val candidates = p.variables.mapNotNull { v ->
            val element = p.elementOf(v.type) ?: return@mapNotNull null
            val key = p.structOf(element)?.fields?.filter { !it.embedded && it.name.equals(field, ignoreCase = true) }?.singleOrNull() ?: return@mapNotNull null
            Triple(v, element, key)
        }
        val (slice, element, key) = candidates.singleOrNull() ?: return null
        if (!GoTypePredicates.comparable(key.type, strict = true)) return null
        return "make(map[${p.typeText(key.type) ?: return null}]${p.typeText(element) ?: return null}, len(${slice.name}))"
    }

    // --- channels (A12, A13, A14, A15) ---

    /** `done :=`: `make(chan struct{})`. */
    fun a12MakeDoneChannel(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        if (name !in DONE_NAMES || !p.slot.define && !p.slot.isVar) return null
        if (uses.sends.any { (it.first.underlying() as? GoStructType)?.fields?.isEmpty() != true }) return null
        return "make(chan struct{})"
    }

    /** `errCh :=`: `make(chan error, 1)`; `len(jobs)` when the goroutines that write to it are started in a loop over `jobs`. */
    fun a13MakeErrorChannel(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        if (!ERROR_CHANNEL_NAMES.matches(name)) return null
        val ranged = uses.sends.mapNotNull { it.third?.text }.distinct()
        val size = ranged.singleOrNull()?.let { "len($it)" } ?: "1"
        return "make(chan error, $size)"
    }

    /** The one type of what is sent below; null when nothing is or two types are. */
    private fun sentElement(uses: GoInlineUses): GoType? {
        val first = uses.sends.firstOrNull()?.first?.takeIf { it !is GoUnknownType && GoTypePredicates.isKnown(it) && !GoTypePredicates.isUntyped(it) } ?: return null
        return first.takeIf { uses.sends.all { GoTypePredicates.identical(it.first, first) } }
    }

    /** `results :=` sent to below by goroutines started in a loop over `jobs`: `make(chan T, len(jobs))`. */
    fun a14ChannelForJobs(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        val element = sentElement(uses) ?: return null
        val ranged = uses.sends.map { it.third?.text }.distinct().singleOrNull() ?: return null
        if (uses.sends.any { !it.second }) return null
        val collection = p.variables.firstOrNull { it.name == ranged }?.takeIf { p.elementOf(it.type) != null || it.type.underlying() is GoMapType } ?: return null
        return "make(chan ${p.typeText(element) ?: return null}, len(${collection.name}))"
    }

    /** `ch :=` sent to below with values of one type: `make(chan T)`. */
    fun a15MakeChannel(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        if (!p.slot.define && !p.slot.isVar) return null
        val element = sentElement(uses) ?: return null
        return "make(chan ${p.typeText(element) ?: return null})"
    }

    /** `v, ok :=` with one channel to receive from in scope and no map: `<-ch`. */
    fun a52Receive(p: GoInlinePlace, first: String, second: String): String? {
        if (second !in OK_NAMES) return null
        if (p.variables.any { it.type.underlying() is GoMapType }) return null
        val channels = p.variables.filter { v -> (v.type.underlying() as? GoChanType)?.dir?.let { it != GoChanDir.SEND } == true }
        return channels.singleOrNull()?.let { "<-${it.name}" }
    }

    // --- numbers (A35, A36) ---

    private val ZERO_NAMES = setOf("i", "n", "count", "total", "sum", "cnt", "num", "acc", "counter")

    /** `count :=` incremented or added to below: `0` (`0.0` for a float). */
    fun a36Zero(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        if (name !in ZERO_NAMES || !uses.incremented && uses.accumulated.isEmpty()) return null
        val kinds = uses.accumulated.map { (GoTypePredicates.defaultType(it).underlying() as? GoBasicType)?.kind }
        return when {
            kinds.all { it == GoBasicKind.INT } -> "0"
            !uses.incremented && kinds.all { it == GoBasicKind.FLOAT64 } -> "0.0"
            else -> null
        }
    }

    /** `n :=` (`size`, `length`) with one slice or map in scope: `len(items)`. */
    fun a35Length(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        if (name !in setOf("n", "size", "length") || uses.incremented || uses.accumulated.isNotEmpty()) return null
        val collections = p.variables.filter { p.elementOf(it.type) != null || it.type.underlying() is GoMapType }
        return collections.singleOrNull()?.let { "len(${it.name})" }
    }

    // --- context (A16, A17, A18) ---

    fun contexts(p: GoInlinePlace): List<GoInlineVariable> = p.variables.filter { p.isStandard(it, "context", "Context") }

    /** The context in scope to derive from: the only one, or the one named `ctx`. */
    fun parentContext(p: GoInlinePlace): String? {
        val all = contexts(p)
        return (all.singleOrNull() ?: all.firstOrNull { it.name == "ctx" })?.name
    }

    /** `ctx :=` without a context in scope: `context.Background()`; `t.Context()` in a test of Go 1.24 and later. */
    fun a16Context(p: GoInlinePlace, name: String): String? {
        if (name != "ctx" || contexts(p).isNotEmpty()) return null
        if (p.original.isTestFile && p.goVersionAtLeast(24)) {
            p.variables.firstOrNull { it.isParameter && (p.isStandard(it, "testing", "T", true) || p.isStandard(it, "testing", "B", true)) }?.let { return "${it.name}.Context()" }
        }
        return "${p.qualifier("context")}.Background()"
    }

    private fun isContextPair(first: String, second: String): Boolean = (first == "ctx" || first.endsWith("Ctx")) && second in CANCEL_NAMES

    /** `ctx, cancel :=` with a context in scope (or none: `context.Background()`): `context.WithCancel(ctx)`. */
    fun a17WithCancel(p: GoInlinePlace, first: String, second: String): String? {
        if (!isContextPair(first, second) || timeout(p) != null) return null
        val context = p.qualifier("context")
        return "$context.WithCancel(${parentContext(p) ?: "$context.Background()"})"
    }

    /** The same with a `time.Duration` in scope, or a function named for a timeout: `context.WithTimeout(ctx, timeout)`. */
    fun a18WithTimeout(p: GoInlinePlace, first: String, second: String): String? {
        if (!isContextPair(first, second)) return null
        val duration = timeout(p) ?: return null
        val context = p.qualifier("context")
        return "$context.WithTimeout(${parentContext(p) ?: "$context.Background()"}, $duration)"
    }

    fun timeout(p: GoInlinePlace): String? {
        val durations = p.variables.filter { p.isStandard(it.type, "time", "Duration") || p.isStandard(it, "time", "Duration") }
        durations.singleOrNull()?.let { return it.name }
        durations.firstOrNull { it.name == "timeout" }?.let { return it.name }
        val function = (p.owner as? GoFunctionOrMethodDeclaration)?.name ?: return null
        return if (durations.isEmpty() && (function.contains("Timeout") || function.contains("Deadline"))) "5*${p.qualifier("time")}.Second" else null
    }

    // --- time (A20, A21) ---

    /** `start :=`: `time.Now()`, unless it is used as a number below. */
    fun a20Now(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        if (name !in START_NAMES || uses.usedAsNumber || uses.passedAs.any { p.isInteger(it) }) return null
        if (uses.passedAs.any { !p.isStandard(it, "time", "Time") }) return null
        return "${p.qualifier("time")}.Now()"
    }

    /** `elapsed :=` with a `time.Time` in scope: `time.Since(start)`. */
    fun a21Since(p: GoInlinePlace, name: String): String? {
        if (name !in ELAPSED_NAMES) return null
        val times = p.variables.filter { p.isStandard(it.type, "time", "Time") || p.isStandard(it, "time", "Time") }
        val start = times.singleOrNull() ?: times.filter { it.name in START_NAMES }.singleOrNull() ?: return null
        return "${p.qualifier("time")}.Since(${start.name})"
    }

    // --- types of the package (A30, A31, A34, A55) ---

    /** The struct type of the package [name] stands for: `user` → `User`; `u` → `User` when the function returns it. */
    fun namedType(p: GoInlinePlace, name: String): GoTypeSpec? {
        val structs = p.packageTypes.values.filter { it.type is io.github.golangsupport.lang.psi.GoStructType }
        structs.filter { it.name.equals(name, ignoreCase = true) }.singleOrNull()?.let { return it }
        val results = (p.owner as? GoFunctionOrMethodDeclaration)?.signature?.result?.text.orEmpty()
        val abbreviated = structs.filter { spec ->
            val type = spec.name ?: return@filter false
            val initials = type.filter(Char::isUpperCase).lowercase()
            (name == initials || name.length >= 3 && type.lowercase().startsWith(name.lowercase())) && Regex("""\b$type\b""").containsMatchIn(results)
        }
        return abbreviated.singleOrNull()
    }

    /** `u :=` with a constructor `NewUser(...)` whose arguments are all in scope: `NewUser(name, email)`. */
    fun a30Constructor(p: GoInlinePlace, names: List<String>): String? {
        val spec = namedType(p, names[0]) ?: return null
        val constructor = p.packageFunctions["New${spec.name}"] as? GoFunctionDeclaration ?: return null
        val signature = p.typeOf(constructor) as? GoSignatureType ?: return null
        val results = signature.results
        if (results.size != names.size || names.size == 2 && !GoReturnValues.isError(results[1].type)) return null
        val arguments = GoInlineValues.fill(p, signature, exclude = names.toSet()) ?: return null
        return "${constructor.name}($arguments)"
    }

    private val CONFIG_NAMES = setOf("cfg", "conf", "config", "opts", "options")

    /** `cfg :=` with `DefaultConfig()` (or `NewConfig()`, `DefaultOptions()`) in the package, the only one that takes nothing: it. */
    fun a32DefaultConfig(p: GoInlinePlace, name: String): String? {
        if (name !in CONFIG_NAMES) return null
        val kind = if (name.startsWith("opt")) listOf("Options", "Opts") else listOf("Config")
        val names = kind.flatMap { listOf("Default$it", "New$it") }
        val owner = (p.owner as? GoFunctionOrMethodDeclaration)?.name
        val candidates = names.mapNotNull { p.packageFunctions[it] as? GoFunctionDeclaration }.filter { f ->
            f.name != owner && (p.typeOf(f) as? GoSignatureType)?.let { it.params.isEmpty() && it.results.size == 1 } == true
        }
        return candidates.singleOrNull()?.let { "${it.name}()" }
    }

    /** The same without a constructor: `&User{}` (or `User{}` for a type whose methods take it by value). */
    fun a31Literal(p: GoInlinePlace, name: String): String? {
        val spec = namedType(p, name) ?: return null
        if (p.packageFunctions.containsKey("New${spec.name}")) return null
        val type = p.typeOf(spec) as? GoNamedType ?: return null
        val methods = runCatching { p.semantic.methodsOf(GoPointerType(type)) }.getOrDefault(emptyList())
        val results = (p.owner as? GoFunctionOrMethodDeclaration)?.signature?.result?.text.orEmpty()
        val pointer = methods.any { it.pointerReceiver } || results.contains("*${spec.name}")
        return (if (pointer) "&" else "") + "${spec.name}{}"
    }

    /** `id :=` with a parameter `req *GetRequest` that has a field `ID`: `req.ID` (a field of the receiver too). */
    fun a34ParameterField(p: GoInlinePlace, name: String): String? {
        val candidates = p.variables.filter { it.isParameter }.flatMap { v ->
            val struct = p.structOf(v.type) ?: return@flatMap emptyList()
            struct.fields.filter { !it.embedded && it.name.equals(name, ignoreCase = true) }.map { "${v.name}.${it.name}" }
        }
        return candidates.singleOrNull()
    }

    /** `got :=` in `TestFoo`: `Foo(...)` with the fields of the case (`tt.input`) or the variables in scope as its arguments. */
    fun a55TestedCall(p: GoInlinePlace, names: List<String>): String? {
        if (names[0] != "got" || !p.original.isTestFile) return null
        val test = (p.owner as? GoFunctionDeclaration ?: com.intellij.psi.util.PsiTreeUtil.getParentOfType(p.leaf, GoFunctionDeclaration::class.java))?.name ?: return null
        if (!test.startsWith("Test") || test.length <= 4) return null
        val tested = test.removePrefix("Test").substringBefore('_')
        val function = (p.packageFunctions[tested] ?: p.packageFunctions[tested.replaceFirstChar(Char::lowercaseChar)]) as? GoFunctionDeclaration ?: return null
        val signature = p.typeOf(function) as? GoSignatureType ?: return null
        if (signature.results.size != names.size) return null
        val arguments = GoInlineValues.fill(p, signature, exclude = names.toSet(), fromCases = true) ?: return null
        return "${function.name}($arguments)"
    }

    // --- the calls of `x, err :=` (A39, A41, A42, A43, A44) ---

    fun strings(p: GoInlinePlace, names: Regex): List<GoInlineVariable> =
        p.variables.filter { names.containsMatchIn(it.name) && (it.type.underlying() as? GoBasicType)?.kind?.isString == true }

    /** `data, err :=` with a path in scope: `os.ReadFile(path)`. */
    fun a39ReadFile(p: GoInlinePlace, name: String): String? {
        if (name !in setOf("data", "b", "bytes", "raw", "content", "contents", "buf")) return null
        val path = strings(p, PATH_NAMES).singleOrNull() ?: return null
        return "${p.qualifier("os")}.ReadFile(${path.name})"
    }

    /** `f, err :=` with a path in scope: `os.Open(path)`, `os.Create(path)` when it is written to below. */
    fun a41OpenFile(p: GoInlinePlace, name: String): String? {
        if (name !in setOf("f", "file", "fd", "fh", "out", "in")) return null
        val path = strings(p, PATH_NAMES).singleOrNull() ?: return null
        val uses = p.definitionOf(name)?.let(p::usesOf) ?: GoInlineUses()
        val written = name == "out" || uses.selected.any { it.startsWith("Write") } || uses.passedAs.any { it.toString().contains("Writer") }
        return "${p.qualifier("os")}.${if (written) "Create" else "Open"}(${path.name})"
    }

    /** `body, err :=` with a response in scope: `io.ReadAll(resp.Body)`. */
    fun a42ReadBody(p: GoInlinePlace, name: String): String? {
        if (name !in setOf("body", "data", "b", "raw", "content", "bytes")) return null
        val response = p.variables.filter { p.isStandard(it, "net/http", "Response", true) }.singleOrNull() ?: return null
        return "${p.qualifier("io")}.ReadAll(${response.name}.Body)"
    }

    /** `req, err :=` with a URL in scope: `http.NewRequestWithContext(ctx, http.MethodGet, url, nil)`; POST with a body. */
    fun a43NewRequest(p: GoInlinePlace, name: String): String? {
        if (name != "req" && name != "request") return null
        val url = strings(p, URL_NAMES).singleOrNull() ?: return null
        val http = p.qualifier("net/http")
        val body = p.variables.filter { it.name in BODY_NAMES && p.isStandard(it, "io", "Reader") }.singleOrNull()
        val method = if (body != null) "$http.MethodPost" else "$http.MethodGet"
        val context = parentContext(p)
        return if (context != null) "$http.NewRequestWithContext($context, $method, ${url.name}, ${body?.name ?: "nil"})"
        else "$http.NewRequest($method, ${url.name}, ${body?.name ?: "nil"})"
    }

    /** `resp, err :=` with a request in scope: `client.Do(req)`, `http.DefaultClient.Do(req)` without a client. */
    fun a44Do(p: GoInlinePlace, name: String): String? {
        if (name !in setOf("resp", "res", "response")) return null
        val request = p.variables.filter { p.isStandard(it, "net/http", "Request", true) }.singleOrNull() ?: return null
        val clients = p.variables.filter { p.isStandard(it, "net/http", "Client", true) }.map { it.name } +
            p.variables.filter { it.element is io.github.golangsupport.lang.psi.GoReceiver }.flatMap { r ->
                p.structOf(r.type)?.fields.orEmpty().filter { f -> f.type is GoPointerType && p.isStandard((f.type as GoPointerType).elem, "net/http", "Client") }.map { "${r.name}.${it.name}" }
            }
        val client = when (clients.size) {
            0 -> "${p.qualifier("net/http")}.DefaultClient"
            1 -> clients[0]
            else -> return null
        }
        return "$client.Do(${request.name})"
    }

    /** `v, ok :=` with a map and a key of its type in scope: `m[k]`. */
    fun a51MapLookup(p: GoInlinePlace, first: String, second: String): String? {
        if (second !in OK_NAMES) return null
        val maps = p.variables.filter { it.type.underlying() is GoMapType }
        val map = maps.singleOrNull() ?: return null
        val keyType = (map.type.underlying() as GoMapType).key
        val keys = p.variables.filter { it !== map && it.name != first && GoTypePredicates.identical(it.type, keyType) }
        val key = keys.singleOrNull() ?: keys.filter { it.name in setOf("k", "key", "id", "name") }.singleOrNull() ?: return null
        return "${map.name}[${key.name}]"
    }

    // --- package level (H2) ---

    /** `var ErrNotFound =`: `errors.New("not found")`. */
    fun h2ErrorVariable(p: GoInlinePlace, names: List<String>): String? {
        val name = names.singleOrNull() ?: return null
        if (!Regex("""^[eE]rr[A-Z]\w*$""").matches(name) || p.slot.define) return null
        if (!p.slot.isVar && PsiTreeUtil.getParentOfType(p.leaf, GoVarSpec::class.java) == null) return null
        return "${p.qualifier("errors")}.New(\"${GoInlineSuggestions.words(name.substring(3))}\")"
    }

    // --- the type after `var x` (A25, A26, A27, A28) ---

    fun varType(p: GoInlinePlace): String? {
        val name = p.slot.names.singleOrNull() ?: return null
        val definition = PsiTreeUtil.getParentOfType(p.leaf, GoVarSpec::class.java)?.varDefinitionList?.firstOrNull { it.name == name }
        // a package-level `var` has no uses in a body to read
        val uses = if (definition != null && PsiTreeUtil.getParentOfType(p.leaf, GoVarDeclaration::class.java) != null && p.owner != null) p.usesOf(definition) else GoInlineUses()
        val result = a27WaitGroup(p, name) ?: a28Mutex(p, name, uses) ?: GoInlineLibrary.a29Once(p, name) ?: a25Builder(p, name, uses) ?: a26Buffer(p, name, uses)
            ?: a4SliceType(p, uses) ?: return null
        return result
    }

    /** `var wg`: ` sync.WaitGroup`. */
    fun a27WaitGroup(p: GoInlinePlace, name: String): String? =
        if (name == "wg" || name == "waitGroup" || name.endsWith("WG") || name.endsWith("Wg")) "${p.slot.lead}${p.qualifier("sync")}.WaitGroup" else null

    /** `var mu`: ` sync.Mutex`, ` sync.RWMutex` when `mu.RLock()` is below. */
    fun a28Mutex(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        if (name !in setOf("mu", "mtx", "mutex", "lock") && !name.endsWith("Mu") && !name.endsWith("Mutex")) return null
        val read = uses.selected.any { it == "RLock" || it == "RUnlock" }
        return "${p.slot.lead}${p.qualifier("sync")}.${if (read) "RWMutex" else "Mutex"}"
    }

    private val BUILDER_ONLY = setOf("WriteString", "WriteByte", "WriteRune", "String", "Len", "Grow", "Reset")
    private val BUFFER_ONLY = setOf("Bytes", "Read", "ReadFrom", "ReadString", "ReadBytes", "Truncate", "WriteTo")

    /** `var sb` (or `var b` written with `WriteString` below): ` strings.Builder`. */
    fun a25Builder(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        val builderName = name == "sb" || name == "builder" || name.endsWith("Builder")
        val writes = uses.selected.isNotEmpty() && uses.selected.all { it in BUILDER_ONLY || it == "Write" } && !uses.addressTaken
        if (!builderName && !(name in setOf("b", "s", "out") && writes)) return null
        if (uses.selected.any { it in BUFFER_ONLY }) return null
        return "${p.slot.lead}${p.qualifier("strings")}.Builder"
    }

    /** `var buf` written to below (`buf.Write`, `json.NewEncoder(&buf)`): ` bytes.Buffer`. */
    fun a26Buffer(p: GoInlinePlace, name: String, uses: GoInlineUses): String? {
        if (name !in setOf("buf", "buffer", "b", "out") && !name.endsWith("Buf") && !name.endsWith("Buffer")) return null
        if (uses.count == 0) return null
        val used = uses.selected.any { it in BUFFER_ONLY || it == "Write" || it == "WriteString" } || uses.addressTaken
        if (!used) return null
        return "${p.slot.lead}${p.qualifier("bytes")}.Buffer"
    }
}
