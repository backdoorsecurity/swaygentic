#!/usr/bin/env bash
# Launch winctl MCP — guest desktop control over QEMU VNC.
# Never starts/stops/reconfigures host wayvnc (phone VIEW :5900).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# Package lives at mcp/winctl/; repo root is ../..
ROOT="$(cd "$HERE/../.." && pwd)"
# Import path: mcp/ must be on PYTHONPATH so `import winctl` works.
MCP_DIR="$(cd "$HERE/.." && pwd)"

VENV_PY="${SWG_WINCTL_PYTHON:-$ROOT/.venv-winctl/bin/python}"
if [[ ! -x "$VENV_PY" ]]; then
  echo "winctl: missing $VENV_PY" >&2
  echo "  python3 -m venv \"$ROOT/.venv-winctl\"" >&2
  echo "  \"$ROOT/.venv-winctl/bin/pip\" install -r \"$HERE/requirements.txt\"" >&2
  exit 1
fi

export PYTHONPATH="${MCP_DIR}${PYTHONPATH:+:$PYTHONPATH}"
export LIBVIRT_DEFAULT_URI="${LIBVIRT_DEFAULT_URI:-qemu:///system}"
export WINCTL_VNC="${WINCTL_VNC:-127.0.0.1::5902}"
export WINCTL_DOMAIN="${WINCTL_DOMAIN:-V2_tiny-10}"
export WINCTL_LOOK_FORMAT="${WINCTL_LOOK_FORMAT:-jpeg}"

cd "$ROOT"
exec "$VENV_PY" -m winctl.server "$@"
