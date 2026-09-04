# ACP — drive Grok Build from another program

In this project, the “named pipe” to the coding agent is **ACP**, not a custom FIFO.

## stdio (local process)

```bash
cd /path/to/swaygentic
grok agent --always-approve stdio
```

The agent speaks JSON-RPC on stdin/stdout ([ACP](https://agentclientprotocol.com/)). Typical flow:

1. Client → `initialize`
2. Client → `session/new` (set `cwd` to this repo so project MCP loads)
3. Client → `session/prompt`
4. Server streams replies and tool calls (including `brave-devtools` MCP tools)

## WebSocket (loopback)

```bash
grok agent --always-approve serve --bind 127.0.0.1:2419 --secret "$GROK_AGENT_SECRET"
```

Bind loopback only unless you intentionally expose more. Pass `--secret` or set `GROK_AGENT_SECRET`.

## Phone (swaygentrc)

The Android app does **not** speak ACP. It uses the HTTP facade under `swaygentrc/server/` (`SWAYGENTRC_FOREGROUND=1 ./swaygentrc/server/system/start_server.sh`). That process opens ACP WebSocket to this bind. See `swaygentrc/README.md`.

## Done when

A second process can `session/new` + `session/prompt` and observe a tool call against the browser MCP (`brave-devtools`), without inventing `/tmp/grok.fifo`.

## Do not

- Add a homegrown FIFO protocol
- Re-implement Grok Build’s Unix-domain leader socket
- Put secrets in the ACP URL path

See also: Grok Build user guide `15-agent-mode.md`.
