#!/usr/bin/env bash
# Build the jar, put it into a test instance, run it. Usage: scripts/t.sh INSTANCE [instance.py run options]
cd "$(dirname "$0")/.." || exit 1
export JAVA_HOME="/c/Program Files/Zulu/zulu-21"
NAME="$1"
shift
./gradlew build --console=plain -q 2>&1 | grep -E "error:|FAILED|\.java:[0-9]+:" | head -20
if [ "${PIPESTATUS[0]}" != "0" ]; then echo "build failed"; exit 1; fi
python scripts/instance.py install "$NAME" && python scripts/instance.py run "$NAME" "$@" 2>&1 | cut -c1-320
