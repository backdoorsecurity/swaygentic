#!/usr/bin/env bash
# Install swaygentic onto PATH (~/.local/bin) without touching ~/.grok/bin/grok.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="${SWAYGENTIC_BIN_DIR:-${HOME:?HOME not set}/.local/bin}"
DEST="$DEST_DIR/swaygentic"
SRC="$ROOT/mcp/swaygentic"
DEST_UNSAFE="$DEST_DIR/swaygentic-unsafe"
SRC_UNSAFE="$ROOT/mcp/swaygentic-unsafe"
STEP="init"

log()  { printf '%s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die()  {
  printf 'ERROR: %s\n' "$*" >&2
  printf 'ERROR: step=%s ROOT=%s DEST_DIR=%s\n' "$STEP" "$ROOT" "$DEST_DIR" >&2
  exit 1
}

on_err() {
  local ec=$? line="${1:-?}"
  printf 'ERROR: install_swaygentic.sh failed (exit %s) at line %s\n' "$ec" "$line" >&2
  printf 'ERROR: step=%s command=%s\n' "$STEP" "${BASH_COMMAND:-?}" >&2
  printf 'ERROR: ROOT=%s HOME=%s DEST_DIR=%s\n' "$ROOT" "${HOME:-}" "$DEST_DIR" >&2
  printf 'ERROR: SRC=%s (exists=%s executable=%s)\n' \
    "$SRC" "$([[ -e $SRC ]] && echo yes || echo no)" "$([[ -x $SRC ]] && echo yes || echo no)" >&2
  printf 'ERROR: SRC_UNSAFE=%s (exists=%s executable=%s)\n' \
    "$SRC_UNSAFE" "$([[ -e $SRC_UNSAFE ]] && echo yes || echo no)" "$([[ -x $SRC_UNSAFE ]] && echo yes || echo no)" >&2
  exit "$ec"
}
trap 'on_err $LINENO' ERR

STEP="preflight"
[[ -d "$ROOT/mcp" ]] || die "mcp/ missing under ROOT=$ROOT"
[[ -f "$SRC" ]] || die "missing launcher source: $SRC"
[[ -f "$SRC_UNSAFE" ]] || die "missing launcher source: $SRC_UNSAFE"
[[ -f "$ROOT/mcp/jail-run.sh" ]] || die "missing jail helper: $ROOT/mcp/jail-run.sh"

STEP="chmod-sources"
chmod +x "$ROOT/mcp/jail-run.sh" "$SRC" "$SRC_UNSAFE" || die "chmod +x failed on launchers/jail-run"

STEP="mkdir-dest"
mkdir -p "$DEST_DIR" || die "cannot create DEST_DIR=$DEST_DIR"

STEP="symlink-swaygentic"
ln -sfn "$SRC" "$DEST" || die "ln -sfn failed: $DEST -> $SRC"
STEP="symlink-unsafe"
ln -sfn "$SRC_UNSAFE" "$DEST_UNSAFE" || die "ln -sfn failed: $DEST_UNSAFE -> $SRC_UNSAFE"

log "Installed $DEST -> $(readlink -f "$DEST" 2>/dev/null || readlink "$DEST")"
log "Installed $DEST_UNSAFE -> $(readlink -f "$DEST_UNSAFE" 2>/dev/null || readlink "$DEST_UNSAFE")"

STEP="path-check"
case ":${PATH:-}:" in
  *":$DEST_DIR:"*) log "OK: $DEST_DIR is on PATH" ;;
  *)
    warn "add to PATH: export PATH=\"$DEST_DIR:\$PATH\""
    ;;
esac

STEP="grok-check"
if [[ -x "${HOME}/.grok/downloads/grok-linux-x86_64" || -x "${HOME}/.grok/bin/grok" ]]; then
  log "Real grok: $(readlink -f "${HOME}/.grok/bin/grok" 2>/dev/null || echo "${HOME}/.grok/downloads/grok-linux-x86_64")"
else
  warn "grok binary not found yet under ~/.grok (Build will not start until installed)"
fi

log "Try: swaygentic --version"
log "Or:  swaygentic --trust            # jailed"
log "Or:  swaygentic-unsafe --trust     # no jail (host grok)"
