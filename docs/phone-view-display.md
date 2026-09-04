# Phone VIEW — remote display options

Concise comparison for wiring the Android **VIEW** tab to this VM’s desktop.
Chat stays on swaygentrc HTTP/SSE. Agent vision stays on CDP (`brave-devtools__take_screenshot`).

**Verified (guest):** virt-manager **VNC :5900** and **SPICE :5901** listen on the **KVM host**, not inside this guest. Host bind `127.0.0.1` means only processes **on the host OS** can connect directly.

**Preferred bridge (no `0.0.0.0`):** virtio-vsock (guest CID ≥ 3) + `scripts/vsock/` — host `host_forward.py` (vsock → `127.0.0.1:5900/5901`), guest `guest_forward.py` (Tailscale TCP → host CID 2). See `scripts/vsock/README.md`.

Alternatives: bind QEMU to host Tailscale IP only; or guest `wayvnc` on `100.x`.

---

## Options

### A. Hypervisor VNC (virt-manager / QEMU `-vnc`)

Second (or primary) display exposed by the **host** as VNC.

- **Pros**
  - No `wayvnc` / grim inside the guest
  - Survives guest compositor quirks; works even if sway is wedged
  - Easy to enable in virt-manager; standard Android VNC clients already exist
  - Good fit after disk resize / cold plug of the new display
- **Cons**
  - Endpoint is on the **KVM host**, not the guest Tailscale IP the phone already uses for chat
  - Phone must reach host VNC (host Tailscale, SSH tunnel, or libvirt graphics listen) — extra network story
  - Second head can mean empty/black until sway is told to use that output
  - Auth/TLS is whatever QEMU/libvirt offers; easy to mis-bind on LAN
  - Building into the APK still means an embedded VNC client (same as B)

### B. Guest `wayvnc` (sway / wlroots)

VNC server **inside** the guest, capturing the live Wayland session.

- **Pros**
  - Same Tailscale path as chat (`credentials` can list guest `vnc_host:port`)
  - True “what’s on sway,” including Brave + terminals
  - `wayvnc` apt-available; pairs cleanly with an in-app VNC client
  - Start/stop can follow phone START without touching the hypervisor
- **Cons**
  - Needs packages + user service; not installed yet
  - Soft GPU / pixman guest may feel sluggish
  - Dies if the Wayland session dies
  - Must bind Tailscale-only (never casual `0.0.0.0`)

### C. Hypervisor SPICE (QEMU SPICE)

- **Pros**
  - Often smoother than VNC for KVM (adaptive; clipboard via `spice-vdagent` already in guest)
  - Natural virt-manager graphics type
- **Cons**
  - Client is SPICE, not VNC — heavier to embed (aSPICE-class) than libvncclient
  - Still a **host** endpoint, same reachability issue as A
  - Overkill if we already commit to VNC-in-APK for A or B

### D. CDP JPEG → `GET /grok/frame` (current stub)

Periodic Brave **page** screenshot over DevTools.

- **Pros**
  - Small server change (`live.py`); existing APK poll/SSE already coded
  - No hypervisor or VNC stack
- **Cons**
  - Not the whole desktop; not realtime; burns bandwidth on poll
  - Duplicate of agent screenshots; wrong product if VIEW means “see the machine”
  - **Skip** unless embedded RD slips and we need a stopgap

### E. waypipe → phone

- **Pros**
  - `waypipe` already on guest; great for single remote apps on Linux/macOS
- **Cons**
  - Not full-desktop capture; needs a Wayland compositor on Android (large R&D)
  - Wrong tool for VIEW

---

## Build-into-app implication

Any of **A / B / C** shipped **inside** swaygentrc (no external viewer) needs an embedded client:

| Server | In-APK client effort |
| --- | --- |
| VNC (A or B) | Moderate — libvncclient / bVNC-derived |
| SPICE (C) | Higher — aSPICE / custom SPICE stack |

Prefer **one** RFB/VNC client in the APK that can point at **either** host VNC (A) or guest wayvnc (B) via credentials.

---

## Practical recommendation

1. **Primary:** guest **`wayvnc`** + phone **Haven companion** (option A — separate APK). swaygentrc VIEW launches `haven://connect?…&transport=vnc` from credentials `vnc_host`/`vnc_port`. See `docs/haven-companion.md`.
2. **Fallback:** virtio-vsock + `scripts/vsock/` (host `127.0.0.1` QEMU VNC/SPICE → guest Tailscale). Keep those scripts.
3. Hypervisor VNC alone on `127.0.0.1` is not phone-reachable; avoid `0.0.0.0`.
4. **Do not** invest in D or E for the primary VIEW path; do not merge Haven into swaygentic.
5. Agent CDP screenshots stay regardless of A/B.
6. Groups for `browser`: `~/groupadd.sh` (`render` required for DRI/`wayvnc`).

---

## Boot checklist (operator)

After shutdown / resize / attach:

- [ ] qcow2 grown; VM starts clean
- [ ] virt-manager graphics/VNC device present and listening (note **host** bind address + port)
- [ ] Guest sees the new output (`swaymsg -t get_outputs`) — enable/arrange if blank
- [ ] Decide VIEW target: host VNC (A) vs install wayvnc (B) vs both with one APK client
- [ ] Record chosen `vnc_url` for `credentials.swaygentrc` (gitignored run dir)
