#!/usr/bin/env bash
# Smoke the Leo compatibility proxy against a fake upstream-shaped rewrite.
# Full upstream test still needs XAI_API_KEY (see smoke_xai.sh).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

python3 - <<'PY'
import json
import subprocess
import sys
import time
import urllib.request

# Import rewrite helper by running a tiny unit check inline
sys.path.insert(0, "proxy")
from leo_proxy import rewrite_body, STRIP_KEYS

raw = json.dumps({
    "model": "grok-4.6",
    "messages": [{"role": "user", "content": "hi"}],
    "temperature": 0.7,
    "top_p": 0.9,
    "stream": False,
}).encode()
out = json.loads(rewrite_body(raw))
assert "temperature" not in out, out
assert "top_p" not in out, out
assert out["model"] == "grok-4.6"
assert out["messages"][0]["content"] == "hi"
print("OK: rewrite_body strips", sorted(STRIP_KEYS & set(json.loads(raw))))
PY

# Boot proxy briefly and hit /healthz
PORT=18787
python3 "$ROOT/proxy/leo_proxy.py" --host 127.0.0.1 --port "$PORT" &
PID=$!
cleanup() { kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; }
trap cleanup EXIT

for i in 1 2 3 4 5; do
  if curl -fsS "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1; then
    break
  fi
  sleep 0.2
done

curl -fsS "http://127.0.0.1:${PORT}/healthz" | python3 -m json.tool
echo "OK: leo proxy healthz"
