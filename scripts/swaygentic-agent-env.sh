#!/usr/bin/env bash
# Builder session env for non-login agent shells.
# Source from swaygentic / swaygentic-unsafe, or:  source scripts/swaygentic-agent-env.sh
#
# UID-math ports (do not hardcode usernames):
#   wayvnc     5$UID  (bravectl 51009, winctl 51008)
#   DevTools   6$UID  (bravectl 61009, winctl 61008)
# Guest QEMU VNC stays 127.0.0.1::5902 (shared; serialize drivers).

# Idempotent: safe to source more than once.
_swg_uid="$(id -u)"

export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/${_swg_uid}}"
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-1}"
export XDG_SESSION_TYPE="${XDG_SESSION_TYPE:-wayland}"
if [[ -z "${DBUS_SESSION_BUS_ADDRESS:-}" && -S "${XDG_RUNTIME_DIR}/bus" ]]; then
  export DBUS_SESSION_BUS_ADDRESS="unix:path=${XDG_RUNTIME_DIR}/bus"
fi

export SWG_DEBUG_HOST="${SWG_DEBUG_HOST:-127.0.0.1}"
export SWG_DEBUG_PORT="${SWG_DEBUG_PORT:-6${_swg_uid}}"
export SWG_WAYVNC_PORT="${SWG_WAYVNC_PORT:-5${_swg_uid}}"

export LIBVIRT_DEFAULT_URI="${LIBVIRT_DEFAULT_URI:-qemu:///system}"
export WINCTL_DOMAIN="${WINCTL_DOMAIN:-V2_tiny-10}"
export WINCTL_VNC="${WINCTL_VNC:-127.0.0.1::5902}"

case ":${PATH:-}:" in
  *":${HOME}/.local/bin:"*) ;;
  *) export PATH="${HOME}/.local/bin:${PATH:-/usr/bin:/bin}" ;;
esac

unset _swg_uid
