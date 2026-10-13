package user

import "example.com/testvariant/tv"

// Another package imports the regular build of tv: no Hidden, no Synced.
func F(d *tv.D) bool { return d.Synced() && tv.Hidden() == 1 }
