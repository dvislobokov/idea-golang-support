package probe

import "time"

// Time layout rows inside the layout string (GoLand probe C5). The caret stands between the quotes (`@@`).

func tlFormat(t time.Time, s string) {
	// caret: `_ = t.Format("@@")` BASIC -> 14 rows: YY  Year (two-digit), YYYY  Year (four-digit), MM  Month (zero-padded),
	// DD  Day of month (zero-padded), hh  24-hour (zero-padded), mm  Minute (zero-padded), ss  Second (zero-padded),
	// year..., month..., day..., hour..., minute..., second..., zone...

	// caret: `_ = t.Format("@@")` BASIC, pick `YYYY` -> `t.Format("2006<caret>")`

	// caret: `_ = t.Format("@@")` BASIC, pick `month...` -> the list reopens: January, Jan, 01, 1 (with descriptions)

	// caret: `_, _ = time.Parse("@@", s)` BASIC -> the same 14 rows

	// caret: `_ = t.AppendFormat(nil, "@@")` and `_, _ = time.ParseInLocation("@@", s, time.UTC)` BASIC -> the same 14 rows

	// caret: `_ = t.Format("2006-")` + type `M` -> the popup opens by itself (autopopup), `MM` first

	// caret: `_ = s + "@@"` BASIC -> no layout rows (not a layout argument)

	_ = s
}
