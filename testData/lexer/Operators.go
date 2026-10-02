package operators

+ - * / % & | ^ << >> &^
+= -= *= /= %= &= |= ^= <<= >>= &^=
&& || <- ++ -- == < > = ! ~
!= <= >= := ... ( ) [ ] { } , . ; :

// longest match
a<-b a< -b a<<-b a&^^b a&&^b x...y x..y a+++b a---b a<<=b a>>=b a==b a=!b a!==b
a:=b a: =b ch<-<-ch

// not Go tokens: one BAD_CHARACTER each, the ASI state is kept
x #
y $ ? @ \ “
