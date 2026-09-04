#!/usr/bin/env bash
# Single source of Brave launch / brave-mcp flags for this project.
# Other scripts source this file. Do not duplicate these flags elsewhere.
set -euo pipefail

# Prefer an already-set ROOT (ensure_brave/launch set it); else resolve from this file.
if [[ -z "${ROOT:-}" || "$ROOT" == "/" ]]; then
  _SWG_FLAGS="${BASH_SOURCE[0]:-$0}"
  ROOT="$(cd "$(dirname "$_SWG_FLAGS")/.." && pwd)"
  unset _SWG_FLAGS
fi

# Nightly's real profile (test VM). Override with SWG_PROFILE_DIR.
# Isolation: Brave tab containers via --container / launch.sh — not a second user-data-dir.
export SWG_PROFILE_DIR="${SWG_PROFILE_DIR:-$HOME/.config/BraveSoftware/Brave-Browser-Nightly}"

# Prefer Nightly on PATH; allow override.
export SWG_BRAVE_BIN="${SWG_BRAVE_BIN:-$(command -v brave-browser-nightly || command -v brave-browser || true)}"

# Wayland-only desktop (KWin/Plasma / sway). No X11 path.
export SWG_OZONE_PLATFORM="${SWG_OZONE_PLATFORM:-wayland}"

# Viewport for agent sessions (MCP attach metadata / docs)
export SWG_VIEWPORT="${SWG_VIEWPORT:-1280x720}"

# Default Brave tab container for mcp/launch.sh when none is specified.
export SWG_CONTAINER="${SWG_CONTAINER:-misc}"

# Loopback-only remote debugging for MCP attach. Never bind 0.0.0.0.
export SWG_DEBUG_PORT="${SWG_DEBUG_PORT:-9222}"
export SWG_DEBUG_HOST="${SWG_DEBUG_HOST:-127.0.0.1}"

# Runtime files (gitignored via run/)
export SWG_RUN_DIR="${SWG_RUN_DIR:-$ROOT/run}"
export SWG_BRAVE_PID_FILE="${SWG_BRAVE_PID_FILE:-$SWG_RUN_DIR/brave.pid}"
export SWG_BRAVE_LOG_FILE="${SWG_BRAVE_LOG_FILE:-$SWG_RUN_DIR/brave.log}"

swg_debug_url() {
  printf 'http://%s:%s' "${SWG_DEBUG_HOST}" "${SWG_DEBUG_PORT}"
}

# Shared Brave process flags (profile + Wayland + loopback DevTools).
swg_brave_common_args() {
  local args=(
    "--user-data-dir=${SWG_PROFILE_DIR}"
    "--ozone-platform=${SWG_OZONE_PLATFORM}"
    "--remote-debugging-address=${SWG_DEBUG_HOST}"
    "--remote-debugging-port=${SWG_DEBUG_PORT}"
    # QXL / no 3D accel: avoid GPU compositing flicker in this VM.
    "--disable-gpu"
    "--disable-gpu-compositing"
  )
  # Inside swaygentic/bwrap the outer Chromium sandbox is unusable; jail is the sandbox.
  # Host-only runs can set SWG_NO_SANDBOX=0. --test-type hides the unsupported-flag infobar.
  if [[ "${SWG_NO_SANDBOX:-1}" == "1" ]]; then
    args+=("--no-sandbox")
    if [[ "${SWG_TEST_TYPE:-1}" == "1" ]]; then
      args+=("--test-type")
    fi
  fi
  if [[ "${SWG_HEADLESS:-0}" == "1" ]]; then
    args+=("--headless=new")
  fi
  if [[ -n "${SWG_EXTRA_BRAVE_ARGS:-}" ]]; then
    # shellcheck disable=SC2206
    local extra=( ${SWG_EXTRA_BRAVE_ARGS} )
    args+=("${extra[@]}")
  fi
  printf '%s\n' "${args[@]}"
}

# Direct Brave argv (we own the process). Caller prepends $SWG_BRAVE_BIN.
swg_brave_browser_argv() {
  if [[ -z "${SWG_BRAVE_BIN}" || ! -x "${SWG_BRAVE_BIN}" ]]; then
    echo "Brave Nightly not found. Install it or set SWG_BRAVE_BIN." >&2
    return 1
  fi
  printf '%s\n' "${SWG_BRAVE_BIN}"
  swg_brave_common_args
}

# Args passed to `npx` — attach mode (Brave already running).
# Pin the package (default 1.8.0) so spawn is not a registry "@latest" check.
# Override with SWG_BRAVE_MCP_PKG=brave-mcp@x.y.z if needed.
# brave-mcp must not launch a second profile instance.
swg_brave_mcp_args() {
  local args=(
    "-y"
    "${SWG_BRAVE_MCP_PKG:-brave-mcp@1.8.0}"
    "--browser-url=$(swg_debug_url)"
    "--viewport=${SWG_VIEWPORT}"
    "--performance-crux=false"
  )
  printf '%s\n' "${args[@]}"
}

# Resolve a browser alias token to an executable path (or empty if unknown).
swg_resolve_browser_alias() {
  local token="${1,,}"
  case "$token" in
    "" | brave-browser-nightly | nightly | brave-nightly)
      printf '%s\n' "$(command -v brave-browser-nightly || true)"
      ;;
    brave-browser | brave | brave-browser-stable | release)
      printf '%s\n' "$(command -v brave-browser || command -v brave || true)"
      ;;
    brave-browser-beta | beta)
      printf '%s\n' "$(command -v brave-browser-beta || true)"
      ;;
    *)
      return 1
      ;;
  esac
}
