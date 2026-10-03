# Подсказки серым текстом по контексту (inline suggestions)

Каталог шаблонов для `lang/GoInlineSuggestions`: что предлагать серым текстом (Tab — принять) по переменным в области видимости, их типам,
сигнатуре функции, объявляемому имени и тому, как имя используется ниже. Без ML и без кода GoLand: только правила над PSI и типами go-psi.

Приоритет: **P1** — первая партия (частые, надёжные), **P2** — вторая, **P3** — нишевые или требующие осторожности.
Обозначения: `|` — каретка, `→` — серый текст; «ниже» — следующие операторы того же блока (уже написанный код, например при правке).

## 0. Общие правила

- Показывается **одна** подсказка и только при уверенности выше порога; при двух равных кандидатах — ничего.
- Сигналы и их вес: тип слота (ожидаемый тип) > использование имени ниже > имя (словарь ниже) > переменные в области (ближе — выше) > пакетные объявления.
- Не показывать: внутри строк и комментариев; во время индексации; если функция длиннее ~400 строк; если подсказка совпадает с уже набранным.
- Текст подсказки — валидный Go в стиле gofmt; недостающие импорты добавляются при принятии.
- Частичное принятие (слово за словом, Ctrl+→) — платформенное, работает само.
- Словарь имён (основа «по имени»): срезы — `arr, list, items, values, res, result(s), out, ids, names, keys, xs, buf` и любое множественное
  число (`users`, `keys`); мапы — `m, byID, by*, *Map, *Index, cache, lookup, counts`; множества — `seen, set, visited, *Set, uniq`;
  каналы — `ch, c, done, quit, stop, errCh, errc, results, jobs, events, *Ch`; контекст — `ctx`; отмена — `cancel`; время — `start, now,
  deadline, elapsed`; ошибки — `err, errs`; построители — `sb, b, buf, w`; счётчики — `n, i, count, total, sum, idx`.

## A. Правая часть объявления `x :=`, `var x =`, `x =`

