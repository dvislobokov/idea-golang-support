// Package lib is partitioned by build constraints in GoPackageResolverTest.
package lib

import "example.com/simple/pkg/lib/internal/secret"

const Name = "lib" + secret.Value
