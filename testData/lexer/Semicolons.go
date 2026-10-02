package semicolons

// ASI after each eligible token kind
ident
42
4.2
4i
'r'
"s"
`raw`
break
continue
fallthrough
return
x++
x--
f()
a[0]
T{}

// no ASI after other tokens
f(a,
  b)
if x {
}
x = a +
  b
x :=
  1
a.
  b
go
func
type

// comments keep the state
x // line comment
y /* block */
z /* multi
   line */
w /* multi
   line */ v

// explicit semicolons and blank lines
a; b;


last
