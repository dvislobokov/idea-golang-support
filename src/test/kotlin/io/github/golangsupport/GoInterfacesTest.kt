package io.github.golangsupport

import com.intellij.openapi.util.TextRange
import io.github.golangsupport.lang.GoImport
import io.github.golangsupport.lang.GoInterfaceMethod
import io.github.golangsupport.lang.GoInterfaceOrigin
import io.github.golangsupport.lang.GoInterfaces
import org.junit.Assert.assertEquals
import org.junit.Test

class GoInterfacesTest {
    @Test fun bodyWithCommentsEmbeddingAndMultilineSignatures() {
        val body = GoInterfaces.parseBody(
            """
            {
                // Handle serves one request.
                Handle(
                    ctx context.Context, // the request context
                    w ResponseWriter,
                ) error
                io.Reader
                Closer /* of the stream */
                error
                any
                ~int | ~string
                Name() string; Len() int
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf("Handle" to "(ctx context.Context, w ResponseWriter) error", "Error" to "() string", "Name" to "() string", "Len" to "() int"),
            body.methods,
        )
        assertEquals(listOf("io.Reader", "Closer"), body.embedded)
    }

    private fun import(path: String, alias: String? = null) = GoImport(path, alias, TextRange.EMPTY_RANGE)
    private val http = GoInterfaceOrigin("net/http", "http", setOf("ResponseWriter", "Request", "Handler"), listOf(import("context"), import("net/url")))

    @Test fun typesOfAnotherPackageAreQualifiedAndImported() {
        val method = GoInterfaceMethod("ServeHTTP", "(ResponseWriter, *Request) (n int, err error)", http)
        val rewritten = GoInterfaces.rewrite(method, "example.com/app", emptyList())
        assertEquals("(http.ResponseWriter, *http.Request) (n int, err error)", rewritten.signature)
        assertEquals(listOf("net/http"), rewritten.imports)
    }

    @Test fun packagesOfTheInterfaceFileAreCalledAsTheTargetImportsThem() {
        val method = GoInterfaceMethod("Do", "(ctx context.Context, u *url.URL, h Handler) error", http)
        val rewritten = GoInterfaces.rewrite(method, "example.com/app", listOf(import("context", "ctx"), import("net/http", "web")))
        // the alias of the target file, `web.Handler`; url is not imported there yet
        assertEquals("(ctx ctx.Context, u *url.URL, h web.Handler) error", rewritten.signature)
        assertEquals(listOf("net/url"), rewritten.imports)
    }

    @Test fun samePackageKeepsBareNamesAndDropsItsOwnQualifier() {
        val store = GoInterfaceOrigin("example.com/app/store", "store", setOf("Item"), listOf(import("example.com/app"), import("io")))
        val method = GoInterfaceMethod("Put", "(item Item, target app.Target, r io.Reader) error", store)
        val rewritten = GoInterfaces.rewrite(method, "example.com/app", listOf(import("io")))
        assertEquals("(item store.Item, target Target, r io.Reader) error", rewritten.signature)
        assertEquals(listOf("example.com/app/store"), rewritten.imports)
        assertEquals("(item Item, target app.Target, r io.Reader) error", GoInterfaces.rewrite(method, "example.com/app/store", emptyList()).signature)
    }

    /** Regression: the dots of a variadic parameter hid the type after them from both qualifications. */
    @Test fun variadicParametersAreQualifiedToo() {
        val method = GoInterfaceMethod("Serve", "(hs ...Handler, us ...url.URL)", http)
        val rewritten = GoInterfaces.rewrite(method, "example.com/app", listOf(import("net/url", "u")))
        assertEquals("(hs ...http.Handler, us ...u.URL)", rewritten.signature)
        assertEquals(listOf("net/http"), rewritten.imports)
    }
}
