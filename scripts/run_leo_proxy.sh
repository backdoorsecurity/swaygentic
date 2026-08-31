#!/usr/bin/env bash
# Start the Leo → xAI compatibility proxy on 127.0.0.1:8787.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -z "${XAI_API_KEY:-}" && -f "$ROOT/run/xai.env" ]]; then
  # shellcheck disable=SC1091
  set -a
  source "$ROOT/run/xai.env"
  set +a
fi

exec python3 "$ROOT/proxy/leo_proxy.py" "$@"
