# Project rules

**Session start:** read `HANDOFF.md` first (live status + how to run), then `SCOPE.md` before any architectural edit. **Current goal: one-shot installer** (repo-root `./install.sh`). Arena builders/advisor: `/srv/arena/docs/STARTUP.md` when that host is in use.

- Faces: Brave Leo BYOM (human chat via xAI Chat Completions); Grok Build (ACP + MCP); phone (swaygentrc HTTP/SSE + VIEW).
- Do not fork Brave / Chromium. Do not add X11. Do not invent a custom FIFO protocol.
- Prefer invoking Build via **`swaygentic`** (jail wrapper around real `grok`) so agent + browser share a bubblewrap jail. Do not rename/replace `~/.grok/bin/grok`. Jail hides most of `$HOME` (no host `~/.cache` / `~/.npm`; **does** bind `~/.pki`, `~/.ZAP`, `~/.local/share/foxyproxy` for Brave NSS / ZAP / FoxyProxy); blacklists `sudo`/`ssh`/`docker`/`systemctl`/… via `mcp/jail-denied.sh`. Escape hatch: **`swaygentic-unsafe`** (or `SWAYGENTIC_OFF=1`) runs host grok with no jail.
- Agent pipe = ACP (`grok agent stdio` or `grok agent serve` on 127.0.0.1).
- Browser: we own Brave (`mcp/ensure_brave.sh` + loopback DevTools on `127.0.0.1:9222` — TCP, not a named pipe); MCP attaches (`brave-mcp` via `mcp/run_brave_mcp.sh`). In-jail Brave uses `--no-sandbox` + `--test-type`.
- Open / new tab: always `mcp/launch.sh '<spec>'` (alias `mcp/new_tab.sh`) so Brave **tab containers** apply. Specs: `https://url`, `misc:https://url`, `brave-browser-nightly:misc:https://url`. Default container `$SWG_CONTAINER` (`misc`). Do **not** use `brave-devtools__new_page` when a container is required.
- Snapshots: `filePath=/tmp/snapshots/<agent>/…` (`bravectl`/`winctl`/`advisor`; `browser` → `advisor`). Jail binds that tree; host `/tmp` is otherwise a private tmpfs.
- Browser speed: one `take_snapshot` per **new URL**; `includeSnapshot: false` on click; prefer `evaluate_script` when the a11y tree is noisy (shoutbox). Do not paste trees onto the board.
- Guest desktop: **winctl** MCP (`winctl__*`) drives a nested guest over QEMU VNC (`WINCTL_VNC`, default `127.0.0.1::5902`). Do **not** touch host wayvnc (`:5900`, phone VIEW). Discover tools with `search_tool`, then `use_tool`.
- Keep `XAI_API_KEY` off the git tree and out of renderer code (`run/xai.env` mode 0600).
- Profile: Nightly default (`~/.config/BraveSoftware/Brave-Browser-Nightly`). Isolation = Brave tab containers, not a second user-data-dir (test VM).
- If Leo returns a generic network error after curl works, use `scripts/run_leo_proxy.sh`. Do not rebuild the browser.
- Stop at the current phase. Phase 4 (brave-core) needs a written justification first.
- Brave/MCP launch flags have one owner: `mcp/brave_flags.sh`. Do not duplicate them.

## Modality (pick one and stay on it)

Successful tools may return a screenshot. Errors start with `Error:` — read them; do not retry the identical call blindly.

| Surface | Prefer | Avoid |
| --- | --- | --- |
| **Web (Brave)** | `mcp/launch.sh` → `take_snapshot` / evaluate / selector click/fill via `brave-devtools__*` | `new_page` when a tab container is required; screenshot every click |
| **Visual verify** | Viewport `take_screenshot` when layout/canvas/images matter | `fullPage: true` by default; shot when DOM already answers |
| **Guest desktop (winctl)** | `win_status` then `win_look` / `win_click` / `win_type` / `win_key` — actions already return a JPEG; coords are **guest pixels** from the last shot | Guessing coords; spamming `win_look` after every action; driving host wayvnc |
| **Bulk files / installs in guest** | SSH/scp into the guest | Pixel-typing long paths |

### Web (brave-devtools)

1. Open URLs with `mcp/launch.sh` (containers).
2. Prefer snapshot / evaluate / network for DOM work — a screenshot is **not** required on every action.
3. After launch / click / navigate / submit: `take_screenshot` when you need to **see** the page.
4. Click/type/fill via brave-devtools. Do not use `run_terminal_cmd` with xdotool, wdotool, or ad-hoc Chromium flags.

### Guest desktop (winctl)

1. `win_status` — text only; confirm VNC + domain.
2. `win_look` — see the guest; never guess click coords.
3. `win_click(x, y)` / `win_type` / `win_key` — settle is built in; do not follow with an extra `win_look` unless the shot was unclear.
4. Shell chords: `win`, `win+r`, `alt+tab`, `ctrl+alt+delete` (virsh-backed when needed).
5. Do not drive the host virt-manager window; talk to guest VNC directly.
