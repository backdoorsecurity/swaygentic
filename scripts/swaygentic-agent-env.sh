#!/usr/bin/env bash
# Env for swaygentic / swaygentic-unsafe (sourced; safe under set -u).
# UID-math: wayvnc 5$UID, DevTools 6$UID. Guest QEMU VNC stays 127.0.0.1::5902.
: "${LIBVIRT_DEFAULT_URI:=qemu:///system}"
: "${WINCTL_DOMAIN:=}"
: "${WINCTL_VNC:=127.0.0.1::5902}"
export LIBVIRT_DEFAULT_URI WINCTL_VNC
[[ -n "$WINCTL_DOMAIN" ]] && export WINCTL_DOMAIN

_swg_uid="$(id -u)"
: "${XDG_RUNTIME_DIR:=/run/user/${_swg_uid}}"
: "${WAYLAND_DISPLAY:=wayland-1}"
: "${XDG_SESSION_TYPE:=wayland}"
: "${SWG_DEBUG_PORT:=6${_swg_uid}}"
: "${SWG_DEBUG_HOST:=127.0.0.1}"
: "${SWG_WAYVNC_PORT:=5${_swg_uid}}"
export XDG_RUNTIME_DIR WAYLAND_DISPLAY XDG_SESSION_TYPE SWG_DEBUG_PORT SWG_DEBUG_HOST SWG_WAYVNC_PORT
unset _swg_uid

case ":${PATH:-}:" in
  *":${HOME}/.local/bin:"*) ;;
  *) export PATH="${HOME}/.local/bin:${PATH:-/usr/bin:/bin}" ;;
esac
