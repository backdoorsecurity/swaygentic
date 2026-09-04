"""Public guest-desktop tools for the winctl MCP (QEMU VNC).

Successful actions return (text, [image_bytes], fmt).
Errors return a string starting with "Error:".
Does not touch host wayvnc (phone VIEW :5900).
"""
from __future__ import annotations

import os
import socket
import subprocess
import time
from pathlib import Path

from . import keymaps, rfb_session

CLICK_SETTLE = float(os.environ.get("WINCTL_CLICK_SETTLE", "0.45"))
KEY_SETTLE = float(os.environ.get("WINCTL_KEY_SETTLE", "0.35"))
TYPE_SETTLE = float(os.environ.get("WINCTL_TYPE_SETTLE", "0.2"))
LOOK_FORMAT = os.environ.get("WINCTL_LOOK_FORMAT", "jpeg")  # jpeg|png
DOMAIN = os.environ.get("WINCTL_DOMAIN", "V2_tiny-10")
LIBVIRT_URI = os.environ.get("LIBVIRT_DEFAULT_URI", "qemu:///system")
VNC_HOSTPORT = os.environ.get("WINCTL_VNC", "127.0.0.1::5902")


def _shot_tuple(note: str):
    if LOOK_FORMAT == "png":
        data = rfb_session.capture_png_bytes()
        fmt = "png"
    else:
        data = rfb_session.capture_jpeg_bytes()
        fmt = "jpeg"
    w, h = rfb_session.resolution()
    text = f"{note} ({w}x{h}, {fmt}, {len(data)} bytes)"
    return text, [data], fmt


def _err(msg: str) -> str:
    return f"Error: {msg}"


def win_status() -> str:
    """Guest VNC / libvirt status. Text only — no screenshot."""
    lines = []
    # TCP probe
    host, _, port_s = VNC_HOSTPORT.partition("::")
    if not port_s:
        # display form host:N
        if ":" in VNC_HOSTPORT and VNC_HOSTPORT.count(":") == 1:
            host, disp = VNC_HOSTPORT.split(":")
            port = 5900 + int(disp)
        else:
            host, port = "127.0.0.1", 5902
    else:
        port = int(port_s)
    vnc_open = False
    try:
        with socket.create_connection((host, port), timeout=2):
            lines.append(f"vnc={host}:{port} open")
            vnc_open = True
    except OSError as exc:
        lines.append(f"vnc={host}:{port} CLOSED ({exc})")

    proc = subprocess.run(
        ["virsh", "-c", LIBVIRT_URI, "domstate", DOMAIN],
        capture_output=True,
        text=True,
        timeout=10,
    )
    if proc.returncode == 0:
        lines.append(f"domain={DOMAIN} state={proc.stdout.strip()}")
    else:
        lines.append(f"domain={DOMAIN} query_failed={proc.stderr.strip()}")

    # Host wayvnc (phone VIEW) — report only, never touch
    try:
        out = subprocess.check_output(["ss", "-tln"], text=True, timeout=5)
        for line in out.splitlines():
            if ":5900" in line:
                lines.append(f"host_wayvnc_listener={line.strip()} (do not touch)")
                break
    except Exception:
        pass

    if vnc_open:
        try:
            w, h = rfb_session.resolution()
            lines.append(f"framebuffer={w}x{h}")
        except Exception as exc:
            lines.append(f"framebuffer=unavailable ({exc})")
    else:
        lines.append("framebuffer=skipped (vnc closed)")

    return "\n".join(lines)


def win_look(note: str = "") -> tuple | str:
    """Capture the guest screen. Coords on the image are guest pixels for win_click(x,y)."""
    try:
        prefix = note.strip() or "Screenshot"
        text, images, fmt = _shot_tuple(prefix)
        return text, images, fmt
    except Exception as exc:
        return _err(str(exc))


def win_click(x: int, y: int, button: int = 1, double: bool = False) -> tuple | str:
    """Click guest pixels from the last win_look. button: 1=left 2=middle 3=right."""
    try:
        rfb_session.mouse_click(int(x), int(y), button=int(button), double=bool(double))
        time.sleep(CLICK_SETTLE)
        kind = "dblclick" if double else "click"
        return _shot_tuple(f"{kind} ({x},{y}) btn={button}")
    except Exception as exc:
        return _err(str(exc))


def win_move(x: int, y: int) -> tuple | str:
    """Move the guest pointer without clicking."""
    try:
        rfb_session.mouse_move(int(x), int(y))
        time.sleep(0.05)
        return _shot_tuple(f"move ({x},{y})")
    except Exception as exc:
        return _err(str(exc))


def win_scroll(ticks: int = 3) -> tuple | str:
    """Scroll wheel at the current pointer. Positive ticks scroll down."""
    try:
        rfb_session.scroll_wheel(int(ticks))
        time.sleep(CLICK_SETTLE)
        return _shot_tuple(f"scroll ticks={ticks}")
    except Exception as exc:
        return _err(str(exc))


def win_type(text: str) -> tuple | str:
    """Type text into the focused guest control (best-effort ASCII)."""
    try:
        if not text:
            return _err("empty text")
        rfb_session.type_text(text)
        time.sleep(TYPE_SETTLE)
        shown = text if len(text) <= 40 else text[:37] + "..."
        return _shot_tuple(f"typed {shown!r}")
    except Exception as exc:
        return _err(str(exc))


def win_key(key: str) -> tuple | str:
    """Press a key or chord. Examples: enter, esc, win, win+r, alt+tab, ctrl+c, ctrl+alt+delete.

    Windows shell chords use virsh send-key (reliable against QEMU VNC). Most others use RFB.
    """
    try:
        route = keymaps.dispatch_key(key, rfb_session.key_press)
        time.sleep(KEY_SETTLE)
        return _shot_tuple(f"key {key!r} via {route}")
    except Exception as exc:
        return _err(str(exc))


def win_drag(x: int, y: int) -> tuple | str:
    """Drag (button held) to guest pixel x,y from the current pointer position."""
    try:
        rfb_session.mouse_drag(int(x), int(y))
        time.sleep(CLICK_SETTLE)
        return _shot_tuple(f"drag to ({x},{y})")
    except Exception as exc:
        return _err(str(exc))


TOOLS = [
    win_status,
    win_look,
    win_click,
    win_move,
    win_scroll,
    win_type,
    win_key,
    win_drag,
]
