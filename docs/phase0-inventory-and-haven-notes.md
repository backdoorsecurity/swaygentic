# Phase 0 — Install inventory + Haven VNC notes

**Date:** 2026-09-01  
**Purpose:** Freeze installer contracts; skim Haven for **ideas only** (do not copy AGPL app code into swaygentrc).

Parent plan: `docs/install-and-native-view-plan.md`.

---

## 1. Credentials / “certs” inventory (this VM)

### Generated today (phone-facing)

| Path | Mode | Role |
| --- | --- | --- |
| `swaygentrc/server/run/api.token` | 0600 | Bearer for phone HTTP API |
| `swaygentrc/server/run/agent.secret` | 0600 | ACP agent serve secret |
| `swaygentrc/server/run/http.port` | 0600 | Persisted listen port |
| `swaygentrc/server/run/credentials.swaygentrc` | 0600 | Phone upload file |

**`credentials.swaygentrc` shape** (from `bootstrap.write_phone_credentials`):

```text
url=http://<tailscale-v4>:<port>
token=<bearer>
vnc_host=<tailscale-v4>
vnc_port=5900
```

### Not generated today

- **No TLS/PEM/CA files** under `swaygentrc/server/run/` for the phone API (URL is plain `http://` on Tailscale).
- Android app has **no** network-security / custom-trust cert wiring for API or VNC in-tree (cleartext Tailscale assumed).
- **ADB “copy certs” icing:** nothing to push unless we add HTTPS later or wayvnc TLS. Installer should say *“no certs generated — skipped”* and still push credentials.

### Related host secrets (not phone)

| Path | Role |
| --- | --- |
| `run/xai.env` | Leo / xAI key (gitignored) |
| `~/.pki/nssdb` | Brave NSS (ZAP CA, etc.) — host browser, not phone |

---

## 2. Host install surface (for Phase 1)

| Piece | Source |
| --- | --- |
| Launchers | `mcp/swaygentic`, `mcp/swaygentic-unsafe` → `~/.local/bin/` via `scripts/install_swaygentic.sh` |
| Jail | `mcp/jail-run.sh`, `mcp/jail-denied.sh` |
| Brave/MCP | `mcp/ensure_brave.sh`, `mcp/run_brave_mcp.sh`, `mcp/launch.sh`, `.grok/config.toml` |
| Phone API unit | `swaygentrc/server/system/swaygentrc.service` (placeholders `@SERVER_DIR@`, `@SYSTEM_DIR@`, `@HOME@`) |
| VNC unit | `scripts/wayvnc.service` + `scripts/wayvnc-tailscale.sh` |
| APK | `swaygentrc/app/swaygentrc_v0.8.0.apk` (package `app.swaygentrc`) |
| Haven APK (legacy) | `swaygentrc/app/haven-arm64Full-debug.apk` — optional after native VIEW |

---

## 3. Haven code map (reference only)

Tree: `/home/browser/Haven` (outside this repo). AGPL app — **read for architecture, do not vend into swaygentrc**.

### Layers worth mirroring conceptually

| Haven file | Idea for swaygentrc VIEW |
| --- | --- |
| `ConnectDeepLink.kt` | Parse host/port/transport; match saved profile or open editor. We already store host/port in prefs/credentials — deep link less critical in-app. |
| `RemoteDesktopSession.kt` | Small interface: move / button / click / wheel / clipboard / pause / resume / close. **Good MVP API** for our VIEW ViewModel. |
| `VncDesktopSession.kt` | Thin adapter: scroll = VNC buttons 4/5; click = move+press+release. |
| `core/vnc/.../VncClient.kt` | Client surface: `start(host,port)`, bitmap frames, pointer, keys, pause. |

### Important licensing clue

Haven’s `VncClient` header states it is **ported from vernacular-vnc (MIT)** with AWT → `android.graphics`.  

**Implication:** prefer **MIT vernacular-vnc** (or another non-AGPL RFB stack) as the implementation dependency, inspired by Haven’s adapter split — not copying Haven’s AGPL module wholesale.

### Out of scope to copy from Haven

- SSH/mosh/ET transports, workspaces, MCP agent tools, on-device wayvnc/chroot, RDP/SPICE sessions, PiP/presentation host.

### MVP input model (from Haven’s docs)

- Mouse buttons: X11 convention 1=left, 2=middle, 3=right, 4=scroll-up, 5=scroll-down.
- Touch mapping (our design): one-finger move+tap → button 1; two-finger drag → wheel 4/5; long-press → button 3 (optional later).
- Keyboard: defer (Haven also treats keysyms as a separate hard problem).

---

## 4. Phase 0 conclusions

1. **ADB cert push:** no phone TLS artifacts today → implement as optional no-op with clear message; push **`credentials.swaygentrc`** only.
2. **Native VIEW:** interface like `RemoteDesktopSession` + MIT VNC client; study Haven adapters, implement in `app.swaygentrc`.
3. **Haven fork:** still **not** required for product.
4. **Installer order unchanged:** Phase 1 host → Phase 2 ADB credentials → Phase 3 in-app VNC.

---

## 5. Suggested first spike after Phase 1

Add a throwaway Android module or activity that connects to `vnc_host:5900` with vernacular-vnc (or chosen lib), renders one framebuffer, sends one click — prove Tailscale path before wiring into VIEW tab chrome.
