# Modules and the rules for shared code

## The seam

```
server/Core + server/Services/*      one server core (Rust, from M2), shared by both hosts
hosts/embedded                       lib<...>server.so inside the patched APK (M2)
hosts/standalone                     the same core as a PC process (dev loop)
reference-server/                    the Python server: b01's standalone host, then the oracle the
                                     Rust port is compared against (same requests, same answers)
patcher/                             APK in -> patched APK out (Python + a small Java helper)
tools/content-gen                    reads game data from the player's own copy
```

Until M2 the only server is `reference-server/`. Its request handlers are the behaviour the Rust
core must reproduce. `reference-server/tests/test_flow.py` can drive any implementation through
`SGMO_SERVER_CMD`, so the same suite will gate the port.

## Language and toolchain levels (pinned on day one)

| Code | Level | Why |
|---|---|---|
| Server core and services (from M2) | Rust stable, edition 2021, MSRV pinned in `rust-toolchain.toml` when the crate is created; `std` plus a short audited crate list | must build both `aarch64-linux-android` (API 24, the game's `minSdkVersion`) and the PC targets from one source |
| Embedded host | as above, plus JNI; no C++ runtime, no global constructors that do work | runs inside the game's process before Unity starts |
| `patcher/sgmo_patcher` | Python 3.10+, **standard library only** | players run it; no `pip install` |
| `patcher/java` helper | Java 17 source/target; dependencies only from `patcher/java/deps.lock` (SHA-256 pinned) | `dexlib2` and `apksig` are Google's own dex and signing libraries |
| `tools/content-gen` | Python 3.10+ with `UnityPy` and `TypeTreeGeneratorAPI` | reading Unity serialized data |
| `reference-server` | Python 3.10+, standard library only | unchanged from the original project |

The game is IL2CPP, so nothing we ship runs inside a managed runtime: there is no "C# 7.3"-style
constraint. The constraint that matters instead is the Android NDK target: **arm64-v8a only**
(the game ships no other ABI), API level 24, Bionic libc, and 16 KiB-page-compatible ELF
alignment for anything we add to `lib/`.

## Rules for shared code

- No game data in the repo, ever (see `CONTRIBUTING.md`). Fingerprints, names, offsets and short
  identifiers as evidence are fine.
- Server code never blocks the game thread: every call from the game into our code goes through a
  latch that falls back to the game's own path if our module is missing or throws.
- The server binds to `127.0.0.1` only and logs the port it uses.
- Analytics, ads and crash-report traffic is accepted and dropped; it never blocks the game.
- Every patch to the game is fingerprinted against the verified build (`patcher/sgmo_patcher/sites/`).
