## Summary

<!-- Build number, what changed and why, in plain language. -->

## Testing

<!-- Suites run (with counts), which env vars were set (SGMO_APK, SGMO_CONTENT, SGMO_BUILD_TOOLS),
     and what still needs a device. -->

## Impact

<!-- What a player sees; save/format changes; patch sites added or changed; risks. -->

## Checklist

- [ ] No game files, extracted data or keys in the diff
- [ ] `scripts/build.sh` passes
- [ ] Every new patch has a name, a description, a switch, fingerprints and tests
- [ ] `docs/STATUS.md` has a "Build NN" section with a device checklist
- [ ] Nothing is called fixed without a device log
