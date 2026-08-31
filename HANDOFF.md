# Handoff — grok-in-browser (read this first)

**Date:** 2026-08-31  
**Repo:** `/home/browser/grok-in-browser`  
**Branch:** `main` @ `c9f7b8c`  
**Operator intent:** Close previous session; resume here and **run** the project (Leo face + Build face).

This is not grokbox. Do not fork Brave. Read `SCOPE.md` for architecture; this file is the live status.

---

## One-sentence goal

Local desktop project: (1) Brave Leo chats with Grok via xAI Chat Completions + personal API key; (2) Grok Build drives isolated Brave Nightly through MCP (`brave-mcp`), reachable over ACP — no custom Chromium, no X11, no custom FIFO.

## Two faces (keep separate)

| Face | Who | Wire |
| --- | --- | --- |
| **Leo** | Human ↔ Grok in Brave sidebar | BYOM → Chat Completions (`/v1/chat/completions`) |
| **Build** | Grok Build ↔ browser | MCP `brave-devtools` → CDP pipe on `profiles/agent` |

---

## What’s already done (Phases 0–3 scaffold)

- Repo skeleton: `README.md`, `SCOPE.md`, `AGENTS.md`, `.gitignore`, `.env.example`
- Leo docs: `docs/leo-byom.md`
- Leo compatibility proxy: `proxy/leo_proxy.py` + `scripts/run_leo_proxy.sh` (strips `temperature` / `top_p` / etc.)
- Smokes: `scripts/smoke_xai.sh`, `smoke_proxy.sh`, `smoke_mcp_config.sh`, `smoke_mcp_browser.sh`
- Phase 2 backend **committed: brave-mcp only** (not browser-use, not a custom CDP stack)
- MCP wrapper: `mcp/brave_flags.sh` (single flag owner) → `mcp/run_brave_mcp.sh`
- Project MCP: `.grok/config.toml` → `bash mcp/run_brave_mcp.sh`
- Isolated profile dir: `profiles/agent/` (gitignored)
- ACP docs: `docs/acp.md`
- **Node.js 22** installed (`npx` available)
- **Brave Nightly** installed: `Brave Browser Nightly 152.1.96.27` at `/bin/brave-browser-nightly`
- Wayland socket: `/run/user/$(id -u)/wayland-1` (no X11 path)

### Verified in the prior session

- `./scripts/smoke_mcp_config.sh` — pass  
- `./scripts/smoke_proxy.sh` — pass  
- MCP initialize + `new_page https://example.com` + `take_screenshot` — pass (`brave_devtools` 1.8.0, ~29 tools)  
- `grok --trust mcp doctor brave-devtools` — healthy  

### Not verified yet

- `./scripts/smoke_xai.sh` — key may now be present at `run/xai.env` (gitignored); **run this first**
- Leo UI BYOM end-to-end (needs Nightly settings + successful API smoke)
- Interactive headed Brave from Grok Build TUI (headless smoke worked)

---

## Operator next steps (run it)

### 1. API key (required for Leo + xAI smoke)

As of this handoff, a personal key was saved to **`run/xai.env`** (mode 0600, gitignored). Do not put keys in `.env.example` or commit them.

```bash
cd ~/grok-in-browser
./scripts/smoke_xai.sh
```

If the file is missing:

```bash
mkdir -p run
cp -n .env.example run/xai.env
chmod 600 run/xai.env
# Edit run/xai.env — set XAI_API_KEY from https://console.x.ai/
./scripts/smoke_xai.sh
```

Never commit `run/xai.env`. Never put the key in renderer JS / extensions / pages.

### 2. Leo face

1. Open Brave Nightly → `brave://settings/leo-ai`
2. BYOM row:
   - Endpoint: `https://api.x.ai/v1/chat/completions`
   - Model request name: exact id (try `grok-4.6`; confirm via `GET /v1/models`)
   - API key: same personal key
3. If Leo shows a generic network error but curl works:

```bash
./scripts/run_leo_proxy.sh
# Point Leo endpoint at:
# http://127.0.0.1:8787/v1/chat/completions
```

Details: `docs/leo-byom.md`

### 3. Build face (Grok drives Brave)

```bash
cd ~/grok-in-browser
./scripts/smoke_mcp_browser.sh          # headless e2e
grok --trust                            # load project MCP
grok mcp doctor brave-devtools
# In TUI: open https://example.com and screenshot via brave-devtools tools
# Do NOT use xdotool / shell clicking
```

Flags live only in `mcp/brave_flags.sh`. Defaults include:

- `--executable-path` Nightly binary (mutually exclusive with `--channel` in brave-mcp)
- `--user-data-dir=$REPO/profiles/agent`
- `--ozone-platform=wayland`
- `--no-sandbox` when `GIB_NO_SANDBOX=1` (default on) for this environment
- Headless when `GIB_HEADLESS=1` (smoke script sets this)

### 4. ACP (optional)

```bash
grok agent --always-approve stdio
# or
grok agent --always-approve serve --bind 127.0.0.1:2419 --secret …
```

See `docs/acp.md`.

---

## Hard rules (from SCOPE / AGENTS)

- Do **not** fork Brave / Chromium (Phase 4 needs a written justification first).
- Do **not** use X11, xdotool, ydotool, Xvfb.
- Do **not** invent `/tmp/grok.fifo`; agent pipe = ACP; browser pipe = CDP.
- Do **not** drive the operator’s daily Brave profile — only `profiles/agent`.
- Do **not** publish debug on `0.0.0.0:9222`.
- Prefer wrapping existing tools; one MCP backend only (`brave-mcp`).
- If a smoke fails, stop — don’t add a second backend to hide it.

---

## Suggested first prompt for the next session

> Read `HANDOFF.md` and `SCOPE.md`. I have (or will set) `XAI_API_KEY` in `run/xai.env`. Run the Leo and Build acceptance paths and help me finish first successful Leo chat + a headed Grok Build browser screenshot.

---

## Quick file map

```text
HANDOFF.md              ← this file
SCOPE.md / AGENTS.md
README.md
docs/leo-byom.md
docs/acp.md
mcp/brave_flags.sh      ← only place for Brave/MCP flags
mcp/run_brave_mcp.sh
proxy/leo_proxy.py
scripts/smoke_*.sh
.grok/config.toml
profiles/agent/         ← isolated; gitignored
run/xai.env             ← secrets; create locally
```
