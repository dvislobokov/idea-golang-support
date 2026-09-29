package main

import (
	"encoding/json"
	"io"
	"net/http"
)

// A handler has no result to return an error in: it answers and returns. Checks 11.12 and 11.13 of README.md: the caret at the end
// of the marked line, Enter.

func handleUpload(w http.ResponseWriter, r *http.Request) {
	data, err := io.ReadAll(r.Body) // 11.12
	_, _ = data, err
	w.WriteHeader(http.StatusNoContent)
}

func handleCreate(w http.ResponseWriter, r *http.Request) {
	var s settings
	err := json.NewDecoder(r.Body).Decode(&s) // 11.13
	_ = err
	w.WriteHeader(http.StatusCreated)
}
