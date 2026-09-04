import os
import pwd
import shutil
import subprocess

from system import config

SERVICE_NAME = "swaygentrc.service"
LEGACY_SERVICE_NAME = "grokrc.service"


def under_systemd():
    return bool(os.environ.get("INVOCATION_ID"))


def _user_home():
    return os.path.expanduser("~") or pwd.getpwuid(os.getuid()).pw_dir


def _unit_path():
    return os.path.join(_user_home(), ".config", "systemd", "user", SERVICE_NAME)


def _unit_body():
    home = _user_home()
    server = config.SERVER_DIR
    template = os.path.join(config.SYSTEM_DIR, "swaygentrc.service")
    script = os.path.join(config.SYSTEM_DIR, "start_server.sh")
    if os.path.isfile(template):
        with open(template, "r", encoding="utf-8") as handle:
            body = handle.read()
        if "@SERVER_DIR@" in body or "@SYSTEM_DIR@" in body:
            return (
                body.replace("@SERVER_DIR@", server)
                .replace("@SYSTEM_DIR@", config.SYSTEM_DIR)
                .replace("@HOME@", home)
            )
    path = (
        "/usr/bin:/usr/sbin:/usr/local/bin:/snap/bin:"
        f"{home}/.local/bin:{home}/.grok/bin:/bin"
    )
    return f"""[Unit]
Description=swaygentrc phone API
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory={server}
ExecStart={script}
Restart=on-failure
RestartSec=5
TimeoutStopSec=20
Environment=HOME={home}
Environment=PATH={path}

[Install]
WantedBy=default.target
"""


def _run(argv, extra_env=None):
    env = os.environ.copy()
    uid = os.getuid()
    env.setdefault("XDG_RUNTIME_DIR", f"/run/user/{uid}")
    if extra_env:
        env.update(extra_env)
    proc = subprocess.run(
        argv,
        capture_output=True,
        text=True,
        env=env,
        timeout=15,
    )
    err = (proc.stderr or proc.stdout or "").strip()
    return proc.returncode == 0, err


def _userctl(*args):
    systemctl = shutil.which("systemctl")
    if not systemctl:
        return False, "systemctl not found"
    return _run([systemctl, "--user", *args])


def _enable_linger():
    loginctl = shutil.which("loginctl")
    if not loginctl:
        return False, "loginctl not found"
    user = pwd.getpwuid(os.getuid()).pw_name
    return _run([loginctl, "enable-linger", user])


def write_unit():
    path = _unit_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    body = _unit_body()
    existing = ""
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8") as handle:
            existing = handle.read()
    if existing != body:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(body)
        return path, True
    return path, False


def _disable_legacy():
    notes = []
    ok, err = _userctl("disable", "--now", LEGACY_SERVICE_NAME)
    if ok:
        notes.append(f"disabled legacy {LEGACY_SERVICE_NAME}")
    elif err:
        low = err.lower()
        # Missing legacy unit is normal on fresh installs.
        if (
            "not found" not in low
            and "not loaded" not in low
            and "does not exist" not in low
        ):
            notes.append(f"legacy disable: {err}")
    legacy_path = os.path.join(
        _user_home(), ".config", "systemd", "user", LEGACY_SERVICE_NAME
    )
    try:
        os.remove(legacy_path)
        notes.append(f"removed {legacy_path}")
    except FileNotFoundError:
        pass
    except OSError as exc:
        notes.append(f"could not remove legacy unit file: {exc}")
    return notes


def ensure_systemd(start=False):
    """Install a user unit so swaygentrc starts on login/boot (with linger)."""
    if os.environ.get("SWAYGENTRC_SKIP_SYSTEMD") == "1":
        return "systemd skipped (SWAYGENTRC_SKIP_SYSTEMD=1)"
    if os.getuid() == 0:
        return "skip systemd: running as root; start swaygentrc as the desktop user"
    if not shutil.which("systemctl"):
        return "systemd not found; run system/start_server.sh in the foreground"

    path, changed = write_unit()
    notes = []
    notes.extend(_disable_legacy())
    if changed:
        ok, err = _userctl("daemon-reload")
        if not ok:
            return f"wrote {path} but daemon-reload failed: {err or 'no user bus'}"
        notes.append(f"wrote {path}")
    else:
        notes.append(f"unit ok ({path})")

    ok, err = _userctl("enable", SERVICE_NAME)
    if not ok:
        notes.append(f"enable failed: {err}")
    linger_ok, linger_err = _enable_linger()
    if linger_ok:
        notes.append("linger on (starts at boot)")
    else:
        notes.append(
            "linger off — run: loginctl enable-linger $USER  (needed for boot without login)"
        )
        if linger_err:
            notes.append(linger_err)

    if start and not under_systemd():
        ok, err = _userctl("restart", SERVICE_NAME)
        if ok:
            notes.append("started via systemd --user")
        else:
            notes.append(f"start failed: {err}")
            return "; ".join(notes)

    return "; ".join(notes)
