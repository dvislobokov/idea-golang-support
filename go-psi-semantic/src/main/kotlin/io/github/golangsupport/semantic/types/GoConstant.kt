package io.github.golangsupport.semantic.types

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/**
 * Compile-time constant values (`go/constant` subset): booleans, arbitrary-precision integers,
 * decimal floats, complex numbers and strings. Operations return null when not representable.
 */
sealed class GoConstant {
    data class Bool(val value: Boolean) : GoConstant() { override fun toString(): String = render() }
    data class Int(val value: BigInteger) : GoConstant() {
        constructor(value: Long) : this(BigInteger.valueOf(value))
        override fun toString(): String = render()
    }
    data class Float(val value: BigDecimal) : GoConstant() { override fun toString(): String = render() }
    data class Complex(val re: BigDecimal, val im: BigDecimal) : GoConstant() { override fun toString(): String = render() }
    data class Str(val value: String) : GoConstant() { override fun toString(): String = render() }

    val kind: GoBasicKind
        get() = when (this) {
            is Bool -> GoBasicKind.UNTYPED_BOOL
            is Int -> GoBasicKind.UNTYPED_INT
            is Float -> GoBasicKind.UNTYPED_FLOAT
            is Complex -> GoBasicKind.UNTYPED_COMPLEX
            is Str -> GoBasicKind.UNTYPED_STRING
        }

    fun toBigInteger(): BigInteger? = when (this) {
        is Int -> value
        is Float -> runCatching { value.toBigIntegerExact() }.getOrNull()
        is Complex -> if (im.signum() == 0) runCatching { re.toBigIntegerExact() }.getOrNull() else null
        else -> null
    }

    fun toBigDecimal(): BigDecimal? = when (this) {
        is Int -> value.toBigDecimal()
        is Float -> value
        is Complex -> if (im.signum() == 0) re else null
        else -> null
    }

    /** go/constant `String()`: the shortest Go-like spelling of the value. */
    fun render(): String = when (this) {
        is Bool -> value.toString()
        is Int -> value.toString()
        is Float -> renderFloat(value)
        is Complex -> "(${re.stripTrailingZeros().toPlainString()} + ${im.stripTrailingZeros().toPlainString()}i)"
        is Str -> "\"" + value + "\""
    }

