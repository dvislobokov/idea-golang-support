package io.github.golangsupport.ide.rules.staticcheck

import io.github.golangsupport.ide.rules.builtin.staticcheck.GoNetSyntax
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoRegexpSyntax
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Go-compatible syntax checks behind SA1000 (`regexp/syntax`), SA1007 (`url.Parse`) and SA1020 (`net.SplitHostPort`): texts as Go prints them. */
class GoStaticcheckSyntaxTest {

    private fun re(expr: String): String? = GoRegexpSyntax.error(expr)?.removePrefix("error parsing regexp: ")

    @Test
    fun regexpErrors() {
        assertEquals("missing closing ]: `[`", re("["))
        assertEquals("missing closing ]: `[a-z`", re("x[a-z"))
        assertEquals("invalid nested repetition operator: `**`", re("a**"))
        assertEquals("invalid nested repetition operator: `*+`", re("a*+"))
        assertEquals("invalid nested repetition operator: `{2}{3}`", re("x{2}{3}"))
        assertEquals("missing argument to repetition operator: `*`", re("*a"))
        assertEquals("missing argument to repetition operator: `+?`", re("(+?)"))
        assertEquals("missing argument to repetition operator: `*`", re("a|*"))
        assertEquals("missing argument to repetition operator: `{2}`", re("{2}"))
        assertEquals("missing closing ): `(abc`", re("(abc"))
        assertEquals("unexpected ): `abc)`", re("abc)"))
        assertEquals("invalid escape sequence: `\\8`", re("\\8"))
        assertEquals("invalid escape sequence: `\\1`", re("(a)\\1"))
        assertEquals("invalid escape sequence: `\\Z`", re("a\\Z"))
        assertEquals("invalid escape sequence: `\\C`", re("\\C"))
        assertEquals("invalid escape sequence: `\\xg1`", re("\\xg1"))
        assertEquals("invalid escape sequence: `\\b`", re("[\\b]"))
        assertEquals("invalid repeat count: `{1001}`", re("a{1001}"))
        assertEquals("invalid repeat count: `{2,1}`", re("a{2,1}"))
        assertEquals("invalid character class range: `z-a`", re("[z-a]"))
        assertEquals("invalid character class range: `[:foo:]`", re("[[:foo:]]"))
        assertEquals("invalid character class range: `\\p{Foo}`", re("\\p{Foo}"))
        assertEquals("invalid named capture: `(?P<n!>`", re("(?P<n!>x)"))
        assertEquals("invalid named capture: `(?P<>`", re("(?P<>x)"))
        assertEquals("invalid or unsupported Perl syntax: `(?=`", re("(?=x)"))
        assertEquals("invalid or unsupported Perl syntax: `(?P`", re("(?P=n)"))
        assertEquals("invalid or unsupported Perl syntax: `(?i-)`", re("(?i-)"))
        assertEquals("trailing backslash at end of expression: ``", re("a\\"))
    }

    @Test
    fun validRegexps() {
        for (ok in listOf(
            "", "^[a-z]+\\d*$", "(?i)abc", "(?i:a)+", "(?P<name>x)", "(?<name>x)", "a{,3}", "x{2,}", "x{0}", "\\x{1F600}", "\\x41",
            "[\\p{Greek}\\d]", "\\pL", "\\p{^Han}", "\\PL", "\\Qa.b\\E+", "[]a]", "[^]a]", "(?:a|b)*?", "\\b\\B\\A\\z", "[^\\n]", "\\.\\-\\_",
            "()", "(|a)", "a||b", "^*", "\\101", "\\0", "[[:alpha:][:^digit:]]", "[a-]", "{", "a{", "a{1", "a{1,", "\\p{Latin}", "\\p{Letter}",
            "(?s).*", "(?U)a+", "(?m)^x$", "x*?", "[\\x00-\\x{10FFFF}]",
        )) {
            assertNull(ok, GoRegexpSyntax.error(ok))
        }
    }

    @Test
    fun urlErrors() {
        assertEquals("parse \"http://%zz\": invalid URL escape \"%zz\"", GoNetSyntax.urlError("http://%zz"))
        assertEquals("parse \":foo\": missing protocol scheme", GoNetSyntax.urlError(":foo"))
        assertEquals("parse \"http://[::1\": missing ']' in host", GoNetSyntax.urlError("http://[::1"))
        assertEquals("parse \"http://host:port\": invalid port \":port\" after host", GoNetSyntax.urlError("http://host:port"))
        assertEquals("parse \"http://a b.com/\": invalid character \" \" in host name", GoNetSyntax.urlError("http://a b.com/"))
        assertEquals("parse \"1a:b\": first path segment in URL cannot contain colon", GoNetSyntax.urlError("1a:b"))
        assertEquals("parse \"http://x/%zz\": invalid URL escape \"%zz\"", GoNetSyntax.urlError("http://x/%zz"))
        assertEquals("parse \"http://x/%2\": invalid URL escape \"%2\"", GoNetSyntax.urlError("http://x/%2"))
        assertEquals("parse \"http://x/#%zz\": invalid URL escape \"%zz\"", GoNetSyntax.urlError("http://x/#%zz"))
        assertEquals("parse \"http://x\\n\": net/url: invalid control character in URL", GoNetSyntax.urlError("http://x\n"))
        assertEquals("parse \"http://a^b@x\": net/url: invalid userinfo", GoNetSyntax.urlError("http://a^b@x"))
    }

    @Test
    fun validUrls() {
        for (ok in listOf(
            "", "*", "https://example.org/a?b=c#d", "mailto:a@b", "/relative/path", "rel/path", "http://[::1]:80/", "http://user:pa%20ss@host/",
            "http://x/?q=%zz", "http://h:/", "file:///etc/hosts", "http://%C3%A9.com/", "//cdn.example.org/x.js", "a:b/c",
        )) {
            assertNull(ok, GoNetSyntax.urlError(ok))
        }
    }

    @Test
    fun hostPort() {
        for (ok in listOf("", ":8080", "localhost:8080", "localhost:http", "[::1]:80", "0.0.0.0:0", ":https")) assertTrue(ok, GoNetSyntax.validHostPort(ok))
        for (bad in listOf("localhost", "localhost:99999", "a:b:c", "host:", ":-1", "[::1]", "[::1]x:80", ":a_b", ":verylongservicename")) {
            assertFalse(bad, GoNetSyntax.validHostPort(bad))
        }
    }
}
