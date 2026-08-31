# Grok-in-browser

Run **Grok** in the browser with a personal xAI API key (Brave Leo BYOM), and let **Grok Build** drive an isolated Brave Nightly profile over MCP — without forking Chromium.

Read **[SCOPE.md](./SCOPE.md)** before changing architecture. Short rules: **[AGENTS.md](./AGENTS.md)**.

## Two faces

| Face | Who talks | Wire |
| --- | --- | --- |
| **Leo** | You ↔ Grok inside Brave’s assistant | Chat Completions → `https://api.x.ai/v1/chat/completions` (optional localhost proxy) |
| **Build** | Grok Build ↔ Brave | MCP `brave-devtools` → CDP (pipe) on an isolated profile |

They share: xAI key on disk/env, Brave Nightly, isolation rules.  
They do **not** share: UI process, protocol, or a custom FIFO.

## Quick start

### 0. Clone / enter

```bash
cd grok-in-browser
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

MCP entrypoint: [`mcp/run_brave_mcp.sh`](./mcp/run_brave_mcp.sh)  
Flags (single owner): [`mcp/brave_flags.sh`](./mcp/brave_flags.sh)  
Profile: `profiles/agent/` (gitignored)

Backend choice for Phase 2: **brave-mcp** only (`npx brave-mcp@latest` + Nightly binary path, isolated `user-data-dir`).  
`--channel` and `--executable-path` are mutually exclusive in brave-mcp; we pin the Nightly binary.

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
mcp/run_brave_mcp.sh
profiles/          # gitignored agent profile
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
| Agent uses your real bookmarks | Wrong profile | Must use `profiles/agent` via `brave_flags.sh` |

## Out of scope (v1)

Forking Brave/Chromium, X11/xdotool, custom `/tmp/grok.fifo`, driving your daily Brave profile, putting the API key in page JS. Phase 4 (brave-core) needs a written justification first — see SCOPE.md.
