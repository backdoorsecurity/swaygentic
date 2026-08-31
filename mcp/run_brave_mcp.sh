#!/usr/bin/env bash
# Thin wrapper: Grok Build MCP entrypoint for Brave Nightly (brave-mcp).
# Flags come only from brave_flags.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=brave_flags.sh
source "$ROOT/mcp/brave_flags.sh"

if ! command -v npx >/dev/null 2>&1; then
  echo "npx not found. Install Node.js LTS, then retry." >&2
  exit 1
fi

if [[ -z "${GIB_BRAVE_BIN}" || ! -x "${GIB_BRAVE_BIN}" ]]; then
  echo "Brave Nightly not found. Install it (see README) or set GIB_BRAVE_BIN." >&2
  exit 1
fi

# Ensure Wayland socket is visible to the child when the session has one.
if [[ -z "${WAYLAND_DISPLAY:-}" && -S "/run/user/$(id -u)/wayland-1" ]]; then
  export WAYLAND_DISPLAY=wayland-1
fi
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"

mapfile -t MCP_ARGS < <(gib_brave_mcp_args)
exec npx "${MCP_ARGS[@]}"
