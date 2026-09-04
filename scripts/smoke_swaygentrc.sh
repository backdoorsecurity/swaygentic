#!/usr/bin/env bash
# Smoke: swaygentrc phone API binds, /health works, /grok/status needs token.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$ROOT/swaygentrc/server"
export SWAYGENTRC_FOREGROUND=1
export SWAYGENTRC_SKIP_SYSTEMD=1
export SWAYGENTIC_OFF="${SWAYGENTIC_OFF:-1}"
# Force loopback for smoke (no Tailscale required).
export SWAYGENTRC_HTTP_HOST=127.0.0.1

if [[ ! -x "${SWAYGENTRC_GROK_BIN:-$HOME/.grok/bin/grok}" ]]; then
  echo "grok binary missing; install Grok Build first" >&2
  exit 1
fi

# Fixed smoke port. Persist override would clobber the live phone port — save/restore.
export SWAYGENTRC_HTTP_PORT="${SWAYGENTRC_HTTP_PORT:-39111}"
PORT_FILE="$SERVER/run/http.port"
CRED_FILE="$SERVER/run/credentials.swaygentrc"
PORT_BAK="$(mktemp)"
CRED_BAK="$(mktemp)"
HAD_PORT=0
HAD_CRED=0
if [[ -f "$PORT_FILE" ]]; then
  cp -a "$PORT_FILE" "$PORT_BAK"
  HAD_PORT=1
fi
if [[ -f "$CRED_FILE" ]]; then
  cp -a "$CRED_FILE" "$CRED_BAK"
  HAD_CRED=1
fi

LOG="$(mktemp)"
cleanup() {
  if [[ -n "${PID:-}" ]] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  rm -f "$LOG"
  if [[ "$HAD_PORT" == 1 ]]; then
    cp -a "$PORT_BAK" "$PORT_FILE"
  else
    rm -f "$PORT_FILE"
  fi
  if [[ "$HAD_CRED" == 1 ]]; then
    cp -a "$CRED_BAK" "$CRED_FILE"
  fi
  rm -f "$PORT_BAK" "$CRED_BAK"
}
trap cleanup EXIT

python3 "$SERVER/main.py" >"$LOG" 2>&1 &
PID=$!

ok=0
for _ in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:${SWAYGENTRC_HTTP_PORT}/health" >/dev/null 2>&1; then
    ok=1
    break
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "server exited early:" >&2
    cat "$LOG" >&2
    exit 1
  fi
  sleep 0.25
done
if [[ "$ok" != 1 ]]; then
  echo "health check timed out:" >&2
  cat "$LOG" >&2
  exit 1
fi

health="$(curl -fsS "http://127.0.0.1:${SWAYGENTRC_HTTP_PORT}/health")"
echo "health: $health"

token="$(tr -d '\n' < "$SERVER/run/api.token")"
code="$(curl -sS -o /tmp/swaygentrc-status.json -w '%{http_code}' \
  -H "Authorization: Bearer ${token}" \
  "http://127.0.0.1:${SWAYGENTRC_HTTP_PORT}/grok/status")"
echo "status http: $code"
python3 - <<'PY'
import json
print(json.dumps(json.load(open("/tmp/swaygentrc-status.json")), indent=2)[:800])
PY

unauth="$(curl -sS -o /dev/null -w '%{http_code}' \
  "http://127.0.0.1:${SWAYGENTRC_HTTP_PORT}/grok/status")"
echo "unauth status http: $unauth (expect 401)"

test -f "$SERVER/run/credentials.swaygentrc"
test -f "$SERVER/run/http.port"
echo "credentials: $SERVER/run/credentials.swaygentrc"
echo "http.port: $(tr -d '\n' < "$SERVER/run/http.port")"
echo "OK swaygentrc smoke"
