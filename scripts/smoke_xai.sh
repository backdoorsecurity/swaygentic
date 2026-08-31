#!/usr/bin/env bash
# Smoke: xAI Chat Completions with $XAI_API_KEY (or run/xai.env).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -z "${XAI_API_KEY:-}" && -f "$ROOT/run/xai.env" ]]; then
  # shellcheck disable=SC1091
  set -a
  source "$ROOT/run/xai.env"
  set +a
fi

if [[ -z "${XAI_API_KEY:-}" ]]; then
  echo "XAI_API_KEY is not set. Export it or put it in run/xai.env (mode 0600)." >&2
  echo "Create a key at https://console.x.ai/ — Leo BYOM needs a personal API key." >&2
  exit 1
fi

MODEL="${XAI_MODEL:-grok-4.6}"
ENDPOINT="${XAI_CHAT_URL:-https://api.x.ai/v1/chat/completions}"

echo "POST $ENDPOINT model=$MODEL"
RESP="$(curl -sS -w "\n%{http_code}" "$ENDPOINT" \
  -H "Authorization: Bearer ${XAI_API_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"${MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":32}")"

HTTP_CODE="$(printf '%s\n' "$RESP" | tail -n1)"
BODY="$(printf '%s\n' "$RESP" | sed '$d')"

echo "$BODY" | python3 -m json.tool 2>/dev/null || echo "$BODY"
echo "HTTP $HTTP_CODE"

if [[ "$HTTP_CODE" != "200" ]]; then
  echo "FAIL: expected HTTP 200" >&2
  exit 1
fi

if ! printf '%s' "$BODY" | grep -q '"content"'; then
  echo "FAIL: response missing content" >&2
  exit 1
fi

echo "OK: xAI Chat Completions reachable"
