# SWAYGENTRC — living plan & resume context

**Read this after compaction.** Phone face of swaygentic: Android APK + Python HTTP/SSE facade + guest wayvnc.  
**Date:** 2026-09-03  
**Repo:** `$HOME/swaygentic`  
**Package id:** `app.swaygentrc`  
**APK:** `swaygentrc/app/swaygentrc_v0.9.4.apk` (also `android/apks/`, `android/app/build/outputs/...`)

**Current goal (whole repo):** one-shot host installer (repo-root `./install.sh`). Prefer a dedicated test account. ADB Phase 2 still later.

Also see: `swaygentrc/README.md`, `docs/install-and-native-view-plan.md`, `docs/phase0-inventory-and-haven-notes.md`, `docs/haven-companion.md`, `docs/phone-view-display.md`, repo `HANDOFF.md` / `AGENTS.md`.

---

## Product shape (keep faces separate)

```text
Phone APK (chat + SYSTEM + VIEW)
    --Bearer-->  swaygentrc HTTP/SSE  (Tailscale, port in run/http.port)
                      |
                      +--> mcp/swaygentic --> grok agent serve 127.0.0.1:2419
                      +--> ACP WebSocket (chat)
                      +--> credentials: url, token, vnc_host, vnc_port

VIEW (0.9.4+): in-app VNC → wayvnc :5900. FULL / KEYB / SCROLL|SELECT.
               KEYB uses system IME KeyEvents (Ctrl/Alt/Shift go over RFB; Hacker's Keyboard if installed).
               SYSTEM → PERFORMANCE: C8/C16/C24, ZLIB, fps (client-side; reconnect applies).
               Haven Intent removed. ZRLE still missing vs Haven (main speed gap).
```

| Launcher | Role |
| --- | --- |
| `swaygentic` | Default: bubblewrap jail around real grok |
| `swaygentic-unsafe` | `SWAYGENTIC_OFF=1` — host grok, no jail |
| `SWAYGENTIC_OFF=1 swaygentic …` | Same as unsafe |

Do **not** run desktop `swaygentic --trust` and phone **START** together (both want `:2419`).

---

## Decisions (locked)

1. **Native VIEW VNC inside swaygentrc** — not a separate APK; no app switching.
2. **Do not fork Haven** for product VIEW. Haven = transitional/optional only.
3. **Study Haven for ideas**; do **not** copy AGPL modules into this tree. Haven’s VNC client is ported from **vernacular-vnc (MIT)** — prefer that (or another MIT/Apache RFB lib).
4. **Installer** should set up host + services + optional ADB; cert push is icing but **no phone TLS certs exist yet**.
5. **wayvnc** stays a **separate** user unit from `swaygentrc.service`.

---

## Current runtime (this VM)

| Item | Value / path |
| --- | --- |
| Credentials | `swaygentrc/server/run/credentials.swaygentrc` |
| API token | `swaygentrc/server/run/api.token` |
| ACP secret | `swaygentrc/server/run/agent.secret` |
| HTTP port file | `swaygentrc/server/run/http.port` |
| Unit template | `swaygentrc/server/system/swaygentrc.service` |
| Start script | `swaygentrc/server/system/start_server.sh` |
| wayvnc | `scripts/wayvnc.service` + `scripts/wayvnc-tailscale.sh` |
| Smoke | `./scripts/smoke_swaygentrc.sh` |
| Haven tree (reference) | `/home/browser/Haven` (outside repo) |
| Haven APK copy | `swaygentrc/app/haven-arm64Full-debug.apk` |

### credentials.swaygentrc shape

```text
# upload in SYSTEM -> API
url=http://<tailscale-v4>:<port>
token=<bearer>
vnc_host=<tailscale-v4>
vnc_port=5900
```

### Cert / ADB icing (Phase 0 finding)

- Phone API is **HTTP on Tailscale** — **no** PEM/CA files generated for the phone.
- Installer ADB path: push **credentials only**; print “no certs generated — skipped” until HTTPS/wayvnc TLS exists.
- Host ZAP/Brave NSS (`~/.pki`) is unrelated to phone install.

---

## Full phased plan

### Phase 0 — Inventory & contracts — **DONE**

- Inventory written (this file + `docs/phase0-inventory-and-haven-notes.md`).
- Haven skim: `ConnectDeepLink`, `RemoteDesktopSession`, `VncDesktopSession`, `core/vnc/VncClient`.

### Phase 1 — Host installer skeleton — **DONE** (2026-09-01)

Script: `scripts/install_swaygentic_system.sh`

```text
--non-interactive
--skip-adb          # Phase 1 default path: print APK + credentials
--adb               # Phase 2 stub (prints paths until implemented)
--skip-wayvnc
--skip-smoke
--no-start
--unsafe-default    # drop-in: SWAYGENTRC_WRAP=…/swaygentic-unsafe
--user NAME         # re-exec as that user when run as root
```

