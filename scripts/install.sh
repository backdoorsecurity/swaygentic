#!/usr/bin/env bash
# Compatibility wrapper — prefer repo-root ./install.sh after clone.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$ROOT/install.sh" "$@"
