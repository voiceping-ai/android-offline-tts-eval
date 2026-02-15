#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
VENV_DIR="$DIR/.venv-nemo"

PYTHON_BIN="${PYTHON_BIN:-python3.12}"

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  echo "Missing $PYTHON_BIN in PATH. Set PYTHON_BIN=python3.12 or install Python 3.12."
  exit 1
fi

"$PYTHON_BIN" -m venv "$VENV_DIR"

source "$VENV_DIR/bin/activate"
python -m pip install --upgrade pip
python -m pip install -r "$DIR/requirements.txt"

echo "✓ NeMo venv ready: $VENV_DIR"

