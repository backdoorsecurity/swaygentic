# Handoff — swaygentic (read this first)

**Date:** 2026-09-03  
**Repo:** https://github.com/backdoorsecurity/swaygentic/ (local checkout under `$HOME/swaygentic`)  
**Branch:** `main`  
**Current goal:** **one-shot host installer** for swaygentic + swaygentrc (install → reboot → services up). Entry: **`./install.sh`** at repo root. Prefer a dedicated test account — do not clobber the operator’s live checkout. Log in as that user (no `sudo -u` for `systemctl --user`).

Arena multi-agent (bravectl/winctl) stays for **dev**; not the product install story. Advisor commits to this tree; sync agent clones when scripts change.

This is not grokbox. Do not fork Brave. Read `SCOPE.md` for architecture; this file is the live status. Installer plan: `docs/install-and-native-view-plan.md`. Phone detail: `swaygentrc/SWAYGENTRC.md`.

---

## Session status (2026-09-03)

**Focus:** harden + verify repo-root `./install.sh` → `scripts/install_swaygentic_system.sh`. Verbose errors already on (step/line/command). `--verbose` echoes commands.

**Context:** Display agents **bravectl** (Brave MCP) / **winctl** (guest MCP) for parallel work. Advisor project MCP is **off** — board + shell + delegate.

### Guest desktop (winctl) — live

| Item | Value |
| --- | --- |
| Domain | `V2_tiny-10` (running before reboot; confirm with `virsh list`) |
| QEMU VNC | **`127.0.0.1:5902`** only (`autoport=no`, loopback) |
| Env | `WINCTL_VNC=127.0.0.1::5902` (repo default — **do not “fix” to 5904**) |
| Domain hint | `WINCTL_DOMAIN=V2_tiny-10` (smoke default `win10` is stale) |
| Smoke | `./scripts/smoke_winctl.sh` → **SMOKE_OK** as `browser` (framebuffer 1280×800) |
| Rule | Never drive host wayvnc / phone VIEW (`:5900`). Guest VNC only. |

Tailscale was stopped during port experiments; do not assume Tailscale IPs for guest control. Guest is loopback VNC.

### Builder accounts (arena v3 — sqlite + sockets)

| User | uid | Home clone | wayvnc `5$UID` | DevTools `6$UID` | Linger |
| --- | --- | --- | --- | --- | --- |
| `bravectl` | 1009 | `$HOME/swaygentic` | **51009** | 61009 | yes (Brave MCP only) |
| `winctl` | 1008 | `$HOME/swaygentic` | **51008** | 61008 | yes (winctl MCP only) |

- **Live comms:** body in `/srv/arena/blackboard/board.sqlite` (`messages`); wake via `/srv/arena/socket/listen.py` + `send.py` (AF_UNIX, id only). **One dest per send.**
- **Advisor inbound:** systemd `--user` `arena-advisor-listen.service` (`listen.py advisor --no-auto-ack`). Monitor `watch_advisor_sock.sh` on `/srv/arena/socket/advisor-listen.log` (`[sock wake]` / `[sock catchup]`, id only). Grok `ack`s. Do **not** run the unread poller.
- **Retired:** `/srv/arena/pipe/` and `/srv/arena/filelog/` (do not start fifo listeners).
- **SOP (central):** `/srv/arena/docs/STARTUP.md`. Home `~/swaygentic/STARTUP.md` is a pointer only. Prefer `swaygentic` / `swaygentic-unsafe`.
- **Judge:** operator (`browser`) criticizes methods; accepted work is patched into **this** checkout, then pushed to agent trees.
- **Groups:** `bravectl`/`winctl` in `libvirt`, `libvirt-qemu`, `arena`. `browser` in `arena`.
- **Display:** `bravectl`/`winctl` headless Sway + wayvnc **enabled** (`5$UID` / DevTools `6$UID`). Guest QEMU VNC `:5902`. Phone VIEW `:5900` untouched.
- **Comms dests:** `bravectl` | `winctl` | `advisor`. Snapshots: `/tmp/snapshots/{bravectl,winctl,advisor}/`.
- **Legacy:** `/srv/arena/swaygentic` → `blackboard`.

