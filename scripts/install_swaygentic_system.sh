#!/usr/bin/env bash
# Host installer: swaygentic launchers + swaygentrc (+ optional wayvnc).
# Entry: repo-root ./install.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT/swaygentrc/server"
SYSTEM_DIR="$SERVER_DIR/system"
RUN_DIR="$SERVER_DIR/run"
CREDENTIALS="$RUN_DIR/credentials.swaygentrc"
XAI_ENV="$ROOT/run/xai.env"
UNIT_DIR_REL=".config/systemd/user"

NON_INTERACTIVE=0
SKIP_ADB=0
SKIP_WAYVNC=0
SKIP_SMOKE=0
UNSAFE_DEFAULT=0
DO_ADB=0
START_SERVICES=1
VERBOSE="${INSTALL_VERBOSE:-0}"
STEP="init"

usage() {
  cat <<'EOF'
Usage: ./install.sh [options]

  Host install for swaygentic + swaygentrc (+ optional wayvnc).
  Run as the target user in a login session (systemctl --user).

Options:
  --non-interactive   No prompts; skip missing xai.env; imply --skip-adb unless --adb
  --skip-adb          Print APK + credentials paths (default with --non-interactive)
  --adb               Attempt ADB phone path (stub)
  --skip-wayvnc       Do not install/enable wayvnc.service
  --skip-smoke        Skip scripts/smoke_swaygentrc.sh
  --no-start          Enable units but do not restart now
  --unsafe-default    Phone wrap = swaygentic-unsafe
  --verbose           Echo each run command
  -h, --help
EOF
}

log()  { printf '%s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die()  {
  printf 'ERROR: %s\n' "$*" >&2
  printf 'ERROR: step=%s user=%s ROOT=%s\n' "${STEP:-?}" "$(id -un)" "$ROOT" >&2
  printf 'ERROR: HOME_DIR=%s UNIT_DIR=%s\n' "${HOME_DIR:-?}" "${UNIT_DIR:-?}" >&2
  exit 1
}

step() {
  STEP="$1"
  log "==> [$STEP] ${2:-}"
}

run() {
  if [[ "$VERBOSE" == 1 ]]; then
    printf '+ (%s) %s\n' "$STEP" "$*" >&2
  fi
  "$@"
}

on_err() {
  local ec=$? line="${1:-?}"
  printf 'ERROR: install failed (exit %s) at line %s of %s\n' \
    "$ec" "$line" "${BASH_SOURCE[0]}" >&2
  printf 'ERROR: step=%s command=%s\n' "${STEP:-?}" "${BASH_COMMAND:-?}" >&2
  printf 'ERROR: ROOT=%s HOME=%s HOME_DIR=%s\n' "$ROOT" "${HOME:-?}" "${HOME_DIR:-?}" >&2
  printf 'ERROR: UNIT_DIR=%s CREDENTIALS=%s\n' "${UNIT_DIR:-?}" "${CREDENTIALS:-?}" >&2
  if command -v systemctl >/dev/null 2>&1; then
    systemctl --user --no-pager --full status swaygentrc.service 2>&1 | tail -n 30 >&2 || true
    systemctl --user --no-pager --full status wayvnc.service 2>&1 | tail -n 20 >&2 || true
  fi
  exit "$ec"
}
trap 'on_err $LINENO' ERR

while [[ $# -gt 0 ]]; do
  case "$1" in
    --non-interactive) NON_INTERACTIVE=1; shift ;;
    --skip-adb) SKIP_ADB=1; shift ;;
    --adb) DO_ADB=1; SKIP_ADB=0; shift ;;
    --skip-wayvnc) SKIP_WAYVNC=1; shift ;;
    --skip-smoke) SKIP_SMOKE=1; shift ;;
    --no-start) START_SERVICES=0; shift ;;
    --unsafe-default) UNSAFE_DEFAULT=1; shift ;;
    --verbose) VERBOSE=1; shift ;;
    --user)
      die "--user removed: log in as the target user and run ./install.sh there"
      ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

if [[ "$NON_INTERACTIVE" == 1 && "$DO_ADB" != 1 ]]; then
  SKIP_ADB=1
fi

TARGET_USER="$(id -un)"
HOME_DIR="${HOME:-$(getent passwd "$TARGET_USER" | cut -d: -f6)}"
[[ -n "$HOME_DIR" && -d "$HOME_DIR" ]] || die "home directory missing for $TARGET_USER"
UNIT_DIR="$HOME_DIR/$UNIT_DIR_REL"
export HOME="$HOME_DIR"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"

