# virtio-vsock bridge: host QEMU VNC/SPICE → guest Tailscale

```text
Phone (Tailscale)
  → guest 100.x:5900/5901     (guest_forward.py)
      → vsock → host CID 2
          → host 127.0.0.1:5900/5901   (host_forward.py → QEMU)
```

Keep virt-manager VNC/SPICE on **127.0.0.1**. Do **not** use `0.0.0.0`.

## Prerequisites

- Guest: virtio-vsock attached; `guest_cid >= 3` (`vmw_vsock_virtio_transport` loaded)
- Host: `modprobe vhost_vsock` (you already did)
- Host QEMU listening: VNC `127.0.0.1:5900`, SPICE `127.0.0.1:5901`

## Host (run on the KVM host OS)

Copy this directory to the host (scp/git), then:

```bash
# one-time
sudo modprobe vhost_vsock

# foreground
python3 host_forward.py

# optional: only VNC
VSOCK_PORTS=5900 python3 host_forward.py
```

Leave this running while you want phone/guest access.

Optional systemd user/system unit sketch:

```ini
[Unit]
Description=vsock → localhost QEMU VNC/SPICE
After=network.target

[Service]
ExecStart=/usr/bin/python3 /path/to/swaygentic/scripts/vsock/host_forward.py
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

## Guest (this VM)

```bash
cd ~/swaygentic/scripts/vsock
python3 guest_forward.py
# binds Tailscale IPv4 automatically; override with BIND_HOST=100.x.x.x
```

Smoke from another Tailscale device:

```bash
# replace with guest tailscale IP
vncviewer 100.81.37.71:5900
# or remote-viewer spice://100.81.37.71:5901
```

## Verify vsock from guest

```bash
python3 - <<'PY'
import socket, fcntl, os, ctypes
fd = os.open('/dev/vsock', os.O_RDONLY)
cid = ctypes.c_uint(); fcntl.ioctl(fd, 0x7b9, cid); os.close(fd)
print('guest_cid', cid.value)  # expect >= 3
s = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
s.settimeout(2)
s.connect((2, 5900))  # works only when host_forward is up
print('host vsock 5900 ok')
PY
```

## Notes

- Guest forwarder binds **Tailscale only**, not `0.0.0.0`.
- Host forwarder never opens a LAN TCP port; only vsock + existing localhost QEMU.
- Chat HTTP stays on the high port in `credentials.swaygentrc`; VNC/SPICE are separate (`:5900` / `:5901`).
