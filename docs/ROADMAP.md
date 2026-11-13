# Roadmap

## Done

- **M0 — research & scaffolding.** Engine and redirect strategy decided from the client
  (IL2CPP arm64; rewrite metadata URL literals). Research notes 01 (redirect), 07 (integrity /
  PairIP), 09 (packaging & split merge). Repo restructured; docs set started; project rules pinned.
- **b01 patcher foundations.** zip I/O + input loader; binary-XML and metadata editors; the Java
  helper (dexlib2 + apksig) for dex fingerprinting/patching and v2+v3 signing; signing-key
  management. All with unit and real-binary tests.

## Next (this build, b01)

- The three patches (merge-splits, redirect-api, pairip-neutralize) wired into a `patch` CLI.
- `sites/266.json`: the verified-build fingerprints for 8.12.0.
- End-to-end: produce a patched, re-signed APK from the real copy and verify it (alignment,
  signatures, patched sites, untouched-elsewhere).
- Device run on the Galaxy S22 → first logs.

## Release checklist (1.0 — not yet)

- [ ] Guest login reaches the main menu on-device with no network (M2).
- [ ] Embedded server `.so` in the patched APK; no PC needed (M2).
- [ ] Saves: versioned player document, atomic writes, rotating backups, export/import (M3).
- [ ] Assets served from the player's own copy; no official CDN (M3.5).
- [ ] Economy, store, inbox, coupons from content (M4).
- [ ] Live ops schedule, quests, seasons; simulated-clock run survives time jumps (M5).
- [ ] Settings page, diagnostics, debloat, device-log hitch pass (M6).
- [ ] PvP/guild/chat entry points hidden or locally stubbed; no dead buttons.
- [ ] `scripts/build.sh` green; player and developer docs complete.
- [ ] Nothing claimed fixed without a device log.

## Later / open questions

- Does the PairIP **native** VM reject a re-signed/repackaged APK? (b01 device run answers this.)
- If it does: patch the native integrity check or the `DT_INIT`/`DT_NEEDED` path (a native patch,
  new fingerprinting).
- CDN redirect and a local asset store (import from the player's installed copy).
- Rift/prize fights and raid/maze against generated rivals — per-mode decision.
