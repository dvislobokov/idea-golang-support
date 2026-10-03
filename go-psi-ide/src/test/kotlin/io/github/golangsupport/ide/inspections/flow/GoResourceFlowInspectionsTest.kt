package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalInspectionTool
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/**
 * The resource and concurrency flow checks (wave 4, second batch): unclosed response bodies and rows, unreleased locks, sends after
 * `close`, `WaitGroup.Add` in the goroutine and dropped contexts; each with the idioms that must stay quiet and its quick fix.
 */
class GoResourceFlowInspectionsTest : GoSemanticIdeTestBase() {

    private fun doHighlight(text: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools)
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        myFixture.checkHighlighting(true, false, true)
    }

    private fun doFix(before: String, fix: String, after: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools)
        myFixture.configureByText("a.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == fix } ?: error("$fix not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    private val helpers = """

        func use(...any) {}
    """.trimIndent()

    // --- response body ---

    fun testBodyNotClosed() = doHighlight(
        """
        package p

        import (
        	"encoding/json"
        	"errors"
        	"io"
        	"net/http"
        )

        func statusPath(u string) error {
        	<warning descr="response body must be closed">resp</warning>, err := http.Get(u)
        	if err != nil {
        		return err
        	}
        	if resp.StatusCode != http.StatusOK {
        		return errors.New(resp.Status)
        	}
        	defer resp.Body.Close()
        	return nil
        }

        func neverClosed(c *http.Client, req *http.Request) int {
        	<warning descr="response body must be closed">resp</warning>, err := c.Do(req)
        	if err != nil {
        		return 0
        	}
        	return resp.StatusCode
        }

        func deferred(u string) ([]byte, error) {
        	resp, err := http.Get(u)
        	if err != nil {
        		return nil, err
        	}
        	defer resp.Body.Close()
        	return io.ReadAll(resp.Body)
        }

        func errorPathOnly(u string) {
        	resp, err := http.Get(u)
        	if err != nil {
        		return
        	}
        	resp.Body.Close()
        }

        func returned(u string) (*http.Response, error) {
        	resp, err := http.Post(u, "text/plain", nil)
        	if err != nil {
        		return nil, err
        	}
        	return resp, nil
        }

        func helper(u string) error {
        	resp, err := http.DefaultClient.Get(u)
        	if err != nil {
        		return err
        	}
        	return drain(resp)
        }

        func bodyPassed(u string, v any) error {
        	resp, err := http.Get(u)
        	if err != nil {
        		return err
        	}
        	return json.NewDecoder(resp.Body).Decode(v)
        }

        func errEqualsNil(u string) {
        	if resp, err := http.Head(u); err == nil {
        		resp.Body.Close()
        	}
        }

        func panics(u string) {
        	resp, err := http.Get(u)
        	if err != nil {
        		panic(err)
        	}
        	if resp.StatusCode != 200 {
        		panic(resp.Status)
        	}
        	resp.Body.Close()
        }

        func captured(u string) error {
        	resp, err := http.Get(u)
        	if err != nil {
        		return err
        	}
        	defer func() { _ = resp.Body.Close() }()
        	return nil
        }

        func nilChecked(u string) error {
        	resp, err := http.Get(u)
        	if resp != nil {
        		defer resp.Body.Close()
        	}
        	return err
        }

        func loop(us []string) {
        	for _, u := range us {
        		resp, err := http.Get(u)
        		if err != nil {
        			continue
        		}
        		resp.Body.Close()
        	}
        }

        func drain(r *http.Response) error { return r.Body.Close() }
        """ + helpers,
        GoBodyNotClosedInspection(),
    )

    fun testAddDeferBodyCloseFix() = doFix(
        """
        package p

        import "net/http"

        func a(u string) (int, error) {
        	re<caret>sp, err := http.Get(u)
        	if err != nil {
        		return 0, err
        	}
        	return resp.StatusCode, nil
        }
        """,
        "Add defer resp.Body.Close()",
        """
        package p

        import "net/http"

        func a(u string) (int, error) {
        	resp, err := http.Get(u)
        	if err != nil {
        		return 0, err
        	}
        	defer resp.Body.Close()
        	return resp.StatusCode, nil
        }
        """,
        GoBodyNotClosedInspection(),
    )

    // --- rows ---

    fun testRowsNotClosed() = doHighlight(
        """
        package p

        import (
        	"context"
        	"database/sql"
        )

        func earlyReturn(db *sql.DB) error {
        	<warning descr="rows must be closed">rows</warning>, err := db.Query("q")
        	if err != nil {
        		return err
        	}
        	for rows.Next() {
        		if err := rows.Scan(); err != nil {
        			return err
        		}
        	}
        	rows.Close()
        	return rows.Err()
        }

        func errNotChecked(ctx context.Context, tx *sql.Tx) error {
        	rows, err := tx.QueryContext(ctx, "q")
        	if err != nil {
        		return err
        	}
        	defer rows.Close()
        	for <weak_warning descr="rows.Err() is not checked after the loop">rows.Next()</weak_warning> {
        		use(rows.Scan())
        	}
        	return nil
        }

        func idiom(db *sql.DB) error {
        	rows, err := db.Query("q")
        	if err != nil {
        		return err
        	}
        	defer rows.Close()
        	for rows.Next() {
        		if err := rows.Scan(); err != nil {
        			return err
        		}
        	}
        	return rows.Err()
        }

        func returned(st *sql.Stmt) (*sql.Rows, error) {
        	rows, err := st.Query()
        	if err != nil {
        		return nil, err
        	}
        	return rows, nil
        }

        func helper(c *sql.Conn, ctx context.Context) error {
        	rows, err := c.QueryContext(ctx, "q")
        	if err != nil {
        		return err
        	}
        	return consume(rows)
        }

        func consume(r *sql.Rows) error {
        	defer r.Close()
        	return r.Err()
        }
        """ + helpers,
        GoRowsNotClosedInspection(),
    )

    fun testAddDeferRowsCloseFix() = doFix(
        """
        package p

        import "database/sql"

        func a(db *sql.DB) error {
        	ro<caret>ws, err := db.Query("q")
        	if err != nil {
        		return err
        	}
        	return rows.Err()
        }
        """,
        "Add defer rows.Close()",
        """
        package p

        import "database/sql"

        func a(db *sql.DB) error {
        	rows, err := db.Query("q")
        	if err != nil {
        		return err
        	}
        	defer rows.Close()
        	return rows.Err()
        }
        """,
        GoRowsNotClosedInspection(),
    )

    // --- locks ---

    fun testLockNotReleased() = doHighlight(
        """
        package p

        import "sync"

        type S struct {
        	mu sync.Mutex
        	rw sync.RWMutex
        	n  int
        }

        func (s *S) early(c bool) int {
        	<warning descr="s.mu.Lock() is not released on all paths">s.mu.Lock()</warning>
        	if c {
        		return 0
        	}
        	s.mu.Unlock()
        	return 1
        }

        func (s *S) read(c bool) int {
        	<warning descr="s.rw.RLock() is not released on all paths">s.rw.RLock()</warning>
        	if c {
        		return s.n
        	}
        	s.rw.RUnlock()
        	return 0
        }

        func param(mu *sync.Mutex, xs []int) {
        	for _, x := range xs {
        		<warning descr="mu.Lock() is not released on all paths">mu.Lock()</warning>
        		if x == 0 {
        			continue
        		}
        		mu.Unlock()
        	}
        }

        func (s *S) deferred() int {
        	s.mu.Lock()
        	defer s.mu.Unlock()
        	return s.n
        }

        func (s *S) loop(xs []int) {
        	for _, x := range xs {
        		s.mu.Lock()
        		s.n += x
        		s.mu.Unlock()
        	}
        }

        func (s *S) branches(c bool) int {
        	s.mu.Lock()
        	if c {
        		s.mu.Unlock()
        		return 1
        	}
        	s.mu.Unlock()
        	return 0
        }

        func (s *S) lockState() {
        	s.mu.Lock()
        	if s.n > 0 {
        		return
        	}
        	s.mu.Unlock()
        }

        func (s *S) handOff() func() {
        	s.mu.Lock()
        	return s.mu.Unlock
        }

        func (s *S) panics(c bool) {
        	s.mu.Lock()
        	if c {
        		panic("x")
        	}
        	s.mu.Unlock()
        }

        func (s *S) noUnlockAtAll() {
        	s.mu.Lock()
        	s.n++
        }

        func (s *S) guarded(c bool) {
        	if c {
        		s.mu.Lock()
        	}
        	s.n++
        	if c {
        		s.mu.Unlock()
        	}
        }

        func (s *S) passed(c bool) {
        	s.mu.Lock()
        	if c {
        		unlockIt(&s.mu)
        		return
        	}
        	s.mu.Unlock()
        }

        func (s *S) deferredLiteral() int {
        	s.mu.Lock()
        	defer func() {
        		s.mu.Unlock()
        	}()
        	return s.n
        }

        func (s *S) switched(k int) error {
        	s.mu.Lock()
        	switch k {
        	case 0:
        		s.mu.Unlock()
        		return nil
        	default:
        		s.n = k
        	}
        	s.mu.Unlock()
        	return nil
        }

        func localMutex() int {
        	var mu sync.Mutex
        	mu.Lock()
        	defer mu.Unlock()
        	return 1
        }

        func unlockIt(mu *sync.Mutex) { mu.Unlock() }
        """,
        GoLockNotReleasedInspection(),
    )

    // --- channels ---

    fun testLockQuietOnReacquireAndFlag() = doHighlight(
        """
        package p

        import "sync"

        type S struct {
        	mu sync.Mutex
        	n  int
        }

        // net/http Server.Close: a deferred Unlock releases the lock taken again after a temporary Unlock
        func (s *S) reacquire(c bool) int {
        	s.mu.Lock()
        	defer s.mu.Unlock()
        	s.mu.Unlock()
        	use(s.n)
        	s.mu.Lock()
        	if c {
        		return 0
        	}
        	return 1
        }

        // x/term handleKey: called with the lock held, it drops it around a callback and takes it back
        func (s *S) callback(c bool) {
        	s.mu.Unlock()
        	use(s.n)
        	s.mu.Lock()
        	if c {
        		return
        	}
        	s.n++
        }

        // testing.go frameSkip: a flag remembers that the lock is held
        func (s *S) flagged(xs []int) int {
        	held := false
        	for _, x := range xs {
        		if x > 0 {
        			held = true
        			s.mu.Lock()
        			continue
        		}
        		if held {
        			s.mu.Unlock()
        		}
        		return x
        	}
        	return 0
        }
        """.trimIndent() + "\n" + helpers,
        GoLockNotReleasedInspection(),
    )

    fun testSendAfterClose() = doHighlight(
        """
        package p

        func twice(ch chan int) {
        	close(ch)
        	<warning descr="channel ch closed twice">close(ch)</warning>
        }

        func send(ch chan int, c bool) {
        	close(ch)
        	if c {
        		<warning descr="send on closed channel ch">ch <- 1</warning>
        	}
        }

        func selected(ch chan int) {
        	go func() {
        		for range ch {
        		}
        	}()
        	close(ch)
        	select {
        	case <warning descr="send on closed channel ch">ch <- 1</warning>:
        	default:
        	}
        }

        func branches(ch chan int, c bool) {
        	if c {
        		close(ch)
        	} else {
        		ch <- 1
        	}
        }

        func otherChannel(ch, other chan int) {
        	close(ch)
        	select {
        	case other <- 1:
        	default:
        	}
        }

        func reassigned(ch chan int) {
        	close(ch)
        	ch = make(chan int, 1)
        	ch <- 1
        }

        func flag(ch chan int, c bool) {
        	closed := false
        	if c {
        		close(ch)
        		closed = true
        	}
        	if !closed {
        		close(ch)
        	}
        }

        func loop(chs []chan int) {
        	for _, ch := range chs {
        		close(ch)
        	}
        }

        func closedInLoop(ch chan int, xs []int) {
        	for _, x := range xs {
        		if x == 0 {
        			close(ch)
        			return
        		}
        		ch <- x
        	}
        }

        func deferred(ch chan int) {
        	defer close(ch)
        	ch <- 1
        }
        """,
        GoSendAfterCloseInspection(),
    )

    // --- WaitGroup ---

    fun testWaitGroupAddInGoroutine() = doHighlight(
        """
        package p

        import "sync"

        func bad(wg *sync.WaitGroup) {
        	go func() {
        		<warning descr="wg.Add called inside the goroutine; call it before the go statement">wg.Add(1)</warning>
        		defer wg.Done()
        	}()
        	wg.Wait()
        }

        func good(wg *sync.WaitGroup) {
        	wg.Add(1)
        	go func() {
        		defer wg.Done()
        	}()
        	wg.Wait()
        }

        func waitsItself() {
        	go func() {
        		var wg sync.WaitGroup
        		wg.Add(1)
        		go func() { wg.Done() }()
        		wg.Wait()
        	}()
        }

        func producer(wg *sync.WaitGroup, xs []int) {
        	go func() {
        		for range xs {
        			wg.Add(1)
        			go func() { wg.Done() }()
        		}
        	}()
        }

        type counter struct{}

        func (counter) Add(int) {}

        func notWaitGroup(c counter) {
        	go func() {
        		c.Add(1)
        	}()
        }
        """,
        GoWaitGroupAddInGoroutineInspection(),
    )

    fun testMoveAddFix() = doFix(
        """
        package p

        import "sync"

        func bad(wg *sync.WaitGroup) {
        	go func() {
        		wg.A<caret>dd(1)
        		defer wg.Done()
        	}()
        	wg.Wait()
        }
        """,
        "Move Add before the go statement",
        """
        package p

        import "sync"

        func bad(wg *sync.WaitGroup) {
        	wg.Add(1)
        	go func() {
        		defer wg.Done()
        	}()
        	wg.Wait()
        }
        """,
        GoWaitGroupAddInGoroutineInspection(),
    )

    fun testNoMoveAddFixWhenArgumentIsLocal() {
        myFixture.enableInspections(GoWaitGroupAddInGoroutineInspection())
        myFixture.configureByText(
            "a.go",
            """
            package p

            import "sync"

            func bad(wg *sync.WaitGroup) {
            	go func() {
            		n := 2
            		wg.A<caret>dd(n)
            		defer wg.Done()
            		defer wg.Done()
            	}()
            }
            """.trimIndent() + "\n",
        )
        assertTrue(myFixture.availableIntentions.none { it.text == "Move Add before the go statement" })
    }

    // --- context ---

    fun testContextNotPropagated() = doHighlight(
        """
        package p

        import "context"

        func call(ctx context.Context) error { return ctx.Err() }

        // a parameter of the innermost function: GoContextPlacement reports it
        func param(ctx context.Context) error {
        	return call(context.Background())
        }

        func localContext() error {
        	c, cancel := context.WithCancel(context.Background())
        	defer cancel()
        	return call(<weak_warning descr="use c instead of context.TODO()">context.TODO()</weak_warning>)
        }

        func closure(ctx context.Context) func() error {
        	return func() error { return call(<weak_warning descr="use ctx instead of context.Background()">context.Background()</weak_warning>) }
        }

        func none() error {
        	return call(context.Background())
        }

        func goroutine(ctx context.Context) {
        	go func() { _ = call(context.Background()) }()
        	_ = ctx
        }

        func deferred(ctx context.Context) {
        	defer call(context.Background())
        	_ = ctx
        }

        func laterLocal() error {
        	err := call(context.Background())
        	ctx := context.TODO()
        	_ = ctx
        	return err
        }

        func innerScope(c bool) error {
        	if c {
        		ctx := context.TODO()
        		_ = ctx
        	}
        	return call(context.Background())
        }

        func main() {
        	ctx := context.TODO()
        	_ = call(ctx)
        	_ = call(context.Background())
        }
        """,
        GoContextNotPropagatedInspection(),
    )

    fun testUseContextFix() = doFix(
        """
        package p

        import "context"

        func call(ctx context.Context) error { return ctx.Err() }

        func withLocal() error {
        	ctx, cancel := context.WithCancel(context.TODO())
        	defer cancel()
        	return call(context.Back<caret>ground())
        }
        """,
        "Use ctx",
        """
        package p

        import "context"

        func call(ctx context.Context) error { return ctx.Err() }

        func withLocal() error {
        	ctx, cancel := context.WithCancel(context.TODO())
        	defer cancel()
        	return call(ctx)
        }
        """,
        GoContextNotPropagatedInspection(),
    )
}
