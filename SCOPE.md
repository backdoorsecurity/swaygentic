# Grok-in-browser — project scope for Grok Build

Read this file before writing code. This is a new repo. It is not grokbox, but it may reuse grokbox ideas.

Owner intent: run Grok in the browser with a personal xAI API key, in a Leo-like panel, and let Grok Build drive / talk to that browser agent over a documented IPC channel (ACP + MCP + CDP). Do not fork Chromium unless Paths 1–3 fail.

## One-sentence goal

A local desktop project where (1) Brave Leo chats with Grok through the official xAI Chat Completions API, and (2) Grok Build can operate the same machine’s browser through MCP tools, without shipping a custom Brave binary.

## Two products, one repo

Keep these separate in code and in docs. Do not collapse them into one process.

1. **Leo face** — human talks to Grok inside Brave’s built-in assistant (BYOM).
2. **Build face** — Grok Build (CLI / ACP) talks to a browser-control MCP server and optionally to the same Grok API.

They share: xAI key on disk or env, Brave Nightly as the browser, isolation rules.
They do not share: UI process, protocol, or “named pipe” format.

## In scope (do these)

- Document and script Brave Nightly + Leo BYOM pointed at `https://api.x.ai/v1/chat/completions`.
- A tiny local compatibility proxy if Leo’s request body makes xAI return 400 (strip/override `temperature` and similar).
- An MCP server Grok Build can call to drive Brave (navigate, click, type, screenshot, tabs). Prefer wrapping existing tools (`brave-mcp`, Browser Use, or grokbox/osctl) over writing a new CDP stack.
- ACP wiring so Grok Build is reachable as `grok agent stdio` and/or `grok agent serve --bind 127.0.0.1:2419`.
- A project `.grok/config.toml` that registers the browser MCP server.
- `AGENTS.md` rules for the coding agent: use tools, do not shell-out xdotool, do not hit the user’s real Brave profile.
- Isolated Brave/Chromium profile for the agent (copy the grokbox idea: `*-osctl` user-data-dir, `--ozone-platform=wayland`, `--remote-debugging-pipe`).
- README with copy-paste setup, curl smoke test, and failure modes.

## Out of scope (do not do)

- Building Brave from source (Nightly 1.96.27 / Chromium 152). That is a multi-GB Chromium checkout. Only revisit if BYOM + MCP cannot deliver the UI.
- Patching Leo C++ in brave-core.
- Binding `/tmp/.X11-unix`, xdotool, ydotool, Xvfb, or an X11 path.
- A custom FIFO protocol (`/tmp/grok.fifo`) unless ACP stdio and Unix-domain leader are proven insufficient. ACP is the agent pipe. CDP is the browser pipe.
- Putting `XAI_API_KEY` in renderer JS, an unpacked extension, or a page.
- Mobile / Android Leo BYOM (desktop only).
- Training, logging prompts to a third party other than xAI, or claiming Brave’s privacy proxy still applies to BYOM.
- Re-implementing grokrc’s phone UI unless asked in a later phase.

## Architecture (canonical)

```text
Human
  └─ Brave Leo sidebar (BYOM)
        └─ [optional localhost proxy]
              └─ https://api.x.ai/v1/chat/completions
                    Authorization: Bearer $XAI_API_KEY
                    model: grok-4.6   (or current xAI id)

Grok Build (TUI or ACP client)
  └─ MCP: browser / osctl tools
        └─ Brave Nightly isolated profile
              └─ CDP over --remote-debugging-pipe
                 (not TCP :9222 on all interfaces)

Optional later
  └─ grok agent serve --bind 127.0.0.1:2419
        └─ phone / local UI  (grokrc-style)
```

Pipes that already exist — use them:

| Job | Wire | Command / flag |
| --- | --- | --- |
| Drive Grok Build from another program | ACP JSON-RPC on stdio | `grok agent --always-approve stdio` |
| Drive Grok Build over local WS | ACP JSON-RPC on WebSocket | `grok agent --always-approve serve --bind 127.0.0.1:2419 --secret …` |
| Grok Build internal multi-client | Unix domain socket leader | built into Grok Build; do not reinvent |
| Drive the browser | CDP pipe FDs 3/4 | `--remote-debugging-pipe` |
| Expose browser tools to Grok Build | MCP stdio | `.grok/config.toml` `[mcp_servers.*]` |

Do not invent a fourth wire.

## Official docs (read before coding)

Leo / BYOM
- https://support.brave.app/hc/en-us/articles/34070140231821-How-do-I-use-the-Bring-Your-Own-Model-BYOM-with-Brave-Leo
- https://brave.com/blog/byom-nightly/
- UI: `brave://settings/leo-ai`

Leo agentic browsing (optional, Nightly flag)
- https://brave.com/blog/ai-browsing/
- Flag: `brave://flags/#brave-ai-chat-agent-profile`
- Isolated profile, no `brave://`, no plain http, prompt-injection is expected

xAI
- https://docs.x.ai/developers/quickstart
- Chat Completions (what Leo speaks): `POST https://api.x.ai/v1/chat/completions`
- Responses API (what some Grok Build paths speak): `POST https://api.x.ai/v1/responses`
- Grok Build: https://x.ai/docs/build/overview
- MCP: https://x.ai/docs/build/features/mcp-servers
- ACP agent mode: https://github.com/xai-org/grok-build/blob/main/crates/codegen/xai-grok-pager/docs/user-guide/15-agent-mode.md
- ACP spec: https://agentclientprotocol.com/

