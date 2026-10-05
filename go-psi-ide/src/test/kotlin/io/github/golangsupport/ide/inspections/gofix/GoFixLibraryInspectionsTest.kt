package io.github.golangsupport.ide.inspections.gofix

import com.intellij.codeInspection.LocalInspectionTool

/** Search loops → `slices.Contains` / `slices.Index` (GoFixSlicesContains). */
class GoFixSlicesContainsInspectionTest : GoFixTestBase() {
    override fun inspection(): LocalInspectionTool = GoFixSlicesContainsInspection()

    private val contains = "Loop can be simplified using slices.Contains"

    fun testReturnTrueReturnFalse() = doTest(
        """
        package p

        func has(names []string, name string) bool {
            ${warn(contains, "for")} _, n := range names {
                if n == name {
                    return true
                }
            }
            return false
        }
        """,
        "Replace loop by call to slices.Contains",
        """
        package p

        import "slices"

        func has(names []string, name string) bool {
            return slices.Contains(names, name)
        }
        """,
    )

    fun testNegatedPair() = doTest(
        """
        package p

        import "slices"

        func missing(xs []int, x int) bool {
            ${warn(contains, "for")} _, v := range xs {
                if x == v {
                    return false
                }
            }
            return true
        }

        var _ = slices.Max[[]int]
        """,
        "Replace loop by call to slices.Contains",
        """
        package p

        import "slices"

        func missing(xs []int, x int) bool {
            return !slices.Contains(xs, x)
        }

        var _ = slices.Max[[]int]
        """,
    )

    fun testIndex() = doTest(
        """
        package p

        func find(xs []string, x string) int {
            ${warn("Loop can be simplified using slices.Index", "for")} i, v := range xs {
                if v == x {
                    return i
                }
            }
            return -1
        }
        """,
        "Replace loop by call to slices.Index",
        """
        package p

        import "slices"

        func find(xs []string, x string) int {
            return slices.Index(xs, x)
        }
        """,
    )

    fun testBreakForm() = doTest(
        """
        package p

        func f(xs []int, x int) bool {
            found := false
            ${warn(contains, "for")} _, v := range xs {
                if v == x {
                    // remember it
                    found = true
                    break
                }
            }
            return found
        }
        """,
        "Replace loop by call to slices.Contains",
        """
        package p

        import "slices"

        func f(xs []int, x int) bool {
            found := false
            if slices.Contains(xs, x) {
                // remember it
                found = true
            }
            return found
        }
        """,
    )

    fun testOtherLoopsStayQuiet() = highlight(
        """
        package p

        func g(int) int { return 0 }

        func f(xs []int, arr [3]int, m map[int]int, x int) (int, bool) {
            for _, v := range xs {
                if v == g(x) {
                    return 0, true
                }
            }
            for _, v := range arr {
                if v == x {
                    return 0, true
                }
            }
            for _, v := range m {
                if v == x {
                    return 0, true
                }
            }
            for i, v := range xs {
                if v == x {
                    return i, true
                }
            }
            for _, v := range xs {
                if v > x {
                    return 0, true
                }
            }
            for _, v := range xs {
                if v == x {
                    x = v
                    break
                }
            }
            for _, v := range xs {
                if v == x {
                    return v, true
                }
            }
            return 0, false
        }

        func slicesShadowed(xs []int, x int) bool {
            slices := 1
            _ = slices
            for _, v := range xs {
                if v == x {
                    return true
                }
            }
            return false
        }
        """
    )
}

/** `sort.Slice` with `<` → `slices.Sort` (GoFixSlicesSort). */
class GoFixSlicesSortInspectionTest : GoFixTestBase() {
    override fun inspection(): LocalInspectionTool = GoFixSlicesSortInspection()

    private val msg = "sort.Slice can be modernized using slices.Sort"

    fun testSortAndDropTheSortImport() = doTest(
        """
        package p

        import "sort"

        func f(s []string) {
            ${warn(msg, "sort.Slice")}(s, func(i, j int) bool { return s[i] < s[j] })
        }
        """,
        "Replace sort.Slice with slices.Sort",
        """
        package p

        import "slices"

        func f(s []string) {
            slices.Sort(s)
        }
        """,
    )