| # | Слот / сигнал | Подсказка | Пр. |
|---|---|---|---|
| A1 | `arr :=` (имя-срез), в области `keys ...string`/`[]string`, ниже `arr[i] = …` | `make([]string, len(keys))` | P1 |
| A2 | то же, ниже `arr = append(arr, …)` или ничего ниже | `make([]string, 0, len(keys))` | P1 |
| A3 | имя-срез, тип элемента известен по использованию ниже (`append(arr, u)`, `u` — `*User`), длины нет | `make([]*User, 0)` → лучше `var arr []*User` (см. A4) | P2 |
| A4 | `var arr` (оператор `var`, имя-срез, тип по использованию ниже) | ` []*User` | P2 |
| A5 | `out :=` при `for _, x := range in` ниже и `append(out, f(x))` | `make([]T, 0, len(in))` | P1 |
| A6 | `cp :=`/`clone :=`, ниже `copy(cp, s)` | `make([]T, len(s))` | P1 |
| A7 | `m :=` (имя-мапа), ниже `m[k] = v` с известными типами k, v | `make(map[K]V)` | P1 |
| A8 | то же, и в области ранжируемый срез `items`, по которому мапа заполняется в цикле | `make(map[K]V, len(items))` | P1 |
| A9 | `seen :=`/`set :=`/`visited :=` (множество), тип ключа по использованию ниже `seen[x]` или по срезу в области | `make(map[string]struct{})`; если ниже `seen[x] = true` — `make(map[string]bool)` | P1 |
| A10 | `byID :=`, в области срез `users []*User`, у `User` поле `ID int` | `make(map[int]*User, len(users))` | P2 |
| A11 | `counts :=`, ниже `counts[k]++` | `make(map[K]int)` | P1 |
| A12 | `done :=`/`quit :=`/`stop :=` | `make(chan struct{})` | P1 |
| A13 | `errCh :=`/`errc :=`, ниже `go func` пишет в него | `make(chan error, 1)` (или `len(jobs)`, если запускается по срезу) | P1 |
| A14 | `results :=` (канал по использованию ниже `results <- v`) при цикле `for _, j := range jobs` с горутиной | `make(chan T, len(jobs))` | P2 |
| A15 | `ch :=`, ниже `ch <- v`/`<-ch`, тип по `v` | `make(chan T)` | P2 |
| A16 | `ctx :=`, в области нет ctx | `context.Background()` (в `_test.go` — `t.Context()` при Go ≥ 1.24, иначе `context.Background()`) | P1 |
| A17 | `ctx, cancel :=`, в области `ctx` | `context.WithCancel(ctx)` | P1 |
| A18 | `ctx, cancel :=`, ниже/в имени функции таймаут, в области `timeout time.Duration` | `context.WithTimeout(ctx, timeout)`; без переменной — `context.WithTimeout(ctx, 5*time.Second)` | P1 |
| A19 | `ctx =`, в области ключ-тип `ctxKey`/`*Key` и значение | `context.WithValue(ctx, key, value)` | P3 |
| A20 | `start :=` | `time.Now()` | P1 |
| A21 | `elapsed :=`/`took :=`/`dur :=`, в области `start time.Time` | `time.Since(start)` | P1 |
| A22 | `deadline :=` | `time.Now().Add(timeout)` (`timeout` из области) | P2 |
| A23 | `ticker :=` | `time.NewTicker(interval)` (переменная `interval`/`d`/`period` из области), следом — `defer ticker.Stop()` (F) | P2 |
| A24 | `timer :=` | `time.NewTimer(d)` | P2 |
| A25 | `var sb`/`var b` (построитель строк), ниже `sb.WriteString` | ` strings.Builder` | P1 |
| A26 | `var buf`, ниже `buf.Write`/`json.NewEncoder(&buf)` | ` bytes.Buffer` | P1 |
| A27 | `var wg` | ` sync.WaitGroup` | P1 |
| A28 | `var mu` | ` sync.Mutex`; если ниже `mu.RLock` — ` sync.RWMutex` | P1 |
| A29 | `var once` | ` sync.Once` | P2 |
| A30 | `u :=`/`user :=`, в пакете тип `User`, есть конструктор `NewUser(...)` с аргументами, совпадающими по типам с переменными области | `NewUser(name, email)` | P1 |
| A31 | то же, конструктора нет | `&User{}` (если у типа методы на указателе), иначе `User{}` | P1 |
| A32 | `cfg :=`/`conf :=`, есть `DefaultConfig()`/`NewConfig()` | `DefaultConfig()` | P2 |
| A33 | имя совпадает с полем получателя (`name :=` в методе `(s *Server)` с полем `name`) | `s.name` | P2 |
| A34 | имя совпадает с полем параметра-структуры (`id :=` при `req *GetRequest` с полем `ID`) | `req.ID` | P1 |
| A35 | `n :=`/`size :=`/`count :=` при единственном срезе/мапе в области | `len(items)` | P2 |
| A36 | `i :=`/`count :=`/`total :=`/`sum :=` без источника | `0` | P2 |
| A37 | `ok :=`/`found :=` | `false` | P3 |
| A38 | `id, err :=`, в области строка `s`/`idStr`/`raw` | `strconv.Atoi(idStr)` (`ParseInt(s, 10, 64)` если ожидается `int64` ниже) | P2 |
| A39 | `data, err :=` (или `b`, `body`, `raw`), в области `path`/`filename`/`file string` | `os.ReadFile(path)` | P1 |
| A40 | `data, err :=`, в области значение `v` и ниже `w.Write(data)`/возврат `[]byte` | `json.Marshal(v)` | P2 |
| A41 | `f, err :=`/`file, err :=` + `path` в области | `os.Open(path)`; если ниже запись — `os.Create(path)` | P1 |
| A42 | `body, err :=`, в области `resp *http.Response` | `io.ReadAll(resp.Body)` | P1 |
| A43 | `req, err :=`, в области `ctx`, `url` | `http.NewRequestWithContext(ctx, http.MethodGet, url, nil)`; если в области `body io.Reader`/`payload` — `MethodPost, url, body` | P1 |
| A44 | `resp, err :=`, в области `req *http.Request` и клиент (`client`, `c.http`, поле `*http.Client`) | `client.Do(req)`; без клиента — `http.DefaultClient.Do(req)` | P1 |
| A45 | `rows, err :=`, в области `db *sql.DB`/`tx`, `ctx` | `db.QueryContext(ctx, query)` (если есть `query`/`q` строка) | P2 |
| A46 | `dec :=`, в области `r *http.Request` | `json.NewDecoder(r.Body)` | P2 |
| A47 | `enc :=`, в области `w http.ResponseWriter`/`io.Writer` | `json.NewEncoder(w)` | P2 |
| A48 | `re :=` на уровне пакета (`var re =`) | `regexp.MustCompile(`…`)` с кареткой внутри строки | P2 |
| A49 | `logger :=`/`log :=`, в области `*slog.Logger` или нет | `slog.With("component", "…")` / `slog.Default()` | P3 |
| A50 | `v, ok :=`, в области интерфейсная `x`, ниже вызов метода конкретного типа `T` | `x.(T)` | P2 |
| A51 | `v, ok :=`, в области мапа `m` и ключ `k` | `m[k]` | P1 |
| A52 | `v, ok :=`, в области канал `ch` | `<-ch` | P2 |
| A53 | `err =`/`err :=` после вызова, где ошибка уже есть, и функция-обёртка в области (`wrap`, `fmt`) | не угадывается — пропуск (слишком спекулятивно) | — |
| A54 | `x :=` при единственной переменной области подходящего ожидаемого типа (тип известен по использованию ниже: передаётся в `f(x)` с параметром `T`) | эта переменная | P2 |
| A55 | `got :=` в тесте `TestFoo` | `Foo(` + аргументы из `tt.`/`tc.` полей с совпадающими именами `)` | P1 |
| A56 | `want :=` в тесте, есть `tt.want` | `tt.want` | P2 |
| A57 | `tests :=`/`cases :=` в начале `TestFoo` | `[]struct {\n\tname string\n\t… поля по параметрам и результатам Foo\n}{\n\t{name: ""},\n}` (многострочная) | P2 |
| A58 | `srv :=` в тесте с `handler` в области | `httptest.NewServer(handler)` + следом `defer srv.Close()` | P2 |
| A59 | `rec :=`/`w :=` в тесте | `httptest.NewRecorder()` | P2 |
| A60 | `req :=` в тесте (без err) | `httptest.NewRequest(http.MethodGet, "/", nil)` | P2 |
| A61 | `dir :=` в тесте | `t.TempDir()` | P2 |
| A62 | `errs :=` (срез ошибок) | `make([]error, 0)`; ниже `errors.Join(errs...)` в return | P3 |
| A63 | `g, ctx :=` при импорте `errgroup` | `errgroup.WithContext(ctx)` | P3 |
| A64 | `sem :=` | `make(chan struct{}, n)` (`n`/`limit`/`workers` из области) | P3 |
| A65 | `keys :=` при мапе `m` в области | `slices.Collect(maps.Keys(m))` (Go ≥ 1.23), иначе `make([]K, 0, len(m))` | P2 |
| A66 | `sorted :=` при срезе `s` | `slices.Clone(s)` (+ ниже `slices.Sort(sorted)`) | P3 |

