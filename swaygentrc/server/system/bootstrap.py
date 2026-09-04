import os
import secrets
import stat

from system import config
from toolbox import swaygentic_wrap
from toolbox.systemd_unit import ensure_systemd


def ensure_run_dir():
    os.makedirs(config.RUN_DIR, exist_ok=True)


def _write_secret(path):
    secret = secrets.token_urlsafe(32)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(secret + "\n")
    return secret


def _read_or_create(path):
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8") as handle:
            secret = handle.read().strip()
        if secret:
            try:
                os.chmod(path, 0o600)
            except OSError:
                pass
            return secret
    return _write_secret(path)


def ensure_agent_secret():
    return _read_or_create(config.SECRET_PATH)


def ensure_api_token():
    """Bearer token for the phone API. Never hardcoded; lives in run/api.token."""
    return _read_or_create(config.API_TOKEN_PATH)


def _phone_listen_url():
    """Prefer Tailscale IPv4 so the phone can upload one file and connect."""
    from toolbox.http_app import listen_url

    ips = config._tailscale_ips()
    v4 = next((ip for ip in ips if ":" not in ip), "")
    if v4:
        return listen_url(v4, config.HTTP_PORT)
    for host in config.HTTP_HOSTS:
        if host and host not in ("0.0.0.0", "::") and ":" not in host:
            return listen_url(host, config.HTTP_PORT)
    host = config.HTTP_HOST
    if host in ("0.0.0.0", "::", ""):
        host = "127.0.0.1"
    return listen_url(host, config.HTTP_PORT)


def write_phone_credentials(api_token):
    """Write run/credentials.swaygentrc with listen URL + token + VNC for phone upload."""
    url = _phone_listen_url()
    vnc_host = config.vnc_host()
    vnc_port = config.VNC_PORT
    body = (
        "# swaygentrc phone credentials\n"
        "# upload this file in SYSTEM -> API\n"
        f"url={url}\n"
        f"token={api_token}\n"
        f"vnc_host={vnc_host}\n"
        f"vnc_port={vnc_port}\n"
    )
    fd = os.open(
        config.CREDENTIALS_PATH,
        os.O_WRONLY | os.O_CREAT | os.O_TRUNC,
        0o600,
    )
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(body)
    return config.CREDENTIALS_PATH, url


def check_grok_bin():
    path = config.GROK_BIN
    if not os.path.isfile(path):
        raise FileNotFoundError(f"grok binary not found: {path}")
    if not os.access(path, os.X_OK):
        mode = stat.S_IMODE(os.stat(path).st_mode)
        raise PermissionError(
            f"grok binary is not executable (mode {oct(mode)}): {path}"
        )
    return path


def setup():
    ensure_run_dir()
    config.refresh_listen()
    secret = ensure_agent_secret()
    api_token = ensure_api_token()
    creds_path, creds_url = write_phone_credentials(api_token)
    if "127.0.0.1" in creds_url or "localhost" in creds_url:
        print(
            "WARNING: no Tailscale IPv4 found; credentials.swaygentrc used "
            f"{creds_url}. Is tailscale on PATH, or is tailscale0 up?"
        )
    else:
        print(f"phone credentials: {creds_path} ({creds_url})")
    print(f"http port: {config.HTTP_PORT} (persisted in {config.HTTP_PORT_PATH})")
    grok_bin = check_grok_bin()
    toml = os.path.join(config.CHAT_CWD, ".grok", "config.toml")
    if os.path.isfile(toml):
        print(f"mcp config (read-only): {toml}")
    else:
        print(f"WARNING: missing project MCP config: {toml}")
    wrap = swaygentic_wrap.runner_path()
    if wrap:
        print(f"swaygentic wrap: {wrap}")
    else:
        print("swaygentic wrap: off — agent on host (SWAYGENTIC_OFF or WRAP=off)")
    print(f"systemd: {ensure_systemd(start=False)}")
    return grok_bin, secret, api_token
