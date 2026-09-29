package main

import (
	"context"
	"os"

	"example.com/playground/internal/codes"
	"example.com/playground/internal/status"
)

// A file of a gRPC service answers with statuses, and what is suggested in it does: check 11.14 of README.md.

type service struct{}

func (s *service) get(ctx context.Context, id string) ([]byte, error) {
	if id == "" {
		return nil, status.Error(codes.InvalidArgument, "id is required")
	}
	data, err := os.ReadFile(id)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "read file: %v", err)
	}
	return data, nil
}

func (s *service) list(ctx context.Context, dir string) ([]os.DirEntry, error) {
	entries, err := os.ReadDir(dir) // 11.14
	return entries, err
}

// 11.15: the caret at the end of this line, Enter twice, then `func (`.