prompt_yn() {
  # $1 prompt, $2 default Y|N
  local prompt="$1" def="${2:-N}" ans
  if [[ "$NON_INTERACTIVE" == 1 ]]; then
    [[ "$def" == "Y" || "$def" == "y" ]] && return 0
    return 1
  fi
  read -r -p "$prompt [${def}/$([[ "$def" == Y ]] && echo n || echo y)] " ans || true
  ans="${ans:-$def}"
  case "$ans" in
    Y|y|yes|YES) return 0 ;;
    *) return 1 ;;
  esac
}

need_cmd() {
  local c="$1"
  if command -v "$c" >/dev/null 2>&1; then
    log "  ok  $c -> $(command -v "$c")"
    return 0
  fi
  log "  MISSING  $c"
  return 1
}

# Map a missing command to distro package name(s). Empty = unknown / not installable here.
pkg_for_cmd() {
  local c="$1"
  case "$c" in
    systemctl) printf 'systemd' ;;
    python3)   printf 'python3' ;;
    curl)      printf 'curl' ;;
    wayvnc)    printf 'wayvnc' ;;
    xdg-dbus-proxy) printf 'wayvnc' ;;
    bwrap)     printf 'bubblewrap' ;;
    npx|node)  printf 'nodejs npm' ;;
    *)         printf '' ;;
  esac
}

as_root() {
  if [[ "$(id -u)" -eq 0 ]]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1; then
    sudo "$@"
  else
    return 1
  fi
}

