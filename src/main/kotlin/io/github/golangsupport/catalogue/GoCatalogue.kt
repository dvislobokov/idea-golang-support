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

/** The exported names of one file; a file that exports nothing still votes for the name of the package of its directory. */
data class GoFileExports(val packageName: String, val symbols: List<GoSymbol>)

class GoPackageSymbols(val importPath: String, val name: String, val symbols: List<GoSymbol>)

/**
 * The packages of a module of one version, or of the standard library of one version of Go: what never changes once it is there.
 * Or the packages of the project itself ([project]), which change and are kept by the index of the platform, not by a file.
 */
class GoModuleSymbols(val key: String, val standard: Boolean, val packages: List<GoPackageSymbols>, val project: Boolean = false)

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
        return GoFileExports(file.packageName ?: return null, symbols)
    }

    /** The package the files of one directory make; null for a program and for a package that exports nothing. */
    fun merge(importPath: String, files: Collection<GoFileExports>): GoPackageSymbols? {
        // a file of another package in the directory is a generator or an example kept out of the build
        val name = files.groupingBy { it.packageName }.eachCount().maxByOrNull { it.value }?.key ?: return null
        if (name == "main") return null
        val symbols = LinkedHashMap<String, GoSymbol>()
        for (file in files) if (file.packageName == name) for (symbol in file.symbols) symbols.putIfAbsent(symbol.name, symbol)
        return if (symbols.isEmpty()) null else GoPackageSymbols(importPath, name, symbols.values.sortedBy { it.name })
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

    // bump when the scanner starts to see declarations differently, or the format changes
    const val VERSION = 2

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
                    GoPackageSymbols(importPath, name, List(input.readInt()) {
                        GoSymbol(input.readUTF(), kinds[input.readByte().toInt()], input.readUTF().ifEmpty { null })
                    })
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
    class Entry(val symbol: GoSymbol, val pack: GoPackageSymbols, val standard: Boolean, val project: Boolean = false) {
        internal val key: String = symbol.name.lowercase()

        /** The packages of the project are what its code is written with; then the standard library; then the modules. */
        internal val origin: Int get() = if (project) 0 else if (standard) 1 else 2
    }

    private val entries: Array<Entry> = modules.flatMap { module -> module.packages.flatMap { pack -> pack.symbols.map { Entry(it, pack, module.standard, module.project) } } }
        .sortedWith(compareBy<Entry> { it.key }.thenBy { it.pack.importPath }).toTypedArray()

    val size: Int get() = entries.size
    val packages: Int = modules.sumOf { it.packages.size }

    /** Every symbol of one kind: the interfaces there are to implement. */
    fun all(kind: GoDeclarationKind): List<Entry> = entries.filter { it.symbol.kind == kind }

    /** The packages by the names they are used by: of the project, of the standard library, of the modules; a shorter path before a longer one. */
    private val byName: Map<String, List<Pair<GoPackageSymbols, Set<String>>>> by lazy {
        modules.flatMap { module -> module.packages.map { (if (module.project) 0 else if (module.standard) 1 else 2) to it } }
            .sortedWith(compareBy<Pair<Int, GoPackageSymbols>> { it.first }.thenBy { it.second.importPath.count { c -> c == '/' } }.thenBy { it.second.importPath })
            .groupBy({ it.second.name }) { it.second to it.second.symbols.mapTo(HashSet()) { s -> s.name } }
    }

    /**
     * The path of the package that is called [name] and has all of [symbols]: `url` with `URL` and `Values` is `net/url`, `rand` with
     * `Reader` is `crypto/rand` and not `math/rand`. Null when no package of the catalogue is that.
     */
    fun packageOf(name: String, symbols: Collection<String>): String? =
        byName[name]?.firstOrNull { (_, names) -> names.containsAll(symbols) }?.first?.importPath

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
    }
}
