#!/usr/bin/env bash
# Unattended test run: builds, starts the client in autotest mode, and prints a summary.
# Usage: scripts/autotest.sh [pack-name] [extra gradle args...]
# Outputs in run/autotest/: screenshot.png, result.txt, latest.log, validation.log
set -u
cd "$(dirname "$0")/.."

if [ -d "/c/Program Files/Zulu/zulu-21" ]; then
	export JAVA_HOME="/c/Program Files/Zulu/zulu-21"
fi

PACK="${1:-}"
shift || true
OUT=run/autotest
mkdir -p "$OUT"
rm -f "$OUT"/screenshot.png "$OUT"/result.txt "$OUT"/validation.log "$OUT"/latest.log "$OUT"/console.log

./gradlew runAutotest --console=plain -Pautotest.pack="$PACK" "$@" > "$OUT/console.log" 2>&1
echo "gradle exit: $?"

cp -f run/logs/latest.log "$OUT/latest.log" 2>/dev/null
echo "--- result.txt"
cat "$OUT/result.txt" 2>/dev/null || echo "(missing)"
VLOG="$OUT/validation.log"
echo "--- validation: $(grep -c 'Validation Error' "$VLOG" 2>/dev/null) errors, $(grep -c 'Validation Warning' "$VLOG" 2>/dev/null) warnings"
# Known VulkanMod Reforged baseline (see PORTING.md): vkQueueSubmit-pSignalSemaphores-00067, vkDestroyDevice-device-05137
grep -oE 'VUID-[A-Za-z0-9_-]+' "$VLOG" 2>/dev/null | sort | uniq -c | sort -rn | head -15
echo "--- log errors"
grep -E '\[[^]]*/(ERROR|FATAL)\]|Exception|OpalCompat' "$OUT/latest.log" 2>/dev/null | grep -v 'mixin/\]: Error loading' | cut -c1-400 | head -60
