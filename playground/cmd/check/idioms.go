package main

import (
	"bufio"
	"context"
	"io"
	"os"
	"os/signal"
)

// The grey text: section 11 of README.md. In every function the caret goes to the end of the line marked with the number of the
// check, then Enter. The grey text is accepted with Tab and refused by typing anything else. The file returns its errors as they
// are; wrapped.go next to it wraps them.

// 11.1: the check of an error.
func idiomOpen(name string) (*os.File, error) {
	f, err := os.Open(name) // 11.1
	return f, err
}

// 11.2: the body of a check typed by hand. Type `if err != nil {` on the empty line, then Enter.
func idiomByHand(name string) ([]byte, error) {
	data, err := os.ReadFile(name)

	return data, err
}

// 11.3: the loop of a scanner, and after the loop is written (Tab, then the caret after its `}` and Enter) the error it has stopped at.
func idiomScanner(r io.Reader) (int, error) {
	scanner := bufio.NewScanner(r) // 11.3
	_ = scanner
	return 0, nil
}

// 11.4: the channel the goroutine writes to is closed by it.
func idiomChannel() <-chan int {
	results := make(chan int)
	go func() { // 11.4
		results <- 1
	}()
	return results
}

// 11.5: what is started is stopped.
func idiomSignals() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt) // 11.5
	<-ctx.Done()
	stop()
}

// 11.6: a span is ended. The tracer is a stand-in for the one of OpenTelemetry, which the playground does not require.
func idiomSpan(ctx context.Context, t tracer) {
	ctx, loadSpan := t.Start(ctx, "load") // 11.6
	_, _ = ctx, loadSpan
}

// 11.7: a temporary directory is removed. The caret after the `}` of the check, Enter.
func idiomDirectory() (string, error) {
	dir, err := os.MkdirTemp("", "check")
	if err != nil {
		return "", err
	} // 11.7
	return dir, nil
}

type tracer struct{}

type span struct{}

func (tracer) Start(ctx context.Context, name string) (context.Context, span) { return ctx, span{} }

func (span) End() {}
