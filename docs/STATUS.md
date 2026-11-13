# Status

Newest build first. Each build: what changed, test counts, a device checklist, what is open.

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

**Tests** (filled as the build completes)
- patcher: unit + real-binary (`SGMO_APK`) — see the PR.
- reference server end-to-end: 10/10 against content extracted from the 8.12.0 copy.
- Java helper self-test: OK.
- Dex fingerprints match androguard on all 190,812 base-dex methods; a no-op dex rewrite of every
  base dex compares equal.

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
