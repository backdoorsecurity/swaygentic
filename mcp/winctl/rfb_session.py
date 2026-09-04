"""Persistent RFB session to the nested guest QEMU VNC server.

Does NOT touch host wayvnc (Tailscale :5900 / phone VIEW). Default target is
127.0.0.1::5902 (QEMU display :2).
"""
from __future__ import annotations

import io
import os
import threading
import time
from pathlib import Path
from typing import Any

from PIL import Image as PILImage
from vncdotool import api

DEFAULT_SERVER = os.environ.get("WINCTL_VNC", "127.0.0.1::5902")
CONNECT_TIMEOUT = float(os.environ.get("WINCTL_VNC_TIMEOUT", "15"))
JPEG_QUALITY = int(os.environ.get("WINCTL_JPEG_QUALITY", "70"))

_lock = threading.RLock()
_client: Any | None = None
_server: str = DEFAULT_SERVER


class RfbError(RuntimeError):
    pass


def _alive(client: Any) -> bool:
    try:
        screen = getattr(client, "screen", None)
        return screen is not None and getattr(screen, "size", None)
    except Exception:
        return False


def get_client(server: str | None = None) -> Any:
    """Return a connected ThreadedVNCClientProxy, reconnecting if needed."""
    global _client, _server
    with _lock:
        if server and server != _server:
            close()
            _server = server
        if _client is not None and _alive(_client):
            return _client
        if _client is not None:
            try:
                _client.disconnect()
            except Exception:
                pass
            _client = None
        try:
            _client = api.connect(_server, timeout=CONNECT_TIMEOUT)
        except Exception as exc:
            _client = None
            raise RfbError(f"VNC connect failed ({_server}): {exc}") from exc
        # Force a first framebuffer so .screen is populated
        try:
            _client.refreshScreen()
        except Exception as exc:
            try:
                _client.disconnect()
            except Exception:
                pass
            _client = None
            raise RfbError(f"VNC refresh failed ({_server}): {exc}") from exc
        if not _alive(_client):
            try:
                _client.disconnect()
            except Exception:
                pass
            _client = None
            raise RfbError(f"VNC has no framebuffer yet ({_server})")
        return _client


def close() -> None:
    global _client
    with _lock:
        if _client is not None:
            try:
                _client.disconnect()
            except Exception:
                pass
            _client = None
        try:
            api.shutdown()
        except Exception:
            pass


def resolution() -> tuple[int, int]:
    client = get_client()
    w, h = client.screen.size
    return int(w), int(h)


def capture_png_bytes() -> bytes:
    client = get_client()
    with _lock:
        buf = io.BytesIO()
        # refresh then encode from in-memory screen (avoids temp file races)
        try:
            client.refreshScreen()
        except Exception:
            pass
        img: PILImage.Image = client.screen.copy()
        img.save(buf, format="PNG", optimize=True)
        return buf.getvalue()


def capture_jpeg_bytes(quality: int | None = None) -> bytes:
    client = get_client()
    q = JPEG_QUALITY if quality is None else quality
    with _lock:
        buf = io.BytesIO()
        try:
            client.refreshScreen()
        except Exception:
            pass
        img: PILImage.Image = client.screen.copy().convert("RGB")
        img.save(buf, format="JPEG", quality=q, optimize=True)
        return buf.getvalue()


def capture_to_path(path: str | Path, fmt: str = "png") -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    data = capture_png_bytes() if fmt == "png" else capture_jpeg_bytes()
    path.write_bytes(data)
    return path


def mouse_move(x: int, y: int) -> None:
    client = get_client()
    w, h = resolution()
    if not (0 <= x < w and 0 <= y < h):
        raise RfbError(f"click out of bounds: ({x},{y}) not in 0..{w-1},0..{h-1}")
    with _lock:
        client.mouseMove(int(x), int(y))


def mouse_click(x: int, y: int, button: int = 1, double: bool = False) -> None:
    client = get_client()
    mouse_move(x, y)
    with _lock:
        client.mousePress(int(button))
        if double:
            client.pause(0.05)
            client.mousePress(int(button))


def mouse_down(button: int = 1) -> None:
    with _lock:
        get_client().mouseDown(int(button))


def mouse_up(button: int = 1) -> None:
    with _lock:
        get_client().mouseUp(int(button))


def mouse_drag(x: int, y: int) -> None:
    client = get_client()
    w, h = resolution()
    if not (0 <= x < w and 0 <= y < h):
        raise RfbError(f"drag out of bounds: ({x},{y})")
    with _lock:
        client.mouseDrag(int(x), int(y))


def scroll_wheel(ticks: int) -> None:
    """Positive ticks = scroll down (button 5), negative = up (button 4)."""
    client = get_client()
    button = 5 if ticks > 0 else 4
    n = abs(int(ticks))
    with _lock:
        for _ in range(n):
            client.mousePress(button)
            client.pause(0.02)


def type_text(text: str) -> None:
    client = get_client()
    with _lock:
        for ch in text:
            if ch == "\n":
                client.keyPress("enter")
            elif ch == "\t":
                client.keyPress("tab")
            else:
                client.keyPress(ch)


def key_press(key: str) -> None:
    """Press a vncdotool key name (e.g. 'enter', 'esc', 'ctrl-c')."""
    with _lock:
        get_client().keyPress(key)


def pause(seconds: float) -> None:
    time.sleep(max(0.0, float(seconds)))