### After reboot — quick checks

```bash
cd ~/swaygentic   # or this checkout
virsh list --all
ss -ltn | grep 5902          # expect 127.0.0.1:5902
export WINCTL_DOMAIN=V2_tiny-10
./scripts/smoke_winctl.sh    # expect SMOKE_OK
# MCP: search_tool / use_tool for winctl__win_status then winctl__win_look
```

As `bravectl` or `winctl` (new login so groups apply): same smoke; virsh domain query needs `libvirt` group.

### Operator next

1. **Installer (now):** as the test user, `cd ~/swaygentic && ./install.sh --non-interactive --skip-adb [--verbose]`. Fix failures until reboot leaves `swaygentrc` (+ wayvnc if enabled) healthy.
2. Sync `/srv/arena/project_dir/swaygentic` from this checkout when you want the arena copy current (project_dir is behind on install scripts).
3. Keep separate UIDs for bravectl/winctl (dev). Sync their trees from operator when MCP/scripts change.
4. Phase 2 ADB still later.

---

## One-sentence goal

Local desktop project: (1) Brave Leo chats with Grok via xAI Chat Completions + personal API key; (2) **swaygentic** runs Grok Build inside a bubblewrap jail with Brave Nightly (tab containers + MCP/CDP on loopback) and optional **winctl** guest-desktop control (QEMU VNC) — no custom Chromium, no X11, no custom FIFO.

## Two faces (keep separate)

| Face | Who | Wire |
| --- | --- | --- |
| **Leo** | Human ↔ Grok in Brave sidebar | BYOM → Chat Completions (`/v1/chat/completions`) |
| **Build** | Grok Build ↔ browser + guest desktop | **`swaygentic`** (bwrap) → grok; `brave-devtools` on `127.0.0.1:9222`; **winctl** on QEMU VNC `127.0.0.1:5902` |
| **Phone** | Android ↔ agent + VIEW | HTTP/SSE + ACP `:2419`; VIEW = wayvnc Tailscale `:5900` |

---

## What this session accomplished

### Leo / API (prior + carried forward)
- Leo proxy + smokes; Nightly + Node 22; Wayland-only
- Personal key path: `run/xai.env` (0600, gitignored)

### Build / MCP
- Backend: **brave-mcp only** (attach mode, not Puppeteer-owned launch)
- We own Brave: `mcp/ensure_brave.sh` + loopback DevTools **`http://127.0.0.1:9222` (TCP — not a named pipe)**
- MCP: `mcp/run_brave_mcp.sh` → `--browser-url=http://127.0.0.1:9222`
- `.grok/config.toml`: `max_output_bytes = 1500000`, allow `MCPTool(brave-devtools__*)`
- Profile: Nightly default `~/.config/BraveSoftware/Brave-Browser-Nightly` (test VM; tab containers for isolation, not a second user-data-dir)

### Tab containers + launch helper
- `mcp/launch.sh` / `mcp/new_tab.sh` open URLs with `--container=<name>`
- Specs: `https://url`, `misc:https://url`, `brave-browser-nightly:misc:https://url`
- Default container: `$SWG_CONTAINER` (`misc`); known: us bank, quickbooks, dor, misc
- Do **not** use `brave-devtools__new_page` when a Brave tab container is required

### Screenshots (agent convention, no wrapper code)
- After launch/click/navigate/submit: `take_screenshot` when vision helps
- Prefer `take_snapshot` / evaluate / network for DOM — CDP means a shot is **not** required every time
- Default screenshot = **viewport**; `fullPage: true` = full page (not whole desktop)

