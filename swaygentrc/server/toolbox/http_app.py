import base64
import hmac
import json
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from system import config
from toolbox import host_power, live, swaygentic_process
from toolbox.acp_client import AcpError


def _json_bytes(payload):
    return json.dumps(payload).encode("utf-8")


def _token_ok(given, expected):
    if not given or not expected:
        return False
    a = given.encode("utf-8")
    b = expected.encode("utf-8")
    if len(a) != len(b):
        return False
    return hmac.compare_digest(a, b)


class SwaygentrcHandler(BaseHTTPRequestHandler):
    grok_bin = None
    agent_secret = None
    api_token = None
    acp = None
    activity = None

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} {fmt % args}")

    def _send(self, code, payload):
        body = _json_bytes(payload)
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _bearer(self):
        hdr = self.headers.get("Authorization", "")
        if hdr.lower().startswith("bearer "):
            return hdr[7:].strip()
        alt = (
            self.headers.get("X-Swaygentrc-Token", "")
            or self.headers.get("X-Grokrc-Token", "")
            or self.headers.get("X-Gtc-Token", "")
        )
        return alt.strip()

    def _authorized(self):
        return _token_ok(self._bearer(), self.api_token or "")

    def _read_json(self):
        raw = self.headers.get("Content-Length", "0")
        try:
            length = int(raw)
        except ValueError:
            length = 0
        if length <= 0:
            return {}
        if length > config.JSON_MAX_BYTES:
            raise ValueError("payload too large")
        blob = self.rfile.read(length)
        if not blob:
            return {}
        return json.loads(blob.decode("utf-8"))

    def _not_found(self):
        self._send(404, {"error": "not found"})

    def _state(self):
        state = swaygentic_process.status()
        if self.acp is not None:
            state["acp"] = self.acp.snapshot()
        if self.activity is not None:
            state["activity"] = list(self.activity[-12:])
        state["frame_mtime"] = live.latest_mtime()
        state["http_port"] = config.HTTP_PORT
        return state

    def _note(self, event):
        if self.activity is None:
            return
        self.activity.append(event)
        del self.activity[:-40]

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/health":
            self._send(200, {"ok": True, "service": "swaygentrc"})
            return
        if not self._authorized():
            self._send(401, {"error": "unauthorized"})
            return
        if path == "/grok/status":
            self._send(200, self._state())
            return
        if path == "/grok/frame":
            data = live.jpeg_bytes()
            if not data:
                self.send_response(204)
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                return
            kind = "image/jpeg" if data[:2] == b"\xff\xd8" else "image/png"
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)
            return
        if path == "/chat":
            self._send(
                405,
                {"error": "POST JSON {\"message\": \"...\"} to /chat"},
            )
            return
        self._not_found()

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if not self._authorized():
            self._send(401, {"error": "unauthorized"})
            return
        if path == "/grok/start":
            swaygentic_process.set_paused(False)
            if self.acp is not None:
                self.acp.close()
            try:
                started, state = swaygentic_process.start(
                    self.grok_bin, self.agent_secret
                )
            except Exception as exc:
                self._send(
                    500,
                    {"error": str(exc), "status": swaygentic_process.status()},
                )
                return
            code = 200 if started else 409
            self._send(code, state)
            return
        if path == "/grok/pause":
            swaygentic_process.set_paused(True)
            if self.acp is not None:
                self.acp.cancel()
                self.acp.close()
            state = swaygentic_process.status()
            state["paused"] = True
            self._send(200, state)
            return
        if path == "/grok/stop":
            swaygentic_process.set_paused(False)
            if self.acp is not None:
                self.acp.close()
                self.acp.forget_session()
            try:
                state = swaygentic_process.stop()
            except Exception as exc:
                self._send(
                    500,
                    {"error": str(exc), "status": swaygentic_process.status()},
                )
                return
            self._send(200, state)
            return
        if path == "/chat":
            if swaygentic_process.is_paused():
                self._send(409, {"error": "paused — tap START to resume"})
                return
            if not swaygentic_process.status()["running"]:
                self._send(409, {"error": "start agent first"})
                return
            try:
                payload = self._read_json()
            except Exception as exc:
                self._send(400, {"error": f"bad json: {exc}"})
                return
            message = (payload.get("message") or "").strip()
            if not message:
                self._send(400, {"error": "empty message"})
                return
            if len(message) > config.CHAT_MAX_CHARS:
                self._send(400, {"error": "message too long"})
                return
            want_sse = "text/event-stream" in (self.headers.get("Accept") or "").lower()
            if want_sse:
                self._chat_sse(message)
                return
            try:
                result = self.acp.prompt(message, on_update=self._note)
            except AcpError as exc:
                self._send(502, {"error": str(exc)})
                return
            except Exception as exc:
                self._send(500, {"error": str(exc)})
                return
            self._send(200, result)
            return
        if path == "/host/poweroff":
            try:
                payload = self._read_json()
            except Exception:
                payload = {}
            confirm = str(payload.get("confirm") or "").strip()
            if confirm != "POWEROFF":
                self._send(
                    400,
                    {"error": "confirm POWEROFF in JSON body to power off the host"},
                )
                return
            if self.acp is not None:
                self.acp.close()
            try:
                swaygentic_process.stop()
            except Exception:
                pass
            try:
                host_power.schedule_poweroff()
            except Exception as exc:
                self._send(500, {"error": str(exc)})
                return
            self._send(200, {"ok": True, "scheduled": True})
            return
        self._not_found()

    def _emit(self, event, data):
        blob = json.dumps(data, ensure_ascii=False)
        self.wfile.write(f"event: {event}\ndata: {blob}\n\n".encode("utf-8"))
        self.wfile.flush()

    def _chat_sse(self, message):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        last_frame = live.latest_mtime()

        def on_update(event):
            self._note(event)
            self._emit(event.get("type") or "update", event)
            nonlocal last_frame
            mtime = live.latest_mtime()
            if mtime and mtime != last_frame:
                last_frame = mtime
                data = live.jpeg_bytes()
                if data:
                    self._emit(
                        "frame",
                        {
                            "mime": "image/jpeg" if data[:2] == b"\xff\xd8" else "image/png",
                            "b64": base64.b64encode(data).decode("ascii"),
                        },
                    )

        try:
            result = self.acp.prompt(message, on_update=on_update)
        except AcpError as exc:
            self._emit("error", {"error": str(exc)})
            return
        except Exception as exc:
            self._emit("error", {"error": str(exc)})
            return
        mtime = live.latest_mtime()
        if mtime and mtime != last_frame:
            data = live.jpeg_bytes()
            if data:
                self._emit(
                    "frame",
                    {
                        "mime": "image/jpeg" if data[:2] == b"\xff\xd8" else "image/png",
                        "b64": base64.b64encode(data).decode("ascii"),
                    },
                )
        self._emit("done", result)


def listen_url(host, port):
    if ":" in host and not host.startswith("["):
        return f"http://[{host}]:{port}"
    return f"http://{host}:{port}"


class IPv6Server(ThreadingHTTPServer):
    address_family = socket.AF_INET6


def bind_server(host, port):
    if host in ("0.0.0.0", "::", ""):
        raise ValueError(
            "refusing to bind 0.0.0.0 / :: ; set SWAYGENTRC_HTTP_HOST to Tailscale or loopback"
        )
    if ":" in host:
        return IPv6Server((host, port), SwaygentrcHandler)
    return ThreadingHTTPServer((host, port), SwaygentrcHandler)


def make_server(host, port, grok_bin, agent_secret, acp, api_token):
    SwaygentrcHandler.grok_bin = grok_bin
    SwaygentrcHandler.agent_secret = agent_secret
    SwaygentrcHandler.api_token = api_token
    SwaygentrcHandler.acp = acp
    SwaygentrcHandler.activity = []
    return bind_server(host, port)
