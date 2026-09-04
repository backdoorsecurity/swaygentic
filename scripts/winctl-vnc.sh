#!/usr/bin/env bash
# Control a nested guest desktop via QEMU VNC (default 127.0.0.1:5902).
# Does NOT touch host wayvnc (Tailscale :5900 / phone VIEW).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VNCDO="${ROOT}/.venv-winctl/bin/vncdo"
SHOT_DIR="${ROOT}/run/winctl-shots"
SERVER="${WINCTL_VNC:-127.0.0.1::5902}"
export LIBVIRT_DEFAULT_URI="${LIBVIRT_DEFAULT_URI:-qemu:///system}"
DOMAIN="${WINCTL_DOMAIN:-V2_tiny-10}"

mkdir -p "$SHOT_DIR"

usage() {
  cat <<'EOF'
Usage: winctl-vnc.sh <command> [args...]

  shot [name]           Capture guest screen to run/winctl-shots/<name>.png
  click <x> <y> [btn]   Mouse click (btn: 1=left 2=middle 3=right; default 1)
  move <x> <y>          Move pointer
  dblclick <x> <y>      Left double-click
  type <text>           Type text via VNC (best-effort alphanumeric)
  key <virsh-key>...    Send keys via virsh (reliable for Win/Esc/etc)
  send <vncdo cmds...>  Raw vncdo commands against the guest
  status                Show VNC listeners + domain state

Guest VNC: WINCTL_VNC (default 127.0.0.1::5902)
Domain:    WINCTL_DOMAIN (default V2_tiny-10)
Host wayvnc is intentionally left alone.
EOF
}

need_vncdo() {
  if [[ ! -x "$VNCDO" ]]; then
    echo "missing $VNCDO — run: ./scripts/smoke_winctl.sh  (creates .venv-winctl)" >&2
    exit 1
  fi
}

cmd="${1:-}"
shift || true

case "$cmd" in
  ""|-h|--help|help) usage ;;
  status)
    virsh list --all 2>/dev/null || true
    echo "---"
    ss -tln | grep -E '5900|5901|5902' || true
    virsh qemu-monitor-command "$DOMAIN" --hmp 'info vnc' 2>/dev/null || true
    ;;
  shot)
    need_vncdo
    name="${1:-latest}"
    out="$SHOT_DIR/${name}.png"
    "$VNCDO" -s "$SERVER" -t 30 --nocursor capture "$out"
    echo "$out"
    ;;
  click)
    need_vncdo
    x="${1:?x}"; y="${2:?y}"; btn="${3:-1}"
    "$VNCDO" -s "$SERVER" -t 30 move "$x" "$y" click "$btn"
    ;;
  move)
    need_vncdo
    x="${1:?x}"; y="${2:?y}"
    "$VNCDO" -s "$SERVER" -t 30 move "$x" "$y"
    ;;
  dblclick)
    need_vncdo
    x="${1:?x}"; y="${2:?y}"
    "$VNCDO" -s "$SERVER" -t 30 move "$x" "$y" click 1 pause 0.05 click 1
    ;;
  type)
    need_vncdo
    text="${1:?text}"
    "$VNCDO" -s "$SERVER" -t 60 type "$text"
    ;;
  key)
    [[ $# -ge 1 ]] || { echo "key requires KEY_* args" >&2; exit 1; }
    virsh send-key "$DOMAIN" "$@"
    ;;
  send)
    need_vncdo
    "$VNCDO" -s "$SERVER" -t 60 "$@"
    ;;
  *)
    echo "unknown command: $cmd" >&2
    usage >&2
    exit 1
    ;;
esac
