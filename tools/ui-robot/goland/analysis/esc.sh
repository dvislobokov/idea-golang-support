# esc TEXT: escape for a JS string literal (backslash, quote, tab, newline), then for a sed replacement with | as the delimiter
esc() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\t/\\t/g' | sed -e ':a' -e 'N' -e '$!ba' -e 's/\n/\\n/g' | sed -e 's/[\\&|]/\\&/g'; }
