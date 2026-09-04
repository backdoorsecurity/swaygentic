import os
import shutil
import signal
import subprocess
import threading
import time

from system import config
from toolbox import swaygentic_wrap

_lock = threading.Lock()
_proc = None
_starting = False
_expect_stop = False


def _read_pid():
    if not os.path.isfile(config.PID_PATH):
        return None
    try:
        with open(config.PID_PATH, "r", encoding="utf-8") as handle:
            text = handle.read().strip()
        if not text:
            return None
        return int(text)
    except (OSError, ValueError):
        return None


def _write_pid(pid):
    with open(config.PID_PATH, "w", encoding="utf-8") as handle:
        handle.write(str(pid) + "\n")


def _clear_pid():
    try:
        os.remove(config.PID_PATH)
    except FileNotFoundError:
        pass


def _decode_rc(rc):
    if rc is None:
        return "still running"
    if rc < 0:
        try:
            name = signal.Signals(-rc).name
        except ValueError:
            name = str(-rc)
        return f"killed by {name}"
    return f"exited with code {rc}"


def _write_last_error(msg):
    os.makedirs(config.RUN_DIR, exist_ok=True)
    with open(config.LAST_ERROR_PATH, "w", encoding="utf-8") as handle:
        handle.write(msg.rstrip() + "\n")


def _harvest_debug():
    src = config.DEBUG_LOG_PATH
    dst = os.path.join(config.RUN_DIR, "swaygentic.debug.log")
    try:
        if os.path.isfile(src):
            shutil.copy2(src, dst)
    except OSError:
        pass


def is_paused():
    return os.path.isfile(config.PAUSED_PATH)


def set_paused(value):
    if value:
        os.makedirs(config.RUN_DIR, exist_ok=True)
        with open(config.PAUSED_PATH, "w", encoding="utf-8") as handle:
            handle.write("1\n")
        return
    try:
        os.remove(config.PAUSED_PATH)
    except FileNotFoundError:
        pass


def _pid_alive(pid):
    if pid is None:
        return False
    try:
        with open(f"/proc/{pid}/stat", "r", encoding="utf-8") as handle:
            stat = handle.read()
    except OSError:
        return False
    rparen = stat.rfind(")")
    if rparen == -1 or rparen + 2 >= len(stat):
        return False
    return stat[rparen + 2] != "Z"


def _reap(pid):
    if pid is None:
        return
    try:
        os.waitpid(pid, os.WNOHANG)
    except OSError:
        pass


def _port_open():
    """True if something is LISTEN on GROK_BIND_PORT. Do not connect — a
    raw TCP probe is not a WebSocket handshake and can drop the agent."""
    port_hex = f"{config.GROK_BIND_PORT:04X}"
    for path in ("/proc/net/tcp", "/proc/net/tcp6"):
        try:
            with open(path, "r", encoding="utf-8") as handle:
                next(handle, None)
                for line in handle:
                    parts = line.split()
                    if len(parts) < 4:
                        continue
                    local = parts[1]
                    state = parts[3]
                    if state != "0A":
                        continue
                    if local.rsplit(":", 1)[-1].upper() == port_hex:
                        return True
        except OSError:
            continue
    return False


def status():
    pid = _read_pid()
    alive = _pid_alive(pid)
    crashed = False
    if pid is not None and not alive and not _starting:
        _clear_pid()
        pid = None
        crashed = True
        _harvest_debug()
    listening = _port_open()
    running = alive and listening
    wrap = swaygentic_wrap.runner_path()
    state = {
        "running": running,
        "pid": pid if alive else None,
        "port": config.GROK_BIND_PORT,
        "bind": config.GROK_BIND,
        "listening": listening,
        "cwd": config.CHAT_CWD,
        "wrap": wrap or None,
        "jailed": bool(wrap),
        "paused": is_paused(),
    }
    if crashed:
        extra = ""
        try:
            extra = open(config.LAST_ERROR_PATH, encoding="utf-8").read().strip()
        except OSError:
            extra = ""
        if not extra:
            extra = (
                f"agent died after listen; see {config.LOG_PATH} "
                f"and {config.DEBUG_LOG_PATH}"
            )
            _write_last_error(extra)
        state["error"] = extra
    elif alive and not listening:
        state["error"] = f"agent pid {pid} is up but not listening on {config.GROK_BIND}"
    return state


