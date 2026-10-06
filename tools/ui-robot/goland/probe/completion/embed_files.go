package probe

import _ "embed"

// Completion of //go:embed patterns (GoLand 2026.2.3: files and directories of the package directory, filtered by the prefix).
//
// caret: delete "embed_files.go" in the directive below, caret after "//go:embed ", Ctrl+Space; expect the files of this directory
//        (`comment_symbols.go`, `embed_files.go`, `regexp_escapes.go`, file-type icons), no `.`/`_` names, no directories of another
//        module; restore the pattern afterwards (the file must keep compiling).
// caret: type "emb" after "//go:embed ", Ctrl+Space; expect `embed_files.go` inserted directly (single match).
// caret: type "*.go" after "//go:embed ", Ctrl+Space; expect nothing (glob patterns are left to the user).

//go:embed embed_files.go
var embedSource string

var _ = embedSource
