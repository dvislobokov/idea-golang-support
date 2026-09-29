package io.github.golangsupport

import io.github.golangsupport.lang.GoCompletionOrder
import io.github.golangsupport.lang.GoIdioms
import io.github.golangsupport.lang.GoSnippets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoIdiomsTest {
    /** The suggestion at `<caret>`, which is cut out of [code]. */
    private fun suggest(code: String): String? {
        val text = code.trimIndent().replace("    ", "\t")
        val offset = text.indexOf("<caret>")
        return GoIdioms.suggest(text.replace("<caret>", ""), offset)
    }

    @Test fun errorCheckReturnsTheZeroValuesOfTheFunction() {
        assertEquals(
            "if err != nil {\n\t\treturn nil, err\n\t}",
            suggest(
                """
                package store

                func Load(name string) (*Order, error) {
                    f, err := os.Open(name)
                    <caret>
                }
                """,
            ),
        )
    }

    @Test fun errorCheckByTheShapeOfTheResults() {
        fun returned(signature: String, name: String = "Load", params: String = "") = suggest(
            """
            package main

            func $name($params) $signature {
                err := run()
                <caret>
            }
            """,
        )?.lineSequence()?.elementAt(1)?.trim()

        assertEquals("return err", returned("error"))
        assertEquals("return 0, \"\", false, err", returned("(int, string, bool, error)"))
        assertEquals("return Order{}, nil, err", returned("(Order, io.Reader, error)"))
        assertEquals("return total, err", returned("(total int, err error)"))
        assertEquals("return a, b, err", returned("(a, b int, err error)"))
        assertEquals("return *new(T), err", returned("(T, error)"))
        assertEquals("return nil, err", returned("(func() error, error)"))
        assertEquals("return", returned(""))
        assertEquals("log.Fatal(err)", returned("", name = "main"))
        assertEquals("t.Fatal(err)", returned("", name = "TestLoad", params = "t *testing.T"))
    }

    @Test fun theNameOfTheErrorIsKept() = assertEquals(
        "if parseErr != nil {\n\t\treturn parseErr\n\t}",
        suggest(
            """
            package main

            func f() error {
                n, parseErr := strconv.Atoi(s)
                <caret>
            }
            """,
        ),
    )

    @Test fun whatIsTypedIsTheBeginningOfTheSuggestion() {
        val code = """
            package main

            func f() error {
                err := run()
                if er<caret>
            }
            """
        assertEquals("r != nil {\n\t\treturn err\n\t}", suggest(code))
        assertNull(suggest(code.replace("if er<caret>", "fo<caret>")))
    }

    /** The editor keeps the caret of a blank line in virtual space: the line has no indent of its own, the suggestion brings it. */
    @Test fun aBlankLineWithoutItsIndent() = assertEquals(
        "\tif err != nil {\n\t\treturn err\n\t}",
        GoIdioms.suggest("package main\n\nfunc f() error {\n\terr := run()\n\n}", "package main\n\nfunc f() error {\n\terr := run()\n".length),
    )

    @Test fun nothingWhereItIsNotTheNextLine() {
        // checked already
        assertNull(suggest("package main\n\nfunc f() error {\n    err := run()\n    <caret>\n    if err != nil {\n        return err\n    }\n}"))
        // not an assignment of an error; a comparison; the middle of a line
        assertNull(suggest("package main\n\nfunc f() {\n    x := run()\n    <caret>\n}"))
        assertNull(suggest("package main\n\nfunc f() {\n    ok := err == nil\n    <caret>\n}"))
        assertNull(suggest("package main\n\nfunc f() {\n    err := run()\n    <caret>x()\n}"))
        // an unfinished statement
        assertNull(suggest("package main\n\nfunc f() {\n    err := run(\n    <caret>\n}"))
    }

    @Test fun defers() {
        fun after(statement: String) = suggest("package main\n\nfunc f() {\n    $statement\n    <caret>\n}")
        assertEquals("defer cancel()", after("ctx, cancel := context.WithTimeout(parent, time.Second)"))
        assertNull(after("ctx, _ := context.WithCancel(parent)"))
        assertEquals("defer s.mu.Unlock()", after("s.mu.Lock()"))
        assertEquals("defer mu.RUnlock()", after("mu.RLock()"))
        assertEquals("defer ticker.Stop()", after("ticker := time.NewTicker(time.Second)"))
    }

    @Test fun deferOfWhatWasOpenedOnceItsErrorIsChecked() {
        fun after(opening: String) = suggest(
            """
            package main

            func f() error {
                $opening
                if err != nil {
                    return err
                }
                <caret>
            }
            """,
        )
        assertEquals("defer f.Close()", after("f, err := os.Open(name)"))
        assertEquals("defer resp.Body.Close()", after("resp, err := http.Get(url)"))
        assertEquals("defer resp.Body.Close()", after("resp, err := s.client.Do(req)"))
        assertEquals("defer rows.Close()", after("rows, err := db.QueryContext(ctx, query)"))
        assertEquals("defer tx.Rollback()", after("tx, err := db.BeginTx(ctx, nil)"))
        assertNull(after("n, err := strconv.Atoi(s)"))
        assertNull(after("_, err := os.Open(name)"))
    }

    @Test fun okCheckAfterACommaOkForm() {
        fun after(statement: String, signature: String = "(*Order, error)") = suggest("package main\n\nfunc f() $signature {\n    $statement\n    <caret>\n}")
        assertEquals("if !ok {\n\t\treturn nil, fmt.Errorf(\"unknown %v\", name)\n\t}", after("v, ok := m[name]"))
        assertEquals("if !ok {\n\t\treturn nil, fmt.Errorf(\"unexpected type %T\", x)\n\t}", after("s, ok := x.(string)"))
        assertEquals("if !ok {\n\t\treturn nil, nil\n\t}", after("v, ok := <-ch"))
        assertEquals("if !ok {\n\t\treturn\n\t}", after("v, ok := <-ch", signature = ""))
        assertNull(after("v, ok := lookup(name)"))
        assertNull(after("if v, ok := m[name]; ok {"))
    }

    @Test fun deferDoneInAGoroutineOfAWaitGroup() {
        assertEquals("defer wg.Done()", suggest("package main\n\nfunc f() {\n    wg.Add(1)\n    go func() {\n        <caret>\n    }()\n}"))
        assertNull(suggest("package main\n\nfunc f() {\n    go func() {\n        <caret>\n    }()\n}"))
    }

    @Test fun loopOverTheRowsOnceTheyAreDeferredClosed() {
        val code = """
            package main

            func f() error {
                rows, err := db.Query(q)
                if err != nil {
                    return err
                }
                defer rows.Close()
                <caret>
            }
            """
        assertEquals("for rows.Next() {\n\t\t\n\t}", suggest(code))
        assertNull(suggest(code.replace("db.Query(q)", "os.Open(q)")))
    }

    @Test fun signatures() {
        val (parameters, results) = GoIdioms.splitSignature("[T any](ctx context.Context, items ...T) (map[string][]T, error)")
        assertEquals(listOf("ctx" to "context.Context", "items" to "...T"), parameters.map { it.name to it.type })
        assertEquals(listOf(null to "map[string][]T", null to "error"), results.map { it.name to it.type })
        assertEquals(listOf("error"), GoIdioms.splitSignature("() error").second.map { it.type })
    }

    /** The values offered at `<caret>`, which is cut out of [code]. */
    private fun returnValues(code: String): String? {
        val text = code.trimIndent().replace("    ", "\t")
        return GoIdioms.returnValues(text.replace("<caret>", ""), text.indexOf("<caret>"))
    }

    @Test fun returnValuesInsideAnErrorCheck() {
        val code = """
            package main

            func (s *Server) DeleteUser(ctx context.Context, req interface{}) (interface{}, error) {
                _, err := os.Open("123")
                if err != nil {
                    return ni<caret>
                }
                return nil, nil
            }
            """
        assertEquals("nil, err", returnValues(code))
        assertEquals("nil, err", returnValues(code.replace("return ni<caret>", "return <caret>")))
        assertEquals("nil, openErr", returnValues(code.replace("err :=", "openErr :=").replace("err !=", "openErr !=")))
        // not after `return`, and not in the middle of what is written
        assertNull(returnValues(code.replace("return ni<caret>", "ni<caret>")))
        assertNull(returnValues(code.replace("return ni<caret>", "return <caret>nil, err")))
    }

    @Test fun returnValuesOutsideAnErrorCheck() {
        assertEquals("0, \"\", nil", returnValues("package main\n\nfunc f() (int, string, error) {\n    return <caret>\n}"))
        assertEquals("nil, nil", returnValues("package main\n\nfunc f(p *T) (*T, error) {\n    if p != nil {\n        return <caret>\n    }\n}"))
        // one value is what the server completes
        assertNull(returnValues("package main\n\nfunc f() error {\n    if err != nil {\n        return <caret>\n    }\n}"))
        assertNull(returnValues("package main\n\nfunc f() {\n    return <caret>\n}"))
    }

    @Test fun theSnippetsOfGopls() {
        // what gopls offers for the function `sort.Slice` takes, seen with tools/gopls/completion.py
        assertEquals("func(i, j int) bool {\$0}", GoSnippets.unescape("func(i, j int) bool {\$0\\}"))
        assertEquals("func(r rune) rune {\$0}", GoSnippets.unescape("func(r rune) rune {\$0\\}"))
        // nothing to do with a call and with a name
        assertEquals("Split(\${1:})", GoSnippets.unescape("Split(\${1:})"))
        assertEquals("Client", GoSnippets.unescape("Client"))
        // a backslash of the code itself, and a dollar that is not a variable
        assertEquals("a\\b", GoSnippets.unescape("a\\\\b"))
        assertEquals("cost \\\$5", GoSnippets.unescape("cost \\\$5"))
        // inside a placeholder the brace would end it
        assertEquals("f(\${1:m map[string]struct{\\}})", GoSnippets.unescape("f(\${1:m map[string]struct{\\}})"))
        assertEquals("f(\${1:a}) {\$0}", GoSnippets.unescape("f(\${1:a}) {\$0\\}"))
    }

    @Test fun namesThatBeginWithWhatIsTypedGoFirst() {
        assertEquals("ni", GoCompletionOrder.typed("\treturn ni", 10))
        assertEquals("Op", GoCompletionOrder.typed("os.Op", 5))
        assertEquals("", GoCompletionOrder.typed("return ", 7))
        assertTrue(GoCompletionOrder.priority("ni", "nil") > GoCompletionOrder.priority("ni", "net.IP"))
        assertTrue(GoCompletionOrder.priority("op", "open") > GoCompletionOrder.priority("op", "Open"))
        assertTrue(GoCompletionOrder.priority("op", "Open") > GoCompletionOrder.priority("op", "os.Pipe"))
        assertEquals(GoCompletionOrder.priority("", "nil"), GoCompletionOrder.priority("", "net.IP"), 0.0)
    }
}
