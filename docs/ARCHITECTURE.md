# Architecture

## The game (Skullgirls Mobile 8.12.0, `com.autumn.skullgirls`, versionCode 266)

- **Engine:** Unity 2022.3, **IL2CPP**, **arm64-v8a only**. The C# game logic is compiled to native
  code in `lib/arm64-v8a/libil2cpp.so` (72 MB) with its type/string data in
  `assets/bin/Data/Managed/Metadata/global-metadata.dat` (version 31, unencrypted).
- **Shipped as an Android App Bundle**: a base APK plus a `config.arm64_v8a` split that carries the
  `lib/` native libraries. A player's XAPK (APKPure) or `.apks` (bundletool/SAI) holds both.
- **Wrapped by Google PairIP** (Play's anti-tamper + license service). The app class is
  `com.pairip.application.Application`; `libpairipcore.so` is a native VM that runs encrypted
  programs from `assets/<16-char>` blobs. PairIP has a **native** component that cannot be removed
  (see `docs/research/07-integrity.md`).
- **Networking** is Mono `HttpWebRequest` (managed sockets) to a game API host and a CDN host, both
  plain string literals in the metadata. The client's TLS callback accepts any certificate, and the
  managed socket path ignores Android's cleartext policy.

## What is dead offline

PvP (prize fights, rift battles, sync PvP), guilds, chat, friends, raid boss, maze, Mission Control,
real-money purchases, and every third-party SDK (Photon/zeuz multiplayer, PubNub chat, Unity
analytics/IAP, Aghanim webshop, Adjust, AppLovin, Facebook, Firebase, Helpshift, BidMachine, Vungle).
These are served empty / accepted-and-dropped by the server, hidden, or left to fail without blocking.

## The components we build

```
patcher/            turns the player's own copy into one offline-ready, re-signed APK
  sgmo_patcher/     Python (stdlib only): inputs, zip I/O, binary-XML + metadata editors, patches,
                    signing, report, CLI
  java/             a small Java 17 helper (Google's dexlib2 + apksig) for dex rewriting and signing
reference-server/   the Python game server: b01's standalone host, and the oracle for the Rust port
tools/content-gen/  reads the game data from the player's own copy (UnityPy)
docs/               research, design, protocol, status, player and developer guides
scripts/build.sh    builds everything and runs the suites the environment allows
```

The embedded server (a native `.so` inside the APK) and the asset/CDN service come in later builds
(M2, M3.5). Until then the game reaches the reference server on a PC over `adb reverse`.

## How the game reaches the server (b01)

The patcher rewrites the game-API host literals in `global-metadata.dat` to
`http://127.0.0.1:<port>` (default 47820) — prod and every fallback environment, because the client
defaults to Dev, not prod. No DNS change, no TLS, no native patching for the redirect. The phone
forwards that port to the PC with `adb reverse`. The CDN host is left pointing at the official CDN in
b01 (it is still alive); art and audio still download from it.

## Build constraints

See `docs/MODULES.md`. In short: the patcher and reference server are Python standard-library only;
the Java helper uses pinned Google libraries; anything added to the APK's `lib/` must be arm64-v8a,
API 24, and 16 KiB-page-safe. The game ships no ABI but arm64, so there is one target.
