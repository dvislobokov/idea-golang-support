package main

import (
	"encoding/json"
	"fmt"
	"os"
)

// This file wraps its errors, and what is suggested in it does the same: check 11.8 of README.md.

type settings struct {
	Name string `json:"name"`
}

func saveSettings(path string, s settings) error {
	data, err := json.Marshal(s)
	if err != nil {
		return fmt.Errorf("marshal settings: %w", err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return fmt.Errorf("write settings: %w", err)
	}
	return nil
}

// 11.8: the caret at the end of the marked line, Enter.
func loadSettings(path string) (settings, error) {
	var s settings
	data, err := os.ReadFile(path) // 11.8
	_ = data
	return s, err
}
