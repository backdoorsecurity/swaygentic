# Leo BYOM → Grok (Phase 1)

Brave Leo speaks **OpenAI Chat Completions** at `/v1/chat/completions`. Point it at xAI.

## Prerequisites

1. Brave Nightly installed (`brave-browser-nightly --version`)
2. Personal API key from [console.x.ai](https://console.x.ai/)
3. Confirm the API works:

```bash
export XAI_API_KEY=xai-...
./scripts/smoke_xai.sh
```

## Configure Leo

1. Open Nightly → `brave://settings/leo-ai`
2. Bring Your Own Model → add a row:
   - **Label:** Grok (pretty name; anything)
   - **Model request name:** exact API id, e.g. `grok-4.6`  
     (confirm with `curl -sS https://api.x.ai/v1/models -H "Authorization: Bearer $XAI_API_KEY"`)
   - **Endpoint URL:** `https://api.x.ai/v1/chat/completions`
   - **API key:** your `XAI_API_KEY` (Leo stores it locally and sends `Authorization: Bearer <key>`)
3. Select that model in the Leo sidebar and ask for a page summary

## If Leo shows a generic network error but curl works

Leo often sends `temperature` / sampling fields some models reject (HTTP 400 → vague UI error).

```bash
./scripts/run_leo_proxy.sh
```

Then set Leo’s endpoint to:

```text
http://127.0.0.1:8787/v1/chat/completions
```

The proxy listens on loopback only, strips known-bad fields, and forwards to xAI. You can still put the key in Leo; the proxy also accepts `XAI_API_KEY` / `run/xai.env` if Authorization is missing.

## Privacy notes

- BYOM traffic goes **directly** to xAI. Brave’s Leo privacy proxy does not wrap it.
- Page text + prompt leave the machine.
- Do not put `XAI_API_KEY` in renderer JS, an unpacked extension, or a page.

## Agentic browsing (optional, Nightly)

Flag: `brave://flags/#brave-ai-chat-agent-profile`  
Use an isolated profile. Expect prompt injection. Never enable agent tools against banking / password-manager origins in v1.
