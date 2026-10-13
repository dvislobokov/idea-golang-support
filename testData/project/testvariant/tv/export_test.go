package tv

// The idiom of export_test.go: an unexported name exported to the external test package.
var Hidden = hidden

func (d *D) synced() bool { return d.On() }

func (d *D) Synced() bool { return d.synced() }
