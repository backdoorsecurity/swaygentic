import json
import os
import random
import shutil
import subprocess
import time


def _env(name, default=""):
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip() or default


def _load_start_message(path):
    if not path or not os.path.isfile(path):
        return ""
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read().strip()


_TAILSCALE_BINS = (
    "tailscale",
    "/usr/bin/tailscale",
    "/usr/sbin/tailscale",
    "/usr/local/bin/tailscale",
    "/opt/homebrew/bin/tailscale",
    "/snap/bin/tailscale",
)


def _is_cgnat_v4(ip):
    parts = ip.split(".")
    if len(parts) != 4:
        return False
    try:
        a, b = int(parts[0]), int(parts[1])
    except ValueError:
        return False
    return a == 100 and 64 <= b <= 127


def _cmd_out(argv, timeout=4):
    try:
        return subprocess.check_output(
            argv,
            timeout=timeout,
            text=True,
            stderr=subprocess.DEVNULL,
        )
    except (OSError, subprocess.SubprocessError):
        return ""


def _tailscale_bin():
    for candidate in _TAILSCALE_BINS:
        if os.path.sep in candidate:
            if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
                return candidate
        else:
            found = shutil.which(candidate)
            if found:
                return found
    home = os.path.expanduser("~/.local/bin/tailscale")
    if os.path.isfile(home) and os.access(home, os.X_OK):
        return home
    return ""


def _ips_from_tailscale_cli():
    binary = _tailscale_bin()
    if not binary:
        return []
    ips = []
    for flag in ("-4", "-6"):
        out = _cmd_out([binary, "ip", flag])
        ip = (out or "").strip().split()
        if ip:
            ips.append(ip[0])
    if ips:
        return ips
    out = _cmd_out([binary, "status", "--json"], timeout=6)
    try:
        data = json.loads(out or "{}")
    except ValueError:
        return []
    for ip in ((data.get("Self") or {}).get("TailscaleIPs") or []):
        ip = str(ip).strip()
        if ip:
            ips.append(ip)
    return ips


def _ips_from_tailscale_iface():
    """Read 100.64/10 off tailscale0 when the CLI is missing from PATH."""
    ip_bin = shutil.which("ip") or ("/sbin/ip" if os.path.isfile("/sbin/ip") else "ip")
    out = _cmd_out([ip_bin, "-4", "-o", "addr", "show"])
    found = []
    for line in (out or "").splitlines():
        if "inet " not in line:
            continue
        if "tailscale" not in line and "100." not in line:
            continue
        for token in line.split():
            if token.startswith("100.") and "/" in token:
                ip = token.split("/", 1)[0]
                if _is_cgnat_v4(ip):
                    found.append(ip)
                    break
    return found


def _tailscale_ips():
    ips = _ips_from_tailscale_cli() or _ips_from_tailscale_iface()
    if not ips:
        time.sleep(0.5)
        ips = _ips_from_tailscale_cli() or _ips_from_tailscale_iface()
    v4 = [ip for ip in ips if ":" not in ip]
    v6 = [ip for ip in ips if ":" in ip]
    return v4 + v6


def _tailscale_dns_name():
    binary = _tailscale_bin()
    if not binary:
        return ""
    out = _cmd_out([binary, "status", "--json"], timeout=6)
    try:
        data = json.loads(out or "{}")
    except ValueError:
        return ""
    return ((data.get("Self") or {}).get("DNSName") or "").strip().rstrip(".")


def _detect_listen_hosts():
    """Bind the phone API to Tailscale if present, else loopback.

    Never default to 0.0.0.0 / :: . Set SWAYGENTRC_HTTP_HOST for a different iface.
    """
    explicit = _env("SWAYGENTRC_HTTP_HOST", "")
    if explicit:
        return [explicit]
    ips = _tailscale_ips()
    if ips:
        return ips
    return ["127.0.0.1"]


# server/ (parent of system/)
SYSTEM_DIR = os.path.dirname(os.path.abspath(__file__))
SERVER_DIR = os.path.dirname(SYSTEM_DIR)
# swaygentrc/
PROJECT_DIR = os.path.dirname(SERVER_DIR)
# swaygentic repo root (parent of swaygentrc/)
_DEFAULT_CHAT_CWD = os.path.dirname(PROJECT_DIR)

RUN_DIR = os.path.join(SERVER_DIR, "run")
HTTP_PORT_PATH = os.path.join(RUN_DIR, "http.port")


