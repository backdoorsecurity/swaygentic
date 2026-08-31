# Project rules

Read `SCOPE.md` before any edit. That file is the scope of this repo.

- Two faces: Brave Leo BYOM (human chat via xAI Chat Completions) and Grok Build (ACP + MCP driving an isolated Brave profile).
- Do not fork Brave / Chromium. Do not add X11. Do not invent a custom FIFO protocol.
- Agent pipe = ACP (`grok agent stdio` or `grok agent serve` on 127.0.0.1).
- Browser pipe = CDP `--remote-debugging-pipe` behind one MCP server.
- Use tools for desktop/browser control. Do not use `run_terminal_cmd` with xdotool, wdotool, or raw Chromium flags to click pages.
- Keep `XAI_API_KEY` off the git tree and out of renderer code.
- Isolated profile only. Not the operator’s daily Brave profile.
- If Leo returns a generic network error after curl works, add the localhost compatibility proxy from SCOPE.md Phase 1. Do not rebuild the browser.
- Stop at the current phase. Phase 4 (brave-core) needs a written justification first.
