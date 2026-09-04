#!/usr/bin/env bash
# Ensure Brave Nightly is running with loopback remote debugging for MCP attach.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=brave_flags.sh
source "$ROOT/mcp/brave_flags.sh"

swg_fill_session_env() {
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
}

swg_devtools_up() {
  curl -fsS --max-time 1 "$(swg_debug_url)/json/version" >/dev/null 2>&1
}

swg_singleton_lock() {
  local lock="${SWG_PROFILE_DIR}/SingletonLock"
  [[ -e "$lock" || -L "$lock" ]]
}

swg_clear_singleton_files() {
  rm -f "${SWG_PROFILE_DIR}/SingletonLock" "${SWG_PROFILE_DIR}/SingletonCookie" \
    "${SWG_PROFILE_DIR}/SingletonSocket" 2>/dev/null || true
}

# Chromium SingletonLock is a symlink whose *target string* is "hostname-pid".
# That path does not exist on disk, so `[[ -e lock ]]` is false even while Brave
# is alive — do NOT treat "-L && ! -e" as stale. Stale only when the parseable
# pid is not alive. Unparseable targets are NOT stale (require SWG_FORCE_QUIT_BRAVE).
swg_stale_singleton_lock() {
  local lock="${SWG_PROFILE_DIR}/SingletonLock"
  local target pid
  [[ -L "$lock" || -e "$lock" ]] || return 1
  target=$(readlink "$lock" 2>/dev/null || true)
  # Non-symlink leftover file: not auto-cleared.
  [[ -n "$target" ]] || return 1
  pid="${target##*-}"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  if kill -0 "$pid" 2>/dev/null; then
    return 1
  fi
  return 0
}

swg_force_quit_brave() {
  # Test-VM helper only. Matches Nightly binary / profile holders, not this script.
  local p cmd
  for p in /proc/[0-9]*; do
    cmd=$(tr '\0' ' ' <"$p/cmdline" 2>/dev/null || true)
    [[ "$cmd" == *'/opt/brave.com/brave-nightly/brave'* ]] || continue
    [[ "$cmd" == *'--type='* ]] && continue
    kill -TERM "$(basename "$p")" 2>/dev/null || true
  done
  sleep 0.5
  for p in /proc/[0-9]*; do
    cmd=$(tr '\0' ' ' <"$p/cmdline" 2>/dev/null || true)
    [[ "$cmd" == *'/opt/brave.com/brave-nightly/brave'* ]] || continue
    [[ "$cmd" == *'--type='* ]] && continue
    kill -KILL "$(basename "$p")" 2>/dev/null || true
  done
  swg_clear_singleton_files
}

swg_fill_session_env
mkdir -p "$SWG_RUN_DIR"

if swg_devtools_up; then
  echo "Brave DevTools already up at $(swg_debug_url)"
  exit 0
fi

if swg_singleton_lock; then
  if [[ "${SWG_FORCE_QUIT_BRAVE:-0}" == "1" ]]; then
    echo "SWG_FORCE_QUIT_BRAVE=1: quitting Brave holding ${SWG_PROFILE_DIR}"
    swg_force_quit_brave
  elif swg_stale_singleton_lock; then
    echo "Stale SingletonLock (dead/missing peer) at ${SWG_PROFILE_DIR}; clearing and starting Brave"
    swg_clear_singleton_files
  else
    echo "Brave profile is locked (${SWG_PROFILE_DIR}/SingletonLock) but DevTools is not on $(swg_debug_url)." >&2
    echo "Quit that Brave window, or re-run with SWG_FORCE_QUIT_BRAVE=1 (test VM only)." >&2
    exit 1
  fi
fi

mapfile -t BRAVE_ARGV < <(swg_brave_browser_argv)
echo "Starting Brave: ${BRAVE_ARGV[*]}"
# Detach from MCP/TTY so the browser outlives this helper.
setsid "${BRAVE_ARGV[@]}" >>"$SWG_BRAVE_LOG_FILE" 2>&1 &
echo $! >"$SWG_BRAVE_PID_FILE"

deadline=$((SECONDS + 40))
while ((SECONDS < deadline)); do
  if swg_devtools_up; then
    echo "Brave DevTools ready at $(swg_debug_url)"
    exit 0
  fi
  sleep 0.2
done

echo "Timed out waiting for $(swg_debug_url)/json/version" >&2
echo "See $SWG_BRAVE_LOG_FILE" >&2
exit 1
