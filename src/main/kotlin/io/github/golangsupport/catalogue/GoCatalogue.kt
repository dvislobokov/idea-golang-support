package io.github.golangsupport.catalogue

import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoTestNames
import io.github.golangsupport.lang.psi.GoFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** What a package gives to those who import it: a function, a type, a constant or a variable with an upper-case name. */
data class GoSymbol(val name: String, val kind: GoDeclarationKind, val signature: String?)

/** An exported method of an exported type: `(*Builder) WriteString(s string) (int, error)` is `Builder`, `WriteString`, the signature, a pointer receiver. */
data class GoMethodSymbol(val receiver: String, val name: String, val signature: String?, val pointer: Boolean)

/** The exported names of one file; a file that exports nothing still votes for the name of the package of its directory. */
data class GoFileExports(val packageName: String, val symbols: List<GoSymbol>, val methods: List<GoMethodSymbol> = emptyList())

class GoPackageSymbols(val importPath: String, val name: String, val symbols: List<GoSymbol>, val methods: List<GoMethodSymbol> = emptyList()) {
    /** The methods of the type [receiver] of this package, by name. */
    fun methodsOf(receiver: String): List<GoMethodSymbol> = methods.filter { it.receiver == receiver }
}

/**
 * The packages of a module of one version, or of the standard library of one version of Go: what never changes once it is there.
 * Or the packages of the project itself ([project]), which change and are kept by the index of the platform, not by a file.
 * [indirect]: a module of the build list no go.mod of the project requires directly; not kept in the file (the same module is direct elsewhere).
 */
class GoModuleSymbols(val key: String, val standard: Boolean, val packages: List<GoPackageSymbols>, val project: Boolean = false, val indirect: Boolean = false) {
    fun asIndirect(): GoModuleSymbols = if (indirect) this else GoModuleSymbols(key, standard, packages, project, true)
}

/**
 * Reads the exported declarations of the packages under a directory, with [GoSourceScanner]: no compiler, no `go list`, no PSI.
 * Build constraints are not looked at: `file_windows.go` and `file_linux.go` declare the same names, one of each is kept.
 */
object GoCatalogueScanner {
    private val SKIPPED = setOf("internal", "testdata", "vendor")
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val MAX_SIGNATURE = 200

    /** [checkCancelled] is called for every directory; [modulePath] is empty for the standard library, whose paths begin at its `src`. */
    fun scanModule(root: File, modulePath: String, standard: Boolean, checkCancelled: () -> Unit = {}): List<GoPackageSymbols> {
        val result = ArrayList<GoPackageSymbols>()
        fun visit(directory: File, relative: String) {
            checkCancelled()
            val children = directory.listFiles() ?: return
            // a directory with a go.mod of its own is another module
            if (relative.isNotEmpty() && children.any { it.name == "go.mod" && it.isFile }) return
            val sources = children.filter { it.isFile && isSource(it.name) && it.length() <= MAX_FILE_BYTES }
            val importPath = listOf(modulePath, relative).filter { it.isNotEmpty() }.joinToString("/")
            if (sources.isNotEmpty() && importPath.isNotEmpty()) {
                scanPackage(importPath, sources.mapNotNull { file -> runCatching { file.readText() }.getOrNull() })?.let { result += it }
            }
            for (child in children.sortedBy { it.name }) {
                if (child.isDirectory && isPackageDirectory(child.name, standard && relative.isEmpty())) visit(child, if (relative.isEmpty()) child.name else "$relative/${child.name}")
            }
        }
        visit(root, "")
        return result
    }

    fun isSource(fileName: String): Boolean = fileName.endsWith(".go") && !fileName.endsWith(GoTestNames.TEST_SUFFIX)

    /** `cmd` of the standard library is the toolchain, not packages to import. */
    fun isPackageDirectory(name: String, topOfStandardLibrary: Boolean): Boolean =
        name !in SKIPPED && !name.startsWith(".") && !name.startsWith("_") && !(topOfStandardLibrary && name == "cmd")

