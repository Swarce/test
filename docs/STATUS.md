# Status

Newest build first. Each build: what changed, test counts, a device checklist, what is open.

## Build 07 (diagnostic) — isolate what PairIP's DT_INIT checks

**Device finding (b06):** the DT_INIT patch removed the `libpairipcore.so` crash, but the app then
died with **SIGILL** deep in `libunity.so` during `call_constructors` (the `INIT_ARRAY`), reached via
`UnityPlayer.loadNative`. Static analysis of the crash site showed the bytes there are **encrypted**
(not valid code, not zero). So PairIP's DT_INIT `ExecuteProgram` **decrypts selected functions** at
load — it is not integrity-only — and neutralising it leaves those functions encrypted, so the first
call into one executes garbage (SIGILL). **Conclusion: DT_INIT cannot be skipped; it must run.** In
b05 (DT_INIT running) it crashed *during* its work because our changes fail its check.

**b07 is a diagnostic, not a fix.** It keeps `global-metadata.dat` byte-identical to the original (no
`redirect-api`) and lets DT_INIT run (no `native-patch`), with the dex stubs, provider removal and
re-sign still in place. The result tells us what DT_INIT checks:
- **Boots / past the `libunity` SIGILL** → DT_INIT tolerates the dex/manifest/signature changes, and
  the metadata redirect was the trigger → redirect a different way (a native `connect()`/`getaddrinfo`
  hook in the embedded server, metadata left pristine).
- **Crashes again in `libpairipcore.so`** → DT_INIT checks the dex, manifest or the signature; the
  signature is re-signed and can't match, so that path needs either decrypted-library dumping (root)
  or patching the native signature comparison.

`native-patch` is now documented as decryption-breaking and is not in the default flow.

## Build 06 — native patch: PairIP's per-library integrity DT_INIT

