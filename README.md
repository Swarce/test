# sgm-offline — Skullgirls Mobile, offline forever

Make **Skullgirls Mobile** (Android, `com.autumn.skullgirls`, 8.12.0) fully playable with no network,
for keeps. The player patches **their own** copy of the game; a server answers every request the
game makes. No multiplayer.

> **Status: early. Build b01.** The server runs on a PC and the phone reaches it over USB
> (`adb reverse`); the goal of b01 is the first patched, re-signed APK and the first device run. The
> later builds move the server inside the APK and add saves, economy, live-ops and a local asset
> store. See [`docs/ROADMAP.md`](docs/ROADMAP.md) and [`docs/STATUS.md`](docs/STATUS.md).

This repository contains **no game files**. The tools read what they need from your own installed
copy. Nothing here is distributed pre-patched.

## How it works

- The game is Unity **IL2CPP** (arm64). It talks to a game-API host whose URL is a plain string in
  the game's metadata.
- The **patcher** merges your APK's ABI split into the base, rewrites that URL to
  `http://127.0.0.1:47820`, neutralises Google PairIP's Java signature/license checks, and re-signs
  the result with your own key — every change fingerprinted against the known 8.12.0 build and listed
  in a report.
- The **server** (in b01, the Python reference server on your PC) answers login, profile, story mode,
  daily events, progression, relics and daily challenges.

## Getting started

- **Players:** [`docs/PLAYER-SETUP.md`](docs/PLAYER-SETUP.md).
- **Developers:** [`docs/DEV-LOOP.md`](docs/DEV-LOOP.md); architecture in
  [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md); how the patcher works in
  [`docs/design/patcher.md`](docs/design/patcher.md); the protocol in
  [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

```bash
scripts/build.sh        # builds the Java helper and runs every suite your env vars allow
```

## Layout

```
patcher/            your copy -> one offline, re-signed APK (Python + a small Java helper)
reference-server/   the Python game server (b01's host; the oracle for the later native port)
tools/content-gen/  reads the game data from your own copy
docs/               research, design, protocol, status, player and developer guides
scripts/build.sh    build + test everything
```

## Legal

An interoperability project for personal, offline use with a copy of the game you own. It contains
no game assets or data, and does not help you obtain the game. Don't redistribute extracted content,
and don't point any of this at the official servers. See [`SECURITY.md`](SECURITY.md) and
[`CONTRIBUTING.md`](CONTRIBUTING.md).
