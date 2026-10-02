package strings

// runes
'a' 'ä' '本' '\a' '\b' '\f' '\n' '\r' '\t' '\v' '\' '\'' '\"'
'\000' '\377' '\x41' 'ዤ' '\U00101234'
'ab' '' '\q' '\x4'

// interpreted strings
"" "abc" "\a\b\f\n\r\t\v\\\"" "\'" "\x41\101ä\U0001F600" "日本語" "a'b"

// raw strings
`` `abc` `with "quotes" and \n` `multi
line
raw`
x := `a`

// unterminated rune and string stop at the end of the line
'x
"abc
"escaped quote at end\"
'\
y