    fun testSortImportStaysWhenUsed() = doTest(
        """
        package p

        import (
            "sort"
        )

        func f(s []int, t []string) {
            ${warn(msg, "sort.Slice")}(s, func(a, b int) bool {
                return s[a] < s[b]
            })
            sort.Strings(t)
        }
        """,
        "Replace sort.Slice with slices.Sort",
        """
        package p

        import (
            "slices"
            "sort"
        )

        func f(s []int, t []string) {
            slices.Sort(s)
            sort.Strings(t)
        }
        """,
    )

    fun testOtherComparisonsStayQuiet() = highlight(
        """
        package p

        import "sort"

        type T struct{ N int }

        func f(s []int, t []T, u []int) {
            sort.Slice(s, func(i, j int) bool { return s[i] > s[j] })
            sort.Slice(s, func(i, j int) bool { return s[j] < s[i] })
            sort.Slice(s, func(i, j int) bool { return u[i] < u[j] })
            sort.Slice(t, func(i, j int) bool { return t[i].N < t[j].N })
            sort.SliceStable(s, func(i, j int) bool { return s[i] < s[j] })
        }
        """
    )

    /** Regression: `slices.Sort` orders NaNs first, `sort.Slice` with `<` does not, so float slices stay as they are. */
    fun testFloatElementsStayQuiet() = highlight(
        """
        package p

        import "sort"

        type F float32

        func f(s []float64, t []F) {
            sort.Slice(s, func(i, j int) bool { return s[i] < s[j] })
            sort.Slice(t, func(i, j int) bool { return t[i] < t[j] })
        }
        """
    )
}

/** Backward index loops → `slices.Backward` (GoFixSlicesBackward). */
class GoFixSlicesBackwardInspectionTest : GoFixTestBase() {
    override fun inspection(): LocalInspectionTool = GoFixSlicesBackwardInspection()

    private val msg = "for loop can be modernized using slices.Backward"

    fun testElementReadsBecomeTheValue() = doTest(
        """
        package p

        import "fmt"

        func f(s []string) {
            for ${warn(msg, "i := len(s) - 1; i >= 0; i--")} {
                fmt.Println(s[i])
            }
        }
        """,
        "Replace with range over slices.Backward",
        """
        package p

        import (
            "fmt"
            "slices"
        )

        func f(s []string) {
            for _, v := range slices.Backward(s) {
                fmt.Println(v)
            }
        }
        """,
    )

    fun testIndexKeptWhenUsedOtherwise() = doTest(
        """
        package p

        func f(s []int) (sum int) {
            for ${warn(msg, "i := len(s) - 1; i >= 0; i--")} {
                s[i] = 0
                sum += i + s[i]
            }
            return
        }
        """,
        "Replace with range over slices.Backward",
        """
        package p

        import "slices"

        func f(s []int) (sum int) {
            for i := range slices.Backward(s) {
                s[i] = 0
                sum += i + s[i]
            }
            return
        }
        """,
    )

    /** Regression: `&s[i]`, `s[i]++` or `s` passed to a call may change the element, so a later `s[i]` must not become the stale `v`. */
    fun testReadsKeptWhenElementsMayChange() = doTest(
        """
        package p

        func g(s []int) {}

        func f(s []int, t []int) (sum int) {
            for ${warn(msg, "i := len(s) - 1; i >= 0; i--")} {
                p := &s[i]
                *p = 1
                sum += s[i]
            }
            for ${warn(msg, "i := len(t) - 1; i >= 0; i--")} {
                g(t)
                sum += t[i]
            }
            for ${warn(msg, "j := len(s) - 1; j >= 0; j--")} {
                s[j]++
                sum += s[j]
            }
            return
        }
        """,
        "Replace with range over slices.Backward",
        """
        package p

        import "slices"

        func g(s []int) {}

        func f(s []int, t []int) (sum int) {
            for i := range slices.Backward(s) {
                p := &s[i]
                *p = 1
                sum += s[i]
            }
            for i := range slices.Backward(t) {
                g(t)
                sum += t[i]
            }
            for j := range slices.Backward(s) {
                s[j]++
                sum += s[j]
            }
            return
        }
        """,
    )

    fun testOtherLoopsStayQuiet() = highlight(
        """
        package p

        func f(s []int, a [4]int, str string) {
            for i := len(s) - 1; i >= 0; i-- {
                s = s[:i]
            }
            for i := len(s) - 1; i > 0; i-- {
            }
            for i := len(s) - 2; i >= 0; i-- {
            }
            for i := len(a) - 1; i >= 0; i-- {
                _ = a[i]
            }
            for i := len(str) - 1; i >= 0; i-- {
                _ = str[i]
            }
            for i := len(s) - 1; i >= 0; i-- {
                i--
            }
        }
        """
    )

