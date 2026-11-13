# Design: the embedded server (M2)

Status: implemented in build b02. The offline server runs **inside the game's own process**, so no
PC is needed. The game still reaches it at `http://127.0.0.1:47820` (the `redirect-api` patch), but
the server answering there is now a native library in the APK.

## Shape

```
server/                     Rust workspace (one source, two hosts + the core)
  core/     sgm-core         protocol, HTTP, content, formulas, player, storage, routing, handlers
  standalone/ sgm-standalone the core as a PC binary (dev loop + the parity oracle harness)
  embedded/   libsgmserver.so the core as a JNI .so loaded into the game
```

The core is a faithful port of `reference-server` (the Python server), gated against it by
`reference-server/tests/test_flow.py` through `SGMO_SERVER_CMD`. SHA-256 and Base64 are implemented
in-crate, storage is file-based, and the HTTP server is std-only, so the `.so` needs nothing but
Bionic + `liblog` (no OpenSSL, no SQLite).

## Bootstrap (how the server starts, and why this way)

PairIP owns `Application` and the manifest's `appComponentFactory`, and its native VM cannot be
removed, so we do **not** try to be the application class. Instead:

1. The patcher adds a tiny class `sgm.offline.Boot` in its own dex (`classes<N>.dex`):
   `start()` calls `System.loadLibrary("sgmserver")` then the native `nativeStart()`.
2. The patcher injects one `invoke-static {}, Lsgm/offline/Boot;->start()V` at the **top** of
   `com.pairip.application.Application.attachBaseContext` (fingerprinted; the rest of the method and
   every other method in the dex is proven unchanged).
3. `libsgmserver.so`'s `Java_sgm_offline_Boot_nativeStart` derives what it needs from `/proc`
   (no Android `Context` crosses JNI): the package from `/proc/self/cmdline`, the running APK path
   from `/proc/self/maps` (the mapped `base.apk`), and a writable data dir
   `/data/data/<pkg>/files/sgm`. It then spawns a thread and returns at once.
4. The server thread reads the bundled content straight out of the APK
   (`assets/sgm-content.json`, stored uncompressed — no extraction to disk) and serves
   `127.0.0.1:47820`.

`attachBaseContext` runs very early in app startup, before the game's networking, so the server is
up by the time the client logs in. There is a small start-race (bind vs first request); the client
retries, and a device log will show if it matters.

## What the patcher bundles (the `embed-server` patch)

| Added to the APK | Why |
|---|---|
| `lib/arm64-v8a/libsgmserver.so` | the server (built from `server/` for `aarch64-linux-android`, API 24) |
| `assets/sgm-content.json` (**stored**) | the game content, merged into one JSON, read in place from the APK |
| `classes<N>.dex` | the `sgm.offline.Boot` class |
| an injected call in `Application.attachBaseContext` | starts `Boot` as the app boots |

`embed-server` is off by default; it needs `--server-so` (the built `.so`) and `--content-json`
(from `tools/content-gen/extract_content.py --combined`). It composes with the b01 patches:
`merge-splits`, `redirect-api` (now pointing at the in-process server) and `pairip-neutralize` all
still run.

## Saves

The server writes under `/data/data/<pkg>/files/sgm` (the app's private, writable storage): one JSON
document per player plus small device/session/account index files, written atomically. This is the
offline save; M3 adds versioning, rotating backups and export/import.

## Constraints and risks (to be confirmed on a device)

- **Boot past PairIP's native VM is still unproven** — the same open question as b01. If the app does
  not launch, that is the native check, and b02's device log is how we find out.
- Content is held in memory (serde `Value`s); the combined JSON is ~30 MB on disk and more parsed.
  Fine on an 8 GB S22; M3 can make loading lazy/indexed.
- Content is bundled **stored**, so the APK grows by ~30 MB. Deflate would shrink it but needs an
  inflate in the `.so`; deferred.
- Only the startup / main-menu endpoints are fully ported; fights, events, store, progression and
  challenges return valid empty shapes until the handler port lands (M3+).
