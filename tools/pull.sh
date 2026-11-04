#!/usr/bin/env bash
# Pull exports from the watch's private app storage (debug build only).
# Usage: tools/pull.sh [file]   e.g. tools/pull.sh probe-latest.json   (default: all files)
set -euo pipefail
PKG=dev.kafli.rftoolkit
OUT="$(dirname "$0")/out"
mkdir -p "$OUT"
if [[ $# -gt 0 ]]; then
  files=("$@")
else
  mapfile -t files < <(adb exec-out run-as "$PKG" ls files/export)
fi
for f in "${files[@]}"; do
  adb exec-out run-as "$PKG" cat "files/export/$f" > "$OUT/$f"
  echo "pulled $OUT/$f"
done
