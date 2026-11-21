#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <native-executable>" >&2
    exit 2
fi

executable="$1"
spike_dir="$(mktemp -d "${TMPDIR:-/tmp}/artemis-native-spike.XXXXXX")"
trap 'rm -rf "$spike_dir"' EXIT

"$executable" write "$spike_dir/db"
"$executable" read "$spike_dir/db"
"$executable" roundtrip "$spike_dir/db"

# The production API exercises column families, snapshots, recovery, DI and writer threads.
"$executable" api-write "$spike_dir/api-db"
"$executable" api-read "$spike_dir/api-db"

# This exercises the native-to-Java RocksDBException/Status JNI constructors.
printf 'not a directory\n' > "$spike_dir/file"
if "$executable" write "$spike_dir/file" > "$spike_dir/error.log" 2>&1; then
    echo "FAIL: native write accepted a file as its database directory" >&2
    exit 1
fi
if ! grep -q '^INTERNAL:' "$spike_dir/error.log" || ! grep -q 'org.rocksdb.RocksDBException' "$spike_dir/error.log"; then
    cat "$spike_dir/error.log" >&2
    echo "FAIL: native storage error was not mapped with its RocksDB cause" >&2
    exit 1
fi

if "$executable" read "$spike_dir/absent" > "$spike_dir/error.log" 2>&1; then
    echo "FAIL: native read accepted a nonexistent database" >&2
    exit 1
fi
if [[ -e "$spike_dir/absent" ]] || ! grep -q '^INTERNAL:' "$spike_dir/error.log"; then
    cat "$spike_dir/error.log" >&2
    echo "FAIL: read created a database or failed without the expected code" >&2
    exit 1
fi

set +e
"$executable" > "$spike_dir/usage.log" 2>&1
usage_status=$?
set -e
if [[ $usage_status -ne 2 ]] || ! grep -q '^Usage:' "$spike_dir/usage.log"; then
    cat "$spike_dir/usage.log" >&2
    echo "FAIL: missing arguments did not return usage and exit status 2" >&2
    exit 1
fi

echo 'NATIVE_ROCKSDB_SPIKE_OK (batch, fresh-process API recovery/search, JNI errors, CLI usage)'
