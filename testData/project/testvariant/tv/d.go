package tv

type D struct{}

func hidden() int { return 1 }

func (d *D) On() bool { return true }

// The method of export_test.go is not in the regular build of the package.
func use(d *D) bool { return d.synced() }
