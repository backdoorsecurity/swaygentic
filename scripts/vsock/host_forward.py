#!/usr/bin/env python3
"""Host: vsock listen → TCP 127.0.0.1 (QEMU VNC/SPICE).

Run on the KVM **host** (not inside the guest):

  python3 host_forward.py
  # or: VSOCK_PORTS=5900,5901 TCP_HOST=127.0.0.1 python3 host_forward.py

Keeps QEMU graphics on loopback; guest connects via virtio-vsock (host CID 2).
"""
from __future__ import annotations

import os
import select
import socket
import sys
import threading

AF_VSOCK = getattr(socket, "AF_VSOCK", None)
if AF_VSOCK is None:
    sys.exit("AF_VSOCK not available on this Python/host")

VMADDR_CID_ANY = getattr(socket, "VMADDR_CID_ANY", 0xFFFFFFFF)


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


def handle(client: socket.socket, tcp_host: str, tcp_port: int) -> None:
    upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        upstream.settimeout(10)
        upstream.connect((tcp_host, tcp_port))
        upstream.settimeout(None)
        client.settimeout(None)
        relay(client, upstream)
    except OSError as exc:
        print(f"upstream {tcp_host}:{tcp_port} failed: {exc}", file=sys.stderr)
        try:
            client.close()
        except OSError:
            pass
        try:
            upstream.close()
        except OSError:
            pass


def serve_port(vsock_port: int, tcp_host: str, tcp_port: int) -> None:
    srv = socket.socket(AF_VSOCK, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((VMADDR_CID_ANY, vsock_port))
    srv.listen(32)
    print(f"host vsock :{vsock_port} → tcp {tcp_host}:{tcp_port}", flush=True)
    while True:
        client, addr = srv.accept()
        print(f"accept vsock from {addr} → {tcp_host}:{tcp_port}", flush=True)
        threading.Thread(
            target=handle,
            args=(client, tcp_host, tcp_port),
            daemon=True,
        ).start()


def main() -> int:
    tcp_host = os.environ.get("TCP_HOST", "127.0.0.1")
    # vsock_port:tcp_port pairs; default identity map for VNC+SPICE
    raw = os.environ.get("VSOCK_PORTS", "5900,5901")
    pairs: list[tuple[int, int]] = []
    for part in raw.split(","):
        part = part.strip()
        if not part:
            continue
        if ":" in part:
            vp, tp = part.split(":", 1)
            pairs.append((int(vp), int(tp)))
        else:
            p = int(part)
            pairs.append((p, p))

    threads = []
    for vsock_port, tcp_port in pairs:
        t = threading.Thread(
            target=serve_port,
            args=(vsock_port, tcp_host, tcp_port),
            daemon=True,
        )
        t.start()
        threads.append(t)

    print("host_forward running (Ctrl-C to stop)", flush=True)
    try:
        for t in threads:
            t.join()
    except KeyboardInterrupt:
        print("stop", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
