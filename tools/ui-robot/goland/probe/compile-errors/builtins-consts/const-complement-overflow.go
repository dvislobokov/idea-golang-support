package probe

const bcMax512 = (1<<256 - 1) * (1<<256 + 1)

// want: constant bitwise complement overflow
const bcCompl = ^bcMax512