Brave bits we may wrap, not fork
- Nightly packages: https://brave.com/linux/nightly/
- brave-mcp: https://github.com/triuzzi/brave-devtools-mcp
- Browser Use × Grok Build: https://github.com/browser-use/plugins/blob/main/grok/README.md

Brave source (reference only)
- https://github.com/brave/brave-browser
- https://github.com/brave/brave-core
- Nightly tag v1.96.27 = Chromium 152.0.7977.64

## Known constraints (treat as requirements)

- Leo BYOM speaks **OpenAI Chat Completions**, full path `/v1/chat/completions`. Not `/v1/responses`.
- Leo stores the key locally and sends `Authorization: Bearer <key>`. Do not add a second `Bearer ` prefix.
- Model request name must be the exact API id (`grok-4.6` or whatever `GET https://api.x.ai/v1/models` returns). Labels can be pretty; ids cannot.
- Leo often sends `temperature` and other sampling fields. Some models 400. Symptom in the UI: “network issue connecting to Leo.” Fix with a local proxy, not a browser rebuild.
- BYOM traffic goes **directly** to xAI. Brave’s Leo privacy proxy does not wrap it. Page text + prompt leave the machine.
- Page-aware Leo + agentic browsing = prompt injection. Isolate the agent profile. Never enable agent tools against banking / password-manager origins in v1.
- Wayland-only desktop assumption (KWin/Plasma), same as grokbox. No X11.
- Chromium inside a jail needs `--no-sandbox` if we reuse bubblewrap. Do not drop that flag “to make the inner sandbox work.”
- Do not publish debug on `0.0.0.0:9222`.

## Phases

### Phase 0 — repo skeleton
- README, this SCOPE.md, AGENTS.md, `.gitignore`
- `.grok/config.toml` placeholder
- `scripts/` for smokes
- No Android, no patched Brave tree

### Phase 1 — Leo + xAI (human chat)
- README steps: install Brave Nightly, open `brave://settings/leo-ai`, add BYOM row
- `scripts/smoke_xai.sh` curls Chat Completions with `$XAI_API_KEY`
- If Leo fails after curl works: `proxy/` small HTTP server
  - listen `127.0.0.1` only
  - POST `/v1/chat/completions` → `https://api.x.ai/v1/chat/completions`
  - strip or rewrite fields xAI rejects
  - forward `Authorization`
  - stream SSE if Leo streams
- Document the exact Leo fields that work on this machine

Done when: a page summary in Leo returns Grok text using the operator’s key.

### Phase 2 — Grok Build drives Brave
- Choose one MCP backend and commit to it (do not ship two):
  - A. wrap grokbox/osctl if that tree is on disk
  - B. `brave-mcp` / Chrome DevTools MCP against an isolated Nightly profile
  - C. Browser Use plugin
- Isolated profile dir under the project (`~/.config/brave-osctl` or `./profiles/agent`)
- Launch flags live in one function, one file
- Grok Build project MCP points at that launcher
- Tools: at least `look`/`screenshot`, `goto`/`navigate`, `click`, `type`/`fill`, `tabs`

Done when: `cd` this repo && `grok --trust` can open example.com and return a screenshot via a tool, not via `run_terminal_cmd`.

### Phase 3 — ACP so other programs can drive the same agent
- Document `grok agent stdio` as the named-pipe equivalent
- Optional `grok agent serve --bind 127.0.0.1:2419 --secret …`
- Do not add a homegrown FIFO
- If a local UI is needed, speak ACP. Reuse grokrc’s client only if asked

Done when: a second process can `session/new` + `session/prompt` and see a tool call against the browser MCP.

### Phase 4 — only if 1–3 cannot meet the UI bar
- Evaluate forking brave-core. Write a one-page justification first (disk, build hours, patch surface).
- Do not start `pnpm run init` in this repo without that justification landing in README.

## Repo layout (target)

```text
README.md
SCOPE.md                 ← this file
AGENTS.md                ← short rules Grok Build always loads
scripts/smoke_xai.sh
scripts/run_leo_proxy.sh
proxy/                   ← Phase 1 compatibility shim (only if needed)
mcp/                     ← Phase 2 launcher / thin wrapper, not a second CDP stack
profiles/                ← gitignored agent profile
.grok/config.toml
```

## Implementation rules for Grok Build

- Prefer deleting and wrapping existing tools over new frameworks.
- One owner per concern (flags, proxy, MCP config).
- Secrets from env (`XAI_API_KEY`) or a 0600 file under `run/`. Never commit keys.
- Listen on loopback unless the operator passes an explicit host.
- If a smoke fails, stop. Do not add a second backend to hide it.
- Do not “keep in sync” copies of browser flags.
- Do not mention xdotool, ydotool, firejail, or X11 as solutions.

## Acceptance smokes

```sh
# API
curl -sS https://api.x.ai/v1/chat/completions \
  -H "Authorization: Bearer $XAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"grok-4.6","messages":[{"role":"user","content":"ping"}]}'

# MCP registered
grep -n 'mcp_servers' .grok/config.toml

# No X11 / open debug port leftovers
grep -RInE 'xdotool|ydotool|Xvfb|/tmp/.X11-unix|0.0.0.0:9222' . || true
```

## Decision the operator already made

- Start with stock Brave + BYOM + MCP/ACP.
- Named pipe means ACP stdio / Unix socket / CDP pipe, not a new protocol.
- Forking Nightly 1.96.27 is a last resort, not milestone 1.
