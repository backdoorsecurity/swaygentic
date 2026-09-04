#!/usr/bin/env bash
# Acceptance: MCP registered; attach-mode flags; no forbidden X11 / open-debug leftovers.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "== MCP registered =="
grep -n 'mcp_servers' .grok/config.toml
grep -n 'brave-devtools\|run_brave_mcp' .grok/config.toml
grep -n 'winctl\|mcp/winctl/launch' .grok/config.toml
grep -q 'max_output_bytes' .grok/config.toml
grep -q 'MCPTool(brave-devtools__\*)' .grok/config.toml
grep -q 'MCPTool(winctl__\*)' .grok/config.toml
grep -q '\[mcp_servers.winctl\]' .grok/config.toml

echo "== Wrapper + flags =="
test -x mcp/run_brave_mcp.sh
test -x mcp/ensure_brave.sh
test -x mcp/launch.sh
test -x mcp/new_tab.sh
test -x mcp/winctl/launch.sh
test -x scripts/winctl-vnc.sh
test -x scripts/smoke_winctl.sh
test -f mcp/brave_flags.sh
# CLI helper must match MCP/smoke default domain (not stale win10).
grep -q 'WINCTL_DOMAIN:-V2_tiny-10' scripts/winctl-vnc.sh
grep -q 'WINCTL_DOMAIN:-V2_tiny-10' mcp/winctl/launch.sh
# shellcheck source=../mcp/brave_flags.sh
source mcp/brave_flags.sh
mapfile -t ARGS < <(swg_brave_mcp_args)
printf 'brave-mcp args (attach mode):\n'
printf '  %s\n' "${ARGS[@]}"
printf '%s\n' "${ARGS[@]}" | grep -Fq -- "--browser-url=$(swg_debug_url)"
# Attach mode must not launch a second profile via mcp.
if printf '%s\n' "${ARGS[@]}" | grep -q 'user-data-dir'; then
  echo "FAIL: attach-mode mcp args still pass user-data-dir" >&2
  exit 1
fi
case "${SWG_PROFILE_DIR}" in
  */BraveSoftware/Brave-Browser-Nightly|*/BraveSoftware/Brave-Browser-Nightly/) ;;
  *)
    echo "WARN: SWG_PROFILE_DIR is not Nightly default (${SWG_PROFILE_DIR})"
    ;;
esac
mapfile -t BRAVE_ARGS < <(swg_brave_browser_argv)
printf 'brave browser argv:\n'
printf '  %s\n' "${BRAVE_ARGS[@]}"
printf '%s\n' "${BRAVE_ARGS[@]}" | grep -Fq -- "--remote-debugging-address=${SWG_DEBUG_HOST}"
printf '%s\n' "${BRAVE_ARGS[@]}" | grep -Fq -- "--remote-debugging-port=${SWG_DEBUG_PORT}"
printf '%s\n' "${BRAVE_ARGS[@]}" | grep -Fq -- "--user-data-dir=${SWG_PROFILE_DIR}"
printf '%s\n' "${BRAVE_ARGS[@]}" | grep -Fq -- '--no-sandbox'
printf '%s\n' "${BRAVE_ARGS[@]}" | grep -Fq -- '--test-type'
test -x mcp/jail-run.sh
test -x mcp/swaygentic

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
