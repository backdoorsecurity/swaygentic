#!/usr/bin/env python3
"""Guest: TCP (Tailscale) → vsock host CID 2 (QEMU VNC/SPICE via host_forward).

Run inside the guest:

  python3 guest_forward.py
  # or: BIND_HOST=100.81.37.71 TCP_PORTS=5900,5901 python3 guest_forward.py

Phone / client connects to guest Tailscale IP:5900 (VNC) or :5901 (SPICE).
"""
from __future__ import annotations

import os
import select
import socket
import subprocess
import sys
import threading

AF_VSOCK = getattr(socket, "AF_VSOCK", None)
if AF_VSOCK is None:
    sys.exit("AF_VSOCK not available in this guest")

HOST_CID = int(os.environ.get("HOST_CID", "2"))


def tailscale_ipv4() -> str | None:
    try:
        out = subprocess.check_output(
            ["tailscale", "ip", "-4"],
            text=True,
            timeout=5,
        ).strip()
        return out.splitlines()[0].strip() if out else None
    except (OSError, subprocess.SubprocessError):
        return None


def relay(a: socket.socket, b: socket.socket) -> None:
    try:
        while True:
            r, _, _ = select.select([a, b], [], [], 60.0)
            if not r:
                continue
            for src, dst in ((a, b), (b, a)):
                if src not in r:
                    continue
                data = src.recv(65536)
                if not data:
                    return
                dst.sendall(data)
    except OSError:
        return
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                s.close()
            except OSError:
                pass


def handle(client: socket.socket, vsock_port: int) -> None:
    upstream = socket.socket(AF_VSOCK, socket.SOCK_STREAM)
    try:
        upstream.settimeout(10)
        upstream.connect((HOST_CID, vsock_port))
        upstream.settimeout(None)
        client.settimeout(None)
        relay(client, upstream)
    except OSError as exc:
        print(f"vsock {HOST_CID}:{vsock_port} failed: {exc}", file=sys.stderr)
        try:
            client.close()
        except OSError:
            pass
        try:
            upstream.close()
        except OSError:
            pass


def serve_port(bind_host: str, tcp_port: int, vsock_port: int) -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((bind_host, tcp_port))
    srv.listen(32)
    print(
        f"guest tcp {bind_host}:{tcp_port} → vsock {HOST_CID}:{vsock_port}",
        flush=True,
    )
    while True:
        client, addr = srv.accept()
        print(f"accept tcp from {addr} → vsock :{vsock_port}", flush=True)
        threading.Thread(
            target=handle,
            args=(client, vsock_port),
            daemon=True,
        ).start()


def main() -> int:
    bind = os.environ.get("BIND_HOST") or tailscale_ipv4()
    if not bind:
        sys.exit("set BIND_HOST=… or ensure `tailscale ip -4` works")

    raw = os.environ.get("TCP_PORTS", "5900,5901")
    pairs: list[tuple[int, int]] = []
    for part in raw.split(","):
        part = part.strip()
        if not part:
            continue
        if ":" in part:
            tp, vp = part.split(":", 1)
            pairs.append((int(tp), int(vp)))
        else:
            p = int(part)
            pairs.append((p, p))

    threads = []
    for tcp_port, vsock_port in pairs:
        t = threading.Thread(
            target=serve_port,
            args=(bind, tcp_port, vsock_port),
            daemon=True,
        )
        t.start()
        threads.append(t)

    print("guest_forward running (Ctrl-C to stop)", flush=True)
    try:
        for t in threads:
            t.join()
    except KeyboardInterrupt:
        print("stop", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
