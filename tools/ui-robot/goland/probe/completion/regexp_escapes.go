package probe

import "regexp"

// Completion inside regular expressions injected into regexp calls (dump completion.txt, probe 30: 187 rows in GoLand 2026.2.3).
//
// caret: after the backslash in `\d` below (delete "d"), Ctrl+Space; expect RE2 escapes with descriptions: `d  digits (== [0-9])`,
//        `D`, `w`, `s`, `b  at ASCII word boundary (...)`, `A  at beginning of text`, `z`, `E`, `Q`, `*  literal *, ...`,
//        `x{10FFFF}`, then `p{Ahom}`, `p{Arabic}`, ... (Unicode scripts) and `p{Lu}` (categories, the plugin adds them).
// caret: after "\p{G" in the second pattern (delete "reek}"), Ctrl+Space; expect `Greek`, `Georgian`, `Gothic`, ...
// caret: after the backslash of the interpreted string in MatchString ("\\" + caret), Ctrl+Space; expect the same escapes.
// none of `Z`, `G`, `R`, `h`, `p{javaLowerCase}`: Java-only escapes are not RE2.

var digits = regexp.MustCompile(`\d+`)

var greek = regexp.MustCompile(`\p{Greek}+`)

func matches(s string) bool {
	ok, _ := regexp.MatchString("^\\w+$", s)
	return ok && digits.MatchString(s) && !greek.MatchString(s)
}