## B. `return |`

| # | Контекст | Подсказка | Пр. |
|---|---|---|---|
| B1 | последний результат `error`, в области `err` после проверки `if err != nil {` | нулевые значения + `err` (`nil, err`, `0, "", err`) — есть в `GoReturnValues`, объединить | P1 |
| B2 | то же, в функции уже встречается обёртка `fmt.Errorf("…: %w", err)` или настройка «оборачивать» | `nil, fmt.Errorf("get user: %w", err)` — префикс из имени функции (`GetUser` → `get user`) | P1 |
| B3 | конец функции, результаты `(T, error)`, в области переменная типа `T`, построенная выше | `x, nil` | P1 |
| B4 | конструктор `NewT(a, b)` с результатом `*T`, поля `T` совпадают с параметрами | `&T{a: a, b: b}` (+ `, nil` при ошибке в результатах) | P1 |
| B5 | метод `String() string` | `fmt.Sprintf("T{%v}", …)` по полям (P3), для перечислений — `switch` по константам (G) | P3 |
| B6 | метод `Error() string` у типа ошибки с полями | `fmt.Sprintf("…: %v", e.Err)` / `e.msg` | P2 |
| B7 | функция `bool`, внутри `if` ранний выход | `false` / `true` по противоположности уже написанного return | P2 |
| B8 | результаты именованы и уже присвоены | ничего (голый `return` — стиль) или `n, err` — по стилю файла | P3 |
| B9 | `Len() int` (sort.Interface) | `len(s)` (получатель-срез) | P2 |
| B10 | `Less(i, j int) bool` | `s[i].<поле> < s[j].<поле>` (первое сравнимое поле, лучше `ID`/`Name`) | P3 |
| B11 | функция возвращает `[]T`, в области накопленный срез | этот срез | P1 |
| B12 | обработчик с `context` и `select` | `ctx.Err()` в ветке `<-ctx.Done()` | P1 |
| B13 | результат `error`, в области `errs []error` | `errors.Join(errs...)` | P2 |

