# Project rules

- Faces: Brave Leo BYOM; Grok Build (ACP + MCP); phone (swaygentrc HTTP/SSE + VIEW).
- Prefer **`swaygentic`** (bubblewrap jail around real `grok`). Escape: **`swaygentic-unsafe`** / `SWAYGENTIC_OFF=1`. Do not replace `~/.grok/bin/grok`.
- Agent pipe = ACP. Browser pipe = CDP (`127.0.0.1:9222`). Tools = MCP.
- We own Brave (`mcp/ensure_brave.sh`); MCP attaches (`mcp/run_brave_mcp.sh`). Open tabs with `mcp/launch.sh` (containers). Do not use `brave-devtools__new_page` when a container is required.
- Guest desktop: **winctl** MCP over QEMU VNC (`WINCTL_VNC`, default `127.0.0.1::5902`). Never host wayvnc `:5900` (phone VIEW).
- `XAI_API_KEY` only in `run/xai.env` (0600, gitignored). Flags live in `mcp/brave_flags.sh` only.
- Installer: repo-root `./install.sh`. Do not fork Brave / add X11 / invent a FIFO protocol.

## Modality

| Surface | Prefer | Avoid |
| --- | --- | --- |
| **Web** | `launch.sh` → snapshot / evaluate / click | `new_page` when containers required; screenshot every click |
| **Visual** | Viewport screenshot when layout matters | `fullPage: true` by default |
| **Guest** | `win_status` → `win_look` → click/type/key | Guessing coords; driving host wayvnc |
| **Guest files** | SSH/scp | Pixel-typing long paths |

### Web

1. `mcp/launch.sh` for URLs.
2. Snapshot / evaluate for DOM; screenshot when you need to **see**.
3. Click/fill via brave-devtools — not xdotool.

### Guest (winctl)

1. `win_status` then `win_look`.
2. Click/type with coords from the last shot.
3. Chords: `win`, `win+r`, `alt+tab`, `ctrl+alt+delete`.
