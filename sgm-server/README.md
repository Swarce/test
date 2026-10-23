# sgm-server — Skullgirls Mobile private server (single-player)

A from-scratch reimplementation of the Skullgirls Mobile (Android client **8.11.0**) game backend,
covering the online front end and the PvE missions. No multiplayer.

The protocol, data shapes and game formulas were recovered by reverse engineering the client
(see [`docs/PROTOCOL.md`](docs/PROTOCOL.md) and [`docs/ENDPOINTS.md`](docs/ENDPOINTS.md)). All
game design data (characters, acts, matches, enemy generators, loot tables, economy settings...)
ships inside the APK, so the server reads it from **your own copy of the client** — no game data
is included in this repository.

## What works

| Area | Status |
|---|---|
| Login | Guest (device + token resume) and email/password accounts; session cookies; clock sync |
| Profile / collection | Characters, moves, team, equip, locks, "new" flags, avatar, handle, tutorial state |
| Story mode | All campaign acts; generated opponents (same algorithm as the client's `CharacterGenerator`); fights with XP, coins, player XP, first-clear, match-challenge, chapter and act rewards |
| Daily events (LTAs) | 124 Limited-Time Acts rotating daily; tickets, entering, play limits, skip, first-clear and random drops |
| Progression | Skill trees (incl. buy-all and shortcuts), power-up/feeding with XP consumables, evolution, super abilities, move upgrades/selling, recycling, energy refills |
| Relics | Store with 33 evergreen relics, buy/open, owned relics |
| Daily challenges | Login, open relic, upgrade skill/move, win story/daily fights; threshold rewards |
| Everything else | Battle pass, offers, mail, chat, ads, guilds, friends: valid empty responses so menus open |

Not implemented: PvP (prize fights, rift battles, sync PvP), guilds/chat/friends, raid boss, maze
events, Mission Control (deployment definitions are server-side only), real-money purchases.
Unknown endpoints answer `200 {}` and are logged as `UNHANDLED`.

## Quick start

**On Android without a PC:** use [`sgm-manager`](../sgm-manager) — an app that imports the game
data from the installed game, runs this server on the phone, redirects only the game to it and
adds an account editor (currencies, fighters, items, progress). The rest of this section is
the PC setup.

Requires Python 3.10+ (server: standard library only).

1. **Extract the game content** from your client (XAPK, APK + split APKs, or an unpacked folder):
   ```sh
   pip install -r requirements-extract.txt
   python tools/extract_content.py "Skullgirls Fighting RPG_8.11.0.xapk" -o content
   ```
2. **Create a TLS certificate** for the API hostname:
   ```sh
   sh tools/make_cert.sh            # writes cert.pem / key.pem
   ```
3. **Run the server** (port 443 needs root/admin, or forward 443 → another port):
   ```sh
   python -m sgserver --content content --cert cert.pem --key key.pem --port 443
   ```
4. **Point the game at it** by resolving `skgm-prod-game-api.skullgirlsmobile.com` to your
   server's IP. Easiest: run the bundled DNS redirector and set the phone's Wi-Fi DNS to your PC
   (Wi-Fi → IP settings: Static → DNS 1; disable Android *Private DNS*):
   ```sh
   sudo python tools/dns_redirect.py --server-ip 192.168.1.50
   ```
   Alternatives: a router DNS override, a Pi-hole local DNS record, or `/etc/hosts` on a rooted
   device/emulator. No APK modification is needed: the client's certificate callback accepts the
   self-signed certificate. Only the API host is redirected — art/audio still download from the
   official CDN, so the first launch needs internet.

Then start the game and choose guest login. New players skip the tutorial and start with the
tutorial's final team (Peacock, Big Band, Cerebella), 50,000 coins, 500 theonite and 4 daily-event
tickets (all configurable).

## Configuration

`python -m sgserver --config config.json` merges your file over the defaults
(see [`config.example.json`](config.example.json)):

| key | default | meaning |
|---|---|---|
| `skip_tutorials` | `true` | mark all tutorials complete for new players |
| `starting_currencies` | coins 50k, theonite 500, 4 tickets | any currency name from `docs/PROTOCOL.md` |
| `extra_starting_characters` | `[]` | variant GUIDs to grant new players |
| `xp_rate`, `currency_rate` | `1.0` | multiply fight XP / coins |
| `player_xp_per_enemy_fs` | `0.25` | player XP per point of defeated enemy fighter score (estimate; see below) |
| `lta_events` / `lta_sets_per_day` | `rotate` / `3` | rotate N LTA sets per UTC day, or `all` |
| `store_gachas` | evergreen set | explicit list of relic GUIDs for the store |
| `auto_register_password_accounts` | `true` | create an account on first email/password login |

Other options: `--db sgserver.db` (SQLite save file), `--host`, `--port`, `-v` (log request and
response bodies — useful when something misbehaves).

## Accuracy notes

Values the client computes itself are ported exactly (XP ladders, stats, fighter score, feeding,
skill node prices, enemy skill-tree spending, fight XP/longshot). The official server's own rules
that never reach the client are approximated and marked as such in the code — mainly player XP
per fight, random-drop rates, the LTA schedule and relic selection.

## Development

```sh
SGM_CONTENT=content python -m unittest discover -s tests -v
```

`tests/client_sim.py` reproduces the client's request signing, cookies, Base64 bodies and
response-signature checks; `tests/test_flow.py` drives login, startup calls, story and event
fights, relics, progression and daily challenges end to end. Set `SGM_JAVA_CP` to the compiled
classes of `../sgm-manager` to run the same suite against its Java port, and use
`tests/parity.py` to compare the game formulas of both.

Layout:

```
sgserver/            server package (stdlib only)
  protocol.py        signatures, encoding, error envelope
  server.py          HTTP(S) server, routing, sessions
  content.py         extracted game data + campaign graph
  formulas.py        XP ladders, stats, fighter score (ported from the client)
  player.py          player document and *ServerData serialization
  enemies.py         opponent generation
  rewards.py         loot granting and reward payloads
  handlers/          endpoints: core, campaign, events, progression, store, challenges, frontend
tools/               extract_content.py, make_cert.sh, dns_redirect.py
docs/                PROTOCOL.md, ENDPOINTS.md
tests/               simulated client + end-to-end tests
```

## Legal

This is an interoperability project for personal, offline use with a copy of the game you own.
It contains no game assets or data; `tools/extract_content.py` reads them from your client at
install time. Don't redistribute extracted content, and don't use any of this against the
official servers.
