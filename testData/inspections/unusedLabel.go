package unusedlabel

func f() {
<error descr="label L declared and not used">L</error>:
	for {
		break
	}
M:
	for {
		continue M
	}
}
