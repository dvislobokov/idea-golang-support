package unusedlabel

func f() {
<warning descr="label L declared and not used">L</warning>:
	for {
		break
	}
M:
	for {
		continue M
	}
}
