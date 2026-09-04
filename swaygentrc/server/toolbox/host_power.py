import shutil
import subprocess
import threading
import time


def _run(cmd):
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=8)
    if proc.returncode == 0:
        return True, " ".join(cmd)
    err = (proc.stderr or proc.stdout or "").strip() or f"exit {proc.returncode}"
    return False, err


def poweroff_now():
    candidates = []
    if shutil.which("loginctl"):
        candidates.append(["loginctl", "poweroff"])
    if shutil.which("systemctl"):
        candidates.append(["systemctl", "poweroff"])
        candidates.append(["sudo", "-n", "systemctl", "poweroff"])
    last = "no poweroff command found"
    for cmd in candidates:
        ok, detail = _run(cmd)
        if ok:
            return detail
        last = detail
    raise RuntimeError(last)


def schedule_poweroff(delay_sec=0.6):
    def worker():
        time.sleep(delay_sec)
        poweroff_now()

    thread = threading.Thread(target=worker, daemon=True)
    thread.start()
    return True
