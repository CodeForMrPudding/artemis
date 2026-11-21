#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
demo_vectors=1000
demo_dimensions=128
demo_warmup=1000
demo_samples=5000
while [[ $# -gt 0 ]]; do
    case "$1" in
        --vectors|--dimensions|--warmup|--samples)
            [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || { echo "Expected integer after $1" >&2; exit 2; }
            case "$1" in
                --vectors) demo_vectors="$2" ;;
                --dimensions) demo_dimensions="$2" ;;
                --warmup) demo_warmup="$2" ;;
                --samples) demo_samples="$2" ;;
            esac
            shift 2 ;;
        *) echo "Usage: bash demo/full-demo.sh [--vectors N] [--dimensions N] [--warmup N] [--samples N]" >&2; exit 2 ;;
    esac
done

demo_java="java"
if [[ -n "${JAVA_HOME:-}" ]]; then demo_java="$JAVA_HOME/bin/java"; fi
mkdir -p build
demo_run_dir="$(mktemp -d "$PWD/build/full-demo.XXXXXX")"
mkdir -p "$demo_run_dir/logs" "$demo_run_dir/test-results"
echo "Full demo evidence: $demo_run_dir"

record_stage() {
    python3 - "$demo_run_dir" "$1" "$2" "$3" "${@:4}" <<'PY'
import json, sys
from pathlib import Path
directory, name, status, log, *command = sys.argv[1:]
path = Path(directory, 'stages.json')
stages = json.loads(path.read_text()) if path.exists() else []
stages.append({'name': name, 'status': status, 'log': 'logs/' + log, 'command': ' '.join(command)})
path.write_text(json.dumps(stages, indent=2))
PY
}

run_stage() {
    local demo_name="$1"
    shift
    echo
    echo "=== $demo_name ==="
    local demo_exit=0
    "$@" > "$demo_run_dir/logs/$demo_name.log" 2>&1 || demo_exit=$?
    if [[ $demo_exit -eq 0 ]]; then
        record_stage "$demo_name" PASS "$demo_name.log" "$@"
        cat "$demo_run_dir/logs/$demo_name.log"
    else
        record_stage "$demo_name" FAIL "$demo_name.log" "$@"
        cat "$demo_run_dir/logs/$demo_name.log" >&2
        return "$demo_exit"
    fi
}

finish_failed() {
    local demo_exit=$?
    if [[ $demo_exit -ne 0 ]]; then
        echo "Demo stopped with status $demo_exit. Preserving all evidence in $demo_run_dir" >&2
        python3 demo/demo_report.py "$demo_run_dir" --test-results "$demo_run_dir/test-results" > "$demo_run_dir/logs/failure-report.log" 2>&1 || true
        echo "Failure report: $demo_run_dir/report.html" >&2
    fi
}
trap finish_failed EXIT

run_stage reporter-tests python3 demo/test_demo_report.py
# Remove prior test evidence and execute every scenario afresh; performance is a separate JVM.
run_stage correctness ./gradlew cleanTest test installDist --console=plain "-PdemoResultsDir=$demo_run_dir/test-results"

run_stage walkthrough-write "$demo_java" --class-path 'build/install/artemis/lib/*' demo/CollectionApiDemo.java write "$demo_run_dir/walkthrough-db"
run_stage walkthrough-recover "$demo_java" --class-path 'build/install/artemis/lib/*' demo/CollectionApiDemo.java recover "$demo_run_dir/walkthrough-db"

demo_graal="${GRAALVM_HOME:-}"
if [[ -z "$demo_graal" ]]; then
    for demo_candidate in .tools/graalvm*/Contents/Home .tools/graalvm*; do
        if [[ -x "$demo_candidate/bin/native-image" ]]; then
            demo_graal="$(cd "$demo_candidate" && pwd)"
            break
        fi
    done
fi
if [[ -z "$demo_graal" || ! -x "$demo_graal/bin/native-image" ]]; then
    record_stage native FAIL native.log "Set GRAALVM_HOME to GraalVM JDK 21 to run the spec's native check"
    echo 'Full native verification requires GRAALVM_HOME pointing to GraalVM JDK 21.' >&2
    exit 2
fi
run_stage native env JAVA_HOME="$demo_graal" GRAALVM_HOME="$demo_graal" ./gradlew nativeRocksDbSpike --console=plain

# Show every mapped edge case before starting the longer latency workload.
python3 demo/demo_report.py "$demo_run_dir" --test-results "$demo_run_dir/test-results" --spec-only
run_stage benchmark "$demo_java" -Xms256m -Xmx1g --class-path 'build/install/artemis/lib/*' demo/LatencyBenchmark.java \
    "$demo_run_dir" "$demo_vectors" "$demo_dimensions" "$demo_warmup" "$demo_samples"
python3 demo/demo_report.py "$demo_run_dir" --test-results "$demo_run_dir/test-results" | tee "$demo_run_dir/summary.txt"
echo
echo "Open: $demo_run_dir/report.html"
