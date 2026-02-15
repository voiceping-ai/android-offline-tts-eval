#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

BUNDLE_DIR="$ROOT_DIR/artifacts/nemo_bundles/nemo-fastpitch-hifigan-en/"
DEVICE_DIR="/sdcard/Android/data/com.voiceping.ttseval/files/models/nemo-fastpitch-hifigan-en/"

if [ ! -d "$BUNDLE_DIR" ]; then
  echo "Missing bundle dir: $BUNDLE_DIR"
  echo "Run export scripts first:"
  echo "  python3 scripts/nemo/export_fastpitch_onnx.py"
  echo "  python3 scripts/nemo/export_hifigan_onnx.py"
  exit 1
fi

adb push "$BUNDLE_DIR" "$DEVICE_DIR"
echo "✓ Pushed bundle to: $DEVICE_DIR"

