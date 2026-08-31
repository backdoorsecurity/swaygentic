# Project rules

**Session start:** read `HANDOFF.md` first (live status + how to run), then `SCOPE.md` before any architectural edit.

- Two faces: Brave Leo BYOM (human chat via xAI Chat Completions) and Grok Build (ACP + MCP driving an isolated Brave profile).
- Do not fork Brave / Chromium. Do not add X11. Do not invent a custom FIFO protocol.
- Agent pipe = ACP (`grok agent stdio` or `grok agent serve` on 127.0.0.1).
- Browser pipe = CDP `--remote-debugging-pipe` behind one MCP server (`brave-mcp` via `mcp/run_brave_mcp.sh`).
- Use tools for desktop/browser control. Do not use `run_terminal_cmd` with xdotool, wdotool, or raw Chromium flags to click pages.
- Keep `XAI_API_KEY` off the git tree and out of renderer code (`run/xai.env` mode 0600).
- Isolated profile only (`profiles/agent`). Not the operator’s daily Brave profile.
- If Leo returns a generic network error after curl works, use `scripts/run_leo_proxy.sh`. Do not rebuild the browser.
- Stop at the current phase. Phase 4 (brave-core) needs a written justification first.
- Brave/MCP launch flags have one owner: `mcp/brave_flags.sh`. Do not duplicate them.
