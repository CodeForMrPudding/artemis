#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
./gradlew installDist --quiet

demo_dir="$(mktemp -d "$PWD/build/collection-demo.XXXXXX")"
java_exec="java"
if [[ -n "${JAVA_HOME:-}" ]]; then
    java_exec="$JAVA_HOME/bin/java"
fi

# Separate JVMs demonstrate that recovery reads persisted data rather than live objects.
"$java_exec" --class-path 'build/install/artemis/lib/*' demo/CollectionApiDemo.java write "$demo_dir"
"$java_exec" --class-path 'build/install/artemis/lib/*' demo/CollectionApiDemo.java recover "$demo_dir"
echo
echo "Demo data retained at: $demo_dir"
