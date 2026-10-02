package main

import "net/http"

// The tag of a field, as the fields above it have theirs: checks 11.16 and 11.17 of README.md. The caret at the end of the marked
// line, Enter, then the name of a field, its type and a space.

type account struct {
	ID        int    `json:"id" db:"id"`
	FirstName string `json:"first_name,omitempty" db:"first_name" validate:"required"` // 11.16
}

type request struct {
	UserName string `json:"userName"` // 11.17
}

func init() {
	_ := 1

	if x {

	}

}
