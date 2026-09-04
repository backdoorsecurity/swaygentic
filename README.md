# Swaygentic

Run **Grok** in the browser with a personal xAI API key (Brave Leo BYOM), and let **Grok Build** drive an isolated Brave Nightly profile over MCP — without forking Chromium.

Read **[SCOPE.md](./SCOPE.md)** before changing architecture. Short rules: **[AGENTS.md](./AGENTS.md)**. Live status: **[HANDOFF.md](./HANDOFF.md)**.

## Install

Reboot-safe host install for swaygentic + swaygentrc (launchers, user units, linger, smokes).

```bash
git clone https://github.com/backdoorsecurity/swaygentic/
cd swaygentic/
./install.sh --non-interactive --skip-adb --verbose
```

Use a **login session** as the target user (so `systemctl --user` works). Prefer a dedicated account if you do not want to touch your main desktop session.

- Entry: [`install.sh`](./install.sh) → [`scripts/install_swaygentic_system.sh`](./scripts/install_swaygentic_system.sh)
- Plan: [`docs/install-and-native-view-plan.md`](./docs/install-and-native-view-plan.md)
- Failures print step / line / command; `--verbose` echoes commands
- Phone APK (sideload): [`swaygentrc/app/swaygentrc_v0.9.4.apk`](./swaygentrc/app/swaygentrc_v0.9.4.apk)

## Two faces

| Face | Who talks | Wire |
| --- | --- | --- |
| **Leo** | You ↔ Grok inside Brave’s assistant | Chat Completions → `https://api.x.ai/v1/chat/completions` (optional localhost proxy) |
| **Build** | Grok Build ↔ Brave (+ optional guest desktop) | `swaygentic` jail → MCP `brave-devtools` on `127.0.0.1:9222` + tab containers; **winctl** on QEMU VNC `:5902` |
| **Phone** | Android ↔ agent + VIEW | swaygentrc HTTP/SSE + ACP; VIEW = wayvnc Tailscale `:5900` |

They share: xAI key on disk/env, Brave Nightly, isolation rules.  
They do **not** share: UI process, protocol, or a custom FIFO. Host wayvnc (`:5900`) is phone VIEW only — winctl never touches it.

## Quick start

### 0. Clone / install

```bash
git clone https://github.com/backdoorsecurity/swaygentic/
cd swaygentic/
./install.sh --non-interactive --skip-adb
```

### 1. API key (Leo + smokes)

```bash
mkdir -p run
cp .env.example run/xai.env
chmod 600 run/xai.env
# edit run/xai.env — set XAI_API_KEY
./scripts/smoke_xai.sh
```

### 2. Leo face (human chat)

See **[docs/leo-byom.md](./docs/leo-byom.md)**. Short version:

1. Install [Brave Nightly](https://brave.com/linux/nightly/) if needed
2. `brave://settings/leo-ai` → BYOM → endpoint `https://api.x.ai/v1/chat/completions`, model id `grok-4.6`
3. If Leo fails after curl works: `./scripts/run_leo_proxy.sh` and point Leo at `http://127.0.0.1:8787/v1/chat/completions`

### 3. Build face (Grok drives Brave)

Requires **Node.js LTS** (`npx`) and Brave Nightly.

```bash
# Node.js LTS required for npx brave-mcp
node -v   # e.g. v22+

# config smoke (no browser launch)
./scripts/smoke_mcp_config.sh

# live MCP: open example.com + screenshot (headless)
./scripts/smoke_mcp_browser.sh

# from this directory, trust the project so .grok/config.toml MCP loads
grok --trust
grok mcp doctor brave-devtools
```

Then ask Grok Build to open `https://example.com` and take a screenshot via the **brave-devtools** tools (not via shell `xdotool`).

**Build entrypoint:** [`mcp/swaygentic`](./mcp/swaygentic) (installed by `./install.sh`, or `./scripts/install_swaygentic.sh` alone) — runs real `grok` inside a loose bubblewrap jail. Does not replace `~/.grok/bin/grok`.

```bash
./scripts/install_swaygentic.sh
cd /path/to/swaygentic && swaygentic --trust
```

Open / new tab (always containerized): [`mcp/launch.sh`](./mcp/launch.sh) (alias [`mcp/new_tab.sh`](./mcp/new_tab.sh))

```bash
./mcp/launch.sh 'https://example.com'                                 # default container (misc)
./mcp/launch.sh 'misc:https://rawze.com'
./mcp/launch.sh 'brave-browser-nightly:misc:https://rawze.com'
```

MCP attach: [`mcp/run_brave_mcp.sh`](./mcp/run_brave_mcp.sh) → `--browser-url=http://127.0.0.1:9222` (TCP; not a named pipe)  
Flags: [`mcp/brave_flags.sh`](./mcp/brave_flags.sh) (`--no-sandbox` + `--test-type` by default for the jail)  
Profile: Nightly default `~/.config/BraveSoftware/Brave-Browser-Nightly`

Backend choice for Phase 2: **brave-mcp** only (attach mode).

**Guest desktop (winctl):** nested OS UI over QEMU VNC (default `:5902`). Setup venv once via `./scripts/smoke_winctl.sh`. Tools are `winctl__*`. Never points at host wayvnc `:5900`.

### 4. ACP (other programs drive Grok Build)

See **[docs/acp.md](./docs/acp.md)**.

```bash
grok agent --always-approve stdio
# or
grok agent --always-approve serve --bind 127.0.0.1:2419 --secret …
```

## Layout

```text
README.md
SCOPE.md
AGENTS.md
docs/leo-byom.md
docs/acp.md
scripts/smoke_xai.sh
scripts/smoke_proxy.sh
scripts/smoke_mcp_config.sh
scripts/run_leo_proxy.sh
proxy/leo_proxy.py
mcp/brave_flags.sh
mcp/jail-run.sh
mcp/swaygentic
mcp/ensure_brave.sh
mcp/launch.sh
mcp/new_tab.sh
mcp/run_brave_mcp.sh
scripts/install_swaygentic.sh
.grok/config.toml
```

## Acceptance smokes

```bash
./scripts/smoke_xai.sh          # needs XAI_API_KEY
./scripts/smoke_proxy.sh        # local rewrite + healthz
./scripts/smoke_mcp_config.sh   # MCP registered; no X11 leftovers
grep -n 'mcp_servers' .grok/config.toml
```

## Failure modes

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Leo “network issue”, curl OK | Leo sends `temperature` etc. | Run `scripts/run_leo_proxy.sh`, point Leo at localhost |
| `smoke_xai.sh` 401 | Missing/bad key | Set `XAI_API_KEY` or `run/xai.env` |
| MCP won’t start | No `npx` | Install Node.js LTS |
| Brave won’t show | No Wayland | This project assumes Wayland (`WAYLAND_DISPLAY`); no X11 |
| ensure_brave SingletonLock | Other Nightly owns profile without our debug port | Quit that Brave, or `SWG_FORCE_QUIT_BRAVE=1` in this VM |
| Tab not in a container | Used CDP `new_page` | Use `mcp/launch.sh 'container:url'` instead |

## Out of scope (v1)

Forking Brave/Chromium, X11/xdotool, custom `/tmp/grok.fifo`, putting the API key in page JS. Phase 4 (brave-core) needs a written justification first — see SCOPE.md.
