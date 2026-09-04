import base64
import json
import os
import socket
import threading
import time

from system import config


class AcpError(Exception):
    pass


class _Ws:
    def __init__(self, sock, leftover=b""):
        self.sock = sock
        self.buf = leftover

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass

    def send_text(self, text):
        payload = text.encode("utf-8")
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        header = bytearray([0x81])
        n = len(payload)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += n.to_bytes(2, "big")
        else:
            header.append(0x80 | 127)
            header += n.to_bytes(8, "big")
        self.sock.sendall(bytes(header) + mask + masked)

    def _recv(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(max(4096, n - len(self.buf)))
            if not chunk:
                raise AcpError("agent websocket closed")
            self.buf += chunk
        data, self.buf = self.buf[:n], self.buf[n:]
        return data

    def read_message(self):
        while True:
            b0, b1 = self._recv(2)
            opcode = b0 & 0x0F
            n = b1 & 0x7F
            if n == 126:
                n = int.from_bytes(self._recv(2), "big")
            elif n == 127:
                n = int.from_bytes(self._recv(8), "big")
            if b1 & 0x80:
                mask = self._recv(4)
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(self._recv(n)))
            else:
                data = self._recv(n)
            if opcode == 9:
                mask = os.urandom(4)
                masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
                header = bytearray([0x8A, 0x80 | len(data)])
                self.sock.sendall(bytes(header) + mask + masked)
                continue
            if opcode == 8:
                raise AcpError("agent websocket closed")
            if opcode in (1, 2):
                return json.loads(data.decode("utf-8"))


def _read_session_id():
    if not os.path.isfile(config.SESSION_PATH):
        return None
    try:
        with open(config.SESSION_PATH, "r", encoding="utf-8") as handle:
            text = handle.read().strip()
        return text or None
    except OSError:
        return None


def _write_session_id(session_id):
    os.makedirs(config.RUN_DIR, exist_ok=True)
    with open(config.SESSION_PATH, "w", encoding="utf-8") as handle:
        handle.write(session_id + "\n")


def _clear_session_id():
    try:
        os.remove(config.SESSION_PATH)
    except FileNotFoundError:
        pass


def _tool_name(update):
    raw = update.get("rawInput") or update.get("raw_input") or {}
    title = (update.get("title") or "").strip()
    kind = (update.get("kind") or "").strip()
    return title or kind or "tool"


def normalize_update(update):
    """Turn an ACP session/update into a small phone event dict, or None."""
    if not isinstance(update, dict):
        return None
    kind = update.get("sessionUpdate") or update.get("session_update")
    content = update.get("content") or {}
    piece = content.get("text") if isinstance(content, dict) else None
    if kind == "agent_message_chunk" and piece:
        return {"type": "text", "text": piece}
    if kind == "agent_thought_chunk" and piece:
        return {"type": "thought", "text": piece}
    if kind in ("tool_call", "tool_call_update"):
        event = {
            "type": "tool",
            "id": update.get("toolCallId") or update.get("tool_call_id") or "",
            "name": _tool_name(update),
            "title": update.get("title") or "",
            "status": update.get("status") or ("pending" if kind == "tool_call" else "running"),
            "kind": update.get("kind") or "",
        }
        raw = update.get("rawInput") or update.get("raw_input")
        if isinstance(raw, dict) and raw:
            # Keep a short, non-secret summary.
            parts = []
            for key in ("url", "query", "name", "app", "binary", "combo", "text", "title"):
                val = raw.get(key)
                if val:
                    parts.append(f"{key}={val}")
            if parts:
                event["detail"] = " ".join(parts)[:240]
            elif "path" in raw:
                event["detail"] = "path=(local)"
        return event
    return None


