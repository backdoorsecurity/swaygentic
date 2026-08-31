#!/usr/bin/env bash
# Single source of Brave launch / brave-mcp flags for this project.
# Other scripts source this file. Do not duplicate these flags elsewhere.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Isolated agent profile — never the operator's daily Brave profile.
export GIB_PROFILE_DIR="${GIB_PROFILE_DIR:-$ROOT/profiles/agent}"

# Prefer Nightly on PATH; allow override.
export GIB_BRAVE_BIN="${GIB_BRAVE_BIN:-$(command -v brave-browser-nightly || command -v brave-browser || true)}"

# Wayland-only desktop (KWin/Plasma / sway). No X11 path.
export GIB_OZONE_PLATFORM="${GIB_OZONE_PLATFORM:-wayland}"

# Viewport for agent sessions
export GIB_VIEWPORT="${GIB_VIEWPORT:-1280x720}"

mkdir -p "$GIB_PROFILE_DIR"

# Args passed to `npx brave-mcp@latest` (one array, one owner).
# brave-mcp launches Brave with pipe-based CDP by default — never bind debug on all interfaces.
# Note: --channel and --executable-path are mutually exclusive in brave-mcp.
gib_brave_mcp_args() {
  local args=(
    "-y"
    "brave-mcp@latest"
    "--user-data-dir=${GIB_PROFILE_DIR}"
    "--viewport=${GIB_VIEWPORT}"
    "--performance-crux=false"
    "--brave-arg=--ozone-platform=${GIB_OZONE_PLATFORM}"
    "--brave-arg=--disable-features=TranslateUI"
  )
  if [[ -n "${GIB_BRAVE_BIN}" ]]; then
    args+=("--executable-path=${GIB_BRAVE_BIN}")
  else
    args+=("--channel=nightly")
  fi
  if [[ "${GIB_HEADLESS:-0}" == "1" ]]; then
    args+=("--headless=true")
  fi
  # Containers / bubblewrap often need the outer Chromium sandbox disabled.
  if [[ "${GIB_NO_SANDBOX:-1}" == "1" ]]; then
    args+=("--brave-arg=--no-sandbox")
  fi
  # Extra brave args from env (space-separated), optional.
  if [[ -n "${GIB_EXTRA_BRAVE_ARGS:-}" ]]; then
    # shellcheck disable=SC2206
    local extra=( ${GIB_EXTRA_BRAVE_ARGS} )
    local a
    for a in "${extra[@]}"; do
      args+=("--brave-arg=${a}")
    done
  fi
  printf '%s\n' "${args[@]}"
}
