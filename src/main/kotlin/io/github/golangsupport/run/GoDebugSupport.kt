package io.github.golangsupport.run

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoDeclarations
import io.github.golangsupport.lang.GoLexer
import io.github.golangsupport.lang.GoTokenTypes
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * What of debugging needs no DAP class of the platform: kept out of the module with the debugger, so that it is there for the tests
 * and for the rest of the plugin in an IDE without that module.
 */
object DlvDap {
    private val LISTENING = Regex("""DAP server listening at:\s*(\S+):(\d+)""")

    /** `DAP server listening at: 127.0.0.1:63940`, the line `dlv dap` prints once it accepts a connection; checked on delve 1.27. */
    fun listeningAt(line: String): Pair<String, Int>? = LISTENING.find(line)?.let { it.groupValues[1] to it.groupValues[2].toInt() }

    /**
     * `--listen=127.0.0.1:0`: delve picks a free port and says which, so there is no race for a port chosen here. No `--log-dest`:
     * with it the line about the port goes to that file as well (seen live), so the log is taken from the output of the process.
     */
    fun arguments(log: Boolean, anyGoVersion: Boolean): List<String> = buildList {
        add("dap")
        add("--listen=127.0.0.1:0")
        // a delve newer than the toolchain refuses to start the program ("Go version ... is too old for this version of Delve")
        if (anyGoVersion) add("--check-go-version=false")
        if (log) addAll(listOf("--log", "--log-output=dap,debugger"))
    }
}

/** The reason of a failed `launch` or `attach`, as delve words it; null for any other message. */
object DapStartFailure {
    fun of(body: ByteArray): String? {
        val text = String(body, Charsets.UTF_8)
        if (!text.contains("\"success\":false")) return null
        val message = runCatching { JsonParser.parseString(text) as? JsonObject }.getOrNull() ?: return null
        val command = message.get("command")?.takeIf { it.isJsonPrimitive }?.asString
        if (command != "launch" && command != "attach") return null
        val error = (message.get("body") as? JsonObject)?.get("error") as? JsonObject
        return error?.get("format")?.takeIf { it.isJsonPrimitive }?.asString
            ?: message.get("message")?.takeIf { it.isJsonPrimitive }?.asString
            ?: "The debugger has refused to $command"
    }
}

/**
 * Remembers what delve has printed before the program started (the errors of the compiler come as `output` events), because
 * "Build error: Check the debug console for details" points at a console that closes together with the failed session.
 */
class DapStartWatcher(private val onStartFailed: (String) -> Unit) {
    private val output = StringBuilder()
    private var started = false

    /** True when the message is a failed `launch` / `attach`: the caller ends the adapter. */
    fun message(body: ByteArray): Boolean {
        if (started) return false
        val reason = DapStartFailure.of(body)
        if (reason != null) {
            onStartFailed((reason + "\n" + output.toString().trim()).trim())
            return true
        }
        val text = String(body, Charsets.UTF_8)
        when {
            text.contains("\"event\":\"output\"") -> outputOf(text)?.let { if (output.length < MAX_OUTPUT) output.append(it) }
            // the program runs: from here on its output is its own business
            text.contains("\"command\":\"launch\"") || text.contains("\"command\":\"attach\"") -> started = true
        }
        return false
    }

    private fun outputOf(text: String): String? {
        val message = runCatching { JsonParser.parseString(text) as? JsonObject }.getOrNull() ?: return null
        return (message.get("body") as? JsonObject)?.get("output")?.takeIf { it.isJsonPrimitive }?.asString
    }

    private companion object {
        const val MAX_OUTPUT = 4000
    }
}

/** What the adapter sends, passed on as it is; [onMessage] gets the body of every complete message. A failure of the watcher never breaks the stream. */
class DapMessageWatchingStream(source: InputStream, private val onMessage: (ByteArray) -> Unit) : FilterInputStream(source) {
    private val copy = DapMessageRewritingStream(OutputStream.nullOutputStream()) { body -> body.also { runCatching { onMessage(it) } } }

    override fun read(): Int = super.read().also { if (it >= 0) runCatching { copy.write(it) } }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) runCatching { copy.write(b, off, it) } }
}