Verified on this VM (`--non-interactive --skip-adb`): launchers, `swaygentrc` + `wayvnc` user units (templated `@REPO_ROOT@` / `@HOME@`), linger, credentials, smoke (port save/restore), Tailscale health.

**Still open for Phase 1 exit criteria:** test install as a **second Linux user**.

**Next:** Phase 2 ADB path (or second-user install test).

### Phase 2 — ADB phone path

1. Prompt for ADB install (default N unless `--adb`).
2. Detect/install `adb`; require `adb devices` = `device`.
3. `adb install -r` newest swaygentrc APK.
4. Offer uninstall `app.grokrc`.
5. `adb push` credentials (path or import Intent — TBD).
6. Cert push if/when files exist; else skip message.
7. No device → print absolute APK + credentials paths.

### Phase 3 — Native VIEW VNC MVP — **DONE** (APK **0.9.4**)

**3a Library:** `android/vernacular/` — MIT [vernacular-vnc](https://github.com/shinyhut/vernacular-vnc) with `java.awt` → `android.graphics.Bitmap` (do **not** copy Haven AGPL `core/vnc`).  
**3b UX:** VIEW auto-connects to `vnc_host`/`vnc_port`; FULL / KEYB / SCROLL|SELECT; Haven Intent **removed**.  
**3c Server:** unchanged; wayvnc capture; JPEG poll skipped while VNC connected.  
**3d Polish (through 0.9.4):** credentials/connect fixes; pointer I/O off main thread; SYSTEM → PERFORMANCE (bits/Zlib/fps); KEYB = system IME with Ctrl/Alt/Shift over RFB (no on-screen modifier buttons).

App types: `app.swaygentrc.vnc.{RemoteDesktopSession,VncDesktopSession,VncController,VncSurface,VncOptions,VncKeyMap,VncKeyboardHost}`.

**Touch:** 1-finger move+tap → button 1; drag → SELECT or SCROLL; 2-finger drag → wheel. Keyboard via IME.

**Verified on phone:** operator OK with 0.9.4 (2026-09-01).

### Phase 4 — Polish

- Optional ZRLE (main remaining speed gap vs Haven).
- **FPS stepped presets (operator ask):** phone SYSTEM → PERFORMANCE should offer discrete FPS steps such as `1, 2, 4, 8, 16, 32` (maybe `64`), not a narrow band around 15. Today `VncOptions` defaults to 15 and `coerceIn(5, 30)` — glitchy on slow networks; also revisit wayvnc `MAX_FPS` so the server can keep up with higher client requests. Deferred until after winctl merge / next UX ask.
- wayvnc auth/TLS if leaving Tailscale-only trust.
- Multi-device ADB; `adb reverse` docs.
- Demote Haven docs to unsupported fallback.
- Installer test matrix.

### Work order

```text
Phase 0 done
Phase 1 done (host installer)
Phase 3 done (native VIEW APK 0.9.4) — phone OK
    → Phase 2 ADB when asked
    → Phase 4 polish / optional ZRLE
    → next: separate project integration (operator TBD)
```

---

## Installer requirements (operator ask — captured)

Must:

- Configure whole host face needed for phone Build.
- Install binaries (`swaygentic`, `swaygentic-unsafe`, helpers).
- Configure swaygentrc server; enable + start service.
- Prompt: install over ADB?
  - Yes: detect adb → install adb if missing → `adb install` APK → push credentials → push certs **if any**.
  - No / no device: print paths to APK (+ credentials) for manual copy.

Nice: second-user test install.

---

## How to run today

```bash
cd ~/swaygentic
./scripts/install_swaygentic_system.sh --skip-adb
# or non-interactive:
# ./scripts/install_swaygentic_system.sh --non-interactive --skip-adb

systemctl --user status swaygentrc wayvnc
./scripts/smoke_swaygentrc.sh   # optional; restores http.port after

# phone
# upload swaygentrc/server/run/credentials.swaygentrc in SYSTEM → API
# VIEW auto-connects in-app VNC (0.9.4); KEYB / PERFORMANCE in-app
```

Unsafe (no jail):

```bash
swaygentic-unsafe --trust
# or: SWAYGENTIC_OFF=1 swaygentic --trust
```

---

## Open questions

1. Default phone wrap: jailed `swaygentic` vs allow `swaygentic-unsafe` via `SWAYGENTRC_WRAP`?
2. Credentials push path on Android (shared storage vs in-app import only)?
3. USB-only labs: Tailscale required vs `adb reverse` for HTTP?
4. Invest in ZRLE now, or leave vernacular Raw/CopyRect/RRE/Hextile + Zlib as-is?

---

## Next coding task

Phone face is good on **0.9.4**. Paused: Phase 2 ADB, optional ZRLE, second-user Phase 1 test.

**Operator next:** winctl is merging into swaygentic (`mcp/winctl/`). Phone FPS stepped presets deferred (Phase 4).
