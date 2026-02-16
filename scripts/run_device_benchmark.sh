#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT_DIR/VoicePingAndroidOfflineTtsEval"

RUN_MODE="${RUN_MODE:-warm}"
WARM_ITERATIONS="${WARM_ITERATIONS:-3}"
THREADS="${THREADS:-4}"
SPEAKER_ID="${SPEAKER_ID:-0}"
SPEED="${SPEED:-1.0}"
DOWNLOAD_HF="${DOWNLOAD_HF:-true}"
DELETE_AFTER="${DELETE_AFTER:-true}"

# Prefer a valid ANDROID_SDK_ROOT (some environments set it to a non-existent subdir).
SDK_ROOT="${ANDROID_SDK_ROOT:-}"
if [ -z "$SDK_ROOT" ] || [ ! -d "$SDK_ROOT" ]; then
  SDK_ROOT="${ANDROID_HOME:-}"
fi
if [ -n "$SDK_ROOT" ] && [ -d "$SDK_ROOT" ]; then
  export ANDROID_SDK_ROOT="$SDK_ROOT"
fi

# Default: the initial planned model set (system + sherpa models).
# NOTE: Allow MODEL_IDS="" (empty) to run all selectable models.
if [ -z "${MODEL_IDS+x}" ]; then
  MODEL_IDS="android-system-tts,kokoro-en-v0-19,kokoro-int8-multi-lang-v1-1,vits-piper-en-us-amy-low,vits-piper-en-us-ryan-low,matcha-icefall-en-us-ljspeech-hifigan,kitten-nano-en-v0-2-fp16"
fi

# adb shell strips empty arguments. Use explicit sentinel for "all models".
MODEL_IDS_ARG="$MODEL_IDS"
if [ -z "$MODEL_IDS_ARG" ]; then
  MODEL_IDS_ARG="__ALL_MODELS__"
fi

echo "Installing app + test APKs…"
(
  cd "$APP_DIR"
  ./gradlew :app:installDebug :app:installDebugAndroidTest
)

INSTR_LINE="$(adb shell pm list instrumentation | grep -F 'com.voiceping.ttseval' | head -n 1 || true)"
if [ -z "$INSTR_LINE" ]; then
  echo "Could not find instrumentation for com.voiceping.ttseval. Is the test APK installed?"
  adb shell pm list instrumentation | head -n 50 || true
  exit 1
fi

COMPONENT="$(echo "$INSTR_LINE" | sed -n 's/^instrumentation:\([^ ]*\).*/\1/p')"
if [ -z "$COMPONENT" ]; then
  echo "Failed to parse instrumentation component from: $INSTR_LINE"
  exit 1
fi

echo "Running benchmark instrumentation:"
echo "  component: $COMPONENT"
echo "  model_ids: $MODEL_IDS"
echo "  run_mode: $RUN_MODE warm_iterations=$WARM_ITERATIONS threads=$THREADS speed=$SPEED speaker_id=$SPEAKER_ID"
echo "  download_hf: $DOWNLOAD_HF delete_after: $DELETE_AFTER"

adb shell am instrument -w -r \
  -e class com.voiceping.offlinettseval.TtsAllModelsBenchmarkTest \
  -e model_ids "$MODEL_IDS_ARG" \
  -e run_mode "$RUN_MODE" \
  -e warm_iterations "$WARM_ITERATIONS" \
  -e threads "$THREADS" \
  -e speaker_id "$SPEAKER_ID" \
  -e speed "$SPEED" \
  -e download_hf "$DOWNLOAD_HF" \
  -e delete_after "$DELETE_AFTER" \
  "$COMPONENT"

echo "Benchmark finished."
