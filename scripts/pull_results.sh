#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT_DIR/artifacts/pulled"

mkdir -p "$OUT_DIR"
mkdir -p "$OUT_DIR/tts_eval"

RUN_ID="${1:-}"
SRC_BASE="/sdcard/Android/data/com.voiceping.ttseval/files/exports/tts_eval"

# Pull evaluation artifacts from a physical Android device.
if [ -n "$RUN_ID" ]; then
  adb pull "$SRC_BASE/$RUN_ID" "$OUT_DIR/tts_eval/$RUN_ID"
  echo "Pulled results to: $OUT_DIR/tts_eval/$RUN_ID"
else
  adb pull "$SRC_BASE" "$OUT_DIR/"
  echo "Pulled results to: $OUT_DIR/tts_eval"
fi
