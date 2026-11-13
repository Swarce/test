# PairIP wall (b08) and the two surviving paths

Confidence tags: **[H]** high, **[M]** medium, **[?]** unverified. This note records the device-proven
end of the in-APK line and the grounded assessment of the two paths that remain. It supersedes the
"redirect vs. signature" open question in STATUS.

## 1. What the device proved

| build | change from stock | result |
|---|---|---|
| b02 | redirect + Java gates + re-sign | SIGSEGV `libpairipcore.so` `0x37b0c` at startup |
| b05 | + stop startup VM program | SIGSEGV `libpairipcore.so` `0x5ae70` as `libunity.so` loads |
| b06 | + skip DT_INIT | SIGILL in `libunity.so` (DT_INIT **decrypts** code; can't be skipped) |
| b07 | metadata pristine, DT_INIT running | still SIGSEGV `libpairipcore.so` `0x5ae70` → redirect **not** the trigger |
| **b08** | **stock bytes, merge + re-sign only** (all dex / metadata / .so byte-identical) | **SIGSEGV `libpairipcore.so` `0x37b0c`** |

**b08 is conclusive [H].** With only the signing key and the zip repack changed, PairIP's native
startup VM still crashes at the same `0x37b0c` it hit in b02. So **PairIP validates the signing
certificate in native code at startup, and any re-sign fails.** Every in-APK patch requires a re-sign,
so the "server bundled inside the APK" architecture is dead. The crash routine is a generic PairIP-VM
FNV-64 hash primitive used in 300+ places; the integrity policy lives in libpairipcore's *encrypted*
bytecode, not cleartext native code, so a static native patch to forge a pass is **low feasibility [H]**.

The patcher, the Rust/Python servers, and the content pipeline are unaffected — they are reused by both
paths below; only *delivery* changes.

## 2. Path A — pristine APK + no-root companion redirect  *(recommended)*

Never modify the game (PairIP stays satisfied, it boots as it does from Play). A no-root companion app
(Android `VpnService` / local DNS) routes **only** the game's hosts to a local server, which answers.

**Why TLS is not the blocker it first looks like [H for transport, M for accept-logic]:**
- The game API (`skgm-prod-game-api.skullgirlsmobile.com`, + dev/qa/test on `skgminternal.com`) uses
  **Mono `System.Net.HttpWebRequest` over IL2CPP managed sockets**, *not* UnityTLS/OkHttp and *not*
  `UnityWebRequest`. Android's Network Security Config is a Java-stack policy that this transport never
  consults (`docs/research/01-boot-redirect.md` [H]). So the "user CAs are excluded at targetSdk 36"
  analysis (true for the Java/Unity stack) **does not apply to the game-API transport.**
- The client installs its own cert check:
  `ServicePointManager.ServerCertificateValidationCallback = HttpsHelper.MyRemoteCertificateValidationCallback`
  in `SGInitialize`. Verified present in the real `global-metadata.dat` (string counts):
  `MyRemoteCertificateValidationCallback`×1 (adjacent to `sslPolicyErrors`, `HttpWebRequestWrapper`),
  `ServerCertificateValidationCallback`×4, `ServicePointManager`×7, `HttpsHelper`×3, `SGInitialize`×3,
  `X509VerificationFlags`×1, `AllFlags`×3, `HttpWebRequest`×11, `RemoteCertificateValidationCallback`×8.
  The pattern (`X509VerificationFlags.AllFlags`, which permits unknown roots) strongly indicates the
  callback rebuilds the chain and accepts a self-signed cert with no CA install and no root — **but the
  callback body itself is not yet reverse-engineered [?]**. No cert pinning exists in the client (no
  `sha256/` pins, no `CertificatePinner`, no embedded server cert) [H].

**Net:** a local server presenting a self-signed cert for the game-API host is *likely* accepted with no
root and no CA install. **Must be confirmed** by (a) RE of the callback body in `libil2cpp.so`, or (b)
one on-device run. Until then this is the main risk.

**Other requirements:**
- The title screen fetches `maintenanceMode.json` from the CDN (`skgm-prod-cdn.skullgirlsmobile.com`);
  if unreachable it shows **Error 867**. The companion must also answer the CDN host (an HTTP error is
  read as "no maintenance") [M]. `867`×9, `maintenanceMode`×5, `skgm-prod-cdn`×2 in metadata.
- **Fully offline** additionally needs art/audio asset bundles served locally (CDN interception) — this
  is roadmap M3.5 and **unbuilt**, so until then first launch still needs the real CDN for assets.
- Reaching a story fight uses the **Python reference server** (login→menu→fight, 10/10 tests); the
  Rust embedded/standalone server is menu-only until M3.
- Sideloaded-pristine first-run Google Play LVL / `GetIntegrityToken` behavior is unverified [?].

**Cost:** no root, no Knox trip, no re-sign, APK byte-pristine, fully reversible. Work = build/extend the
companion (the external `sgm-manager`, referenced by `reference-server/README.md`, already does the
"import data + run server on phone + redirect only the game" shape; it is **not in this repo**).

## 3. Path B — one-time rooted dump + strip PairIP  *(fallback, likely blocked on this unit)*

Root once, run the stock game, dump the **decrypted** `libil2cpp.so`/`libunity.so` from the live process,
strip PairIP (drop the `libpairipcore` load + undefined `ExecuteProgram` relocations, restore the stock
`Application`/`appComponentFactory`, keep DT_INIT neutralised once decrypted), rebuild a clean APK that
re-signs freely → the embedded-server design then works.

**Why it is probably not startable on this device [M]:**
- The unit is `SM-S901E`, board `taro` (**Snapdragon** S22, not Exynos), firmware `S901EXXUDHZCB`,
  **One UI 8 / Android 16**. OEM unlocking is reported removed on One UI 8 across regions → no unlock →
  no root. Must be checked on-device: Settings → Developer options → OEM unlocking (present / greyed?).
- Rooting trips the **permanent Knox efuse**: loss of Secure Folder, Samsung Wallet, secure banking,
  forced factory reset, voided warranty — irreversible.
- PairIP's native anti-root checks may stop the libs decrypting under Magisk even if root is obtained;
  a second/sacrificial device or arm64 emulator may be needed. Dumped `.so` are portable (byte-identical
  to the Play build), so a non-primary dump host works.
- New standalone ELF-surgery tooling is required (today `elf.py` is read-only; no dump tooling exists).

## 4. Recommendation

**Path A**, confidence **medium** (would be high once the cert-accept is confirmed). It is low-cost,
reversible, needs no root, and its building blocks largely exist. Path B is the fallback and is probably
blocked at step 1 on this phone. Immediate next step for A: **confirm the cert-accept** — either RE the
`MyRemoteCertificateValidationCallback` body in `libil2cpp.so` (no device needed), or one on-device run
of the pristine app against the reference server behind a self-signed redirect.

**Open decision for the owner:** the surviving path is a no-root companion that redirects the game and
runs the server on-phone — which is what the previously-set-aside `sgm-manager` already does. Whether to
revisit `sgm-manager`, build a fresh minimal companion, or handle the redirect another way is the owner's
call (see STATUS).
