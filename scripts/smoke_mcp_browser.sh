#!/usr/bin/env bash
# Phase 2 smoke: MCP handshake, open example.com, take a screenshot.
# Uses headless Brave by default (SWG_HEADLESS=1). Requires Node + Nightly.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-1}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
export SWG_HEADLESS="${SWG_HEADLESS:-1}"

if [[ ! -S "${XDG_RUNTIME_DIR}/${WAYLAND_DISPLAY}" && -S "${XDG_RUNTIME_DIR}/wayland-1" ]]; then
  export WAYLAND_DISPLAY=wayland-1
fi

python3 <<'PY'
import json, os, select, subprocess, time

env = os.environ.copy()
proc = subprocess.Popen(
    ["bash", "mcp/run_brave_mcp.sh"],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    env=env,
    cwd=os.getcwd(),
)

def send(obj):
    proc.stdin.write((json.dumps(obj) + "\n").encode())
    proc.stdin.flush()

def read_msg(timeout=90):
    end = time.time() + timeout
    buf = b""
    while time.time() < end:
        if proc.poll() is not None and not select.select([proc.stdout], [], [], 0)[0]:
            err = proc.stderr.read().decode(errors="replace")
            raise SystemExit(f"MCP exited {proc.returncode}: {err[:1200]}")
        r, _, _ = select.select([proc.stdout], [], [], 1.0)
        if not r:
            continue
        chunk = os.read(proc.stdout.fileno(), 65536)
        if not chunk:
            continue
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            line = line.strip()
            if not line:
                continue
            return json.loads(line)
    err = proc.stderr.read().decode(errors="replace")
    raise SystemExit(f"timeout waiting for MCP; stderr={err[:1200]}")

send({
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "swg-smoke", "version": "0"},
    },
})
init = read_msg(30)
assert "result" in init, init
print("OK: initialize", init["result"]["serverInfo"]["name"], init["result"]["serverInfo"]["version"])

send({"jsonrpc": "2.0", "method": "notifications/initialized"})
send({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
tools = read_msg(30)
names = {t["name"] for t in tools["result"]["tools"]}
for need in ("new_page", "take_screenshot", "navigate_page", "list_pages", "click", "fill"):
    assert need in names, f"missing tool {need}; have {sorted(names)[:15]}..."
print(f"OK: tools ({len(names)}) include navigate/click/fill/screenshot")

send({
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {"name": "new_page", "arguments": {"url": "https://example.com"}},
})
page = read_msg(90)
assert "result" in page and "error" not in page, page
text = ""
for c in page["result"].get("content") or []:
    if c.get("type") == "text":
        text += c.get("text", "")
assert "example.com" in text.lower(), text[:500]
print("OK: new_page → example.com")

# Prefer selected page; pageIdRouting may require an id — parse if present.
page_id = None
for line in text.splitlines():
    if "[selected]" in line and ":" in line:
        # e.g. "4: Example Domain (https://example.com/) [selected]"
        page_id = line.split(":", 1)[0].strip().split()[-1]
        try:
            page_id = int(page_id)
        except ValueError:
            page_id = page_id

args = {}
if page_id is not None:
    args["pageId"] = page_id

send({
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {"name": "take_screenshot", "arguments": args},
})
shot = read_msg(90)
assert "result" in shot and "error" not in shot, shot
ctypes = [c.get("type") for c in (shot["result"].get("content") or [])]
# Image and/or text summary both count as a tool-returned screenshot path.
ok = any(t in ("image", "text") for t in ctypes)
assert ok, shot
print("OK: take_screenshot →", ctypes)

proc.terminate()
try:
    proc.wait(timeout=5)
except Exception:
    proc.kill()
print("OK: MCP browser smoke passed")
PY
