# swaygentrc — phone face for swaygentic

Android app (keep the APK) talks **HTTP/SSE** to a small Python facade on this VM.
The facade starts **Grok Build** via ACP on loopback (`127.0.0.1:2419`) and wraps it with **`swaygentic`** so agent + Brave stay jailed.

```text
Phone APK  --Bearer-->  swaygentrc HTTP (Tailscale, persisted high port)
                              |
                              +--> swaygentic --> grok agent serve :2419
                              +--> ACP WebSocket (chat)
```

**VIEW** (APK **0.9.2+**) embeds an in-app VNC client (MIT vernacular-vnc Android port) to guest `wayvnc` using `vnc_host` / `vnc_port` from credentials. Toggle **DRAG: SCROLL** (default) vs **DRAG: SELECT**. Haven is no longer in the APK. JPEG `/grok/frame` remains secondary. See `SWAYGENTRC.md`. Chat / START / STOP / POWER work.

## Install (host)

One-shot host setup (launchers, `swaygentrc` + `wayvnc` user units, credentials, smoke):

```bash
cd ~/swaygentic
./scripts/install_swaygentic_system.sh --skip-adb
# ./scripts/install_swaygentic_system.sh --non-interactive --skip-adb
```

Living plan / resume: [`SWAYGENTRC.md`](./SWAYGENTRC.md).

## Start (foreground)

```bash
cd ~/swaygentic
SWAYGENTRC_FOREGROUND=1 ./swaygentrc/server/system/start_server.sh
# or: SWAYGENTRC_FOREGROUND=1 ./swaygentrc/server/start_server.sh
```

First non-foreground launch installs user unit `swaygentrc.service` (and disables legacy `grokrc.service`). Prefer the system installer above for a full host setup.

## Phone setup

1. Copy `swaygentrc/server/run/credentials.swaygentrc` to the phone (`url`, `token`, `vnc_host`, `vnc_port`).
2. In the app: **SYSTEM → API → UPLOAD**.
3. Tap **START**, then chat. For desktop VIEW: open **VIEW** (in-app VNC). Haven is optional.

HTTP port is a **random high port**, written once to `run/http.port` (override with `SWAYGENTRC_HTTP_PORT`). It does **not** change on failed GETs — only when you delete `run/http.port`, override the env, or start a new allocate. Re-upload credentials if the port file was deleted.

Phone APK id is **`app.swaygentrc`** (APK `swaygentrc_v0.9.0.apk`). HTTP API paths stay `/grok/start`, `/grok/status`, … (agent face, not the app package).

## Env knobs

| Var | Meaning |
| --- | --- |
| `SWAYGENTRC_HTTP_HOST` | Bind host (default: Tailscale IP, else `127.0.0.1`) |
| `SWAYGENTRC_HTTP_PORT` | Force listen port (also updates `run/http.port`) |
| `SWAYGENTRC_VNC_HOST` | Haven Intent host (default: Tailscale IPv4) |
| `SWAYGENTRC_VNC_PORT` | Haven Intent port (default: `5900`) |
| `SWAYGENTRC_GROK_BIND` | ACP bind (default `127.0.0.1:2419`) |
| `SWAYGENTRC_WRAP` | Jail wrapper path, or `off` |
| `SWAYGENTIC_OFF=1` | Skip jail; run real `grok` on host |
| `SWAYGENTRC_FOREGROUND=1` | Do not hand off to systemd |
| `SWAYGENTRC_SKIP_SYSTEMD=1` | Never install/start the user unit |
| `SWAYGENTRC_SERVER_ROOT` | Agent cwd (default: swaygentic repo root) |

## Smoke

```bash
./scripts/smoke_swaygentrc.sh
```

## Notes

- Do **not** run interactive `swaygentic --trust` and phone **START** at the same time — both want `:2419`.
- Leo proxy stays on **8787**; phone API uses the persisted high port.
- MCP config is **read-only** from `.grok/config.toml` (never rewritten).
- APK package: `app.swaygentrc` (uninstall legacy `app.grokrc` if both are installed).

## Living plan

See **[SWAYGENTRC.md](./SWAYGENTRC.md)** (installer + native VIEW; resume after compaction).
