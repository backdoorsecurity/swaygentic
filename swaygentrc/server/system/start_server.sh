#!/bin/sh
set -eu

HERE=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
SERVER_DIR=$(CDPATH= cd -- "$HERE/.." && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SERVER_DIR/../.." && pwd)
# GUI/systemd launches often omit /usr/bin; tailscale lives there.
PATH="/usr/bin:/usr/sbin:/usr/local/bin:/snap/bin:${PATH:-/bin}"
export PATH

# workspace grok uses as cwd (repo root so project MCP loads)
SERVER_ROOT="${SERVER_ROOT:-$REPO_ROOT}"
# wrap agent in swaygentic jail. default ON.
# set SWAYGENTIC_OFF=1 or SWAYGENTRC_WRAP=off for attended host-side grok.
if [ -z "${SWAYGENTRC_WRAP+x}" ]; then
  if [ -x "$SERVER_ROOT/mcp/swaygentic" ]; then
    SWAYGENTRC_WRAP="$SERVER_ROOT/mcp/swaygentic"
  elif [ -x "$HOME/.local/bin/swaygentic" ]; then
    SWAYGENTRC_WRAP="$HOME/.local/bin/swaygentic"
  else
    SWAYGENTRC_WRAP=""
  fi
fi
GROK_BIN="${GROK_BIN:-${SWAYGENTRC_GROK_BIN:-$HOME/.grok/bin/grok}}"
HTTP_HOST="${HTTP_HOST:-${SWAYGENTRC_HTTP_HOST:-}}"
# HTTP_PORT: omit to use persisted random high port in run/http.port
HTTP_PORT="${HTTP_PORT:-${SWAYGENTRC_HTTP_PORT:-}}"
GROK_BIND="${GROK_BIND:-${SWAYGENTRC_GROK_BIND:-127.0.0.1:2419}}"
GROK_EFFORT="${GROK_EFFORT:-${SWAYGENTRC_GROK_EFFORT:-low}}"
GROK_MODEL="${GROK_MODEL:-${SWAYGENTRC_GROK_MODEL:-}}"
START_MESSAGE="${START_MESSAGE:-${SWAYGENTRC_START_MESSAGE:-}}"

if [ ! -d "$SERVER_ROOT" ]; then
  echo "SERVER_ROOT is not a directory: $SERVER_ROOT" >&2
  exit 1
fi

# First manual launch installs a user systemd unit and hands off.
# Foreground: SWAYGENTRC_FOREGROUND=1 ./start_server.sh
if [ -z "${INVOCATION_ID:-}" ] && [ "${SWAYGENTRC_FOREGROUND:-0}" != 1 ]; then
  export SWAYGENTRC_SERVER_ROOT="$SERVER_ROOT"
  export SWAYGENTRC_WRAP
  export SWAYGENTRC_GROK_BIN="$GROK_BIN"
  export SWAYGENTRC_GROK_BIND="$GROK_BIND"
  export SWAYGENTRC_GROK_EFFORT="$GROK_EFFORT"
  export SWAYGENTRC_GROK_MODEL="$GROK_MODEL"
  export SWAYGENTRC_START_MESSAGE="$START_MESSAGE"
  if [ -n "$HTTP_HOST" ]; then
    export SWAYGENTRC_HTTP_HOST="$HTTP_HOST"
  fi
  if [ -n "$HTTP_PORT" ]; then
    export SWAYGENTRC_HTTP_PORT="$HTTP_PORT"
  fi
  HAND_OFF=$(python3 - "$SERVER_DIR" <<'PY'
import sys
sys.path.insert(0, sys.argv[1])
from toolbox.systemd_unit import ensure_systemd, under_systemd
print(ensure_systemd(start=True))
PY
)
  echo "systemd: $HAND_OFF"
  case "$HAND_OFF" in
    *'started via systemd'*)
      systemctl --user --no-pager --full status swaygentrc.service || true
      echo "swaygentrc is a user service. logs: journalctl --user -u swaygentrc -f"
      echo "foreground instead: SWAYGENTRC_FOREGROUND=1 $HERE/start_server.sh"
      exit 0
      ;;
  esac
  echo "could not hand off to systemd; running in the foreground"
fi

export SWAYGENTRC_SERVER_ROOT="$SERVER_ROOT"
export SWAYGENTRC_WRAP
export SWAYGENTRC_GROK_BIN="$GROK_BIN"
export SWAYGENTRC_GROK_BIND="$GROK_BIND"
export SWAYGENTRC_GROK_EFFORT="$GROK_EFFORT"
export SWAYGENTRC_GROK_MODEL="$GROK_MODEL"
export SWAYGENTRC_START_MESSAGE="$START_MESSAGE"
if [ -n "$HTTP_HOST" ]; then
  export SWAYGENTRC_HTTP_HOST="$HTTP_HOST"
fi
if [ -n "$HTTP_PORT" ]; then
  export SWAYGENTRC_HTTP_PORT="$HTTP_PORT"
fi

exec python3 "$SERVER_DIR/main.py"