    fun testOlderModuleIsNotReported() {
        useGoVersion("1.22")
        highlight("package p\n\nfunc f(s []int) {\n    for i := len(s) - 1; i >= 0; i-- {\n        _ = s[i]\n    }\n}")
    }
}

/** `strings.Index` + slicing → `strings.Cut` (GoFixStringsCut). */
class GoFixStringsCutInspectionTest : GoFixTestBase() {
    override fun inspection(): LocalInspectionTool = GoFixStringsCutInspection()

    private val msg = "strings.Index can be simplified using strings.Cut"

    fun testStatementBeforeTheIf() = doTest(
        """
        package p

        import "strings"

        func f(s string) (string, string) {
            i := strings.Index(s, "=")
            ${warn(msg, "if")} i >= 0 {
                return s[:i], s[i+1:]
            }
            return s, ""
        }
        """,
        "Replace strings.Index with strings.Cut",
        """
        package p

        import "strings"

        func f(s string) (string, string) {
            before, after, ok := strings.Cut(s, "=")
            if ok {
                return before, after
            }
            return s, ""
        }
        """,
    )

    fun testIndexInTheHeader() = doTest(
        """
        package p

        import "strings"

        func f(s, sep string) string {
            if i := ${warn(msg, "strings.Index(s, sep)")}; i != -1 {
                return s[i+len(sep):]
            }
            return ""
        }
        """,
        "Replace strings.Index with strings.Cut",
        """
        package p

        import "strings"

        func f(s, sep string) string {
            if _, after, ok := strings.Cut(s, sep); ok {
                return after
            }
            return ""
        }
        """,
    )

    fun testOtherUsesStayQuiet() = highlight(
        """
        package p

        import "strings"

        func f(s, sep string) int {
            i := strings.Index(s, sep)
            if i >= 0 {
                return i
            }
            j := strings.Index(s, sep)
            if j >= 0 {
                _ = s[:j]
            }
            _ = j
            if k := strings.Index(s, sep); k >= 0 {
                _ = s[k+1:]
            }
            if k := strings.Index(s, sep); k > 0 {
                _ = s[:k]
            }
            if k := strings.Index(s, sep); k >= 0 {
                s = "x"
                _ = s[:k]
            }
            return 0
        }
        """
    )
}

/** `HasPrefix` + `TrimPrefix` → `CutPrefix` (GoFixStringsCutPrefix). */
class GoFixStringsCutPrefixInspectionTest : GoFixTestBase() {
    override fun inspection(): LocalInspectionTool = GoFixStringsCutPrefixInspection()

    // seen live: GoLand reports `name = strings.TrimPrefix(name, "go")` inside the `if` as well
    fun testTrimAssignedBackToTheSubject() = doTest(
        """
        package p

        import "strings"

        func f(name string) string {
            if ${warn("HasPrefix + TrimPrefix can be simplified to CutPrefix", "strings.HasPrefix(name, \"go\")")} {
                name = strings.TrimPrefix(name, "go")
            }
            return name
        }
        """,
        "Replace HasPrefix + TrimPrefix with CutPrefix",
        """
        package p

        import "strings"

        func f(name string) string {
            if after, ok := strings.CutPrefix(name, "go"); ok {
                name = after
            }
            return name
        }
        """
    )

    fun testOtherWritesOfTheSubjectStayQuiet() = highlight(
        """
        package p

        import "strings"

        func f(name string) string {
            if strings.HasPrefix(name, "go") {
                name = name + "x"
                name = strings.TrimPrefix(name, "go")
            }
            return name
        }
        """
    )

    fun testHasPrefixThenTrimPrefix() = doTest(
        """
        package p

        import "strings"

        func f(s string) string {
            if ${warn("HasPrefix + TrimPrefix can be simplified to CutPrefix", "strings.HasPrefix(s, \"go\")")} {
                return strings.TrimPrefix(s, "go")
            }
            return s
        }
        """,
        "Replace HasPrefix + TrimPrefix with CutPrefix",
        """
        package p

        import "strings"

        func f(s string) string {
            if after, ok := strings.CutPrefix(s, "go"); ok {
                return after
            }
            return s
        }
        """,
    )