## C. Аргументы вызова `f(|`

| # | Сигнал | Подсказка | Пр. |
|---|---|---|---|
| C1 | параметр `ctx context.Context` первым, в области `ctx` | `ctx` | P1 |
| C2 | параметр типа `T` — единственная переменная области типа `T` (или присваиваемая) | она | P1 |
| C3 | несколько кандидатов типа `T` — по имени параметра (точное, без регистра, по корню: `userID` ↔ `id`) | лучший | P1 |
| C4 | все аргументы сразу, если для каждого есть однозначный кандидат | `ctx, id, opts` | P1 |
| C5 | вариадический `...T`, в области `[]T` | `items...` | P1 |
| C6 | `json.Unmarshal(data, |` / `Decode(|`, `Scan(|` — параметр `any`, ниже/выше `var v T` | `&v` | P1 |
| C7 | `rows.Scan(|`, у структуры `x` поля по столбцам запроса (`SELECT id, name`) | `&x.ID, &x.Name` | P2 |
| C8 | `fmt.Printf("… %s … %d", |` | переменные области по типам глаголов по порядку | P1 |
| C9 | `errors.Is(err, |` | переменная/пакетная `Err*` того же пакета, упомянутая в функции или ближайшая по имени | P2 |
| C10 | `errors.As(err, |` | `&target` + объявление `var target *MyErr` выше (две правки — P3) | P3 |
| C11 | `make([]T, |` | `0, len(src)` / `len(src)` по правилам A1–A2 | P1 |
| C12 | `append(s, |` в цикле `range src` | элемент цикла или его преобразование по типу | P2 |
| C13 | `t.Run(|` в цикле по `tests` | `tt.name, func(t *testing.T) {` | P1 |
| C14 | `assert.Equal(t, |` / `require` | `tt.want, got` (по порядку expected, actual) | P2 |
| C15 | `strings.Join(parts, |` | `", "` | P3 |
| C16 | `http.HandleFunc("/…", |` | обработчик пакета с сигнатурой `func(w, r)` по имени пути | P3 |
| C17 | `signal.NotifyContext(|` | `context.Background(), os.Interrupt, syscall.SIGTERM` | P2 |
| C18 | `sort.Slice(s, |` | `func(i, j int) bool { return s[i].X < s[j].X }` | P2 |
| C19 | `slices.SortFunc(s, |` | `func(a, b T) int { return cmp.Compare(a.X, b.X) }` | P2 |

## D. Составной литерал `T{|` и поля `T{F: |`

| # | Сигнал | Подсказка | Пр. |
|---|---|---|---|
| D1 | `T{` — у полей есть переменные области с теми же именами (без регистра) и подходящими типами | `Name: name, Email: email` (все однозначные поля) | P1 |
| D2 | `T{F: |` — переменная области с именем поля | она | P1 |
| D3 | `T{F: |` — поле параметра-структуры с тем же именем (`req.Name`) | `req.Name` | P1 |
| D4 | поле типа `time.Time` с именем `CreatedAt`/`UpdatedAt` | `time.Now()` | P2 |
| D5 | поле `context.Context`/`*slog.Logger`/`*http.Client` | переменная области того же типа | P2 |
| D6 | `&http.Client{|` | `Timeout: 10 * time.Second` | P3 |
| D7 | `&http.Server{|` | `Addr: addr, Handler: mux` из области | P2 |

