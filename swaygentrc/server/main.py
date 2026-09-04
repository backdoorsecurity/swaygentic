import os
import sys
import threading

SERVER_DIR = os.path.dirname(os.path.abspath(__file__))
if SERVER_DIR not in sys.path:
    sys.path.insert(0, SERVER_DIR)

from system import bootstrap, config
from toolbox import swaygentic_wrap
from toolbox.acp_client import AcpClient
from toolbox.http_app import bind_server, listen_url, make_server


def main():
    grok_bin, secret, api_token = bootstrap.setup()
    acp = AcpClient(secret)
    servers = []
    for host in config.HTTP_HOSTS:
        try:
            if not servers:
                srv = make_server(
                    host, config.HTTP_PORT, grok_bin, secret, acp, api_token,
                )
            else:
                srv = bind_server(host, config.HTTP_PORT)
            servers.append((host, srv))
        except OSError as exc:
            print(f"bind failed on {host}:{config.HTTP_PORT}: {exc}")
    if not servers:
        print("no working listen address")
        return 1
    for host, _srv in servers:
        print(f"swaygentrc listening on {listen_url(host, config.HTTP_PORT)}")
    if config.TAILSCALE_DNS:
        print(
            f"swaygentrc magicdns: http://{config.TAILSCALE_DNS}:{config.HTTP_PORT}"
        )
    print(f"api token: {config.API_TOKEN_PATH}")
    print(f"phone credentials: {config.CREDENTIALS_PATH}")
    print("phone: upload run/credentials.swaygentrc in SYSTEM → API (url + token).")
    print(f"server root (agent cwd): {config.CHAT_CWD}")
    if not os.path.isdir(config.CHAT_CWD):
        print(f"WARNING: server root does not exist: {config.CHAT_CWD}")
    print(f"grok binary: {grok_bin}")
    print(f"agent bind: {config.GROK_BIND}")
    wrap = swaygentic_wrap.runner_path()
    if wrap:
        print(f"swaygentic wrap: {wrap} (agent + browser in jail)")
    else:
        print("swaygentic wrap: off — agent on host (attended mode)")
    mcp = swaygentic_wrap.mcp_servers()
    if mcp:
        print("mcp: " + ", ".join(s["name"] for s in mcp))
    else:
        print("mcp: (none from project config)")
    if config.GROK_EFFORT:
        print(f"grok effort: {config.GROK_EFFORT}")
    if config.GROK_MODEL:
        print(f"grok model: {config.GROK_MODEL}")
    if config.START_MESSAGE_PATH:
        if config.START_MESSAGE:
            print(f"start message: {config.START_MESSAGE_PATH}")
        else:
            print(f"start message missing: {config.START_MESSAGE_PATH}")
    for host, srv in servers[1:]:
        threading.Thread(
            target=srv.serve_forever,
            name=f"swaygentrc-{host}",
            daemon=True,
        ).start()
    try:
        servers[0][1].serve_forever()
    except KeyboardInterrupt:
        print("\nstopping swaygentrc")
        for _host, srv in servers:
            srv.shutdown()
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
