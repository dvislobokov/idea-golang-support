package probe

// Not reported by the plugin: the cycle goes through the constant expression of the array
// length; evaluating it is left out to keep the cycle walk on type references only.
// want: invalid recursive type: raZ refers to itself
type raZ [][[]raZ{}[0][0]]int