def start(grok_bin, secret):
    global _proc, _starting, _expect_stop
    with _lock:
        set_paused(False)
        current = status()
        if current["running"]:
            return False, current
        if current["pid"] is not None:
            _stop_locked()
        if _port_open():
            raise RuntimeError(
                f"port {config.GROK_BIND} is already in use by another process"
            )

        os.makedirs(os.path.dirname(config.DEBUG_LOG_PATH), exist_ok=True)
        try:
            os.remove(config.DEBUG_LOG_PATH)
        except FileNotFoundError:
            pass

        cmd = [
            grok_bin,
            "--trust",
            "--cwd",
            config.CHAT_CWD,
            "--debug-file",
            config.DEBUG_LOG_PATH,
            "agent",
            "--always-approve",
            "--no-leader",
        ]
        if config.GROK_MODEL:
            cmd.extend(["-m", config.GROK_MODEL])
        if config.GROK_EFFORT:
            cmd.extend(["--reasoning-effort", config.GROK_EFFORT])
        cmd.extend(
            [
                "serve",
                "--bind",
                config.GROK_BIND,
                "--secret",
                secret,
            ]
        )
        cmd = swaygentic_wrap.wrap(cmd)

        env = os.environ.copy()
        env["GROK_DISABLE_AUTOUPDATER"] = "1"

        log_handle = open(config.LOG_PATH, "ab")
        _expect_stop = False
        _starting = True
        try:
            proc = subprocess.Popen(
                cmd,
                stdout=log_handle,
                stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
                # No start_new_session: bwrap already uses --new-session, and
                # killpg on the wrapper PGID was SIGKILL-racing the agent.
                cwd=config.CHAT_CWD,
                env=env,
            )
        except Exception:
            log_handle.close()
            _starting = False
            raise

        log_handle.close()
        _proc = proc
        _write_pid(proc.pid)
        threading.Thread(
            target=_watch_proc,
            args=(proc,),
            name="swaygentrc-agent-wait",
            daemon=True,
        ).start()

        deadline = time.time() + config.START_TIMEOUT_SEC
        try:
            while time.time() < deadline:
                if proc.poll() is not None:
                    _clear_pid()
                    _harvest_debug()
                    why = _decode_rc(proc.returncode)
                    msg = (
                        f"agent {why}; see {config.LOG_PATH} "
                        f"and {config.DEBUG_LOG_PATH}"
                    )
                    _write_last_error(msg)
                    raise RuntimeError(msg)
                if _port_open():
                    time.sleep(0.4)
                    if proc.poll() is None and _port_open():
                        return True, status()
                time.sleep(0.2)
        finally:
            _starting = False

        _stop_locked()
        msg = (
            f"agent did not listen on {config.GROK_BIND} in time; "
            f"see {config.LOG_PATH} and {config.DEBUG_LOG_PATH}"
        )
        _write_last_error(msg)
        raise RuntimeError(msg)


def _child_pids(pid):
    kids = []
    try:
        for name in os.listdir("/proc"):
            if not name.isdigit():
                continue
            try:
                with open(f"/proc/{name}/stat", "r", encoding="utf-8") as handle:
                    stat = handle.read()
            except OSError:
                continue
            rparen = stat.rfind(")")
            if rparen == -1:
                continue
            fields = stat[rparen + 2 :].split()
            if len(fields) < 2:
                continue
            if int(fields[1]) == pid:
                kids.append(int(name))
    except OSError:
        pass
    return kids


def _kill_tree(pid):
    """Kill pid and descendants. Avoid killpg — bwrap --new-session breaks groups."""
    if pid is None:
        return
    stack = [pid]
    seen = set()
    while stack:
        cur = stack.pop()
        if cur in seen:
            continue
        seen.add(cur)
        stack.extend(_child_pids(cur))
    for cur in sorted(seen, reverse=True):
        try:
            os.kill(cur, signal.SIGKILL)
        except OSError:
            pass


def _watch_proc(proc):
    rc = proc.wait()
    _harvest_debug()
    if _expect_stop:
        return
    msg = (
        f"agent {_decode_rc(rc)} after listen; "
        f"see {config.LOG_PATH} and {config.DEBUG_LOG_PATH}"
    )
    _write_last_error(msg)


def _stop_locked():
    global _proc, _expect_stop
    _expect_stop = True
    pid = _read_pid()
    if _proc is not None:
        pid = pid or _proc.pid
    if pid is not None:
        _kill_tree(pid)
        _reap(pid)
    if _proc is not None:
        try:
            _proc.poll()
            _proc.wait(timeout=1)
        except Exception:
            pass
        _proc = None
    _clear_pid()
    deadline = time.time() + 2
    while time.time() < deadline:
        if pid is not None:
            _reap(pid)
            if _pid_alive(pid):
                _kill_tree(pid)
        if (pid is None or not _pid_alive(pid)) and not _port_open():
            break
        time.sleep(0.1)
    if pid is not None:
        _reap(pid)
    return status()


def stop():
    with _lock:
        if _starting:
            return status()
        return _stop_locked()
