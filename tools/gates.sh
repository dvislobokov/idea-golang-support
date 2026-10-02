#!/usr/bin/env bash
# Runs project gates and prints only the lines that matter: metrics, benchmark results, failures and the
# build result. The full Gradle output of every gate is kept in build/gates/<gate>.log.
#
# Usage: tools/gates.sh <gate>... [-- <extra gradle args>]
#   test | build                     ./gradlew test | build
#   corpus[:core|semantic|ide]       corpusTest of one module, or all three in order
#   bench[:core|semantic|ide]        benchmark of one module, or the root aggregate
#   :module:task                     any Gradle task, filtered the same way
# Examples:
#   tools/gates.sh corpus:semantic -- --tests '*GorootCheckCorpusTest*'
#   tools/gates.sh test bench:semantic
set -u
cd "$(dirname "$0")/.." || exit 2

gates=(); extra=()
while [ $# -gt 0 ]; do
    if [ "$1" = "--" ]; then shift; extra=("$@"); break; fi
    gates+=("$1"); shift
done
[ ${#gates[@]} -gt 0 ] || { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

module() { case "$1" in core|semantic|ide) echo ":go-psi-$1" ;; *) echo "unknown module: $1" >&2; exit 2 ;; esac; }

tasks_for() {
    case "$1" in
        test|build) echo "$1" ;;
        corpus) echo ":go-psi-core:corpusTest :go-psi-semantic:corpusTest :go-psi-ide:corpusTest" ;;
        corpus:*) m=$(module "${1#corpus:}") || exit 2; echo "$m:corpusTest" ;;
        bench) echo "benchmark" ;;
        bench:*) m=$(module "${1#bench:}") || exit 2; echo "$m:benchmark" ;;
        :*) echo "$1" ;;
        *) echo "unknown gate: $1" >&2; exit 2 ;;
    esac
}

# Keeps what tests print themselves (the STANDARD_OUT blocks of test methods: corpus summaries, BENCH lines,
# failure samples) minus platform log lines, plus compile errors, failed tests/tasks and the build result.
filter() {
    sed 's/\r$//' "$1" | awk '
        /^[^ ].* > .* STANDARD_(OUT|ERROR)$/ { inTest = 1; print "-- " $1 " > " $3; next }
        /^[^ ].* > .* FAILED$/ { inTest = 0; failLines = 8; print; next }
        failLines > 0 && /^    / { failLines--; print; next }
        /^[^ ]/ { inTest = 0; failLines = 0 }
        /JPLISAgent|ASSERTION FAILED|\[cds\]/ { next }
        inTest && /^    / {
            if ($0 ~ /^    ([0-9]{4}-[0-9]{2}-[0-9]{2}|\[ *[0-9]+\] +(INFO|WARN|ERROR|DEBUG))/) next
            print substr($0, 5); next
        }
        /^e: |regressed|[0-9]+ tests? completed|^BUILD (SUCCESSFUL|FAILED)|^FAILURE: |^> Task .* FAILED/ { print }
        /^\* What went wrong/ { wrong = 1; print; next }
        wrong { if ($0 ~ /^$|^\* Try/) wrong = 0; else print; next }
    ' | head -n 400
}

mkdir -p build/gates
status=0
for gate in "${gates[@]}"; do
    spec=$(tasks_for "$gate") || exit 2
    read -r -a tasks <<< "$spec"
    log="build/gates/${gate//[:\/]/_}.log"
    start=$(date +%s)
    ./gradlew "${tasks[@]}" --console=plain "${extra[@]}" > "$log" 2>&1
    rc=$?
    echo "== $gate (${tasks[*]}) exit=$rc $(( $(date +%s) - start ))s  log: $log"
    filter "$log"
    [ $rc -eq 0 ] || status=$rc
done
exit $status
