#!/usr/bin/env bash
# Run OUTSIDE swaygentic (host shell) to finish the directory rename.
set -euo pipefail
HOME_DIR="${HOME:?HOME must be set}"
SRC="$HOME_DIR/grok-in-browser"
DST="$HOME_DIR/swaygentic"
if [[ -d "$DST" ]]; then
  echo "Already renamed: $DST"
elif [[ -d "$SRC" ]]; then
  cd "$HOME_DIR"
  mv "$SRC" "$DST"
  echo "Renamed $SRC -> $DST"
else
  echo "Neither $SRC nor $DST found" >&2
  exit 1
fi
cd "$DST"
./scripts/install_swaygentic.sh
./scripts/smoke_mcp_config.sh
echo "Done. Next: cd ~/swaygentic && swaygentic --trust"