### swaygentic jail (agent + browser)
- `mcp/jail-run.sh` — bubblewrap adapted from grokbox-run (loose → tightening)
- `mcp/swaygentic` — entrypoint; does **not** replace `~/.grok/bin/grok` (survives grok updates)
- Install: `./scripts/install_swaygentic.sh` → `~/.local/bin/swaygentic` (+ `swaygentic-unsafe`)
- Escape hatch: `SWAYGENTIC_OFF=1 swaygentic …` or **`swaygentic-unsafe`** (no bubblewrap jail)
- Home is tmpfs whitelist: `.grok`, BraveSoftware (+ Nightly cache), `~/.pki` (NSS / ZAP CA), `~/.ZAP`, `~/.local/share/foxyproxy`, Downloads, `.local/bin|lib`, repo, gtk/fontconfig bits
- **Tightened:** removed broad `~/.cache` and `~/.npm` binds (only `~/.cache/BraveSoftware`); keep `~/.pki` / `~/.ZAP` / foxyproxy share so in-jail Brave+ZAP MITM works
- **Binary blacklist:** `mcp/jail-denied.sh` overlaid on sudo/su/ssh/docker/systemctl/mount/nsenter/…
- In-jail Brave: `--no-sandbox` + `--test-type` (hide unsupported-flag banner)
- QXL / no 3D: `--disable-gpu` + `--disable-gpu-compositing` (anti-flicker; verified)

### winctl (guest desktop MCP) — absorbed
- Tree: `mcp/winctl/` (from dirty_diesel-grok). Tools: `winctl__win_status|look|click|move|scroll|type|key|drag`.
- Target: QEMU guest VNC `WINCTL_VNC` default `127.0.0.1::5902`. **Never** touch host wayvnc `:5900`.
- Launch: `mcp/winctl/launch.sh` (`.venv-winctl`). Smoke: `./scripts/smoke_winctl.sh`. CLI: `./scripts/winctl-vnc.sh`.
- Registered in `.grok/config.toml` next to brave-devtools. Diesel/INSITE apps are workloads, not the product name.
- Archive source: `/home/git/dirty_diesel-grok` (read-only after absorb).

### Verified in this session
- MCP config smoke; container launch handoff (no ProcessSingleton when we own Brave)
- Filesystem jail: host-only paths absent; Downloads/repo writable; blacklist exit 126
- Manual Brave launch + DevTools + `misc:https://example.com` with GPU-disable flags
- `swaygentic --version` → real grok
- winctl package + relative MCP registration; `smoke_mcp_config` sees both MCP servers
- Jail `--share-net` proven: in-bwrap TCP to host loopback works (`:22` OK). Guest VNC **`:5902`** verified 2026-09-03 on domain `V2_tiny-10` (`smoke_winctl.sh` SMOKE_OK). Use `WINCTL_DOMAIN=V2_tiny-10`.
- `jail-run.sh` now creates missing `XDG_RUNTIME_DIR` when possible (non-logind sessions)

