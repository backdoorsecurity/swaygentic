#!/usr/bin/env bash
# Thin wrapper: Grok Build MCP entrypoint for Brave Nightly (brave-mcp).
# We own the Brave process (ensure_brave.sh); MCP attaches via loopback DevTools.
# Flags come only from brave_flags.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=brave_flags.sh
source "$ROOT/mcp/brave_flags.sh"

if ! command -v npx >/dev/null 2>&1; then
  echo "npx not found. Install Node.js LTS, then retry." >&2
  exit 1
fi

if [[ -z "${SWG_BRAVE_BIN}" || ! -x "${SWG_BRAVE_BIN}" ]]; then
  echo "Brave Nightly not found. Install it (see README) or set SWG_BRAVE_BIN." >&2
  exit 1
fi

# Headed Brave needs the real Wayland/DBus session. MCP children are often a
# headless TTY with an empty env — fill only what is missing.
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
if [[ -z "${WAYLAND_DISPLAY:-}" ]]; then
  if [[ -S "${XDG_RUNTIME_DIR}/wayland-1" ]]; then
    export WAYLAND_DISPLAY=wayland-1
  elif [[ -S "${XDG_RUNTIME_DIR}/wayland-0" ]]; then
    export WAYLAND_DISPLAY=wayland-0
  fi
fi
if [[ -z "${DBUS_SESSION_BUS_ADDRESS:-}" && -S "${XDG_RUNTIME_DIR}/bus" ]]; then
  export DBUS_SESSION_BUS_ADDRESS="unix:path=${XDG_RUNTIME_DIR}/bus"
fi
export XDG_SESSION_TYPE="${XDG_SESSION_TYPE:-wayland}"

# Start (or reuse) Brave with loopback debugging, then attach — do not let
# brave-mcp launch a second profile instance.
SWG_FORCE_QUIT_BRAVE="${SWG_FORCE_QUIT_BRAVE:-0}" \
  "$ROOT/mcp/ensure_brave.sh"

mapfile -t MCP_ARGS < <(swg_brave_mcp_args)
exec npx "${MCP_ARGS[@]}"
