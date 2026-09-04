#!/usr/bin/env python3
"""MCP adapter for nested guest desktop control over QEMU VNC.

Grok talks to this process over stdio. Tools are registered as winctl__<name>.
Does not touch host wayvnc (phone VIEW).
"""
from __future__ import annotations

import functools
import inspect
import sys
from pathlib import Path

# mcp/winctl/server.py → parent mcp/ so `import winctl` resolves.
_MCP_DIR = Path(__file__).resolve().parent.parent
if str(_MCP_DIR) not in sys.path:
    sys.path.insert(0, str(_MCP_DIR))

from mcp.server.mcpserver import Image, MCPServer  # noqa: E402

from winctl import toolset  # noqa: E402

mcp = MCPServer("winctl")


def _mcp_result(result):
    # (text, [bytes], fmt) from toolset
    if (
        isinstance(result, (list, tuple))
        and len(result) >= 2
        and isinstance(result[0], str)
        and isinstance(result[1], list)
        and result[1]
    ):
        raw = result[1][0]
        if isinstance(raw, str):
            import base64

            data = base64.b64decode(raw)
        elif isinstance(raw, bytes):
            data = raw
        else:
            return result[0]
        fmt = result[2] if len(result) > 2 else "png"
        if fmt not in ("png", "jpeg", "jpg", "gif", "webp"):
            fmt = "png"
        if fmt == "jpg":
            fmt = "jpeg"
        return [result[0], Image(data=data, format=fmt)]
    return result


def _register(fn):
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        return _mcp_result(fn(*args, **kwargs))

    mcp.add_tool(
        wrapper,
        name=fn.__name__,
        description=inspect.getdoc(fn) or fn.__name__,
        structured_output=False,
    )


for _fn in toolset.TOOLS:
    _register(_fn)


if __name__ == "__main__":
    mcp.run()