## E. Управляющие конструкции

| # | Слот | Подсказка | Пр. |
|---|---|---|---|
| E1 | `for _, user := range |` | `users` (множественное к имени), иначе единственный ранжируемый | P1 |
| E2 | `for i, x := range |` при параметре-срезе | он | P1 |
| E3 | `for |` после `ch := …`/`ticker` | `range ch {` / `{\n\tselect { … }` | P2 |
| E4 | `for i := |` при срезе `s` в области | `0; i < len(s); i++ {` | P2 |
| E5 | `if |` после `v, ok := …` | `!ok {` | P1 |
| E6 | `if |` в начале функции с указателем-параметром, который ниже разыменовывается | `p == nil {` | P2 |
| E7 | `if |` в начале функции со срезом-параметром | `len(s) == 0 {` | P2 |
| E8 | `if |` после `err := …` | `err != nil {` (есть в `GoIdioms`, объединить) | P1 |
| E9 | `if errors.|` при наличии пакетных `Err*` | `Is(err, ErrNotFound) {` | P2 |
| E10 | `switch |` при переменной перечисляемого типа (константы `iota`) | `x {` + ветки — через существующий Exhaustive fix (P2) | P2 |
| E11 | `switch v := |` при интерфейсе в области | `x.(type) {` | P2 |
| E12 | `select {` внутри горутины/цикла с `ctx` | `\n\tcase <-ctx.Done():\n\t\treturn ctx.Err()` | P1 |
| E13 | `case |` в `select` с каналом в области | `v := <-ch:` / `ch <- v:` по направлению | P2 |

## F. Следующая строка (после оператора)

| # | После | Подсказка | Пр. |
|---|---|---|---|
| F1 | `ctx, cancel := context.With…(…)` | `defer cancel()` | P1 |
| F2 | `f, err := os.Open/Create(…)` + `if err` | `defer f.Close()` (есть в `GoIdioms`, сверить) | P1 |
| F3 | `resp, err := …Do/Get(…)` + `if err` | `defer resp.Body.Close()` | P1 |
| F4 | `rows, err := …Query…(…)` + `if err` | `defer rows.Close()`; после цикла `for rows.Next()` — `if err := rows.Err(); err != nil {` | P1 |
| F5 | `mu.Lock()` | `defer mu.Unlock()` (`RLock` → `RUnlock`) | P1 |
| F6 | `wg.Add(1)` | `go func() {\n\tdefer wg.Done()` | P1 |
| F7 | `go func() {` при `wg` в области | `defer wg.Done()` | P1 |
| F8 | цикл с `go`/`wg.Add` закрыт | `wg.Wait()` | P1 |
| F9 | `ticker := time.NewTicker(…)` | `defer ticker.Stop()` | P1 |
| F10 | `timer := time.NewTimer(…)` | `defer timer.Stop()` | P2 |
| F11 | цикл-производитель в горутине пишет в `ch` | после цикла `close(ch)` (в горутине — `defer close(ch)` первой строкой) | P2 |
| F12 | `srv := httptest.NewServer(…)` | `defer srv.Close()` | P1 |
| F13 | первая строка функции-помощника теста (`t *testing.T` не первый тест) | `t.Helper()` | P1 |
| F14 | первая строка `TestX`/подтеста при `t.Parallel()` в соседних тестах файла | `t.Parallel()` | P2 |
| F15 | `tx, err := db.BeginTx(…)` + `if err` | `defer tx.Rollback()` | P1 |
| F16 | `l, err := net.Listen(…)` + `if err` | `defer l.Close()` | P2 |
| F17 | `conn, err := …Dial…(…)` + `if err` | `defer conn.Close()` | P2 |
| F18 | `sb.WriteString(…)` в цикле, после цикла | `return sb.String()` | P2 |
| F19 | `stmt, err := db.Prepare…` + `if err` | `defer stmt.Close()` | P2 |
| F20 | `signal.NotifyContext(…)` | `defer stop()` | P1 |
| F21 | `gz := gzip.NewWriter(w)` / `zip.NewWriter` / `csv.NewWriter` | `defer gz.Close()` / `defer w.Flush()` | P3 |