class AcpClient:
    def __init__(self, secret):
        self.secret = secret
        self.lock = threading.Lock()
        self.ws = None
        self.session_id = _read_session_id()
        self.next_id = 1
        self.last_error = None

    def snapshot(self):
        return {
            "connected": self.ws is not None,
            "session_id": self.session_id,
            "last_error": self.last_error,
        }

    def _log(self, line):
        stamp = time.strftime("%Y-%m-%d %H:%M:%S")
        msg = f"{stamp} {line}\n"
        print(f"acp {line}")
        try:
            os.makedirs(config.RUN_DIR, exist_ok=True)
            with open(config.ACP_LOG_PATH, "a", encoding="utf-8") as handle:
                handle.write(msg)
        except OSError:
            pass

    def close(self):
        ws = self.ws
        if ws is not None:
            ws.close()
        with self.lock:
            if self.ws is ws:
                self.ws = None
        self._log("close")

    def forget_session(self):
        self.session_id = None
        _clear_session_id()

    def cancel(self):
        ws = self.ws
        sid = self.session_id
        if ws is None or not sid:
            return
        try:
            ws.send_text(
                json.dumps(
                    {
                        "jsonrpc": "2.0",
                        "method": "session/cancel",
                        "params": {"sessionId": sid},
                    }
                )
            )
            self._log(f"cancel session={sid}")
        except Exception as exc:
            self._log(f"cancel-fail {exc}")

    def _drop(self):
        if self.ws is not None:
            self.ws.close()
        self.ws = None

    def _connect(self):
        from toolbox import swaygentic_wrap

        resume_id = self.session_id
        self._drop()
        self._log("connect")
        sock = socket.create_connection(
            (config.GROK_BIND_HOST, config.GROK_BIND_PORT),
            timeout=8,
        )
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        req = (
            "GET /ws HTTP/1.1\r\n"
            f"Host: {config.GROK_BIND}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            f"Authorization: Bearer {self.secret}\r\n"
            "\r\n"
        )
        sock.sendall(req.encode("ascii"))
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = sock.recv(4096)
            if not chunk:
                sock.close()
                raise AcpError("no websocket handshake")
            buf += chunk
        head, rest = buf.split(b"\r\n\r\n", 1)
        first = head.split(b"\r\n", 1)[0].decode("ascii", "replace")
        if b" 101 " not in head.split(b"\r\n", 1)[0]:
            sock.close()
            raise AcpError(f"websocket handshake failed: {first}")
        sock.settimeout(30)
        self.ws = _Ws(sock, rest)
        self.next_id = 1
        self._rpc(
            "initialize",
            {
                "protocolVersion": 1,
                "clientInfo": {"name": "swaygentrc", "version": "0.1.0"},
                "clientCapabilities": {
                    "fs": {"readTextFile": False, "writeTextFile": False},
                    "terminal": False,
                },
            },
        )
        servers = swaygentic_wrap.mcp_servers()
        if resume_id:
            try:
                self._rpc(
                    "session/load",
                    {
                        "sessionId": resume_id,
                        "cwd": config.CHAT_CWD,
                        "mcpServers": servers,
                    },
                )
                self.session_id = resume_id
                _write_session_id(resume_id)
                sock.settimeout(None)
                self._log(f"load {self.session_id}")
                return
            except AcpError as exc:
                self._log(f"load-fail {resume_id} {exc}")
                _clear_session_id()
                self.session_id = None
        meta = {"yoloMode": True}
        rules = swaygentic_wrap.session_rules()
        if rules:
            meta["rules"] = rules
        created = self._rpc(
            "session/new",
            {
                "cwd": config.CHAT_CWD,
                "mcpServers": servers,
                "_meta": meta,
            },
        )
        self.session_id = created.get("sessionId")
        if not self.session_id:
            raise AcpError("session/new returned no sessionId")
        _write_session_id(self.session_id)
        sock.settimeout(None)
        self._log(f"session {self.session_id}")

    def _answer_agent(self, ws, msg):
        method = msg.get("method")
        req_id = msg.get("id")
        params = msg.get("params") or {}
        self._log(f"agent-req {method} id={req_id}")
        if method == "session/request_permission":
            options = params.get("options") or []
            option_id = None
            for kind in ("allow_always", "allow_once"):
                for opt in options:
                    if opt.get("kind") == kind:
                        option_id = opt.get("optionId")
                        break
                if option_id:
                    break
            if option_id is None and options:
                option_id = options[0].get("optionId")
            ws.send_text(
                json.dumps(
                    {
                        "jsonrpc": "2.0",
                        "id": req_id,
                        "result": {
                            "outcome": {"outcome": "selected", "optionId": option_id}
                        },
                    }
                )
            )
            self._log(f"permission allow {option_id}")
            return
        ws.send_text(
            json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32601, "message": f"not implemented: {method}"},
                }
            )
        )

    def _read(self, ws):
        while True:
            msg = ws.read_message()
            if msg.get("method") and "id" in msg and "result" not in msg and "error" not in msg:
                self._answer_agent(ws, msg)
                continue
            return msg

    def _rpc(self, method, params):
        req_id = self.next_id
        self.next_id += 1
        self.ws.send_text(
            json.dumps({"jsonrpc": "2.0", "id": req_id, "method": method, "params": params})
        )
        deadline = time.time() + 30
        while time.time() < deadline:
            msg = self._read(self.ws)
            if msg.get("id") == req_id:
                if "error" in msg:
                    err = msg["error"]
                    raise AcpError(str(err.get("message") or err))
                return msg.get("result") or {}
        raise AcpError(f"timeout waiting for {method}")

    def prompt(self, text, on_update=None):
        with self.lock:
            try:
                if self.ws is None or self.session_id is None:
                    self._connect()
                req_id = self.next_id
                self.next_id += 1
                sid = self.session_id
                ws = self.ws
                self._log(f"prompt id={req_id} session={sid} chars={len(text)}")
                ws.send_text(
                    json.dumps(
                        {
                            "jsonrpc": "2.0",
                            "id": req_id,
                            "method": "session/prompt",
                            "params": {
                                "sessionId": sid,
                                "prompt": [{"type": "text", "text": text}],
                            },
                        }
                    )
                )
            except Exception as exc:
                self.last_error = str(exc)
                self._drop()
                self._log(f"prompt-send-fail {exc}")
                raise
        chunks = []
        thoughts = []
        try:
            while True:
                msg = self._read(ws)
                if msg.get("id") == req_id:
                    if "error" in msg:
                        err = msg["error"]
                        raise AcpError(str(err.get("message") or err))
                    result = {
                        "text": "".join(chunks),
                        "thoughts": "".join(thoughts),
                    }
                    self.last_error = None
                    self._log(f"prompt-done id={req_id} text={len(result['text'])}")
                    return result
                if msg.get("method") != "session/update":
                    continue
                update = (msg.get("params") or {}).get("update") or {}
                kind = update.get("sessionUpdate")
                content = update.get("content") or {}
                piece = content.get("text") if isinstance(content, dict) else None
                if kind == "agent_message_chunk" and piece:
                    chunks.append(piece)
                elif kind == "agent_thought_chunk" and piece:
                    thoughts.append(piece)
                if on_update is not None:
                    event = normalize_update(update)
                    if event is not None:
                        try:
                            on_update(event)
                        except Exception as exc:
                            self._log(f"on-update-fail {exc}")
        except Exception as exc:
            self.last_error = str(exc)
            self._log(f"prompt-fail id={req_id} {exc}")
            with self.lock:
                if self.ws is ws:
                    self._drop()
            raise