    companion object {
        private val MC = MathContext(64)

        /** go/constant `String()` for floats: `%.6g`-like, plain for moderate magnitudes. */
        fun renderFloat(v: BigDecimal): String {
            val stripped = v.stripTrailingZeros()
            if (stripped.signum() == 0) return "0"
            val plain = stripped.toPlainString()
            val exp = stripped.precision() - stripped.scale() - 1
            if (exp in -4..20 && plain.length <= 22) return plain
            val rounded = stripped.round(MathContext(6))
            val unscaled = rounded.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
            val mantissa = if (unscaled.length > 1) unscaled[0] + "." + unscaled.substring(1) else unscaled
            val e = rounded.precision() - rounded.scale() - 1
            return (if (rounded.signum() < 0) "-" else "") + mantissa + "e" + (if (e < 0) "-" else "+") + kotlin.math.abs(e).toString().padStart(2, '0')
        }

        fun binary(op: String, a: GoConstant, b: GoConstant): GoConstant? {
            if (a is Str && b is Str) return if (op == "+") Str(a.value + b.value) else compare(op, a.value.compareTo(b.value))
            if (a is Bool && b is Bool) return when (op) {
                "&&" -> Bool(a.value && b.value)
                "||" -> Bool(a.value || b.value)
                "==" -> Bool(a.value == b.value)
                "!=" -> Bool(a.value != b.value)
                else -> null
            }
            if (op == "<<" || op == ">>") {
                val x = a.toBigInteger() ?: return null
                val n = b.toBigInteger()?.takeIf { it.signum() >= 0 && it.bitLength() < 16 }?.toInt() ?: return null
                return Int(if (op == "<<") x.shiftLeft(n) else x.shiftRight(n))
            }
            if (a is Int && b is Int) {
                val x = a.value
                val y = b.value
                return when (op) {
                    "+" -> Int(x + y); "-" -> Int(x - y); "*" -> Int(x * y)
                    "/" -> if (y.signum() == 0) null else Int(x.divide(y))
                    "%" -> if (y.signum() == 0) null else Int(x.rem(y))
                    "&" -> Int(x.and(y)); "|" -> Int(x.or(y)); "^" -> Int(x.xor(y)); "&^" -> Int(x.andNot(y))
                    else -> compare(op, x.compareTo(y))
                }
            }
            if (a is Complex || b is Complex) {
                val (ar, ai) = complexParts(a) ?: return null
                val (br, bi) = complexParts(b) ?: return null
                return when (op) {
                    "+" -> Complex(ar + br, ai + bi)
                    "-" -> Complex(ar - br, ai - bi)
                    "*" -> Complex(ar * br - ai * bi, ar * bi + ai * br)
                    "/" -> {
                        val d = br * br + bi * bi
                        if (d.signum() == 0) null else Complex((ar * br + ai * bi).divide(d, MC), (ai * br - ar * bi).divide(d, MC))
                    }
                    "==" -> Bool(ar.compareTo(br) == 0 && ai.compareTo(bi) == 0)
                    "!=" -> Bool(ar.compareTo(br) != 0 || ai.compareTo(bi) != 0)
                    else -> null
                }
            }
            val x = a.toBigDecimal() ?: return null
            val y = b.toBigDecimal() ?: return null
            return when (op) {
                "+" -> Float(x + y); "-" -> Float(x - y); "*" -> Float(x * y)
                "/" -> if (y.signum() == 0) null else Float(x.divide(y, MC))
                else -> compare(op, x.compareTo(y))
            }
        }

        private fun complexParts(c: GoConstant): Pair<BigDecimal, BigDecimal>? = when (c) {
            is Complex -> c.re to c.im
            else -> c.toBigDecimal()?.let { it to BigDecimal.ZERO }
        }

        private fun compare(op: String, cmp: kotlin.Int): GoConstant? = when (op) {
            "==" -> Bool(cmp == 0); "!=" -> Bool(cmp != 0); "<" -> Bool(cmp < 0)
            "<=" -> Bool(cmp <= 0); ">" -> Bool(cmp > 0); ">=" -> Bool(cmp >= 0)
            else -> null
        }

        fun unary(op: String, a: GoConstant): GoConstant? = when (op) {
            "+" -> a.takeIf { it !is Bool && it !is Str }
            "-" -> when (a) {
                is Int -> Int(a.value.negate())
                is Float -> Float(a.value.negate())
                is Complex -> Complex(a.re.negate(), a.im.negate())
                else -> null
            }
            "!" -> (a as? Bool)?.let { Bool(!it.value) }
            "^" -> (a as? Int)?.let { Int(it.value.not()) }
            else -> null
        }

        /** Parses a Go integer literal (`0x`, `0o`, `0b`, legacy octal, `_` separators). */
        fun parseInt(text: String): Int? {
            val t = text.replace("_", "")
            return runCatching {
                when {
                    t.startsWith("0x") || t.startsWith("0X") -> BigInteger(t.substring(2), 16)
                    t.startsWith("0b") || t.startsWith("0B") -> BigInteger(t.substring(2), 2)
                    t.startsWith("0o") || t.startsWith("0O") -> BigInteger(t.substring(2), 8)
                    t.length > 1 && t[0] == '0' && t.all { it.isDigit() } -> BigInteger(t, 8)
                    else -> BigInteger(t)
                }
            }.getOrNull()?.let(::Int)
        }

        /** Parses a Go float literal, including hexadecimal floats. */
        fun parseFloat(text: String): Float? {
            val t = text.replace("_", "")
            if (t.startsWith("0x") || t.startsWith("0X")) {
                return runCatching { Float(BigDecimal(java.lang.Double.parseDouble(if (t.contains('p') || t.contains('P')) t else t + "p0"))) }.getOrNull()
            }
            return runCatching { Float(BigDecimal(t)) }.getOrNull()
        }

        fun parseImag(text: String): Complex? {
            val body = text.removeSuffix("i")
            val value = parseFloat(body)?.value ?: parseInt(body)?.value?.toBigDecimal() ?: return null
            return Complex(BigDecimal.ZERO, value)
        }

        /** Parses a rune literal to its code point. */
        fun parseRune(text: String): Int? {
            if (text.length < 3 || text[0] != '\'') return null
            val body = text.substring(1, if (text.last() == '\'') text.length - 1 else text.length)
            val cp = unescape(body)?.let { s -> if (s.isEmpty()) null else s.codePointAt(0) } ?: return null
            return Int(cp.toLong())
        }

        /** Unescapes the body of an interpreted string literal; null for invalid escapes. */
        fun unescape(body: String): String? {
            if (!body.contains('\\')) return body
            val sb = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c != '\\') { sb.append(c); i++; continue }
                if (i + 1 >= body.length) return null
                val e = body[i + 1]
                i += 2
                when (e) {
                    'a' -> sb.append('\u0007'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); 'v' -> sb.append('\u000B')
                    '\\' -> sb.append('\\'); '\'' -> sb.append('\''); '"' -> sb.append('"')
                    'x' -> { val h = body.substring(i, minOf(i + 2, body.length)); sb.append(h.toIntOrNull(16)?.toChar() ?: return null); i += 2 }
                    'u' -> { val h = body.substring(i, minOf(i + 4, body.length)); sb.appendCodePoint(h.toIntOrNull(16) ?: return null); i += 4 }
                    'U' -> { val h = body.substring(i, minOf(i + 8, body.length)); sb.appendCodePoint(h.toIntOrNull(16) ?: return null); i += 8 }
                    in '0'..'7' -> { val o = body.substring(i - 1, minOf(i + 2, body.length)); sb.append(o.toIntOrNull(8)?.toChar() ?: return null); i += 2 }
                    else -> return null
                }
            }
            return sb.toString()
        }
    }
}
