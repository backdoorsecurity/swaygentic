#!/usr/bin/env bash
# Loose bubblewrap jail for agent + browser (swaygentic).
# Adapted from grokbox-run.sh — tighten binds/seccomp later.
#
# Usage: mcp/jail-run.sh [--dry-run] [--] command [args...]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DRY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY=1; shift ;;
    --) shift; break ;;
    -*) echo "jail-run.sh: unknown option $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
if [[ $# -lt 1 ]]; then
  echo "Usage: $0 [--dry-run] [--] command [args...]" >&2
  exit 2
fi

# Already inside this jail (or another bwrap with the same marker).
if [[ "${container:-}" == "bwrap" ]]; then
  exec "$@"
fi

if ! command -v bwrap >/dev/null 2>&1; then
  echo "jail-run.sh: bwrap not found (install bubblewrap)" >&2
  exit 1
fi

# Session env for Wayland / D-Bus (MCP children are often headless TTYs).
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
if [[ -z "${WAYLAND_DISPLAY:-}" ]]; then
  if [[ -S "${XDG_RUNTIME_DIR}/wayland-1" ]]; then
    export WAYLAND_DISPLAY=wayland-1
  elif [[ -S "${XDG_RUNTIME_DIR}/wayland-0" ]]; then
    export WAYLAND_DISPLAY=wayland-0
  else
    export WAYLAND_DISPLAY=wayland-1
  fi
fi
if [[ -z "${DBUS_SESSION_BUS_ADDRESS:-}" && -S "${XDG_RUNTIME_DIR}/bus" ]]; then
  export DBUS_SESSION_BUS_ADDRESS="unix:path=${XDG_RUNTIME_DIR}/bus"
fi
export XDG_SESSION_TYPE="${XDG_SESSION_TYPE:-wayland}"

RUNTIME_DIR="$XDG_RUNTIME_DIR"
# Headless / non-logind sessions may lack /run/user/$UID — create if we can.
if [[ ! -d "$RUNTIME_DIR" ]]; then
  mkdir -p "$RUNTIME_DIR" 2>/dev/null || {
    echo "jail-run.sh: XDG_RUNTIME_DIR missing ($RUNTIME_DIR); set it or log in with a session" >&2
    exit 1
  }
fi
WAYLAND_SOCKET="$RUNTIME_DIR/${WAYLAND_DISPLAY}"
BUS_ADDR="${DBUS_SESSION_BUS_ADDRESS:-unix:path=$RUNTIME_DIR/bus}"
PROXY_SOCK="$RUNTIME_DIR/swaygentic-dbus"
PROXY_PIDFILE="$RUNTIME_DIR/swaygentic-dbus.pid"
PROXY_SIGFILE="$RUNTIME_DIR/swaygentic-dbus.sig"

# Loose D-Bus allowlist (tighten later). Never bind the raw session bus.
DBUS_POLICY=(
  --talk=org.a11y.Bus
  --talk=org.a11y.*
  --talk=org.freedesktop.Notifications
  --talk=org.freedesktop.portal.Desktop
  --talk=org.freedesktop.portal.Documents
)

args=()

bind_into_home() {
  local flag="$1" src="$2" dest="${3:-$2}"
  [[ -e "$src" ]] || return 0
  local rel="${dest#"$HOME"/}"
  if [[ "$rel" == "$dest" ]]; then
    echo "jail-run.sh: $dest is not under \$HOME" >&2
    return 1
  fi
  local acc="$HOME"
  local IFS=/
  local -a parts
  read -ra parts <<<"$rel"
  local last=$((${#parts[@]} - 1)) i
  for ((i = 0; i < last; i++)); do
    acc="$acc/${parts[i]}"
    args+=(--dir "$acc")
  done
  if [[ -d "$src" ]]; then
    args+=(--dir "$dest")
  fi
  args+=("$flag" "$src" "$dest")
}

args+=(
  --unshare-all --share-net
  --cap-drop ALL
)
# die-with-parent SIGKILLs agent serve when launched under the phone HTTP
# API (threading + Popen). Keep it for interactive TTY sessions only.
if [[ -t 0 || "${SWAYGENTIC_BWRAP_DIE_WITH_PARENT:-0}" == "1" ]]; then
  args+=(--die-with-parent)
fi
# Snapshot dir lives on the host; jail --tmpfs /tmp would hide it otherwise.
SWG_SNAPSHOT_AGENT=browser
case "$(id -un)" in
  forge|loom|bravectl|winctl) SWG_SNAPSHOT_AGENT="$(id -un)" ;;
  browser|judge) SWG_SNAPSHOT_AGENT=advisor ;;
  *) SWG_SNAPSHOT_AGENT="$(id -un)" ;;
esac
SWG_SNAPSHOT_DIR="/tmp/snapshots/${SWG_SNAPSHOT_AGENT}"
if [[ -x "$ROOT/scripts/ensure_snapshots.sh" ]]; then
  "$ROOT/scripts/ensure_snapshots.sh" >/dev/null || true
else
  mkdir -p "$SWG_SNAPSHOT_DIR" || true
fi

args+=(
  --clearenv
  --proc /proc
  --dev /dev
  --tmpfs /tmp
  --dir /tmp/snapshots
  --bind-try /tmp/snapshots /tmp/snapshots
  --tmpfs "$HOME"
  --dir "$RUNTIME_DIR"
  --ro-bind /usr /usr
  --symlink usr/bin /bin
  --symlink usr/sbin /sbin
  --symlink usr/lib /lib
  --symlink usr/lib64 /lib64
  --ro-bind /etc /etc
  --ro-bind-try /opt /opt
  --ro-bind-try /var/cache/fontconfig /var/cache/fontconfig
  --ro-bind-try /run/systemd/resolve /run/systemd/resolve
)

# Default OFF: --new-session + --die-with-parent has been observed to
# SIGKILL agent serve when launched via the phone API (stdin=DEVNULL).
# Set SWAYGENTIC_BWRAP_NEW_SESSION=1 to restore the old behavior.
if [[ "${SWAYGENTIC_BWRAP_NEW_SESSION:-0}" == "1" ]]; then
  args+=(--new-session)
fi

if [[ -d /dev/dri ]]; then
  shopt -s nullglob
  local_nodes=(/dev/dri/renderD*)
  shopt -u nullglob
  if ((${#local_nodes[@]} > 0)); then
    args+=(--dir /dev/dri)
    for n in "${local_nodes[@]}"; do
      args+=(--dev-bind "$n" "$n")
    done
    args+=(--ro-bind-try /sys/devices /sys/devices)
    args+=(--ro-bind-try /sys/dev/char /sys/dev/char)
    args+=(--ro-bind-try /sys/class/drm /sys/class/drm)
  fi
fi

args+=(--ro-bind-try "$WAYLAND_SOCKET" "$WAYLAND_SOCKET")
if [[ -d "$RUNTIME_DIR/at-spi" ]]; then
  args+=(--bind "$RUNTIME_DIR/at-spi" "$RUNTIME_DIR/at-spi")
fi
args+=(--bind "$PROXY_SOCK" "$RUNTIME_DIR/bus")

# Home whitelist — agent + Brave Nightly. No broad ~/.cache or ~/.npm
# (npx may re-fetch into a tmpfs ~/.npm for the session; fine for now).
# ~/.pki = NSS DB Brave uses for user-imported CAs (e.g. ZAP root for MITM).
# ~/.ZAP = ZAP home (config, sessions, API key).
# ~/.local/share/foxyproxy = unpacked FoxyProxy for --load-extension.
mkdir -p \
  "$HOME/.grok" \
  "$HOME/.config/BraveSoftware" \
  "$HOME/.cache/BraveSoftware" \
  "$HOME/.pki" \
  "$HOME/.ZAP" \
  "$HOME/Downloads" \
  "$HOME/.local/bin" \
  "$HOME/.local/lib" \
  "$HOME/.local/share/foxyproxy" \
  "$ROOT/run" \
  "$ROOT/profiles"

for p in \
  "$HOME/.grok" \
  "$HOME/.config/BraveSoftware" \
  "$HOME/.cache/BraveSoftware" \
  "$HOME/.pki" \
  "$HOME/.ZAP" \
  "$HOME/Downloads" \
  "$HOME/.local/bin" \
  "$HOME/.local/lib" \
  "$HOME/.local/share/foxyproxy" \
  "$HOME/.config/gtk-3.0" \
  "$HOME/.config/gtk-4.0" \
  "$HOME/.config/fontconfig" \
  "$HOME/.config/kdeglobals" \
  "$HOME/.config/brave-flags.conf"
do
  bind_into_home --bind "$p"
done

bind_into_home --ro-bind "$HOME/.gitconfig"
bind_into_home --bind "$ROOT"

# Arena comms (sqlite board + AF_UNIX socks). Path sockets need the dir.
if [[ -d /srv/arena ]]; then
  args+=(--dir /srv --bind /srv/arena /srv/arena)
fi

# If cwd is under $HOME and outside the repo, bind it too (attended worktrees).
if [[ "${PWD:-}" == "$HOME"/* && "${PWD:-}" != "$ROOT" && "${PWD:-}" != "$ROOT"/* ]]; then
  bind_into_home --bind "$PWD"
fi

# Blacklisted binaries → deny stub (overlay after /usr is mounted).
# Speed bump against prompt-injection / accidental host damage; not a seccomp boundary.
DENY_STUB="$ROOT/mcp/jail-denied.sh"
if [[ ! -x "$DENY_STUB" ]]; then
  chmod +x "$DENY_STUB" 2>/dev/null || true
fi
DENY_BINARIES=(
  sudo su doas pkexec
  passwd chpasswd
  mount umount
  nsenter unshare chroot pivot_root
  newuidmap newgidmap
  systemctl machinectl loginctl
  docker podman nerdctl kubectl
  ssh scp sftp ssh-agent ssh-add
  firewall-cmd iptables ip6tables nft
  losetup
)
# bwrap cannot --ro-bind onto a symlink path ("Can't mount on symlink
# destination"). Resolve to the real file and dedupe (iptables → xtables-*).
if [[ -x "$DENY_STUB" ]]; then
  declare -A _SWG_DENY_SEEN=()
  for b in "${DENY_BINARIES[@]}"; do
    for dir in /usr/bin /usr/sbin /bin /sbin; do
      path="$dir/$b"
      [[ -e "$path" || -L "$path" ]] || continue
      if [[ -L "$path" ]]; then
        dest="$(readlink -f "$path" 2>/dev/null || true)"
      else
        dest="$path"
      fi
      [[ -n "$dest" && -e "$dest" ]] || continue
      [[ -n "${_SWG_DENY_SEEN[$dest]:-}" ]] && continue
      _SWG_DENY_SEEN[$dest]=1
      args+=(--ro-bind "$DENY_STUB" "$dest")
    done
  done
  unset _SWG_DENY_SEEN
else
  echo "jail-run.sh: warn: deny stub missing at $DENY_STUB" >&2
fi

args+=(
  --setenv HOME "$HOME"
  --setenv USER "$(id -un)"
  --setenv LOGNAME "$(id -un)"
  --setenv PATH "$HOME/.local/bin:/usr/bin:/usr/sbin:/bin:/sbin"
  --setenv XDG_RUNTIME_DIR "$RUNTIME_DIR"
  --setenv XDG_SESSION_TYPE wayland
  --setenv DBUS_SESSION_BUS_ADDRESS "unix:path=$RUNTIME_DIR/bus"
  --setenv container bwrap
  --setenv SWAYGENTIC_JAIL 1
  --setenv SWG_SNAPSHOT_DIR "$SWG_SNAPSHOT_DIR"
  --setenv QT_QPA_PLATFORM wayland
  --setenv GDK_BACKEND wayland
  --chdir "${PWD:-$ROOT}"
)

ENV_PASSTHROUGH=(
  LANG LANGUAGE TZ
  LC_ALL LC_CTYPE LC_NUMERIC LC_TIME LC_COLLATE LC_MONETARY LC_MESSAGES
  LC_PAPER LC_NAME LC_ADDRESS LC_TELEPHONE LC_MEASUREMENT LC_IDENTIFICATION
  XDG_CURRENT_DESKTOP XDG_SESSION_DESKTOP XDG_SESSION_ID
  XDG_DATA_DIRS XDG_CONFIG_DIRS
  DESKTOP_SESSION KDE_FULL_SESSION KDE_SESSION_VERSION
  QT_QPA_PLATFORMTHEME QT_STYLE_OVERRIDE
  QT_SCALE_FACTOR QT_AUTO_SCREEN_SCALE_FACTOR QT_SCREEN_SCALE_FACTORS
  QT_FONT_DPI QT_WAYLAND_DECORATION
  GTK_THEME GTK_USE_PORTAL GDK_SCALE GDK_DPI_SCALE
  XCURSOR_THEME XCURSOR_SIZE
  TERM COLORTERM
  # Agent / project knobs
  SWG_PROFILE_DIR SWG_BRAVE_BIN SWG_CONTAINER SWG_DEBUG_PORT SWG_DEBUG_HOST
  SWG_NO_SANDBOX SWG_HEADLESS SWG_FORCE_QUIT_BRAVE SWG_TEMPORARY_CONTAINER
  SWG_EXTRA_BRAVE_ARGS SWG_OZONE_PLATFORM SWG_VIEWPORT SWG_TEST_TYPE
  SWG_SNAPSHOT_DIR
  SWG_WINCTL_PYTHON SWG_WINCTL_SMOKE_SOFT
  # Guest desktop (winctl) — overrides must survive --clearenv
  WINCTL_VNC WINCTL_DOMAIN WINCTL_LOOK_FORMAT
  LIBVIRT_DEFAULT_URI
  XAI_API_KEY GROK_MODEL
)
for var in "${ENV_PASSTHROUGH[@]}"; do
  if [[ -n "${!var:-}" ]]; then
    args+=(--setenv "$var" "${!var}")
  fi
done

if [[ -S "$WAYLAND_SOCKET" ]]; then
  args+=(--setenv WAYLAND_DISPLAY "$WAYLAND_DISPLAY")
fi
args+=("$@")

start_proxy() {
  if ! command -v xdg-dbus-proxy >/dev/null 2>&1; then
    echo "jail-run.sh: xdg-dbus-proxy not found (install xdg-dbus-proxy)" >&2
    return 1
  fi
  local sig
  sig="$(printf '%s\n' "${DBUS_POLICY[@]}" | sha256sum | cut -d' ' -f1)"
  if [[ -f "$PROXY_PIDFILE" && -f "$PROXY_SIGFILE" ]] \
    && [[ "$(cat "$PROXY_SIGFILE" 2>/dev/null)" == "$sig" ]] \
    && kill -0 "$(cat "$PROXY_PIDFILE" 2>/dev/null)" 2>/dev/null \
    && [[ -S "$PROXY_SOCK" ]]; then
    return 0
  fi
  if [[ -f "$PROXY_PIDFILE" ]]; then
    kill "$(cat "$PROXY_PIDFILE" 2>/dev/null)" 2>/dev/null || true
  fi
  rm -f "$PROXY_SOCK"
  setsid xdg-dbus-proxy "$BUS_ADDR" "$PROXY_SOCK" --filter "${DBUS_POLICY[@]}" \
    >/dev/null 2>&1 &
  echo $! >"$PROXY_PIDFILE"
  echo "$sig" >"$PROXY_SIGFILE"
  local i
  for i in $(seq 1 50); do
    [[ -S "$PROXY_SOCK" ]] && return 0
    sleep 0.1
  done
  echo "jail-run.sh: xdg-dbus-proxy did not create $PROXY_SOCK" >&2
  return 1
}

if [[ "$DRY" == "1" ]]; then
  printf 'bwrap'
  printf ' %q' "${args[@]}"
  printf '\n'
  exit 0
fi

start_proxy
exec bwrap "${args[@]}"
