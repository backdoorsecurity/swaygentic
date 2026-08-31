#!/usr/bin/env bash
# Acceptance: MCP registered; no forbidden X11 / open-debug leftovers.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "== MCP registered =="
grep -n 'mcp_servers' .grok/config.toml
grep -n 'brave-devtools\|run_brave_mcp' .grok/config.toml

echo "== Wrapper + flags =="
test -x mcp/run_brave_mcp.sh
test -f mcp/brave_flags.sh
# Show default (non-headless) args so this smoke is stable across shells.
# shellcheck source=../mcp/brave_flags.sh
GIB_HEADLESS=0 source mcp/brave_flags.sh
mapfile -t ARGS < <(GIB_HEADLESS=0 gib_brave_mcp_args)
printf 'brave-mcp args:\n'
printf '  %s\n' "${ARGS[@]}"
printf '%s\n' "${ARGS[@]}" | grep -q 'executable-path\|channel=nightly'
printf '%s\n' "${ARGS[@]}" | grep -q "user-data-dir=${GIB_PROFILE_DIR}"

echo "== Forbidden patterns in implementation (must be empty) =="
# Docs may name these as bans; fail only if code/config would use them.
HITS="$(grep -RInE 'xdotool|ydotool|Xvfb|/tmp/\.X11-unix|0\.0\.0\.0:9222' \
  --exclude-dir=.git --exclude-dir=profiles --exclude-dir=node_modules \
  mcp proxy .grok 2>/dev/null || true)"
# scripts: ignore this smoke file's own pattern string
HITS+=$'\n'"$(grep -RInE 'xdotool|ydotool|Xvfb|/tmp/\.X11-unix|0\.0\.0\.0:9222' \
  scripts --exclude='smoke_mcp_config.sh' 2>/dev/null || true)"
HITS="$(printf '%s\n' "$HITS" | sed '/^$/d')"
if [[ -n "$HITS" ]]; then
  echo "$HITS"
  echo "FAIL: found forbidden patterns in implementation" >&2
  exit 1
fi
echo "(none)"

echo "== Node / Brave presence =="
if command -v npx >/dev/null 2>&1; then
  echo "npx: $(command -v npx)"
else
  echo "WARN: npx missing — install Node.js before Phase 2 MCP works"
fi
if command -v brave-browser-nightly >/dev/null 2>&1; then
  brave-browser-nightly --version || true
else
  echo "WARN: brave-browser-nightly not on PATH"
fi

echo "OK: MCP config smoke passed"