### Phone face (swaygentrc) — **working on device (0.9.4)**
- Tree: `swaygentrc/` (Android APK + Python facade). Server: `server/system/` + `server/toolbox/`. Living plan: **`swaygentrc/SWAYGENTRC.md`**.
- **Host installer Phase 1 DONE:** `./scripts/install_swaygentic_system.sh --skip-adb` (launchers, user units, credentials, smoke). Phase 2 ADB **paused** (UX settled; resume when asked).
- Phone talks **HTTP/SSE** + Bearer `api.token` (not raw ACP). Facade → ACP on `127.0.0.1:2419`, agent wrapped with `mcp/swaygentic`.
- Listen port: **random high**, persisted in `swaygentrc/server/run/http.port` (live example **55440**) → `credentials.swaygentrc`. Re-upload credentials after port churn.
- MCP config is **read-only**. JPEG `/grok/frame` stub (204) while VNC is primary VIEW.
- Smoke: `./scripts/smoke_swaygentrc.sh` (saves/restores `http.port`). Docs: `swaygentrc/README.md`.
- Do not run desktop `swaygentic --trust` and phone START together (both want `:2419`).
- Package **`app.swaygentrc`**. Current APK: **`swaygentrc/app/swaygentrc_v0.9.4.apk`** (also under `android/apks/`). Uninstall legacy `app.grokrc` if present.
- **VIEW (native VNC, MIT vernacular):** `android/vernacular/` → guest **`wayvnc`** on Tailscale `:5900`. Haven Intent **removed** from product UI (AGPL companion not required).
  - Credentials: `url`, `token`, `vnc_host`, `vnc_port` (Tailscale IPv4 + `5900`; env `SWAYGENTRC_VNC_*`).
  - VIEW chrome: **FULL** (immersive), **KEYB** (system IME; Ctrl/Alt/Shift over RFB — use Hacker's Keyboard if installed; **no on-screen modifier buttons**), **SCROLL|SELECT** drag mode.
  - SYSTEM → **PERFORMANCE**: C8/C16/C24, ZLIB, fps (client-side; reconnect applies). Main remaining gap vs Haven: **no ZRLE**.
  - Pointer I/O on background thread (fixed NetworkOnMainThread on drag).
  - User unit **`wayvnc.service`** (`scripts/wayvnc-tailscale.sh`) — separate from `swaygentrc.service`.
  - vsock fallback still in `scripts/vsock/` if wayvnc fails. Haven tree `/home/browser/Haven` = reference only (`docs/haven-companion.md`).
- **Groups:** `browser` in `render` / `input` / … for DRI/wayvnc.

### Display
- Guest on **virtio** video; `WLR_RENDERER=pixman` + `WLR_NO_HARDWARE_CURSORS=1` in `~/.zprofile`.
- Hypervisor VNC/SPICE on host `127.0.0.1:5900` / `:5901` — not phone-reachable; phone uses Tailscale wayvnc.
- virtio-vsock available (guest CID **3**) as fallback only.

### Not fully closed / next operator notes
- `brave-devtools` MCP can fail handshake until Brave/DevTools is up — `ensure_brave` via MCP entrypoint should autostart
- Leo UI BYOM may still need a manual settings check
- Jail still “medium tight” — further bind/seccomp later
- Optional: ZRLE encoding (speed), Phase 2 ADB installer path, second-user Phase 1 install test
- Phone face marked good by operator (2026-09-01); **winctl M0:** guest `:5902` smoke OK (2026-09-03) — next: ACP hardening (M1), turn compression (M2), arena builder rounds
- **Later (phone VIEW):** FPS picker in stepped presets `1, 2, 4, 8, 16, 32` (maybe `64`) — current default 15 feels glitchy on slow networks; also raise/revisit client `coerceIn` and wayvnc `MAX_FPS`

---

## Operator next steps (run it)

### 0. Finish directory rename (if still named `grok-in-browser`)

This session may still be inside swaygentic with the repo bind mounted, so `mv` of the project dir can fail with "Device or resource busy". On a **host** shell (not inside swaygentic):

```bash
~/grok-in-browser/scripts/finish_rename_swaygentic.sh
# or: cd ~ && mv grok-in-browser swaygentic && cd swaygentic && ./scripts/install_swaygentic.sh
```

### 1. Install / enter

```bash
cd ~/swaygentic   # or: git clone … && cd swaygentic
./install.sh --non-interactive --skip-adb
# launchers only: ./scripts/install_swaygentic.sh
```

### 2. API key (Leo + xAI smoke)

```bash
./scripts/smoke_xai.sh   # needs run/xai.env
```

### 3. Build face

```bash
cd ~/swaygentic
./scripts/smoke_mcp_config.sh
./scripts/smoke_launch_container.sh
./mcp/launch.sh 'brave-browser-nightly:misc:https://example.com'
swaygentic --trust
# click/type via brave-devtools; screenshot when you need vision
```

### 4. Leo face

1. Brave Nightly → `brave://settings/leo-ai`
2. BYOM: `https://api.x.ai/v1/chat/completions`, model id from `/v1/models`, personal key
3. If Leo “network” error but curl works: `./scripts/run_leo_proxy.sh` → `http://127.0.0.1:8787/v1/chat/completions`

### 5. Phone face (swaygentrc)

```bash
./install.sh --skip-adb   # once / after host changes
./scripts/smoke_swaygentrc.sh
systemctl --user status swaygentrc wayvnc
# upload swaygentrc/server/run/credentials.swaygentrc in SYSTEM → API → START → chat
# sideload: adb install -r swaygentrc/app/swaygentrc_v0.9.4.apk
```

### 6. Phone VIEW (in-app VNC → wayvnc)

```bash
systemctl --user enable --now wayvnc
ss -tlnp | grep 5900   # Tailscale IPv4:5900
# In app: VIEW auto-connects; FULL / KEYB / SCROLL|SELECT; SYSTEM → PERFORMANCE

# vsock fallback only if wayvnc misbehaves — scripts/vsock/README.md
```

---

## Hard rules

- Do **not** fork Brave / Chromium (Phase 4 needs written justification).
- Do **not** use X11, xdotool, ydotool, Xvfb.
- Do **not** invent `/tmp/grok.fifo`; agent pipe = ACP; browser pipe = CDP on **127.0.0.1:9222**.
- Prefer Brave tab containers over a second user-data-dir (test VM).
- Do **not** publish debug on `0.0.0.0:9222`.
- One browser MCP backend only (`brave-mcp`).
- Prefer **`swaygentic`**, not raw `grok`, for Build so agent+browser stay jailed.
- If a smoke fails, stop — don’t add a second backend.

---

## Quick file map

```text
HANDOFF.md              ← this file
SCOPE.md / AGENTS.md
README.md
docs/leo-byom.md
docs/acp.md
mcp/brave_flags.sh      ← only place for Brave/MCP flags
mcp/jail-run.sh         ← bwrap (agent + browser)
mcp/jail-denied.sh      ← stub over blacklisted binaries
mcp/swaygentic          ← jail entrypoint → real grok
mcp/ensure_brave.sh
mcp/launch.sh / new_tab.sh
mcp/run_brave_mcp.sh    ← MCP attaches via --browser-url
mcp/winctl/             ← guest desktop MCP (QEMU VNC; not wayvnc)
swaygentrc/             ← phone APK + HTTP→ACP facade
swaygentrc/server/system/   config, bootstrap, start_server, unit
swaygentrc/server/toolbox/  http_app, acp_client, process, wrap
install.sh              ← clone entrypoint → scripts/install_swaygentic_system.sh
scripts/install_swaygentic.sh
scripts/smoke_*.sh
scripts/winctl-vnc.sh
proxy/leo_proxy.py
.grok/config.toml
run/xai.env             ← secrets; create locally
# Nightly profile: ~/.config/BraveSoftware/Brave-Browser-Nightly
```

## Env knobs (prefix `SWG_`)

| Var | Default | Meaning |
| --- | --- | --- |
| `SWG_CONTAINER` | `misc` | Default Brave tab container for `launch.sh` |
| `SWG_PROFILE_DIR` | Nightly user-data-dir | Brave profile |
| `SWG_DEBUG_PORT` | `9222` | Loopback DevTools |
| `SWG_NO_SANDBOX` | `1` | Needed in bwrap; pair with `--test-type` |
| `SWG_FORCE_QUIT_BRAVE` | `0` | Test-VM: kill profile lock holder |
| `SWAYGENTIC_OFF` | `0` | Skip jail; run real grok on host |
| `SWAYGENTIC_GROK` | auto | Path to real grok binary |
| `WINCTL_VNC` | `127.0.0.1::5902` | Guest QEMU VNC for winctl |
| `WINCTL_DOMAIN` | `V2_tiny-10` | libvirt domain for virsh send-key |
| `SWG_WINCTL_PYTHON` | `.venv-winctl/bin/python` | Override winctl interpreter |
