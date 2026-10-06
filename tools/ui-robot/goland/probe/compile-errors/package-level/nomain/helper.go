// want: function main is undeclared in the main package
package main

func helper() int { return 1 }

var _ = helper()