    /** The exported names of the files of one directory; null for a program and for a package that exports nothing. */
    fun scanPackage(importPath: String, texts: List<CharSequence>): GoPackageSymbols? = merge(importPath, texts.mapNotNull(::exportsOf))

    /** What one file exports; null for a text without a package clause. A file that exports nothing still tells the name of its package. */
    fun exportsOf(text: CharSequence): GoFileExports? {
        val file = GoSourceScanner.scan(text)
        val symbols = file.declarations.filter { it.isExported && it.kind != GoDeclarationKind.METHOD }
            .map { GoSymbol(it.name, it.kind, it.signature?.take(MAX_SIGNATURE)) }
        val methods = file.declarations.filter { it.kind == GoDeclarationKind.METHOD && it.isExported && it.receiver?.firstOrNull()?.isUpperCase() == true }
            .map { GoMethodSymbol(it.receiver!!, it.name, it.signature?.take(MAX_SIGNATURE), isPointerReceiver(text, it.range.startOffset, it.nameRange.startOffset)) }
        return GoFileExports(file.packageName ?: return null, symbols, methods)
    }

    /** `func (s *Server) Start`: a star between the brackets of the receiver; type parameters of a receiver are names only. */
    private fun isPointerReceiver(text: CharSequence, from: Int, to: Int): Boolean {
        val receiver = text.subSequence(from.coerceIn(0, text.length), to.coerceIn(0, text.length))
        val open = receiver.indexOf('(')
        val close = receiver.indexOf(')')
        return open >= 0 && close > open && receiver.subSequence(open, close).contains('*')
    }

    /** The package the files of one directory make; null for a program and for a package that exports nothing. */
    fun merge(importPath: String, files: Collection<GoFileExports>): GoPackageSymbols? {
        // a file of another package in the directory is a generator or an example kept out of the build
        val name = files.groupingBy { it.packageName }.eachCount().maxByOrNull { it.value }?.key ?: return null
        if (name == "main") return null
        val symbols = LinkedHashMap<String, GoSymbol>()
        for (file in files) if (file.packageName == name) for (symbol in file.symbols) symbols.putIfAbsent(symbol.name, symbol)
        if (symbols.isEmpty()) return null
        // a method of a type the package does not export is out of reach of an importer by name; of one exported, it is reached through a value
        val types = symbols.values.filter { GoCatalogueInsertion.isType(it) }.mapTo(HashSet()) { it.name }
        val methods = LinkedHashMap<String, GoMethodSymbol>()
        for (file in files) if (file.packageName == name) for (method in file.methods) if (method.receiver in types) methods.putIfAbsent(method.receiver + "." + method.name, method)
        return GoPackageSymbols(importPath, name, symbols.values.sortedBy { it.name }, methods.values.sortedWith(compareBy({ it.receiver }, { it.name })))
    }

    /**
     * Whether a package may be imported from another: `a/internal/b` is for the packages under `a` only. The rule of the `go`
     * command, which matters for the packages of the project: the ones of the dependencies are left out when they are read.
     */
    fun isVisible(importPath: String, from: String?): Boolean {
        val parts = importPath.split('/')
        val internal = parts.lastIndexOf("internal")
        if (internal < 0) return true
        val parent = parts.take(internal).joinToString("/")
        return from != null && (parent.isEmpty() || from == parent || from.startsWith("$parent/"))
    }
}

/** A module of the catalogue as a file: written once for a version of a module, read by every project that requires it. */
object GoCatalogueFiles {
    private const val MAGIC = 0x476F4361 // "GoCa"

    // bump when the scanner starts to see declarations differently, or the format changes; 3: the methods of the exported types
    const val VERSION = 3

