package io.github.golangsupport

import io.github.golangsupport.catalogue.GoSourceScanner
import io.github.golangsupport.lang.GoCompletionOrder
import io.github.golangsupport.lang.GoIdioms
import io.github.golangsupport.lang.GoSnippets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoIdiomsTest {
    /**
     * The function around [offset] as the PSI gives it to [GoIdioms] ([io.github.golangsupport.lang.GoReturnValues.function]): here from
     * its signature as written, read by the scanner of the catalogue, so that the rules are tested without a project.
     */
    private fun function(text: String, offset: Int): GoIdioms.Function? {
        val scanned = GoSourceScanner.scan(text)
        val declaration = scanned.declarations.lastOrNull { it.body != null && offset > it.body!!.startOffset && offset <= it.body!!.endOffset && it.signature != null } ?: return null
        val (parameters, results) = GoIdioms.splitSignature(declaration.signature!!)
        return GoIdioms.Function(declaration.name, parameters, results, declaration.name == "main" && scanned.packageName == "main")
    }

    /** What only the PSI knows is not known here, but the function around the caret. */
    private class Known(override val function: GoIdioms.Function?) : GoIdioms.Types {
        override fun assignsError(lineStart: Int): Boolean? = null
        override fun hasMethod(lineStart: Int, receiver: String, method: String, returnsError: Boolean): Boolean? = null
        override fun selectCases(lineStart: Int): List<String>? = null
        override fun switchCases(lineStart: Int): List<String>? = null
    }

    private fun suggest(text: String, offset: Int): String? = GoIdioms.suggest(text, offset, types = Known(function(text, offset)))

    /** The suggestion at `<caret>`, which is cut out of [code]. */
    private fun suggest(code: String): String? {
        val text = code.trimIndent().replace("    ", "\t")
        val offset = text.indexOf("<caret>")
        return suggest(text.replace("<caret>", ""), offset)
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
        suggest("package main\n\nfunc f() error {\n\terr := run()\n\n}", "package main\n\nfunc f() error {\n\terr := run()\n".length),
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

    @Test fun theBodyOfAnErrorCheckTypedByHand() {
        val code = """
            package store

            func Load(name string) (*Order, error) {
                f, err := os.Open(name)
                if err != nil {
                    <caret>
                }
                return parse(f)
            }
            """
        assertEquals("return nil, err", suggest(code))
        assertEquals("turn nil, err", suggest(code.replace("<caret>", "re<caret>")))
        // the error of the `if` itself
        assertEquals("return nil, err", suggest(code.replace("f, err := os.Open(name)\n", "").replace("if err != nil {", "if err := check(name); err != nil {")))
        // a line that has lost its indent gets the one of the body
        assertEquals("\t\treturn nil, err", suggest(code.replace("        <caret>", "<caret>")))
        // a line added to a body that has something in it is not the whole of the body
        assertNull(suggest(code.replace("<caret>\n", "<caret>\n        log.Println(err)\n")))
        assertNull(suggest(code.replace("if err != nil {", "if f != nil {")))
    }

    @Test fun errorsAreWrappedWhereTheFileWrapsThem() {
        val code = """
            package store

            func Save(o *Order) error {
                data, err := json.Marshal(o)
                if err != nil {
                    return fmt.Errorf("marshal order: %w", err)
                }
                if err := os.WriteFile(path, data, 0o600); err != nil {
                    return fmt.Errorf("write order: %w", err)
                }
                return nil
            }

            func Load(name string) (*Order, error) {
                data, err := os.ReadFile(name)
                <caret>
            }
            """
        assertTrue(GoIdioms.wrapsErrors(code))
        assertEquals("if err != nil {\n\t\treturn nil, fmt.Errorf(\"read file: %w\", err)\n\t}", suggest(code))
        assertEquals("if err != nil {\n\t\treturn nil, fmt.Errorf(\"find user: %w\", err)\n\t}", suggest(code.replace("os.ReadFile(name)", "s.repo.FindUser(ctx, id)")))
        // a test says where it is by itself
        val test = code.replace("func Load(name string) (*Order, error) {", "func TestLoad(t *testing.T) {")
        assertEquals("if err != nil {\n\t\tt.Fatal(err)\n\t}", suggest(test))
        // one wrapped error among plain ones is not a habit
        val plain = code.replace("return fmt.Errorf(\"marshal order: %w\", err)", "return err").replace("return fmt.Errorf(\"write order: %w\", err)", "return err")
        assertFalse(GoIdioms.wrapsErrors(plain))
        assertEquals("if err != nil {\n\t\treturn nil, err\n\t}", suggest(plain))
    }

    @Test fun aHandlerAnswersAndReturns() {
        val handler = """
            package api

            func (s *Server) create(w http.ResponseWriter, r *http.Request) {
                order, err := s.store.Create(r.Context())
                <caret>
            }
            """
        assertEquals("if err != nil {\n\t\thttp.Error(w, err.Error(), http.StatusInternalServerError)\n\t\treturn\n\t}", suggest(handler))
        // what the caller has sent cannot be read: the request is bad
        assertEquals(
            "if err != nil {\n\t\thttp.Error(rw, err.Error(), http.StatusBadRequest)\n\t\treturn\n\t}",
            suggest(handler.replace("w http.ResponseWriter", "rw http.ResponseWriter").replace("order, err := s.store.Create(r.Context())", "err := json.NewDecoder(r.Body).Decode(&order)")),
        )
        // the body of a check typed by hand
        val byHand = "package api\n\nfunc create(w http.ResponseWriter, r *http.Request) {\n    order, err := load(r)\n    if err != nil {\n        <caret>\n    }\n    _ = order\n}"
        assertEquals("http.Error(w, err.Error(), http.StatusInternalServerError)\n\t\treturn", suggest(byHand))
        assertEquals(
            "if err != nil {\n\t\tc.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{\"error\": err.Error()})\n\t\treturn\n\t}",
            suggest(handler.replace("(w http.ResponseWriter, r *http.Request)", "(c *gin.Context)").replace("r.Context()", "c")),
        )
        // a function that takes a writer and returns an error returns it
        assertEquals("if err != nil {\n\t\treturn err\n\t}", suggest(handler.replace("r *http.Request) {", "r *http.Request) error {")))
    }

    @Test fun aServiceOfGrpcAnswersWithAStatus() {
        val service = """
            package api

            func (s *Server) Get(ctx context.Context, req *pb.GetRequest) (*pb.Order, error) {
                if req.Id == "" {
                    return nil, status.Error(codes.InvalidArgument, "id is required")
                }
                order, err := s.store.Find(ctx, req.Id)
                if err != nil {
                    return nil, status.Errorf(codes.Internal, "find: %v", err)
                }
                return order, nil
            }

            func (s *Server) List(ctx context.Context, req *pb.ListRequest) (*pb.Orders, error) {
                orders, err := s.store.ListOrders(ctx)
                <caret>
            }
            """
        assertTrue(GoIdioms.answersWithStatus(service))
        assertEquals("if err != nil {\n\t\treturn nil, status.Errorf(codes.Internal, \"list orders: %v\", err)\n\t}", suggest(service))
        assertEquals(
            "if err != nil {\n\t\treturn nil, status.Errorf(codes.InvalidArgument, \"parse filter: %v\", err)\n\t}",
            suggest(service.replace("orders, err := s.store.ListOrders(ctx)", "filter, err := ParseFilter(req.Filter)")),
        )
        // a file that returns its errors as they are is not one of a service
        assertFalse(GoIdioms.answersWithStatus(service.replace("status.Errorf(codes.Internal, \"find: %v\", err)", "err")))
    }

    @Test fun theReceiverOfAMethod() {
        val code = """
            package api

            type Server struct {
                store Store
            }

            func (srv *Server) Start() error { return nil }

            <caret>
            """
        assertEquals("srv *Server) ", suggest(code.replace("<caret>", "func (<caret>")))
        // the bracket the editor has closed by itself
        assertEquals("srv *Server", suggest(code.replace("<caret>", "func (<caret>)")))
        assertEquals("v *Server) ", suggest(code.replace("<caret>", "func (sr<caret>")))
        assertNull(suggest(code.replace("<caret>", "func (x<caret>")))
        assertNull(suggest(code.replace("<caret>", "func <caret>")))
        // no method yet: the first letter of the type, by pointer
        assertEquals("s *Server) ", suggest(code.replace("func (srv *Server) Start() error { return nil }\n", "").replace("<caret>", "func (<caret>")))
        // by value, as the methods of the type are
        assertEquals("o Order) ", suggest("package store\n\ntype Order struct{}\n\nfunc (o Order) Total() int { return 0 }\n\nfunc (<caret>"))
        // the type that is nearest above, and not an interface
        val server = "package api\n\ntype Server struct {\n    store Store\n}\n\nfunc (srv *Server) Start() error { return nil }\n\n"
        assertEquals("c *Client) ", suggest(server + "type Client struct{}\n\nfunc (<caret>"))
        assertEquals("srv *Server) ", suggest(server + "type Doer interface {\n    Do()\n}\n\nfunc (<caret>"))
        assertNull(suggest("package store\n\ntype Priced interface {\n    Total() int\n}\n\nfunc (<caret>"))
        // inside a function it is a function literal
        assertNull(suggest(server + "func f() {\n    go func (<caret>\n}"))
    }

    @Test fun theTagOfAFieldAsTheFieldsAboveHaveIt() {
        val snake = """
            package store

            type User struct {
                ID        int    `json:"id" db:"id"`
                FirstName string `json:"first_name,omitempty" db:"first_name" validate:"required"`
                <caret>
            }
            """
        assertEquals("`json:\"last_name,omitempty\" db:\"last_name\"`", suggest(snake.replace("<caret>", "LastName string <caret>")))
        // a type that is whole as it is typed needs no space after it
        assertEquals(" `json:\"last_name,omitempty\" db:\"last_name\"`", suggest(snake.replace("<caret>", "LastName string<caret>")))
        assertEquals("`json:\"created_at,omitempty\" db:\"created_at\"`", suggest(snake.replace("<caret>", "CreatedAt *timestamppb.Timestamp <caret>")))
        assertNull(suggest(snake.replace("<caret>", "LastName stri<caret>")))
        assertNull(suggest(snake.replace("<caret>", "lastName string <caret>")))
        assertNull(suggest(snake.replace("<caret>", "LastName <caret>")))

        val camel = """
            package api

            type Request struct {
                UserName string `json:"userName"`
                <caret>
            }
            """
        assertEquals("`json:\"orderId\"`", suggest(camel.replace("<caret>", "OrderId int64 <caret>")))
        // no tags above, or not a struct
        assertNull(suggest("package api\n\ntype Request struct {\n    UserName string\n    OrderId int64 <caret>\n}"))
        assertNull(suggest("package api\n\nfunc f() {\n    Name string <caret>\n}"))
    }

    @Test fun whatHasFailedInWords() {
        assertEquals("open", GoIdioms.failure("f, err := os.Open(name)"))
        assertEquals("read file", GoIdioms.failure("data, err := os.ReadFile(name)"))
        assertEquals("new request with context", GoIdioms.failure("req, err = http.NewRequestWithContext(ctx, m, u, nil)"))
        assertEquals("parse url", GoIdioms.failure("u, err := url.ParseURL(raw)"))
        assertEquals("find user", GoIdioms.failure("user, err := s.repo.FindUser(ctx, id)"))
        assertEquals("check", GoIdioms.failure("err := check(name)"))
        assertNull(GoIdioms.failure("err = lastError"))
        assertNull(GoIdioms.failure(null))
    }

    @Test fun defersOfWhatIsStartedAndMade() {
        assertEquals("defer span.End()", suggest("package main\n\nfunc f(ctx context.Context) {\n    ctx, span := tracer.Start(ctx, \"load\")\n    <caret>\n}"))
        assertEquals("defer childSpan.End()", suggest("package main\n\nfunc f(ctx context.Context) {\n    _, childSpan := otel.Tracer(\"x\").Start(ctx, \"load\")\n    <caret>\n}"))
        // a `Start` of something that is not a span
        assertNull(suggest("package main\n\nfunc f() {\n    a, b := server.Start(ctx)\n    <caret>\n}"))
        assertEquals("defer stop()", suggest("package main\n\nfunc main() {\n    ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)\n    <caret>\n}"))
        assertEquals("defer signal.Stop(ch)", suggest("package main\n\nfunc main() {\n    signal.Notify(ch, os.Interrupt)\n    <caret>\n}"))
        assertEquals("defer close(results)", suggest("package main\n\nfunc f() {\n    results := make(chan int, 8)\n    go func() {\n        <caret>\n    }()\n}"))
        // the group of the goroutine goes first
        assertEquals("defer wg.Done()", suggest("package main\n\nfunc f() {\n    results := make(chan int)\n    wg.Add(1)\n    go func() {\n        <caret>\n    }()\n}"))
        val directory = """
            package main

            func f() error {
                dir, err := os.MkdirTemp("", "x")
                if err != nil {
                    return err
                }
                <caret>
            }
            """
        assertEquals("defer os.RemoveAll(dir)", suggest(directory))
    }

    @Test fun theLoopOfAScannerAndTheErrorAfterALoop() {
        assertEquals("for scanner.Scan() {\n\t\t\n\t}", suggest("package main\n\nfunc f(r io.Reader) error {\n    scanner := bufio.NewScanner(r)\n    <caret>\n}"))
        val lines = """
            package main

            func count(r io.Reader) (int, error) {
                scanner := bufio.NewScanner(r)
                n := 0
                for scanner.Scan() {
                    if scanner.Text() != "" {
                        n++
                    }
                }
                <caret>
            }
            """
        assertEquals("if err := scanner.Err(); err != nil {\n\t\treturn 0, err\n\t}", suggest(lines))
        assertEquals("if err := rows.Err(); err != nil {\n\t\treturn 0, err\n\t}", suggest(lines.replace("scanner.Scan()", "rows.Next()")))
        // not after any loop
        assertNull(suggest(lines.replace("for scanner.Scan() {", "for i := range items {")))
    }

    @Test fun signatures() {
        val (parameters, results) = GoIdioms.splitSignature("[T any](ctx context.Context, items ...T) (map[string][]T, error)")
        assertEquals(listOf("ctx" to "context.Context", "items" to "...T"), parameters.map { it.name to it.type })
        assertEquals(listOf(null to "map[string][]T", null to "error"), results.map { it.name to it.type })
        assertEquals(listOf("error"), GoIdioms.splitSignature("() error").second.map { it.type })
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
    /** The `err` live template fills its statement with this: the function around the caret decides what leaves it with the error. */
    @Test fun errorReturnForTheTemplate() {
        val source = "package p\n\nfunc load(name string) ([]byte, error) {\n\tf, err := open(name)\n\t\n}\n\nfunc TestLoad(t *testing.T) {\n\t\n}\n\nfunc plain() {\n\t\n}\n"
        assertEquals("return nil, err", GoIdioms.returnStatement("err", function(source, source.indexOf("open(name)\n\t") + "open(name)\n\t".length)))
        assertEquals("t.Fatal(err)", GoIdioms.returnStatement("err", function(source, source.indexOf("testing.T) {\n\t") + "testing.T) {\n\t".length)))
        assertEquals("return", GoIdioms.returnStatement("err", function(source, source.indexOf("plain() {\n\t") + "plain() {\n\t".length)))
        assertEquals("the function is not known", "return err", GoIdioms.returnStatement("err", null))
    }
}