def _persist_http_port(port):
    os.makedirs(RUN_DIR, exist_ok=True)
    fd = os.open(HTTP_PORT_PATH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(str(port) + "\n")


def ensure_http_port():
    """Random high port once, persisted in run/http.port.

    Env ``SWAYGENTRC_HTTP_PORT`` overrides and rewrites the file.
    Failed HTTP requests do **not** change the port — only a new allocate
    (missing/invalid file) or an explicit env override does.
    """
    explicit = _env("SWAYGENTRC_HTTP_PORT", "")
    if explicit:
        port = int(explicit)
        if not (1 <= port <= 65535):
            raise ValueError(f"invalid SWAYGENTRC_HTTP_PORT: {explicit}")
        prev = None
        if os.path.isfile(HTTP_PORT_PATH):
            try:
                prev = int(open(HTTP_PORT_PATH, encoding="utf-8").read().strip())
            except (OSError, ValueError):
                prev = None
        _persist_http_port(port)
        if prev is not None and prev != port:
            print(f"swaygentrc: HTTP port override {prev} → {port} (SWAYGENTRC_HTTP_PORT)")
        return port
    if os.path.isfile(HTTP_PORT_PATH):
        try:
            with open(HTTP_PORT_PATH, "r", encoding="utf-8") as handle:
                text = handle.read().strip()
            port = int(text)
            if 1 <= port <= 65535:
                return port
            print(f"swaygentrc: ignoring invalid http.port value {text!r}")
        except (OSError, ValueError) as exc:
            print(f"swaygentrc: could not read http.port ({exc}); allocating new")
    port = random.randint(30000, 59999)
    _persist_http_port(port)
    print(f"swaygentrc: allocated HTTP port {port} (persisted {HTTP_PORT_PATH})")
    return port


HTTP_PORT = ensure_http_port()
HTTP_HOSTS = []
HTTP_HOST = "127.0.0.1"
TAILSCALE_DNS = ""


def refresh_listen():
    """Re-detect Tailscale before bind/credentials."""
    global HTTP_HOSTS, HTTP_HOST, TAILSCALE_DNS, HTTP_PORT
    HTTP_PORT = ensure_http_port()
    HTTP_HOSTS = _detect_listen_hosts()
    HTTP_HOST = HTTP_HOSTS[0]
    TAILSCALE_DNS = _tailscale_dns_name()
    return HTTP_HOSTS


refresh_listen()

GROK_BIN = os.path.expanduser(_env("SWAYGENTRC_GROK_BIN", "~/.grok/bin/grok"))
GROK_BIND = _env("SWAYGENTRC_GROK_BIND", "127.0.0.1:2419")
if ":" in GROK_BIND:
    GROK_BIND_HOST, bind_port = GROK_BIND.rsplit(":", 1)
    GROK_BIND_PORT = int(bind_port)
else:
    GROK_BIND_HOST = "127.0.0.1"
    GROK_BIND_PORT = int(GROK_BIND)
    GROK_BIND = f"{GROK_BIND_HOST}:{GROK_BIND_PORT}"

GROK_EFFORT = _env("SWAYGENTRC_GROK_EFFORT", "low")
GROK_MODEL = _env("SWAYGENTRC_GROK_MODEL", "")

PID_PATH = os.path.join(RUN_DIR, "swaygentic.pid")
LOG_PATH = os.path.join(RUN_DIR, "swaygentic.log")
ACP_LOG_PATH = os.path.join(RUN_DIR, "acp.log")
LAST_ERROR_PATH = os.path.join(RUN_DIR, "last-error.txt")
SECRET_PATH = os.path.join(RUN_DIR, "agent.secret")
API_TOKEN_PATH = os.path.join(RUN_DIR, "api.token")
CREDENTIALS_PATH = os.path.join(RUN_DIR, "credentials.swaygentrc")
SESSION_PATH = os.path.join(RUN_DIR, "session.id")
PAUSED_PATH = os.path.join(RUN_DIR, "paused.flag")

# Guest wayvnc endpoint for phone Haven Intent (option A VIEW).
VNC_PORT = int(_env("SWAYGENTRC_VNC_PORT", "5900") or "5900")


def vnc_host():
    """Tailscale IPv4 (or override) for Haven VNC deep link."""
    explicit = _env("SWAYGENTRC_VNC_HOST", "")
    if explicit:
        return explicit
    for ip in _tailscale_ips():
        if ":" not in ip:
            return ip
    if HTTP_HOST and HTTP_HOST not in ("0.0.0.0", "::", ""):
        return HTTP_HOST
    return "127.0.0.1"

START_TIMEOUT_SEC = 40
STOP_TIMEOUT_SEC = 8
CHAT_CWD = os.path.abspath(
    os.path.expanduser(_env("SWAYGENTRC_SERVER_ROOT", _DEFAULT_CHAT_CWD))
)
# Must live under CHAT_CWD — home is tmpfs in the jail except bound trees.
DEBUG_LOG_PATH = os.path.join(CHAT_CWD, ".grok", "swaygentrc-serve.log")
# Empty = wrap via mcp/swaygentic. Set SWAYGENTIC_OFF=1 or SWAYGENTRC_WRAP=off for host grok.
SWAYGENTRC_WRAP = _env("SWAYGENTRC_WRAP", "")
START_MESSAGE_PATH = os.path.expanduser(_env("SWAYGENTRC_START_MESSAGE", ""))
START_MESSAGE = _load_start_message(START_MESSAGE_PATH)

JSON_MAX_BYTES = 1_000_000
CHAT_MAX_CHARS = 100_000
