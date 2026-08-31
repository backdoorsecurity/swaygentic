#!/usr/bin/env python3
"""Leo BYOM → xAI Chat Completions compatibility proxy.

Listens on loopback only. Forwards POST /v1/chat/completions to
https://api.x.ai/v1/chat/completions, stripping or rewriting fields that
make some xAI models return 400 (symptom in Leo: generic network error).

Usage:
  export XAI_API_KEY=...
  python3 proxy/leo_proxy.py
  # or: scripts/run_leo_proxy.sh
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

UPSTREAM = "https://api.x.ai/v1/chat/completions"

# Fields Leo may send that some xAI models reject. Drop unless overridden.
STRIP_KEYS = {
    "temperature",
    "top_p",
    "presence_penalty",
    "frequency_penalty",
    "logit_bias",
    "user",
}


def load_key() -> str:
    key = os.environ.get("XAI_API_KEY", "").strip()
    if key:
        return key
    run_file = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "run",
        "xai.env",
    )
    if os.path.isfile(run_file):
        with open(run_file, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("XAI_API_KEY="):
                    return line.split("=", 1)[1].strip().strip('"').strip("'")
    return ""


def rewrite_body(raw: bytes) -> bytes:
    try:
        data: Any = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return raw
    if not isinstance(data, dict):
        return raw
    for k in list(STRIP_KEYS):
        data.pop(k, None)
    # Keep stream as Leo sent it; default false if missing.
    return json.dumps(data).encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    server_version = "leo-xai-proxy/0.1"

    def log_message(self, fmt: str, *args: Any) -> None:
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _send(self, code: int, body: bytes, content_type: str = "application/json") -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path in ("/", "/healthz"):
            self._send(200, b'{"ok":true,"upstream":"https://api.x.ai/v1/chat/completions"}')
            return
        self._send(404, b'{"error":"not found"}')

    def do_POST(self) -> None:  # noqa: N802
        if not self.path.startswith("/v1/chat/completions"):
            self._send(404, b'{"error":"only POST /v1/chat/completions is supported"}')
            return

        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length else b"{}"
        body = rewrite_body(raw)

        key = load_key()
        auth = self.headers.get("Authorization", "").strip()
        if not auth and key:
            auth = f"Bearer {key}"
        # Leo already sends Bearer <key>. Do not double-prefix.
        if auth and not auth.lower().startswith("bearer ") and key:
            auth = f"Bearer {key}"

        if not auth:
            self._send(
                401,
                b'{"error":"missing Authorization and XAI_API_KEY"}',
            )
            return

        req = urllib.request.Request(
            UPSTREAM,
            data=body,
            method="POST",
            headers={
                "Authorization": auth,
                "Content-Type": "application/json",
                "Accept": self.headers.get("Accept", "application/json"),
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=300) as resp:
                # Stream SSE / chunked as opaque bytes.
                content_type = resp.headers.get("Content-Type", "application/json")
                self.send_response(resp.status)
                self.send_header("Content-Type", content_type)
                self.send_header("Cache-Control", "no-store")
                # Do not set Content-Length; stream.
                self.end_headers()
                while True:
                    chunk = resp.read(8192)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
        except urllib.error.HTTPError as e:
            err_body = e.read()
            self._send(e.code, err_body or b'{"error":"upstream error"}')
        except urllib.error.URLError as e:
            msg = json.dumps({"error": f"upstream unreachable: {e.reason}"}).encode()
            self._send(502, msg)


def main() -> int:
    p = argparse.ArgumentParser(description="Leo → xAI Chat Completions proxy")
    p.add_argument("--host", default=os.environ.get("LEO_PROXY_HOST", "127.0.0.1"))
    p.add_argument("--port", type=int, default=int(os.environ.get("LEO_PROXY_PORT", "8787")))
    args = p.parse_args()

    if args.host not in ("127.0.0.1", "localhost", "::1"):
        print("Refusing non-loopback host. Pass an explicit local address only.", file=sys.stderr)
        return 2

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"leo proxy listening on http://{args.host}:{args.port}", flush=True)
    print(f"point Leo BYOM endpoint at http://{args.host}:{args.port}/v1/chat/completions", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nshutting down", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