/** The exception breakpoint filters of delve, as its `initialize` response lists them. */
enum class GoPanicFilter(val id: String, val title: String) {
    UNRECOVERED_PANIC("unrecovered-panic", "Unrecovered panics"),
    FATAL_THROW("runtime-fatal-throw", "Fatal throws of the runtime"),
}

/** The 0-based lines a breakpoint makes sense at: code inside the body of a function, or inside a multi-line initializer of a variable (a function literal). */
object GoBreakpointLines {
    fun find(text: CharSequence): Set<Int> {
        val structure = GoDeclarations.scan(text)
        val bodies = structure.declarations.mapNotNull { declaration ->
            when (declaration.kind) {
                GoDeclarationKind.FUNCTION, GoDeclarationKind.METHOD -> declaration.body
                GoDeclarationKind.VAR -> declaration.range
                else -> null
            }
        }
        if (bodies.isEmpty()) return emptySet()
        val lineStarts = ArrayList<Int>().apply {
            add(0)
            for (i in text.indices) if (text[i] == '\n') add(i + 1)
        }
        fun lineOf(offset: Int): Int = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }

        val result = HashSet<Int>()
        val lexer = GoLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            if (type != TokenType.WHITE_SPACE && type !in GoTokenTypes.COMMENTS && bodies.any { start > it.startOffset && start < it.endOffset - 1 }) result += lineOf(start)
            lexer.advance()
        }
        // the header of a function is a place to stop at as well: delve puts the breakpoint at its first instruction
        structure.declarations.filter { it.body != null && it.kind != GoDeclarationKind.STRUCT && it.kind != GoDeclarationKind.INTERFACE }.forEach { result += lineOf(it.nameRange.startOffset) }
        return result
    }
}

/**
 * The expression a debugger evaluates when the mouse rests on code: the identifier under the pointer with the selectors to the left of
 * it (`order.Currency` on `Currency`). By tokens. Nothing that would run code of the program (a name followed by `(`), no package
 * qualifiers the debugger cannot tell from variables anyway: delve answers those with an error and the hint stays empty.
 */
object GoHoverExpression {
    private class Token(val type: IElementType, val start: Int, val end: Int)

    fun rangeAt(text: CharSequence, offset: Int): TextRange? {
        val tokens = ArrayList<Token>()
        val lexer = GoLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in GoTokenTypes.COMMENTS) tokens += Token(type, lexer.tokenStart, lexer.tokenEnd)
            if (lexer.tokenStart > offset) break // one token past the pointer is enough to see a call
            lexer.advance()
        }
        val index = tokens.indexOfFirst { offset >= it.start && offset < it.end }
        if (index < 0 || tokens[index].type != GoTokenTypes.IDENTIFIER) return null
        if (tokens.getOrNull(index + 1)?.type == GoTokenTypes.LPAREN) return null
        var first = index
        while (first >= 2 && tokens[first - 1].type == GoTokenTypes.DOT && tokens[first - 2].type == GoTokenTypes.IDENTIFIER) first -= 2
        // `f().x`, `items[0].Name`: what is to the left is not a plain name, the chain would be evaluated out of its context
        if (first >= 1 && tokens[first - 1].type == GoTokenTypes.DOT) return null
        return TextRange(tokens[first].start, tokens[index].end)
    }
}

/** What a line breakpoint has beyond the line and the condition, in the terms of the protocol: `hitCondition` and `logMessage`. */
class BreakpointExtras(val hitCondition: String?, val logMessage: String?) {
    val isEmpty: Boolean get() = hitCondition.isNullOrBlank() && logMessage.isNullOrBlank()
}

/** The hit conditions delve understands: a positive number, optionally after `==`, `!=`, `>=`, `>`, `<=`, `<` or `%`. */
object HitCondition {
    private val SYNTAX = Regex("""(==|!=|>=|>|<=|<|%)?\s*([1-9]\d*)""")

    fun isValid(text: String?): Boolean = text.isNullOrBlank() || SYNTAX.matches(text.trim())

    /** Delve wants a space between the operator and the number (`>= 3`) and takes a bare number as `==`; blank is no condition. */
    fun normalize(text: String?): String? {
        val match = SYNTAX.matchEntire(text?.trim().orEmpty()) ?: return null
        val (operator, number) = match.destructured
        return if (operator.isEmpty()) number else "$operator $number"
    }
}

