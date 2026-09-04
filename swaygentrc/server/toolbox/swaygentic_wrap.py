import os
import tomllib

from system import config


def _off(raw):
    return raw.strip().lower() in ("0", "off", "none", "false", "no")


def _executable(path):
    if not os.path.isfile(path):
        return ""
    if not os.access(path, os.X_OK):
        try:
            os.chmod(path, os.stat(path).st_mode | 0o111)
        except OSError:
            return ""
    if os.path.isfile(path) and os.access(path, os.X_OK):
        return path
    return ""


def project_runner():
    """Prefer repo mcp/swaygentic, else ~/.local/bin/swaygentic."""
    candidates = [
        os.path.join(config.CHAT_CWD, "mcp", "swaygentic"),
        os.path.expanduser("~/.local/bin/swaygentic"),
    ]
    for path in candidates:
        got = _executable(path)
        if got:
            return got
    return ""


def mcp_servers():
    """Read MCP servers from committed project config — never rewrite the file."""
    path = os.path.join(config.CHAT_CWD, ".grok", "config.toml")
    if not os.path.isfile(path):
        return []
    try:
        with open(path, "rb") as handle:
            data = tomllib.load(handle)
    except (OSError, tomllib.TOMLDecodeError):
        return []
    servers = []
    block = data.get("mcp_servers") or {}
    for name, spec in block.items():
        if not isinstance(spec, dict):
            continue
        if spec.get("enabled", True) is False:
            continue
        command = spec.get("command")
        if not command:
            continue
        env = []
        raw_env = spec.get("env") or {}
        if isinstance(raw_env, dict):
            for key, value in raw_env.items():
                env.append({"name": str(key), "value": str(value)})
        servers.append(
            {
                "name": str(name),
                "command": str(command),
                "args": [str(a) for a in (spec.get("args") or [])],
                "env": env,
            }
        )
    return servers


def runner_path():
    """Wrap agent in swaygentic jail. SWAYGENTIC_OFF=1 or WRAP=off → host grok."""
    off = os.environ.get("SWAYGENTIC_OFF", "").strip().lower()
    if off in ("1", "true", "yes", "on"):
        return ""
    raw = config.SWAYGENTRC_WRAP
    if raw and _off(raw):
        return ""
    if raw:
        return _executable(os.path.expanduser(raw))
    return project_runner()


def wrap(cmd):
    """Run argv via swaygentic jail.

    ``swaygentic`` resolves the real grok binary itself. Pass only grok's
    arguments (drop ``cmd[0]``). Do not insert a ``--`` separator — that
    would be forwarded to grok as a positional arg.
    """
    runner = runner_path()
    if not runner:
        return list(cmd)
    if not cmd:
        return [runner]
    return [runner, *cmd[1:]]


def session_rules():
    extra = config.START_MESSAGE
    if extra:
        return extra
    agents = os.path.join(config.CHAT_CWD, "AGENTS.md")
    if os.path.isfile(agents):
        with open(agents, "r", encoding="utf-8") as handle:
            return handle.read().strip()
    return ""
