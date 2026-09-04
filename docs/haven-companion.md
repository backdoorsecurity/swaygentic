# Haven companion (option A)

Phone **VIEW** uses [GlassHaven/Haven](https://github.com/GlassHaven/Haven) as a **separate** AGPL app.
swaygentrc stays chat/agent only; Haven owns the VNC session to guest `wayvnc`.

Do **not** merge Haven into this repo.

## Layout on this VM

| Path | Role |
| --- | --- |
| `/home/browser/Haven` | Haven source (`git clone --recurse-submodules`) — **do not** clone into this dir again (creates nested `Haven/Haven`) |
| `wayland-android/build-android.sh` | Local patch: `PYTHONPATH` for `xcbgen` when building libxcb |
| `~/.config/haven-build.env` | `ANDROID_*`, cargo, go PATH |
| `~/Android/Sdk` | SDK + NDK **29.0.14206865** (Haven `android.ndkVersion`) |
| Guest `wayvnc` | user unit `wayvnc.service` → `scripts/wayvnc-tailscale.sh` (Tailscale IP `:5900`, output `Virtual-1`) |

## Host packages this VM needed (beyond README)

```bash
sudo apt-get install -y cmake ninja-build nasm yasm \
  gcc-aarch64-linux-gnu g++-aarch64-linux-gnu \
  gcc-arm-linux-gnueabihf g++-arm-linux-gnueabihf \
  autoconf automake libtool gawk \
  meson wayland-protocols libwayland-bin \
  libexpat1-dev libffi-dev libxml2-dev \
  xutils-dev x11proto-dev \
  python3-xcbgen python3-yaml python3-mako
```

Optional faster VNC-only build (skips on-device labwc/virgl natives; remote `wayvnc` still works):

```bash
./gradlew :app:assembleArm64FullDebug -PskipWaylandNatives=true
```



Android SDK: platforms 35–37, build-tools, **NDK 29.0.14206865**, cmake 3.31.6.
`local.properties`: `sdk.dir=…` and `cmake.dir=$ANDROID_HOME/cmake/3.31.6`.

## Build

```bash
. ~/.config/haven-build.env
cd ~/Haven
./gradlew :app:assembleArm64FullDebug
```

APK under `~/Haven/app/build/outputs/apk/arm64Full/debug/` (e.g. `haven-5.87.73-arm64-debug.apk`).
Stable copy: `swaygentrc/app/haven-arm64Full-debug.apk` (116 MB debug).
Application id: `sh.haven.app`.

**Verified on this VM:** `BUILD SUCCESSFUL` for `:app:assembleArm64FullDebug` after host deps + xcbgen PYTHONPATH patch.

## Use with swaygentic

1. Ensure **wayvnc** user service is up (`systemctl --user status wayvnc` — should listen on Tailscale `:5900`).
2. Install the Haven APK on the phone (`swaygentrc/app/haven-arm64Full-debug.apk`).
3. Re-upload `swaygentrc/server/run/credentials.swaygentrc` (includes `vnc_host` / `vnc_port`).
4. In swaygentrc: **VIEW → OPEN HAVEN** — launches  
   `haven://connect?host=<vnc_host>&port=<vnc_port>&transport=vnc`
5. First open: save the prefilled **VNC** profile and connect. Later opens: confirm → session.
6. Keep swaygentrc for START/chat; Haven owns the desktop click/type session.

Manual fallback (no Intent): Haven → new VNC → Tailscale IP port `5900`.

### wayvnc systemd (user)

`swaygentrc.service` is only the phone HTTP API — it does **not** start VNC. Separate unit:

```bash
# repo files
#   scripts/wayvnc-tailscale.sh
#   scripts/wayvnc.service  →  ~/.config/systemd/user/wayvnc.service

systemctl --user enable --now wayvnc
systemctl --user status wayvnc
ss -tlnp | grep 5900   # expect 100.x.x.x:5900
journalctl --user -u wayvnc -f
```

Wrapper waits for `$XDG_RUNTIME_DIR/wayland-1` and `tailscale ip -4`, then runs  
`wayvnc -o Virtual-1 …`. Tunables live in **`~/.config/wayvnc/env`** (example: `scripts/wayvnc.env.example`) — not `swaygentrc` `config.py` / `start_server.sh` (those are phone HTTP only).

| Var | Default | Effect |
| --- | --- | --- |
| `WAYVNC_MAX_FPS` | `15` | Cap capture rate (`wayvnc -f`) |
| `WAYVNC_DISABLE_RESIZING` | `1` | Lock desktop size (`wayvnc -R`) |
| `WAYVNC_OUTPUT` / `WAYVNC_PORT` / `WAYVNC_HOST` | Virtual-1 / 5900 / Tailscale v4 | Bind |
| `WAYVNC_SWAY_MODE` | unset | Option 2: e.g. `1024x768` via `swaymsg` |

After edits: `systemctl --user restart wayvnc`. wayvnc has no Tight/JPEG quality knob on the server; Haven negotiates encodings client-side.

Local Haven patch (this VM): `haven://connect` understands `transport=vnc` (match + New Connection prefill). Upstream Haven was SSH-only for cold prefills.

## Fallback

If wayvnc fails: `scripts/vsock/` bridges host QEMU `127.0.0.1:5900` through guest Tailscale.
