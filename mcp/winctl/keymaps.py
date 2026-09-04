"""Key routing: RFB when reliable, virsh send-key for Windows specials.

vncdotool Super_L / Win chords have hung against QEMU VNC in practice;
virsh send-key is reliable for those.
"""
from __future__ import annotations

import os
import subprocess

LIBVIRT_URI = os.environ.get("LIBVIRT_DEFAULT_URI", "qemu:///system")
DOMAIN = os.environ.get("WINCTL_DOMAIN", "V2_tiny-10")

# Logical name -> either ("rfb", keyname) or ("virsh", [KEY_*...])
# Chord names use '+' joining (grokbox style): ctrl+alt+delete
KEY_ROUTES: dict[str, tuple[str, object]] = {
    "enter": ("rfb", "enter"),
    "return": ("rfb", "enter"),
    "esc": ("rfb", "esc"),
    "escape": ("rfb", "esc"),
    "tab": ("rfb", "tab"),
    "backspace": ("rfb", "bsp"),
    "bsp": ("rfb", "bsp"),
    "delete": ("rfb", "del"),
    "del": ("rfb", "del"),
    "space": ("rfb", "space"),
    "up": ("rfb", "up"),
    "down": ("rfb", "down"),
    "left": ("rfb", "left"),
    "right": ("rfb", "right"),
    "home": ("rfb", "home"),
    "end": ("rfb", "end"),
    "pageup": ("rfb", "pgup"),
    "pagedown": ("rfb", "pgdn"),
    "f1": ("rfb", "f1"),
    "f2": ("rfb", "f2"),
    "f3": ("rfb", "f3"),
    "f4": ("rfb", "f4"),
    "f5": ("rfb", "f5"),
    "f6": ("rfb", "f6"),
    "f7": ("rfb", "f7"),
    "f8": ("rfb", "f8"),
    "f9": ("rfb", "f9"),
    "f10": ("rfb", "f10"),
    "f11": ("rfb", "f11"),
    "f12": ("rfb", "f12"),
    # Prefer virsh for Windows shell chords
    "win": ("virsh", ["KEY_LEFTMETA"]),
    "super": ("virsh", ["KEY_LEFTMETA"]),
    "meta": ("virsh", ["KEY_LEFTMETA"]),
    "win+r": ("virsh", ["KEY_LEFTMETA", "KEY_R"]),
    "win+e": ("virsh", ["KEY_LEFTMETA", "KEY_E"]),
    "win+d": ("virsh", ["KEY_LEFTMETA", "KEY_D"]),
    "win+i": ("virsh", ["KEY_LEFTMETA", "KEY_I"]),
    "win+x": ("virsh", ["KEY_LEFTMETA", "KEY_X"]),
    "alt+tab": ("virsh", ["KEY_LEFTALT", "KEY_TAB"]),
    "alt+f4": ("virsh", ["KEY_LEFTALT", "KEY_F4"]),
    "alt+a": ("virsh", ["KEY_LEFTALT", "KEY_A"]),
    "ctrl+alt+delete": ("virsh", ["KEY_LEFTCTRL", "KEY_LEFTALT", "KEY_DELETE"]),
    "ctrl+alt+del": ("virsh", ["KEY_LEFTCTRL", "KEY_LEFTALT", "KEY_DELETE"]),
    "ctrl+c": ("rfb", "ctrl-c"),
    "ctrl+v": ("rfb", "ctrl-v"),
    "ctrl+a": ("rfb", "ctrl-a"),
    "ctrl+x": ("rfb", "ctrl-x"),
    "ctrl+z": ("rfb", "ctrl-z"),
    "ctrl+s": ("rfb", "ctrl-s"),
    "ctrl+w": ("rfb", "ctrl-w"),
    "ctrl+l": ("rfb", "ctrl-l"),
}

# Single-letter Alt/Ctrl chords via virsh (vncdotool rejects "alt+x" — ord() on multi-char).
_LETTER_KEYS = {c: f"KEY_{c.upper()}" for c in "abcdefghijklmnopqrstuvwxyz"}


def normalize_key(name: str) -> str:
    return name.strip().lower().replace(" ", "")


def virsh_send(keys: list[str]) -> None:
    cmd = [
        "virsh",
        "-c",
        LIBVIRT_URI,
        "send-key",
        DOMAIN,
        *keys,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    if proc.returncode != 0:
        raise RuntimeError(
            f"virsh send-key failed ({proc.returncode}): {proc.stderr.strip() or proc.stdout.strip()}"
        )


def dispatch_key(name: str, rfb_key_press) -> str:
    """Send a logical key. rfb_key_press is a callable(str). Returns route used."""
    key = normalize_key(name)
    route = KEY_ROUTES.get(key)
    if route is None:
        # alt+x / ctrl+x for letters → virsh (never pass "alt+x" to vncdotool)
        if key.startswith("alt+") and key[4:] in _LETTER_KEYS:
            payload = ["KEY_LEFTALT", _LETTER_KEYS[key[4:]]]
            virsh_send(payload)
            return f"virsh:{'+'.join(payload)}"
        if key.startswith("ctrl+") and key[5:] in _LETTER_KEYS:
            payload = ["KEY_LEFTCTRL", _LETTER_KEYS[key[5:]]]
            virsh_send(payload)
            return f"virsh:{'+'.join(payload)}"
        # Single printable / raw vncdotool name (hyphen chords e.g. shift-a)
        if len(key) == 1:
            rfb_key_press(key)
            return f"rfb:{key}"
        if "+" in key:
            raise ValueError(
                f"unknown chord {name!r}; use win+r / alt+tab / ctrl-c style, "
                "or a mapped KEY_ROUTES name"
            )
        rfb_key_press(name)
        return f"rfb:{name}"
    kind, payload = route
    if kind == "rfb":
        rfb_key_press(str(payload))
        return f"rfb:{payload}"
    virsh_send(list(payload))  # type: ignore[arg-type]
    return f"virsh:{'+'.join(payload)}"  # type: ignore[arg-type]
