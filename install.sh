#!/usr/bin/env bash
# One-shot host installer for swaygentic + swaygentrc.
#
#   git clone https://github.com/backdoorsecurity/swaygentic/
#   cd swaygentic/
#   ./install.sh --non-interactive --skip-adb
#
# Paths come from this checkout and $HOME — nothing is hard-coded to a user.
# Same flags as scripts/install_swaygentic_system.sh (see --help).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALLER="$ROOT/scripts/install_swaygentic_system.sh"

if [[ ! -f "$INSTALLER" ]]; then
  printf 'ERROR: missing system installer\n' >&2
  printf 'ERROR: expected: %s\n' "$INSTALLER" >&2
  printf 'ERROR: ROOT=%s\n' "$ROOT" >&2
  exit 1
fi
if [[ ! -x "$INSTALLER" ]]; then
  chmod +x "$INSTALLER" || {
    printf 'ERROR: cannot chmod +x %s\n' "$INSTALLER" >&2
    exit 1
  }
fi

exec "$INSTALLER" "$@"
