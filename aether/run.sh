#!/usr/bin/env bash
# Start the AETHER AI service and live dashboard.
# Usage: bash aether/run.sh   (or AETHER_PORT=8080 bash aether/run.sh)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
VENV="$ROOT/.venv"
if [ ! -x "$VENV/bin/python" ]; then
  echo "[aether] creating Python virtualenv…"
  python3 -m venv "$VENV"
fi
if ! "$VENV/bin/python" -c 'import cryptography' 2>/dev/null; then
  echo "[aether] installing dependencies…"
  "$VENV/bin/pip" install -q -r aether/requirements.txt
fi
export PYTHONPATH="$ROOT/aether${PYTHONPATH:+:$PYTHONPATH}"
export PYTHONUNBUFFERED=1
export AETHER_HOST="${AETHER_HOST:-0.0.0.0}"
export AETHER_PORT="${AETHER_PORT:-8000}"
exec "$VENV/bin/python" -m core.api
