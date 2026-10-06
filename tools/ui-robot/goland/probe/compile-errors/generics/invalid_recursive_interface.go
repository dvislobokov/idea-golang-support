package probe

// want: invalid recursive type: irSelf refers to itself
type irSelf interface{ irSelf }