## G. Тела функций (после `{` на пустой строке)

| # | Объявление | Подсказка (многострочная) | Пр. |
|---|---|---|---|
| G1 | `func NewT(a A, b B) *T {` | `return &T{a: a, b: b}` (поля по совпадению имён) | P1 |
| G2 | геттер `func (t *T) Name() string {` | `return t.name` | P1 |
| G3 | сеттер `func (t *T) SetName(name string) {` | `t.name = name` | P1 |
| G4 | `func (e Enum) String() string {` с константами `iota` | `switch e {\n case A: return "A" … }\n return fmt.Sprintf("Enum(%d)", int(e))` | P2 |
| G5 | `func (e *MyErr) Error() string {` | `return e.msg` / `fmt.Sprintf(...)` | P2 |
| G6 | `func (e *MyErr) Unwrap() error {` | `return e.err` (поле типа error) | P1 |
| G7 | обработчик `func(w http.ResponseWriter, r *http.Request) {` | `if r.Method != http.MethodGet {\n\thttp.Error(w, "method not allowed", http.StatusMethodNotAllowed)\n\treturn\n}` | P3 |
| G8 | `func TestX(t *testing.T) {` для функции `X` пакета | таблица тестов (A57) + цикл `t.Run` | P2 |
| G9 | `func BenchmarkX(b *testing.B) {` | `for b.Loop() {\n\tX(…)\n}` (Go ≥ 1.24, иначе `for i := 0; i < b.N; i++`) | P2 |
| G10 | `func FuzzX(f *testing.F) {` | `f.Add(…)\nf.Fuzz(func(t *testing.T, … ) {` | P3 |
| G11 | `func main() {` в пакете с `run(ctx)` | `if err := run(context.Background()); err != nil {\n\tlog.Fatal(err)\n}` | P3 |
| G12 | метод интерфейса, который тип реализует частично | тело-заглушка `panic("not implemented")` — уже есть Implement, здесь не дублировать | — |

## H. Уровень пакета и прочее

| # | Слот | Подсказка | Пр. |
|---|---|---|---|
| H1 | после `type T struct` при интерфейсе пакета, который `T` реализует методами | `var _ I = (*T)(nil)` | P2 |
| H2 | `var ErrNotFound =` | `errors.New("not found")` (текст из имени: `ErrNotFound` → `not found`) | P1 |
| H3 | `const (` после типа-перечисления `type Color int` | `\n\tColorRed Color = iota` | P2 |
| H4 | `var re =` / `var xRe =` | `regexp.MustCompile(`…`)` | P2 |
| H5 | `//go:` | `generate` / `embed` / `build` по контексту (следующая строка — `var x embed.FS` → `embed`) | P3 |
| H6 | `import (` при неразрешённых именах в файле | недостающие пути — делает autoimport, не дублировать | — |

## I. Сигналы «использование ниже» (общий анализ)

Для объявляемого имени `x` собирается по следующим операторам блока (до конца функции, без входа в вложенные функции):
`append(x, v)` → срез элементов типа `v`; `x[i] = v` (i — int) → срез с длиной; `x[k] = v` (k — не int) → мапа K→V; `x[k]++` → мапа K→int;
`x <- v` / `<-x` / `range x` по каналу → канал; `x.Method()` → тип с методом; `f(x)` → тип параметра `f`; `return …, x, …` → тип результата N;
`len(x)`, `cap(x)` → коллекция; `x.Lock()` → мьютекс; `defer x.Close()` → `io.Closer`.

## Порядок реализации

1. **P1** этой таблицы — первая партия (агент «inline suggestions»): A1–A2, A5–A9, A11–A13, A16–A18, A20–A21, A25–A28, A30–A31, A34, A39,
   A41–A44, A51, A55; B1–B4, B11–B12; C1–C6, C8, C11, C13; D1–D3; E1–E2, E5, E8, E12; F1–F9, F12–F13, F15, F20; G1–G3, G6; H2.
2. **P2** — вторая партия, **P3** — по отзывам.
3. Каждое правило — отдельная функция и тест (`<caret>`-фикстура → ожидаемый текст), плюс отрицательный тест (неоднозначность → пусто).
