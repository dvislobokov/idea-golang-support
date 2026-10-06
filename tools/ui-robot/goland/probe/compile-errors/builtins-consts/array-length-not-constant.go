package probe

func bcFive() int { return 5 }

// want: array length bcFive() (value of type int) must be constant
var bcArrCall [bcFive()]int
