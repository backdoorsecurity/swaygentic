#!/usr/bin/env bash
# Smoke-test winctl against guest VNC without starting the MCP stdio loop.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VENV_PY="${SWG_WINCTL_PYTHON:-$ROOT/.venv-winctl/bin/python}"
if [[ ! -x "$VENV_PY" ]]; then
  echo "Creating $ROOT/.venv-winctl …"
  python3 -m venv "$ROOT/.venv-winctl"
  "$ROOT/.venv-winctl/bin/pip" install -q -r "$ROOT/mcp/winctl/requirements.txt"
  VENV_PY="$ROOT/.venv-winctl/bin/python"
fi

export PYTHONPATH="$ROOT/mcp${PYTHONPATH:+:$PYTHONPATH}"
export LIBVIRT_DEFAULT_URI="${LIBVIRT_DEFAULT_URI:-qemu:///system}"
export WINCTL_VNC="${WINCTL_VNC:-127.0.0.1::5902}"
export WINCTL_DOMAIN="${WINCTL_DOMAIN:-V2_tiny-10}"

# Soft mode (status print only, exit 0) when SWG_WINCTL_SMOKE_SOFT=1.
"$VENV_PY" - <<'PY'
import os
from winctl import toolset, rfb_session

soft = os.environ.get("SWG_WINCTL_SMOKE_SOFT", "0") in ("1", "true", "yes")
print(toolset.win_status())
try:
    result = toolset.win_look("smoke")
    if isinstance(result, str):
        print(f"SMOKE_FAIL ({result})")
        raise SystemExit(0 if soft else 1)
    text, imgs, _fmt = result[0], result[1], result[2] if len(result) > 2 else "jpeg"
    assert imgs and len(imgs[0]) > 1000, text
    print(text)
    print("SMOKE_OK")
except SystemExit:
    raise
except Exception as exc:
    print(f"SMOKE_FAIL (look failed: {exc})")
    raise SystemExit(0 if soft else 1)
finally:
    rfb_session.close()
PY
