#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
OUTPUT="$SCRIPT_DIR/output"

rm -rf "$OUTPUT"

java "$REPO_ROOT/src/ParanoidSourceDeobfuscator.java" \
  "$SCRIPT_DIR/fixtures/main" \
  "$OUTPUT" \
  "$SCRIPT_DIR/fixtures/support"

CALLS="$OUTPUT/sources/example/Calls.java"

grep -F 'return "direct-ok";' "$CALLS" >/dev/null
grep -F 'return "wrapper-ok";' "$CALLS" >/dev/null

if grep -E 'getString\([[:space:]]*[+-]?[0-9]+[lL]' "$CALLS" >/dev/null; then
  echo "A literal getString call remains in the patched fixture." >&2
  exit 1
fi

echo "Smoke test passed."