/**
 * The DAP client of the platform sends `line`, `column` and `condition` of a breakpoint and nothing else, and its breakpoint handler is
 * final. The messages to the adapter, however, go through a stream the plugin creates itself: a `setBreakpoints` request gets its
 * `hitCondition` / `logMessage` on the way, and everything else passes untouched. The same trick as in the .NET sibling of this plugin.
 */
object DapSetBreakpoints {
    /** Adds the extras to the breakpoints of a `setBreakpoints` request; false when [message] is something else or nothing was added. */
    fun addExtras(message: JsonObject, extras: (path: String, line: Int) -> BreakpointExtras?): Boolean {
        if (message.get("command")?.takeIf { it.isJsonPrimitive }?.asString != "setBreakpoints") return false
        val arguments = message.get("arguments") as? JsonObject ?: return false
        val path = (arguments.get("source") as? JsonObject)?.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: return false
        val breakpoints = arguments.get("breakpoints")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        var changed = false
        for (breakpoint in breakpoints) {
            val item = breakpoint as? JsonObject ?: continue
            val line = item.get("line")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
            val extra = extras(path, line)?.takeIf { !it.isEmpty } ?: continue
            HitCondition.normalize(extra.hitCondition)?.let { item.addProperty("hitCondition", it) }
            extra.logMessage?.takeIf { it.isNotBlank() }?.let { item.addProperty("logMessage", it) }
            changed = true
        }
        return changed
    }

    /** The body of one DAP message, rewritten if it is a `setBreakpoints` request with extras; anything unexpected is returned as it came. */
    fun rewrite(body: ByteArray, extras: (path: String, line: Int) -> BreakpointExtras?, onError: (Exception) -> Unit = {}): ByteArray = try {
        // cheap check first: almost every message is something else
        if (!String(body, Charsets.UTF_8).contains("\"setBreakpoints\"")) body
        else {
            val message = JsonParser.parseString(String(body, Charsets.UTF_8)) as? JsonObject
            if (message != null && addExtras(message, extras)) message.toString().toByteArray(Charsets.UTF_8) else body
        }
    } catch (e: Exception) {
        // the message goes out as the client wrote it: a breakpoint without its hit count is better than a broken session
        onError(e)
        body
    }
}

/**
 * A stream of DAP messages (`Content-Length: N\r\n\r\n` and N bytes of JSON) that lets [transform] replace the body of every message.
 * The writer may cut the stream anywhere, so bytes are kept until a message is complete; a stream that does not look like DAP is passed on.
 */
class DapMessageRewritingStream(private val target: OutputStream, private val transform: (ByteArray) -> ByteArray) : OutputStream() {
    private val pending = ByteArrayOutputStream()

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        pending.write(b, off, len)
        drain()
    }

    private fun drain() {
        while (true) {
            val bytes = pending.toByteArray()
            val headerEnd = indexOf(bytes, HEADER_END)
            if (headerEnd < 0) {
                if (bytes.size > MAX_HEADER) passThrough(bytes) // not a DAP header: do not hold the stream back
                return
            }
            val length = CONTENT_LENGTH.find(String(bytes, 0, headerEnd, Charsets.US_ASCII))?.groupValues?.get(1)?.toIntOrNull()
            if (length == null) {
                passThrough(bytes)
                return
            }
            val bodyStart = headerEnd + HEADER_END.size
            if (bytes.size < bodyStart + length) return
            val body = transform(bytes.copyOfRange(bodyStart, bodyStart + length))
            target.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            target.write(body)
            pending.reset()
            pending.write(bytes, bodyStart + length, bytes.size - bodyStart - length)
        }
    }

    private fun passThrough(bytes: ByteArray) {
        target.write(bytes)
        pending.reset()
    }

    @Synchronized
    override fun flush() = target.flush()

    override fun close() = target.close()

    private companion object {
        val HEADER_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val CONTENT_LENGTH = Regex("""(?i)Content-Length:\s*(\d+)""")
        const val MAX_HEADER = 8192

        fun indexOf(bytes: ByteArray, part: ByteArray): Int {
            outer@ for (i in 0..bytes.size - part.size) {
                for (j in part.indices) if (bytes[i + j] != part[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
