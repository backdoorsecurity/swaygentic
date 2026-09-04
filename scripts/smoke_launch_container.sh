#!/usr/bin/env bash
# Acceptance: containerized launch + DevTools handoff (no ProcessSingleton).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=../mcp/brave_flags.sh
source mcp/brave_flags.sh

export SWG_FORCE_QUIT_BRAVE="${SWG_FORCE_QUIT_BRAVE:-1}"

echo "== ensure + launch misc:example.com =="
./mcp/launch.sh "misc:https://example.com"
sleep 1
curl -fsS "$(swg_debug_url)/json/version" >/dev/null
LIST="$(curl -fsS "$(swg_debug_url)/json/list")"
printf '%s\n' "$LIST" | grep -q 'example.com'
echo "devtools sees example.com"

echo "== second launch (handoff) misc:example.org =="
OUT="$(./mcp/launch.sh "misc:https://example.org" 2>&1 || true)"
printf '%s\n' "$OUT"
printf '%s\n' "$OUT" | grep -qi 'ProcessSingleton' && {
  echo "FAIL: ProcessSingleton on handoff" >&2
  exit 1
}
printf '%s\n' "$OUT" | grep -q "opened https://example.org in container"
sleep 1
curl -fsS "$(swg_debug_url)/json/list" | grep -q 'example.org'
echo "devtools sees example.org"

echo "== browser:container:url form =="
./mcp/launch.sh "brave-browser-nightly:misc:https://example.net" >/tmp/swg-launch-form.out
grep -q "opened https://example.net in container" /tmp/swg-launch-form.out

echo "OK: launch container smoke passed"
