#!/usr/bin/env bash
# Host dirs for Brave MCP take_snapshot / take_screenshot filePath.
# Layout: /tmp/snapshots/<agent-name>
# /tmp/snapshots/browser -> advisor  ($USER for the operator)
set -euo pipefail
BASE=/tmp/snapshots

agent_name() {
  case "${1:-$(id -un)}" in
    forge|loom|bravectl|winctl) echo "${1:-$(id -un)}" ;;
    browser|judge|advisor) echo advisor ;;
    *) echo "${1:-$(id -un)}" ;;
  esac
}

mkdir -p "$BASE"
chmod 2775 "$BASE" 2>/dev/null || true
chgrp arena "$BASE" 2>/dev/null || true

ensure_one() {
  local name="$1" owner="$2"
  mkdir -p "$BASE/$name"
  if [[ "$(id -u)" -eq 0 ]]; then
    chown "$owner:arena" "$BASE/$name"
    chmod 2770 "$BASE/$name"
  else
    chmod 2770 "$BASE/$name" 2>/dev/null || chmod 770 "$BASE/$name" 2>/dev/null || true
    chgrp arena "$BASE/$name" 2>/dev/null || true
  fi
}

if [[ "$(id -u)" -eq 0 ]]; then
  ensure_one forge forge
  ensure_one loom loom
  ensure_one bravectl bravectl
  ensure_one winctl winctl
  ensure_one advisor browser
else
  me="$(id -un)"
  ag="$(agent_name "$me")"
  owner="$me"
  [[ "$ag" == advisor ]] && owner=browser
  ensure_one "$ag" "$owner"
  if [[ "$me" != "$ag" ]]; then
    ln -sfn "$ag" "$BASE/$me" 2>/dev/null || ensure_one "$me" "$me"
  fi
fi

if [[ "$(id -u)" -eq 0 || "$(id -un)" == browser ]]; then
  ln -sfn advisor "$BASE/browser" 2>/dev/null || true
fi

if [[ "$(id -u)" -eq 0 ]]; then
  printf '%s\n' "$BASE"
else
  printf '%s\n' "$BASE/$(agent_name "$(id -un)")"
fi
