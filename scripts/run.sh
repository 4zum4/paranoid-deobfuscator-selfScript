#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
TOOL="$REPO_ROOT/src/ParanoidSourceDeobfuscator.java"

if ! command -v java >/dev/null 2>&1; then
  echo "[-] java was not found. Install JDK 17 or newer, or add Java to PATH." >&2
  exit 1
fi

if [ ! -f "$TOOL" ]; then
  echo "[-] Tool source was not found: $TOOL" >&2
  exit 1
fi

exec java "$TOOL" "$@"
