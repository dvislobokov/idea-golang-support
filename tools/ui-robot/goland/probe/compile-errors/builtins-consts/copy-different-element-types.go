package probe

type bcMyByte byte

func bcCopy[T ~[]byte](y T) {
	var b []bcMyByte
	// want: invalid copy: arguments b (variable of type []bcMyByte) and y (variable of type T constrained by ~[]byte) have different element types bcMyByte and byte
	copy(b, y)
}
