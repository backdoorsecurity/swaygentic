#!/usr/bin/env bash
# Alias for mcp/launch.sh — open/new-tab share one containerized code path.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$ROOT/mcp/launch.sh" "$@"
