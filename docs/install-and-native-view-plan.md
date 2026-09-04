# Phased plan — system installer + native VIEW VNC

**Status:** **Current project goal (2026-09-03)** — finish one-shot host installer (`./install.sh` at repo root after clone). Test on a dedicated account (login session, not `sudo -u`). Phase 0–1 skeleton exists; Phase 2 ADB later; native VIEW APK already on device (0.9.4).  
**Date:** 2026-09-03  
**Decision:** Embed a **thin VNC viewer in swaygentrc** (VIEW tab). Do **not** fork Haven for product VIEW. Haven remains optional/manual for operators who already use it.  
**Installer:** `scripts/install.sh` → `scripts/install_swaygentic_system.sh` (see `swaygentrc/SWAYGENTRC.md`).

Related: `docs/haven-companion.md` (legacy option A), `docs/phone-view-display.md`, `swaygentrc/README.md`.

---

## Goals

1. **One-shot (or few-shot) host install** for a fresh user account / machine: binaries, config, user services, smokes.
2. **Phone path:** optional ADB install of swaygentrc APK + push credentials/certs; else print APK + credentials paths.
3. **VIEW:** in-app VNC to guest `wayvnc` (Tailscale), touch → pointer/click, no app switch, no Haven required.

## Non-goals (this plan)

- Forking Brave / Chromium.
- Merging Haven source into this repo.
- Building a second APK solely for VNC (rejected — embed in swaygentrc).
- Haven chroot / on-device Wayland / MCP features.
- Opening hypervisor VNC on `0.0.0.0`.

---

## Haven: fork or not?

| Option | Verdict |
| --- | --- |
| **Fork Haven** | Unnecessary for product if VIEW is native. AGPL + huge NDK surface + features we do not need. |
| **Local patch only** | Fine as a **transitional** fallback while native VIEW lands; keep `docs/haven-companion.md` + prebuilt APK. |
| **Own VNC in swaygentrc** | **Chosen.** Smaller UX, one APK, installer only ships `app.swaygentrc`. |

Deprecate **OPEN HAVEN** after native VIEW is usable; keep Intent as optional advanced escape for one release if needed.

---

## Phase 0 — Inventory & contracts (½ day)

Document and freeze:

- Binaries: `swaygentic`, `swaygentic-unsafe`, `mcp/*.sh`, Leo proxy (optional).
- User units: `swaygentrc.service`, `wayvnc.service` (separate).
- Artifacts: `swaygentrc/app/swaygentrc_v*.apk`, credentials file shape (`url`, `token`, `vnc_host`, `vnc_port`, TLS bits if any).
- Cert story: what is “newly generated” today (HTTP TLS? Tailscale? none?) — list exact paths before coding ADB push.
- Preconditions: Brave Nightly, Node for brave-mcp, Wayland session, groups (`render`, `input`, …).

**Exit:** checklist in this doc or `docs/install-checklist.md` with paths filled from this VM.

---

## Phase 1 — Host installer skeleton (1–2 days)

`scripts/install_swaygentic_system.sh` (name TBD) orchestrates, with flags:

```text
--non-interactive
--skip-adb
--skip-wayvnc
--unsafe-default          # symlink preference; still install both launchers
--user NAME               # target account (default: current)
```

Steps (idempotent where possible):

1. Apt/deps check (document; optionally install `adb`, `wayvnc`, `jq`, …).
2. `./scripts/install_swaygentic.sh` → `~/.local/bin/swaygentic{,-unsafe}`.
3. Ensure repo layout + `.grok/config.toml` trust note (`grok --trust` / project MCP).
4. `run/xai.env` prompt if missing (do not overwrite).
5. Install user units from `swaygentrc/server/system/swaygentrc.service` + `scripts/wayvnc.service` (substitute `@HOME@` etc.).
6. `systemctl --user daemon-reload && enable --now swaygentrc wayvnc` (wayvnc optional via flag).
7. Generate/refresh `credentials.swaygentrc` via existing bootstrap.
8. Run `./scripts/smoke_swaygentrc.sh` (and a cheap MCP/Brave smoke if Brave present).

**Exit:** second local user can run the script and get healthy HTTP API + wayvnc listen on Tailscale `:5900`.

---

## Phase 2 — ADB phone path (1 day)

Prompt (default N in non-interactive unless `--adb`):

1. Detect `adb`; if missing, offer `apt install adb` (or print install hint).
2. `adb devices` — require one `device` (not unauthorized).
3. `adb install -r` swaygentrc APK (`swaygentrc/app/swaygentrc_v0.8.0.apk` or newest).
4. Uninstall legacy `app.grokrc` if present (prompt).
5. Push credentials: `adb push …/credentials.swaygentrc` to a documented phone path / use app import Intent if we add one.
6. **Cert icing:** if bootstrap emitted TLS/CA files the app trusts, `adb push` those too; document filenames. If none exist yet, skip with “no certs generated”.
7. If no ADB / no device: print absolute APK path + credentials path + short manual steps.

**Exit:** from a clean machine, “yes” to ADB leaves a phone that can paste/import credentials and talk to the guest.

---

## Phase 3 — Native VIEW VNC (MVP) (3–5 days)

Replace Haven Intent as the primary VIEW path.

### 3a — Library choice

Prefer a maintained Android VNC client library (RFB) over writing RFB from scratch. Evaluate license (Apache/MIT preferred) vs AGPL Haven. Spike: connect to `wayvnc` on Tailscale, paint framebuffer, send pointer events.

### 3b — UX

- VIEW tab: full-bleed remote surface (keep optional tiny JPEG stub only if useful offline).
- Touch → mouse move/click; basic scroll; reconnect; show `host:port` status.
- SYSTEM still edits `vnc_host` / `vnc_port`.
- Remove or demote **OPEN HAVEN** button.

### 3c — Server

- No change to chat/ACP.
- wayvnc remains the capture server; credentials already carry host/port.
- Do not require `/grok/frame` for primary VIEW.

**Exit:** phone VIEW shows live sway desktop and can click a terminal/Brave without leaving swaygentrc.

---

## Phase 4 — Hardening & polish (ongoing)

- TLS/auth for wayvnc if we ever leave Tailscale-only trust model.
- Cert generation story aligned with whatever the APK actually verifies.
- Multi-device ADB picker; `adb reverse` docs for USB-only labs.
- Remove Haven from default docs; keep companion doc as “unsupported fallback”.
- Installer test matrix: fresh user, no Tailscale, no Brave, ADB unauthorized.

---

## Suggested order of work

```text
Phase 0  inventory/certs
   ↓
Phase 1  host installer + services   ←  test on second user ASAP
   ↓
Phase 2  ADB + credentials (+ certs if any)
   ↓
Phase 3  native VIEW VNC MVP
   ↓
Phase 4  polish / Haven demotion
```

Installer-first lets you validate multi-user install while VIEW is still Haven/Intent.

---

## Open questions (resolve in Phase 0)

1. Exact cert files to push (paths + whether APK consumes them today).
2. VNC library license + min SDK vs current swaygentrc.
3. Should installer default to `swaygentic` (jailed) or also configure phone facade to allow `swaygentic-unsafe`?
4. USB-only: document `adb reverse` for HTTP API vs requiring Tailscale.

---

## Immediate next coding task (when approved)

Implement **Phase 1** skeleton: `scripts/install_swaygentic_system.sh` calling existing install/smoke scripts + user-unit enablement, with `--skip-adb` and a stub ADB section returning APK paths.

## Phase 0 output

See `docs/phase0-inventory-and-haven-notes.md`.
