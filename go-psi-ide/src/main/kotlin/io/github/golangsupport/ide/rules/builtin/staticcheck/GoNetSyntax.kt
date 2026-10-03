package io.github.golangsupport.ide.rules.builtin.staticcheck

/**
 * The errors of Go's `net/url.Parse` and `net.SplitHostPort` for constant arguments, re-implemented from their behaviour with the
 * same texts (`parse "http://%zz": invalid URL escape "%zz"`). IPv6 literal contents are not validated (newer Go versions do):
 * missing that only means no report.
 */
internal object GoNetSyntax {

    /** Go's `url.Parse(raw)` error text, or null when it parses. */
    fun urlError(raw: String): String? {
        val hash = raw.indexOf('#')
        val u = if (hash < 0) raw else raw.substring(0, hash)
        parse(u)?.let { return "parse ${GoStaticcheckPsi.quote(u)}: $it" }
        if (hash < 0) return null
        val fragment = raw.substring(hash + 1)
        if (fragment.isEmpty()) return null
        unescape(fragment, Mode.FRAGMENT)?.let { return "parse ${GoStaticcheckPsi.quote(raw)}: $it" }
        return null
    }

    private enum class Mode { HOST, ZONE, PATH, USER, FRAGMENT }

    private fun parse(raw: String): String? {
        if (raw.any { it < ' ' || it == '\u007f' }) return "net/url: invalid control character in URL"
        if (raw == "*") return null
        var scheme = ""
        var rest = raw
        loop@ for (i in raw.indices) {
            val c = raw[i]
            when {
                c in 'a'..'z' || c in 'A'..'Z' -> {}
                c in '0'..'9' || c == '+' || c == '-' || c == '.' -> if (i == 0) break@loop
                c == ':' -> {
                    if (i == 0) return "missing protocol scheme"
                    scheme = raw.substring(0, i)
                    rest = raw.substring(i + 1)
                    break@loop
                }
                else -> break@loop
            }
        }
        rest = if (rest.endsWith("?") && rest.count { it == '?' } == 1) rest.dropLast(1) else rest.substringBefore('?')
        if (!rest.startsWith("/")) {
            if (scheme.isNotEmpty()) return null // opaque
            if (':' in rest.substringBefore('/')) return "first path segment in URL cannot contain colon"
        }
        if ((scheme.isNotEmpty() || !rest.startsWith("///")) && rest.startsWith("//")) {
            var authority = rest.substring(2)
            rest = ""
            val slash = authority.indexOf('/')
            if (slash >= 0) {
                rest = authority.substring(slash)
                authority = authority.substring(0, slash)
            }
            parseAuthority(authority)?.let { return it }
        }
        return unescape(rest, Mode.PATH)
    }

    private fun parseAuthority(authority: String): String? {
        val at = authority.lastIndexOf('@')
        parseHost(if (at < 0) authority else authority.substring(at + 1))?.let { return it }
        if (at < 0) return null
        val userinfo = authority.substring(0, at)
        if (!userinfo.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._:~!$&'()*+,;=%@" }) return "net/url: invalid userinfo"
        for (part in userinfo.split(":", limit = 2)) unescape(part, Mode.USER)?.let { return it }
        return null
    }

    private fun parseHost(host: String): String? {
        if (host.startsWith("[")) {
            val close = host.lastIndexOf(']')
            if (close < 0) return "missing ']' in host"
            val colonPort = host.substring(close + 1)
            if (!validOptionalPort(colonPort)) return "invalid port ${GoStaticcheckPsi.quote(colonPort)} after host"
            val zone = host.substring(0, close).indexOf("%25")
            if (zone >= 0) {
                return unescape(host.substring(0, zone), Mode.HOST) ?: unescape(host.substring(zone, close), Mode.ZONE)
                    ?: unescape(host.substring(close), Mode.HOST)
            }
        } else {
            val colon = host.lastIndexOf(':')
            if (colon >= 0) {
                val colonPort = host.substring(colon)
                if (!validOptionalPort(colonPort)) return "invalid port ${GoStaticcheckPsi.quote(colonPort)} after host"
            }
        }
        return unescape(host, Mode.HOST)
    }

    private fun validOptionalPort(port: String): Boolean = port.isEmpty() || port[0] == ':' && port.substring(1).all { it in '0'..'9' }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun unhex(c: Char): Int = Character.digit(c, 16)

    /** The error of Go's `unescape(s, mode)`, or null. */
    private fun unescape(s: String, mode: Mode): String? {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length || !isHex(s[i + 1]) || !isHex(s[i + 2])) return escapeError(s.substring(i, minOf(s.length, i + 3)))
                val escape = s.substring(i, i + 3)
                if (mode == Mode.HOST && unhex(s[i + 1]) < 8 && escape != "%25") return escapeError(escape)
                if (mode == Mode.ZONE) {
                    val v = unhex(s[i + 1]) shl 4 or unhex(s[i + 2])
                    if (escape != "%25" && v != ' '.code && v < 0x80 && !hostAllows(v.toChar())) return escapeError(escape)
                }
                i += 3
            } else {
                if ((mode == Mode.HOST || mode == Mode.ZONE) && c.code < 0x80 && !hostAllows(c)) return "invalid character ${GoStaticcheckPsi.quote(c.toString())} in host name"
                i++
            }
        }
        return null
    }

    private fun escapeError(s: String): String = "invalid URL escape ${GoStaticcheckPsi.quote(s)}"

    /** Go's `!shouldEscape(c, encodeHost)` for ASCII. */
    private fun hostAllows(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!$&'()*+,;=:[]<>\"-_.~"

    /** Whether staticcheck's `ValidHostPort` accepts [s]: `net.SplitHostPort` succeeds and the port is a number or a service name. */
    fun validHostPort(s: String): Boolean {
        if (s.isEmpty()) return true
        val port = splitPort(s) ?: return false
        val n = port.toLongOrNull() ?: return validServiceName(port)
        return n in 0..65535
    }

    /** The port of Go's `net.SplitHostPort(s)`, or null when it fails. */
    private fun splitPort(s: String): String? {
        val i = s.lastIndexOf(':')
        if (i < 0) return null
        var j = 0
        var k = 0
        if (s[0] == '[') {
            val end = s.indexOf(']')
            if (end < 0) return null
            when (end + 1) {
                s.length -> return null
                i -> {}
                else -> return null
            }
            j = 1
            k = end + 1
        } else if (':' in s.substring(0, i)) {
            return null
        }
        if ('[' in s.substring(j)) return null
        if (']' in s.substring(k)) return null
        return s.substring(i + 1)
    }

    private fun validServiceName(s: String): Boolean {
        if (s.isEmpty() || s.length > 15) return false
        if (s.first() == '-' || s.last() == '-' || "--" in s) return false
        var letter = false
        for (c in s) {
            when (c) {
                in 'A'..'Z', in 'a'..'z' -> letter = true
                in '0'..'9', '-' -> {}
                else -> return false
            }
        }
        return letter
    }
}