    fun write(file: File, module: GoModuleSymbols) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        DataOutputStream(GZIPOutputStream(temporary.outputStream().buffered())).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(module.key)
            out.writeBoolean(module.standard)
            out.writeInt(module.packages.size)
            for (pack in module.packages) {
                out.writeUTF(pack.importPath)
                out.writeUTF(pack.name)
                out.writeInt(pack.symbols.size)
                for (symbol in pack.symbols) {
                    out.writeUTF(symbol.name)
                    out.writeByte(symbol.kind.ordinal)
                    out.writeUTF(symbol.signature.orEmpty())
                }
                out.writeInt(pack.methods.size)
                for (method in pack.methods) {
                    out.writeUTF(method.receiver)
                    out.writeUTF(method.name)
                    out.writeUTF(method.signature.orEmpty())
                    out.writeBoolean(method.pointer)
                }
            }
        }
        // whole or not at all: another IDE may be reading the same cache
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) temporary.delete()
        }
    }

    /** Null for a file that is not there, is of another version or cannot be read: the module is scanned again then. */
    fun read(file: File, key: String): GoModuleSymbols? {
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(GZIPInputStream(file.inputStream().buffered())).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readUTF() != key) return null
                val standard = input.readBoolean()
                val kinds = GoDeclarationKind.entries
                val packages = List(input.readInt()) {
                    val importPath = input.readUTF()
                    val name = input.readUTF()
                    val symbols = List(input.readInt()) { GoSymbol(input.readUTF(), kinds[input.readByte().toInt()], input.readUTF().ifEmpty { null }) }
                    val methods = List(input.readInt()) { GoMethodSymbol(input.readUTF(), input.readUTF(), input.readUTF().ifEmpty { null }, input.readBoolean()) }
                    GoPackageSymbols(importPath, name, symbols, methods)
                }
                GoModuleSymbols(key, standard, packages)
            }
        }.getOrNull()
    }

    /** `github.com_google_uuid@v1.6.0-1a2b3c4d.bin`: readable, and different for keys that differ in what a file name cannot hold. */
    fun fileName(key: String): String = key.replace(Regex("[^A-Za-z0-9.@-]"), "_").take(120) + "-" + Integer.toHexString(key.hashCode()) + ".bin"
}

/**
 * The symbols of the packages a project may import, found by the beginning of a name. Made once for a set of modules and then only
 * read: a sorted array and a binary search, so that a list for every key typed costs nothing.
 */
class GoSymbolIndex(modules: List<GoModuleSymbols>) {
    class Entry(val symbol: GoSymbol, val pack: GoPackageSymbols, val standard: Boolean, val project: Boolean = false, val indirect: Boolean = false) {
        internal val key: String = symbol.name.lowercase()

        /** The packages of the project are what its code is written with; then the standard library; then the modules required directly; then the rest of the build list. */
        internal val origin: Int get() = originOf(project, standard, indirect)
    }

    private val entries: Array<Entry> = modules.flatMap { module -> module.packages.flatMap { pack -> pack.symbols.map { Entry(it, pack, module.standard, module.project, module.indirect) } } }
        .sortedWith(compareBy<Entry> { it.key }.thenBy { it.pack.importPath }).toTypedArray()

    private val byPath: Map<String, GoPackageSymbols> by lazy { modules.flatMap { it.packages }.associateBy { it.importPath } }

    /** The exported methods of the exported type [type] of the package [importPath]; empty for a package or a type the catalogue does not know. */
    fun methodsOf(importPath: String, type: String): List<GoMethodSymbol> = byPath[importPath]?.methodsOf(type).orEmpty()

    /** The functions, variables and constants by the key of the type of their value ([GoCatalogueSmart.resultKey]), built on the first smart list. */
    private val byResult: Map<String, List<Entry>> by lazy {
        val result = HashMap<String, MutableList<Entry>>()
        for (entry in entries) GoCatalogueSmart.resultKey(entry.symbol, entry.pack.importPath)?.let { result.getOrPut(it) { ArrayList() } += entry }
        result
    }

    /**
     * What gives a value of one of the types [keys] ([GoCatalogueSmart.expectedKeys]), the best first: a package of [preferred] (imported
     * by the file), then by origin, then by name; no more than [perPackage] of one package and [limit] in all.
     */
    fun fitting(keys: Collection<String>, limit: Int, perPackage: Int, preferred: Set<String> = emptySet(), visible: (Entry) -> Boolean = { true }): List<Entry> {
        val all = keys.flatMap { byResult[it].orEmpty() }.distinct().filter(visible)
            .sortedWith(compareBy<Entry> { it.pack.importPath !in preferred }.thenBy { it.origin }.thenBy { it.pack.importPath }.thenBy { it.symbol.name })
        val perPack = HashMap<String, Int>()
        return all.filter { perPack.merge(it.pack.importPath, 1, Int::plus)!! <= perPackage }.take(limit)
    }

