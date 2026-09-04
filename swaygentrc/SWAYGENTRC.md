# swaygentrc — phone face (short resume)

Android APK + Python HTTP/SSE facade + wayvnc VIEW. Package: `app.swaygentrc`. APK: `swaygentrc/app/swaygentrc_v0.9.4.apk`.

```text
Phone --Bearer--> swaygentrc HTTP (Tailscale, high port in run/http.port)
                    ├─ swaygentic → grok agent serve 127.0.0.1:2419
                    └─ VIEW → wayvnc :5900 (vnc_host / vnc_port in credentials)
```

**Host install:** repo-root `./install.sh` (see `--help`). Do not run desktop `swaygentic --trust` and phone START together (both want `:2419`).

**Credentials** (`swaygentrc/server/run/credentials.swaygentrc`): `url`, `token`, `vnc_host`, `vnc_port`. Upload in app SYSTEM → API.

**Units:** `swaygentrc.service` and `wayvnc.service` (separate). `systemctl --user status swaygentrc wayvnc`.

Details for chat/VIEW UX: `swaygentrc/README.md`. ACP notes: `docs/acp.md`.
