# Contributing

## Legal rules (non-negotiable)

- **No game files in the repo, ever**: no APK, XAPK, split, dex, `.so`, `global-metadata.dat`,
  decompiled code, extracted data, textures, audio or keys. `.gitignore` blocks the common ones;
  check `git status` anyway.
- Tests that need the real game read **your local copy** through environment variables and skip
  without them:
  - `SGMO_APK`: your XAPK, `.apks`, or a folder with `base.apk` and the split APKs
  - `SGMO_CONTENT`: a folder produced by `tools/content-gen/extract_content.py`
  - `SGMO_BUILD_TOOLS`: optional Android build-tools folder, for an independent `apksigner verify`
- The project never hosts, links or attaches game data. Players patch their own copy.
- No real payments, no tracking. Analytics, ads and crash-report endpoints are accepted and dropped.
- Use real user data only from logs a player chose to send; use synthetic data everywhere else.

## Patch rules

- One patch per concern: a name, a one-line description, an on/off switch and tests.
- Fingerprint every patch site against the verified build. On a mismatch, a required site makes
  the patcher refuse; an optional site is skipped and the game keeps its own code there.
- Every removal (debloat) records its reason in the patch report.
- Verify every output: alignment, v1/v2/v3 signatures, certificate digest, the patched methods
  decoded back, and the patch report.
- Never claim something is fixed before a device log shows it. Write "expected; to be confirmed on
  a device".

## Code rules

- See `docs/MODULES.md` for language levels. The patcher and the reference server use the Python
  standard library only.
- `scripts/build.sh` must pass before a PR: it builds everything and runs every suite your
  environment variables allow.
- Each build has a number, a `wip/bNN` branch, a "Build NN" section in `docs/STATUS.md`, and one
  PR that follows `.github/pull_request_template.md`.