install_pkgs() {
  # "$@" = package names
  [[ $# -gt 0 ]] || return 0
  log "  installing packages: $*"
  if command -v apt-get >/dev/null 2>&1; then
    run as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y "$@"
  elif command -v dnf >/dev/null 2>&1; then
    run as_root dnf install -y "$@"
  elif command -v pacman >/dev/null 2>&1; then
    run as_root pacman -Sy --noconfirm "$@"
  elif command -v zypper >/dev/null 2>&1; then
    run as_root zypper --non-interactive install "$@"
  else
    die "no supported package manager (apt-get/dnf/pacman/zypper); install manually: $*"
  fi
}

ensure_cmds() {
  # Ensure each command exists; collect missing → packages → install → re-check.
  local c pkgs=() missing=() pkg
  for c in "$@"; do
    if command -v "$c" >/dev/null 2>&1; then
      log "  ok  $c -> $(command -v "$c")"
      continue
    fi
    log "  MISSING  $c"
    missing+=("$c")
    pkg="$(pkg_for_cmd "$c")"
    if [[ -z "$pkg" ]]; then
      die "required command '$c' missing and no package mapping; install it and re-run"
    fi
    # shellcheck disable=SC2206
    pkgs+=($pkg)
  done
  if [[ ${#pkgs[@]} -eq 0 ]]; then
    return 0
  fi
  # Deduplicate package list
  local -A seen=()
  local uniq=() p
  for p in "${pkgs[@]}"; do
    [[ -n "${seen[$p]:-}" ]] && continue
    seen[$p]=1
    uniq+=("$p")
  done
  install_pkgs "${uniq[@]}"
  for c in "${missing[@]}"; do
    if command -v "$c" >/dev/null 2>&1; then
      log "  ok  $c -> $(command -v "$c") (installed)"
    else
      die "installed packages but '$c' still not on PATH"
    fi
  done
}

# --- 1. deps check / install ---
step deps "deps check"
REQUIRED_CMDS=(systemctl python3 curl bwrap)
if [[ "$SKIP_WAYVNC" != 1 ]]; then
  REQUIRED_CMDS+=(wayvnc)
fi
ensure_cmds "${REQUIRED_CMDS[@]}"
need_cmd tailscale || warn "tailscale not on PATH (credentials may fall back to 127.0.0.1)"
need_cmd npx || warn "npx not on PATH (install Node.js LTS for brave-mcp)"
# adb optional until Phase 2
if command -v adb >/dev/null 2>&1; then
  log "  ok  adb -> $(command -v adb)"
else
  log "  note adb not on PATH (manual APK install)"
fi
if [[ ! -x "${SWAYGENTRC_GROK_BIN:-$HOME_DIR/.grok/bin/grok}" && ! -x "$HOME_DIR/.grok/downloads/grok-linux-x86_64" ]]; then
  warn "grok binary not found under ~/.grok — install Grok Build before smoke"
fi

# --- 2. launchers ---
step launchers "install launchers (swaygentic + swaygentic-unsafe)"
run env SWAYGENTIC_BIN_DIR="${SWAYGENTIC_BIN_DIR:-$HOME_DIR/.local/bin}" \
  "$ROOT/scripts/install_swaygentic.sh"

# --- 3. MCP / trust note ---
step project-mcp "project MCP"
if [[ -f "$ROOT/.grok/config.toml" ]]; then
  log "  ok  $ROOT/.grok/config.toml (read-only for phone facade)"
  log "  note: first desktop session may need: cd $ROOT && swaygentic --trust"
else
  warn "missing $ROOT/.grok/config.toml"
fi

# --- 4. xai.env (never overwrite) ---
step xai-env "xAI key (run/xai.env)"
mkdir -p "$ROOT/run"
if [[ -f "$XAI_ENV" ]]; then
  log "  ok  $XAI_ENV (unchanged)"
else
  if [[ "$NON_INTERACTIVE" == 1 ]]; then
    warn "missing $XAI_ENV — create manually (mode 0600); Leo/Build need XAI_API_KEY"
  else
    if prompt_yn "Create $XAI_ENV now? Paste key after." N; then
      read -r -p "XAI_API_KEY= " key || true
      if [[ -n "${key:-}" ]]; then
        umask 077
        printf 'XAI_API_KEY=%s\n' "$key" >"$XAI_ENV"
        chmod 0600 "$XAI_ENV"
        log "  wrote $XAI_ENV (0600)"
      else
        warn "empty key; skipped"
      fi
    else
      warn "skipped; create $XAI_ENV before Leo/Build cloud calls"
    fi
  fi
fi

# --- 5. user units ---
step user-units "install user units under $UNIT_DIR"
mkdir -p "$UNIT_DIR"

# swaygentrc via existing Python helper (substitutes @HOME@ / @SERVER_DIR@)
SWAYGENTRC_SERVER_ROOT="$ROOT" \
python3 - "$SERVER_DIR" <<'PY'
import sys
sys.path.insert(0, sys.argv[1])
from toolbox.systemd_unit import ensure_systemd
print(ensure_systemd(start=False))
PY

if [[ "$UNSAFE_DEFAULT" == 1 ]]; then
  drop="$UNIT_DIR/swaygentrc.service.d"
  mkdir -p "$drop"
  wrap="$HOME_DIR/.local/bin/swaygentic-unsafe"
  cat >"$drop/wrap.conf" <<EOF
[Service]
Environment=SWAYGENTRC_WRAP=$wrap
EOF
  log "  unsafe-default: SWAYGENTRC_WRAP=$wrap"
else
  # Remove stale drop-in if operator turns off the flag on a re-run.
  if [[ -f "$UNIT_DIR/swaygentrc.service.d/wrap.conf" ]]; then
    rm -f "$UNIT_DIR/swaygentrc.service.d/wrap.conf"
    rmdir "$UNIT_DIR/swaygentrc.service.d" 2>/dev/null || true
    log "  removed swaygentrc wrap drop-in (jailed default)"
  fi
fi

if [[ "$SKIP_WAYVNC" != 1 ]]; then
  tmpl="$ROOT/scripts/wayvnc.service"
  [[ -f "$tmpl" ]] || die "missing $tmpl"
  body="$(
    sed \
      -e "s|@REPO_ROOT@|$ROOT|g" \
      -e "s|@HOME@|$HOME_DIR|g" \
      "$tmpl"
  )"
  dest="$UNIT_DIR/wayvnc.service"
  if [[ -f "$dest" ]] && [[ "$(cat "$dest")" == "$body" ]]; then
    log "  unit ok ($dest)"
  else
    printf '%s\n' "$body" >"$dest"
    log "  wrote $dest"
  fi
  mkdir -p "$HOME_DIR/.config/wayvnc"
  if [[ ! -f "$HOME_DIR/.config/wayvnc/env" ]]; then
    cp "$ROOT/scripts/wayvnc.env.example" "$HOME_DIR/.config/wayvnc/env"
    log "  seeded $HOME_DIR/.config/wayvnc/env"
  else
    log "  ok  $HOME_DIR/.config/wayvnc/env"
  fi
  chmod +x "$ROOT/scripts/wayvnc-tailscale.sh"
else
  log "  skip wayvnc unit (--skip-wayvnc)"
fi

run systemctl --user daemon-reload

# linger so phone API can start at boot without interactive login
if command -v loginctl >/dev/null 2>&1; then
  if loginctl enable-linger "$TARGET_USER" 2>/dev/null; then
    log "  linger on for $TARGET_USER"
  else
    warn "could not enable linger (may need: loginctl enable-linger $TARGET_USER)"
  fi
fi

# --- 6. enable / start ---
step enable-services "enable user services"
run systemctl --user enable swaygentrc.service
if [[ "$SKIP_WAYVNC" != 1 ]]; then
  systemctl --user enable wayvnc.service
fi

if [[ "$START_SERVICES" == 1 ]]; then
  run systemctl --user restart swaygentrc.service
  log "  restarted swaygentrc.service"
  if [[ "$SKIP_WAYVNC" != 1 ]]; then
    if systemctl --user restart wayvnc.service; then
      log "  restarted wayvnc.service"
    else
      warn "wayvnc restart failed (Wayland/Tailscale may not be ready yet); unit is enabled"
      systemctl --user --no-pager --full status wayvnc.service || true
    fi
  fi
else
  log "  --no-start: units enabled but not restarted"
fi

# --- 7. credentials bootstrap ---
# Server bootstrap writes credentials on start; also refresh via Python if needed.
step credentials "credentials"
mkdir -p "$RUN_DIR"
# Give swaygentrc a moment to write port/token after restart.
for _ in $(seq 1 20); do
  if [[ -f "$CREDENTIALS" && -f "$RUN_DIR/http.port" && -f "$RUN_DIR/api.token" ]]; then
    break
  fi
  sleep 0.25
done
if [[ ! -f "$CREDENTIALS" ]]; then
  # Foreground bootstrap without leaving a daemon if service failed.
  warn "credentials missing after service start; running one-shot bootstrap"
  SWAYGENTRC_SERVER_ROOT="$ROOT" SWAYGENTRC_SKIP_SYSTEMD=1 \
  python3 - "$SERVER_DIR" <<'PY'
import sys
sys.path.insert(0, sys.argv[1])
from system import bootstrap
bootstrap.setup()
PY
fi
[[ -f "$CREDENTIALS" ]] || die "failed to create $CREDENTIALS"
log "  ok  $CREDENTIALS"
if [[ -f "$RUN_DIR/http.port" ]]; then
  log "  http.port=$(tr -d '\n' <"$RUN_DIR/http.port")"
fi

# --- 8. smoke ---
if [[ "$SKIP_SMOKE" != 1 ]]; then
  step smoke "smoke_swaygentrc.sh"
  if [[ -x "${SWAYGENTRC_GROK_BIN:-$HOME_DIR/.grok/bin/grok}" || -x "$HOME_DIR/.grok/downloads/grok-linux-x86_64" ]]; then
    "$ROOT/scripts/smoke_swaygentrc.sh"
    # Smoke saves/restores http.port; bounce the user unit so it matches credentials.
    if [[ "$START_SERVICES" == 1 ]] && systemctl --user is-enabled swaygentrc.service >/dev/null 2>&1; then
      systemctl --user restart swaygentrc.service || warn "swaygentrc restart after smoke failed"
      # Refresh credentials for the live listen port (smoke may have left a stale URL briefly).
      sleep 0.5
      if [[ -f "$RUN_DIR/http.port" ]]; then
        log "  live http.port=$(tr -d '\n' <"$RUN_DIR/http.port")"
      fi
    fi
  else
    warn "skipping smoke — grok binary missing"
  fi
else
  step smoke "skip smoke (--skip-smoke)"
fi

# --- ADB (Phase 1 stub / Phase 2 hook) ---
newest_apk() {
  # Sort by semver in the filename (mtime is unreliable when APKs share a stamp).
  local f
  f="$(
    compgen -G "$ROOT/swaygentrc/app/swaygentrc_v*.apk" >/dev/null \
      && printf '%s\n' "$ROOT"/swaygentrc/app/swaygentrc_v*.apk \
      | sort -t_ -k2,2V \
      | tail -n1 \
      || true
  )"
  printf '%s' "$f"
}

print_phone_paths() {
  local apk
  apk="$(newest_apk)"
  log ""
  step phone-artifacts "phone artifacts (manual / --skip-adb)"
  if [[ -n "$apk" ]]; then
    log "  APK:         $apk"
    log "  package:     app.swaygentrc"
  else
    warn "no swaygentrc_v*.apk under swaygentrc/app/"
  fi
  log "  credentials: $CREDENTIALS"
  log "  certs:       none generated (HTTP on Tailscale) — skipped"
  log "  manual: upload credentials in SYSTEM → API; install APK by hand if needed"
}

if [[ "$DO_ADB" == 1 ]]; then
  step adb "ADB path"
  log "  ADB path not implemented yet — printing paths instead"
  print_phone_paths
elif [[ "$SKIP_ADB" == 1 ]]; then
  print_phone_paths
else
  if prompt_yn "Install swaygentrc APK over ADB now?" N; then
    log "  ADB path not implemented yet — printing paths instead"
    print_phone_paths
  else
    print_phone_paths
  fi
fi

log ""
log "OK install_swaygentic_system complete for user=$TARGET_USER"
log "  swaygentrc: systemctl --user status swaygentrc"
if [[ "$SKIP_WAYVNC" != 1 ]]; then
  log "  wayvnc:     systemctl --user status wayvnc"
fi
log "  do not run desktop swaygentic --trust and phone START together (both want :2419)"
