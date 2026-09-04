#!/usr/bin/env bash
# Open a URL in a Brave tab container with project launch flags.
#
# Spec forms (single argument):
#   https://example.com
#   misc:https://example.com
#   brave-browser-nightly:misc:https://example.com
#   brave-browser-nightly:misc          # about:blank in container
#
# Same entrypoint as new_tab.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=brave_flags.sh
source "$ROOT/mcp/brave_flags.sh"

usage() {
  cat <<EOF
Usage: mcp/launch.sh <spec>

  https://url
  <container>:<url>
  <browser>:<container>:<url>
  <browser>:<container>

Default container: \$SWG_CONTAINER (${SWG_CONTAINER})
Known containers come from Brave prefs (aliases: usbank → 'us bank').
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || $# -lt 1 ]]; then
  usage
  [[ $# -ge 1 ]] || exit 2
  exit 0
fi

SPEC="$1"

# --- parse spec -------------------------------------------------------------
BROWSER_TOKEN=""
CONTAINER_TOKEN=""
URL=""

is_url() {
  case "$1" in
    https://* | http://* | about:* | file://*) return 0 ;;
    *) return 1 ;;
  esac
}

# Pull a trailing URL (scheme…) so colons inside https://… stay intact.
PREFIX=""
URL=""
if [[ "$SPEC" =~ ^(.*)(https://.*|http://.*|about:.*|file://.*)$ ]]; then
  PREFIX="${BASH_REMATCH[1]}"
  URL="${BASH_REMATCH[2]}"
  PREFIX="${PREFIX%:}" # drop trailing colon between prefix and URL
elif is_url "$SPEC"; then
  URL="$SPEC"
else
  PREFIX="$SPEC"
  URL="about:blank"
fi

if [[ -z "$PREFIX" ]]; then
  CONTAINER_TOKEN="$SWG_CONTAINER"
else
  IFS=':' read -r -a PARTS <<<"$PREFIX"
  n=${#PARTS[@]}
  if ((n == 1)); then
    CONTAINER_TOKEN="${PARTS[0]}"
  elif ((n == 2)); then
    if swg_resolve_browser_alias "${PARTS[0]}" >/dev/null 2>&1; then
      BROWSER_TOKEN="${PARTS[0]}"
      CONTAINER_TOKEN="${PARTS[1]}"
    else
      echo "launch.sh: unknown browser alias '${PARTS[0]}' in spec: $SPEC" >&2
      exit 2
    fi
  else
    echo "launch.sh: unrecognized spec: $SPEC" >&2
    usage >&2
    exit 2
  fi
fi

if [[ -z "$CONTAINER_TOKEN" ]]; then
  echo "launch.sh: empty container in spec: $SPEC" >&2
  exit 2
fi

# --- resolve browser --------------------------------------------------------
if [[ -n "$BROWSER_TOKEN" ]]; then
  resolved="$(swg_resolve_browser_alias "$BROWSER_TOKEN" || true)"
  if [[ -z "$resolved" || ! -x "$resolved" ]]; then
    echo "launch.sh: unknown or missing browser alias: $BROWSER_TOKEN" >&2
    exit 2
  fi
  export SWG_BRAVE_BIN="$resolved"
fi

if [[ -z "${SWG_BRAVE_BIN}" || ! -x "${SWG_BRAVE_BIN}" ]]; then
  echo "launch.sh: Brave binary not found (set SWG_BRAVE_BIN)" >&2
  exit 1
fi

# --- resolve container name against prefs -----------------------------------
swg_list_containers() {
  python3 - <<'PY'
import json, os, sys
from pathlib import Path
pref = Path(os.environ["SWG_PROFILE_DIR"]) / "Default" / "Preferences"
if not pref.is_file():
    sys.exit(0)
try:
    data = json.loads(pref.read_text())
except Exception:
    sys.exit(0)
rows = ((data.get("brave") or {}).get("containers") or {}).get("list") or []
for row in rows:
    name = (row or {}).get("name")
    if name:
        print(name)
PY
}

swg_resolve_container() {
  local want="$1"
  local want_norm names name norm
  want_norm="$(printf '%s' "$want" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]_-')"
  # Built-in aliases → canonical prefs names on this machine
  case "$want_norm" in
    usbank) want_norm="usbank" ;;
  esac
  mapfile -t names < <(swg_list_containers)
  if ((${#names[@]} == 0)); then
    # Prefs missing (fresh profile): accept the token as-is.
    printf '%s\n' "$want"
    return 0
  fi
  for name in "${names[@]}"; do
    norm="$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]_-')"
    if [[ "$norm" == "$want_norm" ]]; then
      printf '%s\n' "$name"
      return 0
    fi
  done
  echo "launch.sh: unknown container '$want'. Known:" >&2
  printf '  %s\n' "${names[@]}" >&2
  return 1
}

CONTAINER_NAME="$(swg_resolve_container "$CONTAINER_TOKEN")"

# --- ensure browser + open --------------------------------------------------
SWG_FORCE_QUIT_BRAVE="${SWG_FORCE_QUIT_BRAVE:-0}" \
  "$ROOT/mcp/ensure_brave.sh"

mapfile -t COMMON < <(swg_brave_common_args)
ARGS=("${SWG_BRAVE_BIN}" "${COMMON[@]}" "--container=${CONTAINER_NAME}")
if [[ "${SWG_TEMPORARY_CONTAINER:-0}" == "1" ]]; then
  ARGS+=("--temporary-container")
fi
ARGS+=("$URL")

# Handoff into the running singleton (must not be Puppeteer-owned).
set +e
OUT="$("${ARGS[@]}" 2>&1)"
RC=$?
set -e
if [[ -n "$OUT" ]]; then
  printf '%s\n' "$OUT"
fi
if [[ "$RC" -ne 0 ]]; then
  echo "launch.sh: Brave exited $RC opening $URL in container '$CONTAINER_NAME'" >&2
  exit "$RC"
fi

echo "opened ${URL} in container '${CONTAINER_NAME}' (browser=${SWG_BRAVE_BIN})"
