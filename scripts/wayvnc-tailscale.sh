#!/bin/sh
# Bind wayvnc to Tailscale IPv4 for Haven VIEW (not 0.0.0.0 / not 127.0.0.1).
# Tunables: ~/.config/wayvnc/env (see scripts/wayvnc.env.example).
set -eu

WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-1}"
export WAYLAND_DISPLAY
XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
export XDG_RUNTIME_DIR

OUTPUT="${WAYVNC_OUTPUT:-Virtual-1}"
PORT="${WAYVNC_PORT:-${SWAYGENTRC_VNC_PORT:-5900}}"
HOST_OVERRIDE="${WAYVNC_HOST:-${SWAYGENTRC_VNC_HOST:-}}"
MAX_FPS="${WAYVNC_MAX_FPS:-15}"
DISABLE_RESIZING="${WAYVNC_DISABLE_RESIZING:-1}"
SWAY_MODE="${WAYVNC_SWAY_MODE:-}"

sock="$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY"
i=0
while [ ! -S "$sock" ]; do
  i=$((i + 1))
  if [ "$i" -gt 180 ]; then
    echo "wayvnc: no Wayland socket $sock after 180s" >&2
    exit 1
  fi
  sleep 1
done

if [ -n "$HOST_OVERRIDE" ]; then
  ADDR="$HOST_OVERRIDE"
else
  ADDR=""
  i=0
  while [ -z "$ADDR" ]; do
    ADDR=$(tailscale ip -4 2>/dev/null | head -n1 | tr -d '[:space:]' || true)
    if [ -n "$ADDR" ]; then
      break
    fi
    i=$((i + 1))
    if [ "$i" -gt 90 ]; then
      echo "wayvnc: no Tailscale IPv4 after 90s (set WAYVNC_HOST)" >&2
      exit 1
    fi
    sleep 1
  done
fi

if [ -n "$SWAY_MODE" ] && command -v swaymsg >/dev/null 2>&1; then
  swaymsg "output $OUTPUT mode $SWAY_MODE" >/dev/null 2>&1 || \
    echo "wayvnc: sway mode $SWAY_MODE failed (continuing)" >&2
fi

set -- wayvnc -o "$OUTPUT"
if [ -n "$MAX_FPS" ] && [ "$MAX_FPS" -gt 0 ] 2>/dev/null; then
  set -- "$@" -f "$MAX_FPS"
fi
case "$DISABLE_RESIZING" in
  1|true|TRUE|yes|YES|on|ON) set -- "$@" -R ;;
esac
set -- "$@" "$ADDR" "$PORT"

echo "wayvnc: output=$OUTPUT listen=$ADDR:$PORT fps=${MAX_FPS:-default} resize_lock=$DISABLE_RESIZING wayland=$WAYLAND_DISPLAY"
exec "$@"