    val size: Int get() = entries.size
    val packages: Int = modules.sumOf { it.packages.size }

    /** Every symbol of one kind: the interfaces there are to implement. */
    fun all(kind: GoDeclarationKind): List<Entry> = entries.filter { it.symbol.kind == kind }

    /** The packages by the names they are used by: of the project, of the standard library, of the modules; a shorter path before a longer one. */
    private val byName: Map<String, List<Pair<GoPackageSymbols, Set<String>>>> by lazy {
        modules.flatMap { module -> module.packages.map { originOf(module.project, module.standard, module.indirect) to it } }
            .sortedWith(compareBy<Pair<Int, GoPackageSymbols>> { it.first }.thenBy { it.second.importPath.count { c -> c == '/' } }.thenBy { it.second.importPath })
            .groupBy({ it.second.name }) { it.second to it.second.symbols.mapTo(HashSet()) { s -> s.name } }
    }

    /**
     * The path of the package that is called [name] and has all of [symbols]: `url` with `URL` and `Values` is `net/url`, `rand` with
     * `Reader` is `crypto/rand` and not `math/rand`. Null when no package of the catalogue is that.
     */
    fun packageOf(name: String, symbols: Collection<String>): String? =
        byName[name]?.firstOrNull { (_, names) -> names.containsAll(symbols) }?.first?.importPath

    /** The paths of the standard library's packages called [name] that have all of [symbols]: `template` with `New` is two of them. */
    fun standardPackagesOf(name: String, symbols: Collection<String>): List<String> =
        standardByName[name].orEmpty().filter { it.symbols.mapTo(HashSet()) { s -> s.name }.containsAll(symbols) }.map { it.importPath }

    private val standardByName: Map<String, List<GoPackageSymbols>> by lazy {
        modules.filter { it.standard }.flatMap { it.packages }.groupBy { it.name }
    }

    /**
     * The names that begin with [prefix], whatever the case of the letters, the best first: the case as typed, a package of
     * [preferred] (the ones the file imports), the project before the standard library before the modules, a shorter name. No more
     * than [limit] of the ones [visible] lets through (a package of the file itself, an `internal` one of another tree).
     */
    fun find(prefix: String, limit: Int, preferred: Set<String> = emptySet(), qualifier: String? = null, visible: (GoPackageSymbols) -> Boolean = { true }): List<Entry> {
        if (prefix.isEmpty() && qualifier == null) return emptyList()
        val wanted = prefix.lowercase()
        val found = ArrayList<Entry>()
        if (qualifier != null) {
            // the names of the packages of one name: few enough to look through all there is
            for (entry in entries) if (entry.pack.name.equals(qualifier, ignoreCase = true) && entry.key.startsWith(wanted)) found += entry
        } else {
            var low = 0
            var high = entries.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (entries[middle].key < wanted) low = middle + 1 else high = middle
            }
            var i = low
            while (i < entries.size && entries[i].key.startsWith(wanted) && found.size < MAX_MATCHES) found += entries[i++]
        }
        return found.filter { visible(it.pack) }.sortedWith(
            compareBy<Entry> { !it.symbol.name.startsWith(prefix) }.thenBy { it.pack.importPath !in preferred }.thenBy { it.origin }
                .thenBy { it.symbol.name.length }.thenBy { it.pack.importPath.count { c -> c == '/' } }.thenBy { it.key }.thenBy { it.pack.importPath },
        ).take(limit)
    }

    companion object {
        val EMPTY = GoSymbolIndex(emptyList())
        private const val MAX_MATCHES = 2000

        internal fun originOf(project: Boolean, standard: Boolean, indirect: Boolean): Int = when {
            project -> 0
            standard -> 1
            indirect -> 3
            else -> 2
        }
    }
}