**Device finding (b05):** boot gets much further — our embedded server starts, the window is
created, and Unity's `libmain.so` loads — then SIGSEGV in `libpairipcore.so` at a *new* offset
(`0x5ae70`), as the Unity native libraries load; and the app is bounced to the Play Store for
"license verification" (PairIP's native tamper response). This is the second native entry point the
research predicted: each of `libil2cpp.so`, `libunity.so` and `libnative-googlesignin.so` carries a
PairIP-injected `DT_INIT` whose whole body is `ExecuteProgram("<program>")`, run when the library
loads. On a repackaged APK it crashes / redirects.

**Changed (b06)**
- `native-patch` (off by default): for each of the three libraries, make `DT_INIT` return
  immediately — keep the leading `bti c`, write a `ret` right after — so `ExecuteProgram` is never
  called. The libraries' own `INIT_ARRAY` constructors and `ExecuteProgram` itself are untouched.
  Each `DT_INIT` is fingerprinted. A minimal stdlib ELF reader (`elf.py`) locates `DT_INIT`.
- `sites/266.json` gains the three DT_INIT fingerprints.
- The Play Store "license" redirect shares this native-integrity root (the Java `checkLicense` is
  already stubbed and `LicenseContentProvider` is not in the manifest), so it should clear too.

**Tests:** 109 patcher tests; apksigner v2+v3; zipalign; all three DT_INITs verified `bti c; ret` in
the output and the libraries re-parse as valid ELF.

**Device checklist**
- [ ] Gets past the `libpairipcore.so` crash as Unity loads (no SIGSEGV).
- [ ] No Play Store "license verification" redirect.
- [ ] Reaches the main menu; `adb logcat -s sgm-offline` shows the server answering.

**Open:** if a later crash appears inside the game (IL2CPP) rather than PairIP, that's a new class of
issue; the log will show it.

## Build 03 — native anti-tamper: experiment 1 (stop the startup VM program)

**Device finding (b02, Galaxy S22 / Android 16):** the app crashes at launch with SIGSEGV
(SEGV_ACCERR) **inside `libpairipcore.so`** (`pc 0x37b0c`), ~2 s in, right after the library loads
from `classes2.dex`, and loops. This is PairIP's native startup integrity program detecting the
repackage (re-sign + modified `classes2.dex`/metadata) — the open question from b01/b02, now
answered. It happens in `…CoreComponentFactory.<clinit> → StartupLauncher.launch()`, **before** our
`Boot` hook, so the embedded server never ran. The same check would stop b01 too.

**Changed (b03)**
- `pairip-bootstrap-stubs` patch (off by default): stub `StartupLauncher.launch()` → return-void, so
  PairIP's startup VM program never runs. Fingerprinted; `libpairipcore.so` and the encrypted assets
  are untouched. `sites/266.json` gains the `launch()` fingerprint.

**Experiment:** b03 = b02 + `pairip-bootstrap-stubs`. Two possible outcomes on-device: (a) it boots
further / to the menu (the startup program was the only early anti-tamper), or (b) it crashes later
in `libil2cpp.so`'s `DT_INIT` (a second native program `ExecuteProgram("EE9dHUAG90EJYLZv")`), which
would need a native patch — the next step per the owner's decision.

**Device checklist**
- [ ] App gets past the `libpairipcore.so` startup crash.
- [ ] If it crashes, capture the new tombstone: is it still `libpairipcore.so`, or now `libil2cpp.so`?
- [ ] `adb logcat -s sgm-offline` — does the embedded server start now?


## Build 02 — the embedded server (no PC needed)

**Goal:** run the offline server inside the game's own process, so the patched APK reaches the main
menu with no network and no PC.

**Changed**
- `server/`: a Rust workspace porting `reference-server` — `core` (protocol, a std-only HTTP server,
  content loader, float32 game math, the player document + serialization, loot, file storage,
  routing, the login/startup handlers), `standalone` (the PC binary + the parity oracle harness),
  and `embedded` (`libsgmserver.so`).
- Embedded host: a JNI `nativeStart` derives the APK path and a writable data dir from `/proc`, reads
  the bundled content straight out of the APK, and serves `127.0.0.1:47820` from a background thread.
- Patcher `embed-server` patch: bundles `libsgmserver.so`, `assets/sgm-content.json` (stored), a
  `Boot` dex, and injects `Boot.start()` at the top of `Application.attachBaseContext` (fingerprinted).
- Java helper: `make-boot-dex` and `dex-inject-call`. Content extractor: `--combined`.
- Docs: `docs/design/embedded-server.md`.

**Tests**
- Rust core: `cargo build` (host + aarch64-linux-android), unit tests, and the Python conformance
  suite's login/startup/session/unknown/signature tests pass against the Rust standalone **and**
  against content read from a stored APK entry.
- Patcher: 107 tests. The `embed-server` end-to-end test patches the real copy and checks the APK is
  self-contained (server `.so`, stored content, Boot dex, the injected call), signs (v2+v3), aligns,
  and that `classes2.dex` changed only in the injected method and the two PairIP gates.

**Device checklist (Galaxy S22, Android 16) — to be confirmed on a device**
- [ ] Patched APK installs over an uninstalled Play copy.
- [ ] App launches (PairIP's native VM does not reject the re-signed APK).
- [ ] `adb logcat -s sgm-offline` shows the server starting (apk/data/port).
- [ ] Guest login reaches the main menu **with the phone in airplane mode** (no PC, no network).
- [ ] One story fight is attempted (full fight logic is M3; the menu is the b02 goal).

**Open**
- The PairIP native-boot question is still the gate (same as b01).
- Action endpoints (fights, events, store, progression, challenges) return empty shapes; the full
  handler port is M3+.
- Content is bundled stored (~30 MB) and held in memory; optimisation is deferred.


## Build 01 — patcher foundations and the first patched APK

**Goal:** turn the player's own 8.12.0 copy into one merged, re-signed APK whose game API points at
`http://127.0.0.1:47820`, with PairIP's Java resign/license gates neutralised; reach the main menu
against the reference server over `adb reverse`.

**Changed**
- Repo restructured for the offline project (reference server, patcher, content-gen, docs).
- Patcher library: input loading (XAPK/.apks/folder/base+splits), raw-copy aligned ZIP writer,
  binary-XML and IL2CPP-metadata editors, a Java helper (dexlib2 + apksig) for dex
  fingerprint/patch/compare and v1+v2+v3 signing, signing-key management, the patch report.
- Patches: `merge-splits`, `redirect-api` (prod + Dev/QA/Staging/LiveDebug), `pairip-neutralize`
  (Java-only: `SignatureCheck.verifyIntegrity` and `LicenseClient.checkLicense` → return-void).
- Research notes 01/07/09; design doc; docs set; `scripts/build.sh`.

**Tests** (`scripts/build.sh`, all green)
- patcher: **104** tests. Without `SGMO_APK`: 21 real-binary tests skip. With it (and the venv's
  androguard): all 104 pass.
- reference server end-to-end: **10/10** against content extracted from the 8.12.0 copy.
- Java helper self-test: OK (stubs, dex-compare, required-mismatch, stub-fit, fingerprint-required).
- Dex `rawSha256` matches androguard on all **190,812** base-dex methods; a no-op `DexPool` rewrite of
  every base dex compares equal.
- **End-to-end on the real copy:** the patched APK passes Google `apksigner` (v2+v3) and
  `zipalign -P 16`; the game-API literal reads `http://127.0.0.1:47820` and the CDN literal is
  unchanged; `manifest@requiredSplitTypes` is cleared; **classes2.dex differs from the input only in
  the two stubbed PairIP gates and every other dex is byte-identical.**

**Device checklist (Galaxy S22, Android 16) — to be confirmed on a device**
- [ ] Patched APK installs over an uninstalled Play copy.
- [ ] App launches (PairIP native VM does not reject the re-signed APK).
- [ ] Guest login reaches the main menu against the reference server.
- [ ] One story fight completes with rewards.
- [ ] No native abort near the il2cpp/unity `ExecuteProgram` sites in logcat.

**Open**
- The PairIP native component is untouched and unproven against a resign — this build's device run is
  the test. If it rejects the APK, b01b adds a native patch.
- CDN still points at the official server (needs internet on first launch).
