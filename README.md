# Swaygentic

Swaygentic lets you run [Grok](https://x.ai/) on a Linux desktop and remotely through an android device.

You get four related surfaces in one checkout:

| Surface | Who uses it | What it does |
| --- | --- | --- |
| Leo | You, in Brave | Grok controls brave through Brave Leo BYOM and your personal xAI API key |
| Build (swaygentic) | Grok Build | Drive the browser (and optionally a nested guest desktop "window or linux") through MCP |
| Phone (swaygentrc) | Android app | Start/stop the agent, chat over HTTP/SSE, and view/directly control the desktop over VNC |
| Guest (winctl) | Grok Build | Click and type inside a QEMU Windows (or other) VM over loopback VNC |

## Architecture (canonical)

```text
User
  └─ Brave Leo sidebar (BYOM)
        └─ [optional localhost proxy]
              └─ https://api.x.ai/v1/chat/completions
                    Authorization: Bearer $XAI_API_KEY
                    model: grok-4.6   (or current xAI id)

Grok Build (TUI or ACP client)
  └─ MCP: brave-devtools + winctl
        ├─ Brave Nightly (tab container via mcp/launch.sh)
        │     └─ CDP on 127.0.0.1:9222
        └─ Guest desktop (mcp/winctl)
              └─ QEMU VNC 127.0.0.1:5902

Phone face (swaygentrc)
  └─ Android APK → HTTP/SSE (Tailscale, persisted high port)
        └─ swaygentrc/server → grok agent serve 127.0.0.1:2419 (via swaygentic)
              └─ project MCP (.grok/config.toml, read-only)
        └─ VIEW → wayvnc Tailscale :5900
```

| Job | Wire | Command / flag |
| --- | --- | --- |
| Drive Grok Build from another program | ACP JSON-RPC on stdio | `grok agent --always-approve stdio` |
| Drive Grok Build over local WebSocket | ACP JSON-RPC on WebSocket | `grok agent --always-approve serve --bind 127.0.0.1:2419 --secret …` |
| Grok Build internal multi-client | Unix domain socket leader | built into Grok Build; |
| Drive the browser | CDP on loopback TCP | DevTools at `http://127.0.0.1:9222` |
| Expose browser and guest tools to Grok Build | MCP stdio | `.grok/config.toml` `[mcp_servers.<name>]` |

Agent pipe = ACP. Browser pipe = CDP. Tool surface = MCP.

---

## Prerequisites and install

### Prerequisites

- Linux desktop on Wayland
- [Grok Build](https://x.ai/docs/build/overview) installed (`~/.grok/bin/grok` or the downloaded binary)
- [Brave Nightly](https://brave.com/linux/nightly/)
- Node.js LTS (`npx`) for `brave-mcp`
- `python3`, `systemctl --user`, `curl`
- Optional phone VIEW: [Tailscale](https://tailscale.com/) + `wayvnc`
- Optional nested guest desktop: libvirt/QEMU guest with VNC on loopback (default `127.0.0.1:5902`)
- Optional Leo BYOM: personal `XAI_API_KEY` from xAI

### Install

```bash
git clone https://github.com/backdoorsecurity/swaygentic.git/
cd swaygentic/
./install.sh --non-interactive --skip-adb --verbose
```

Flags: `./install.sh --help`. Run as the target user in a **login session** (`systemctl --user`). Prefer a dedicated account if you do not want to touch your main desktop.

Installer: launchers in `~/.local/bin`, `swaygentrc` + `wayvnc` user units + linger, phone credentials, optional smoke, prints APK path (ADB later).

### API key

```bash
mkdir -p run
cp .env.example run/xai.env
chmod 600 run/xai.env
# edit run/xai.env — set XAI_API_KEY
./scripts/smoke_xai.sh
```

### Phone APK

Sideload the release APK:

```bash
adb install -r swaygentic/swaygentrc/app/swaygentrc_v0.9.4.apk
```

Package id: `app.swaygentrc`.

### Quick checks after install

```bash
systemctl --user status swaygentrc wayvnc
./scripts/smoke_mcp_config.sh
./scripts/smoke_xai.sh
```

---

## Swaygentic (Grok Build)

Swaygentic is the Build face: Grok Build (TUI or an ACP client) talks to tools over MCP while the real `grok` binary runs inside a bubblewrap jail.

### Entry points

| Command | Meaning |
| --- | --- |
| `swaygentic` | Jailed grok (default). Does not replace `~/.grok/bin/grok`. |
| `swaygentic-unsafe` | Host grok, no jail (`SWAYGENTIC_OFF=1`) |
| `swaygentic --trust` | Trust this checkout so `.grok/config.toml` MCP servers load |

```bash
cd /path/to/swaygentic
swaygentic --trust
```

### How Build reaches the browser

1. This project owns Brave Nightly (`mcp/ensure_brave.sh`).
2. DevTools listen on loopback only: (`http://127.0.0.1:9222`).
3. MCP attaches with `brave-mcp` via `mcp/run_brave_mcp.sh` (`--browser-url=…`).
4. Project registration lives in `.grok/config.toml` under `[mcp_servers.brave-devtools]`.

Open or new-tab always goes through built in brave tab containers:

```bash
./mcp/launch.sh 'https://example.com'
./mcp/launch.sh 'misc:https://example.com'
./mcp/launch.sh 'brave-browser-nightly:misc:https://example.com'
```

### ACP (other programs drive the same agent)

See [docs/acp.md](./docs/acp.md).

```bash
grok agent --always-approve stdio
# or
grok agent --always-approve serve --bind 127.0.0.1:2419 --secret …
```

Phone START and desktop `swaygentic --trust` both want `:2419` — do not run them together.

### Leo face (human chat in Brave)

Optional, same key as Build:

1. Brave Nightly → `brave://settings/leo-ai`
2. BYOM endpoint `https://api.x.ai/v1/chat/completions`, model id from `GET /v1/models` (for example `grok-4.6`)
3. If Leo shows a network error but `./scripts/smoke_xai.sh` works, run `./scripts/run_leo_proxy.sh` and point Leo at `http://127.0.0.1:8787/v1/chat/completions`

Details: [docs/leo-byom.md](./docs/leo-byom.md).

### Useful env

| Var | Default | Meaning |
| --- | --- | --- |
| `SWG_CONTAINER` | `misc` | Default Brave tab container for `launch.sh` |
| `SWG_PROFILE_DIR` | Nightly user-data-dir | Brave profile |
| `SWG_DEBUG_PORT` | `9222` | Loopback DevTools port |
| `SWG_NO_SANDBOX` | `1` | Needed in bwrap; pair with `--test-type` |
| `SWAYGENTIC_OFF` | `0` | Skip jail; run real grok on host |
| `SWAYGENTIC_GROK` | auto | Path to real grok binary |

---

## Swaygentrc (phone face)

Swaygentrc is the Android + host facade so a phone can remotely start the agent, chat, view and control the desktop.

```text
Phone APK  --Bearer-->  swaygentrc HTTP (Tailscale, persisted high port)
                              |
                              +--> swaygentic --> grok agent serve :2419
                              +--> ACP WebSocket (chat)
                              +--> VIEW --> wayvnc on Tailscale :5900
```

The APK does not speak ACP. It talks HTTP/SSE with a Bearer token. The Python facade under `swaygentrc/server/` opens ACP to loopback `:2419` and wraps the agent with `swaygentic` so Build + Brave stay jailed.

### Host side

Installed by `./install.sh`:

- User unit `swaygentrc.service`
- User unit `wayvnc.service` (VIEW)
- Credentials file `swaygentrc/server/run/credentials.swaygentrc` (`url`, `token`, `vnc_host`, `vnc_port`)
- Listen port persisted in `swaygentrc/server/run/http.port` (random high port; Tailscale bind by default)

```bash
systemctl --user status swaygentrc wayvnc
./scripts/smoke_swaygentrc.sh
```

### Phone side

1. Sideload `swaygentrc/app/swaygentrc_v0.9.4.apk` (`app.swaygentrc`).
2. Copy `credentials.swaygentrc` to the phone.
3. In the app: SYSTEM → API → UPLOAD.
4. Tap START, then chat.
5. Open VIEW for the desktop (in-app VNC to wayvnc).

---

## Bravectl (browser control)

Bravectl is the browser-control path: Grok Build drives Brave Nightly through the `brave-devtools` MCP server.

### Ownership model

- This repo launches and owns Brave (`mcp/ensure_brave.sh`).
- MCP does not launch its own Chromium; it attaches to DevTools on `127.0.0.1:9222`.
- Isolation on a shared profile uses Brave tab containers via `mcp/launch.sh`, not a second user-data-dir.

### Smokes

```bash
./scripts/smoke_mcp_config.sh
./scripts/smoke_mcp_browser.sh
./scripts/smoke_launch_container.sh
```

---

## Winctl (guest desktop)

Winctl is an MCP server that drives a nested guest OS over QEMU VNC (pixels + keys). It is not a second browser stack and it never touches host wayvnc (`:5900`).

### Target

| Item | Default |
| --- | --- |
| VNC | `WINCTL_VNC=127.0.0.1::5902` |
| Domain hint | `WINCTL_DOMAIN` (libvirt name for virsh-backed keys) |
| Package | `mcp/winctl/` |
| Launch | `mcp/winctl/launch.sh` (uses `.venv-winctl`) |
| Smoke | `./scripts/smoke_winctl.sh` |
| CLI helper | `./scripts/winctl-vnc.sh` |

Registered next to brave-devtools in `.grok/config.toml`.

### Tools

Discover with MCP tool search, then call:

- `win_status` — text only; confirm VNC + domain
- `win_look` — framebuffer JPEG; use this before guessing coordinates
- `win_click` / `win_move` / `win_scroll` / `win_drag` — guest pixel coords from the last shot
- `win_type` / `win_key` — typing and chords (`win`, `win+r`, `alt+tab`, `ctrl+alt+delete`, …)

Actions that change the UI already return a JPEG. Prefer SSH/scp into the guest for bulk file work instead of pixel-typing long paths.

### Setup once

```bash
export WINCTL_VNC=127.0.0.1::5902
export WINCTL_DOMAIN=your-libvirt-domain
./scripts/smoke_winctl.sh
```

Confirm the nested guest VNC is loopback-only (`ss -ltn | grep 5902` should show `127.0.0.1:5902`).

---

## Security

### Goals

- Keep the coding agent and browser in a jail when using `swaygentic`
- Keep browser DevTools and ACP on loopback unless you deliberately widen them
- Keep phone API on Tailscale (or explicit bind), not a casual `0.0.0.0` default
- Keep the nested Windows (or other) guest reachable only via loopback QEMU VNC for winctl
- Keep `XAI_API_KEY` out of git, renderer JS, and unpacked extensions

### Bubblewrap jail (`swaygentic`)

`mcp/swaygentic` runs real grok inside `mcp/jail-run.sh`:

- Home is mostly a tmpfs whitelist (`.grok`, Brave profile/cache bits, `~/.pki`, `~/.ZAP`, foxyproxy share, Downloads, repo, selected `.local` paths)
- Broad host `~/.cache` and `~/.npm` are not bound
- Blacklisted binaries are overlaid with `mcp/jail-denied.sh` (for example `sudo`, `su`, `ssh`, `docker`, `systemctl`, `mount`, `nsenter`) and exit 126
- Network is shared so loopback DevTools and guest VNC still work from inside the jail
- In-jail Brave uses `--no-sandbox` + `--test-type` because Chromium cannot nest its sandbox usefully under this bwrap setup

Escape hatch for debugging: `swaygentic-unsafe` or `SWAYGENTIC_OFF=1`.

### Secrets and listening sockets

| Asset | Rule |
| --- | --- |
| `run/xai.env` | Mode 0600, gitignored |
| Phone `api.token` / `agent.secret` | Under `swaygentrc/server/run/`, gitignored |
| DevTools | `127.0.0.1:9222` only |
| ACP serve | `127.0.0.1:2419` only unless you pass an explicit bind |
| Guest VNC | `127.0.0.1:5902` only |
| Phone HTTP | Tailscale (or `SWAYGENTRC_HTTP_HOST`), persisted high port |

---

## Smokes

```bash
./scripts/smoke_xai.sh
./scripts/smoke_proxy.sh
./scripts/smoke_mcp_config.sh
./scripts/smoke_mcp_browser.sh
./scripts/smoke_swaygentrc.sh
./scripts/smoke_winctl.sh
```

## Layout

```text
install.sh              ← clone entrypoint
README.md  AGENTS.md
docs/leo-byom.md  docs/acp.md
mcp/                    ← swaygentic jail, Brave, winctl
swaygentrc/             ← phone APK + HTTP→ACP facade
scripts/smoke_*.sh
run/xai.env             ← local secret; not committed
```