    fun testTrimSuffixComparedWithTheInput() = doTest(
        """
        package p

        import "strings"

        const suffix = ".go"

        func f(s string) string {
            if ${warn("HasSuffix + TrimSuffix can be simplified to CutSuffix", "rest := strings.TrimSuffix(s, suffix)")}; s != rest {
                return rest
            }
            return ""
        }
        """,
        "Replace HasSuffix + TrimSuffix with CutSuffix",
        """
        package p

        import "strings"

        const suffix = ".go"

        func f(s string) string {
            if rest, ok := strings.CutSuffix(s, suffix); ok {
                return rest
            }
            return ""
        }
        """,
    )

    /** Regression: with `pre == ""` the comparison is false where `ok` is true, so only a non-empty constant `pre` qualifies. */
    fun testTrimComparedNeedsNonEmptyConstant() = highlight(
        """
        package p

        import "strings"

        const empty = ""

        func f(s, pre string) string {
            if after := strings.TrimPrefix(s, pre); after != s {
                return after
            }
            if after := strings.TrimPrefix(s, ""); after != s {
                return after
            }
            if after := strings.TrimPrefix(s, empty); after != s {
                return after
            }
            return ""
        }
        """
    )

    fun testMismatchesStayQuiet() = highlight(
        """
        package p

        import "strings"

        func f(s, t string) string {
            if strings.HasPrefix(s, "a") {
                return strings.TrimPrefix(t, "a")
            }
            if strings.HasPrefix(s, "a") {
                return strings.TrimSuffix(s, "a")
            }
            if strings.HasPrefix(s, "a") {
                s = "b"
                return strings.TrimPrefix(s, "a")
            }
            if strings.HasPrefix(s, "a") {
                return s
            }
            return ""
        }
        """
    )

    fun testOlderModuleIsNotReported() {
        useGoVersion("1.19")
        highlight("package p\n\nimport \"strings\"\n\nfunc f(s string) string {\n    if strings.HasPrefix(s, \"a\") {\n        return strings.TrimPrefix(s, \"a\")\n    }\n    return s\n}")
    }
}

/** Map loops → `maps.Copy` / `slices.Collect(maps.Keys(m))` (GoFixMapsLoop). */
class GoFixMapsLoopInspectionTest : GoFixTestBase() {
    override fun inspection(): LocalInspectionTool = GoFixMapsLoopInspection()

    fun testCopy() = doTest(
        """
        package p

        func f(dst, src map[string]int) {
            ${warn("Replace m[k]=v loop with maps.Copy", "for")} k, v := range src {
                dst[k] = v
            }
        }
        """,
        "Replace loop with maps.Copy",
        """
        package p

        import "maps"

        func f(dst, src map[string]int) {
            maps.Copy(dst, src)
        }
        """,
    )

    fun testCollectKeysAfterVar() = doTest(
        """
        package p

        func f(m map[string]int) []string {
            var keys []string
            ${warn("Replace append loop with slices.Collect", "for")} k := range m {
                keys = append(keys, k)
            }
            return keys
        }
        """,
        "Replace loop with slices.Collect",
        """
        package p

        import (
            "maps"
            "slices"
        )

        func f(m map[string]int) []string {
            keys := slices.Collect(maps.Keys(m))
            return keys
        }
        """,
    )

    fun testAppendValues() = doTest(
        """
        package p

        func f(m map[string]int, out []int) []int {
            ${warn("Replace append loop with slices.AppendSeq", "for")} _, v := range m {
                out = append(out, v)
            }
            return out
        }
        """,
        "Replace loop with slices.AppendSeq",
        """
        package p

        import (
            "maps"
            "slices"
        )

        func f(m map[string]int, out []int) []int {
            out = slices.AppendSeq(out, maps.Values(m))
            return out
        }
        """,
    )

    fun testOtherLoopsStayQuiet() = highlight(
        """
        package p

        type Keys []string

        func f(src map[string]int, dst map[string]int64, other map[string]int, s []int) {
            for k, v := range src {
                dst[k] = int64(v)
            }
            for k, v := range src {
                other[k] = v + 1
            }
            for k, v := range src {
                other[k+"x"] = v
            }
            for i, v := range s {
                other[string(rune(i))] = v
            }
            var n []int
            for k := range src {
                n = append(n, len(k))
            }
            for k := range src {
                n = append(n, src[k])
            }
        }
        """
    )

    fun testKeysNeedGo123() {
        useGoVersion("1.22")
        highlight("package p\n\nfunc f(m map[string]int) []string {\n    var keys []string\n    for k := range m {\n        keys = append(keys, k)\n    }\n    return keys\n}")
    }
}
