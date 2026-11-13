# Design: the patcher (b01)

Status: design for build 01. Written before the code; update it when the code disagrees.

## Goal

Turn the player's own copy of Skullgirls Mobile 8.12.0 (an XAPK, an `.apks`, a folder, or
`base.apk` plus split APKs pulled from a phone) into **one** installable, re-signed APK in which:

- the game API base URL points to `http://127.0.0.1:<port>` (default 47820);
- Google PairIP (the Play anti-tamper and license wrapper) no longer runs;
- the ABI split is merged into the base, so a plain `adb install` works;
- every change is fingerprinted against the verified build, switchable, and listed in a patch report.

Non-goals for b01: the embedded server (M2), the CDN redirect (left to the official CDN while it
lives), SDK debloat beyond PairIP, content generation at patch time.

## Shape

```
patcher/
  sgmo_patcher/              Python 3.10+, standard library only
    __main__.py              CLI: patch | verify | inspect | keygen
    inputs.py                load XAPK / .apks / folder / base+splits -> ApkSet
    zipio.py                 raw zip reader + aligned writer (copies compressed bytes, no recompression)
    axml.py                  binary XML (AndroidManifest.xml) parser and in-place typed-value editor
    metadata.py              IL2CPP global-metadata.dat (v31) string-literal table, in-place rewrite
    dex.py                   wrapper around the Java helper's dex commands
    sign.py                  wrapper around the Java helper's sign/verify commands; keystore handling
    javahelper.py            builds/locates the helper jar (pinned, checksummed Maven deps)
    patches/                 one module per patch (see "Patches")
    report.py                patch report (JSON + text)
    sites/266.json           verified-build fingerprints for versionCode 266 (8.12.0)
  java/                      the helper (dexlib2 + apksig), built by scripts/build.sh or on first use
    deps.lock                Maven coordinates, URLs and SHA-256 of every jar
    src/sgmo/helper/*.java
  tests/                     unit tests on synthetic inputs (no game files)
tests/real/                  real-binary tests, skipped unless SGMO_APK is set
```

Why Python + a small Java helper: the content extractor (`tools/content-gen`) is already Python, the
player needs Python anyway, and the two jobs Python cannot do well — rewriting dex files and APK
Signature Scheme v2/v3 — are done by Google's own libraries (`dexlib2` from smali, `apksig` from
apksigner). The player needs Python 3.10+ and a JDK 17+; nothing from the Android SDK.

## Data flow

```
inputs.load(paths)                       -> ApkSet{base: ZipIn, splits: [ZipIn], package, versionCode}
sites = load sites/<versionCode>.json   (refuse unknown versions unless --force-unverified)
plan  = patches selected by defaults + --enable/--disable
for each patch in plan order: patch.apply(ctx)   ctx holds the mutable output entry map + report
zipio.write(entries, unsigned.apk)       aligned: stored entries 4 B, stored .so 16 KiB
sign.sign(unsigned.apk, out.apk, keystore)       v1 + v2 + v3
verify(out.apk)                          signature schemes, cert digest, fingerprints of the result
report.write(out.apk + ".report.json/.txt")
```

The output entry map is ordered (base order first, then merged split entries) and holds either a
raw copy of an input entry (compressed bytes and method preserved) or new bytes plus a method.

## The fingerprint rule

Every patch site carries the expected fingerprint from the verified build in `sites/266.json`:

| Site kind | Fingerprint |
|---|---|
| whole file (dex, metadata, manifest) | SHA-256 of the input entry's uncompressed bytes |
| dex method | SHA-256 of the method's raw `insns` bytes in the input dex (same as androguard `get_bc().get_raw()`) |
| metadata literal | the literal's exact UTF-8 bytes + table index |
| manifest attribute | element path + attribute name + current typed value |

If a **required** site does not match, the patcher stops before writing anything. If an
**optional** site does not match, that site is skipped, the game keeps its own code there, and the
report says so. Nothing half-patches silently.

## Patches (one per concern, each switchable)

| Name | Default | What | Required sites |
|---|---|---|---|
| `merge-splits` | on | copy the ABI split's `lib/` entries into the base; clear `manifest@requiredSplitTypes`; set meta-data `com.android.vending.splits.required` to false | yes |
| `strip-signatures` | on (always) | drop `META-INF/*.SF|*.RSA|*.EC|*.DSA|MANIFEST.MF` and `stamp-cert-sha256` | — |
| `redirect-api` | on | metadata literal `https://skgm-prod-game-api.skullgirlsmobile.com` -> `http://127.0.0.1:<port>` | yes |
| `redirect-cdn` | off in b01 | metadata literal `https://skgm-prod-cdn.skullgirlsmobile.com/` -> `http://127.0.0.1:<port>/cdn/` | yes (when on) |
| `pairip-neutralize` | on | rewrite PairIP's entry points so nothing loads `libpairipcore.so`, checks the signature or the Play license | yes |
| `pairip-guarded-stubs` | on | replace the 20 SDK methods whose bodies PairIP encrypted with safe stubs | optional per site |
| `debloat-pairip-files` | on | remove `libpairipcore.so` and the encrypted VM program assets (dead once PairIP is off) | — |

Each patch module exposes `NAME`, `DESCRIPTION` (one line, shown in the report), `DEFAULT_ON`,
and `apply(ctx) -> PatchResult{status: applied|skipped|refused, sites:[...], notes}`.

### Dex stubs (closed set)

| Kind | Body emitted |
|---|---|
| `RETURN_VOID` | `return-void` |
| `RETURN_NULL` | `const/4 v0, 0; return-object v0` (one extra register) |
| `SUPER_CALL` | `invoke-super/range {p0 .. pN}` on the same name/descriptor of the direct superclass; `return-void` |
| `SUPER_CALL_THEN_FINISH` | as `SUPER_CALL`, then `invoke-virtual {p0}, Landroid/app/Activity;->finish()V`; `return-void` |

The helper rebuilds only the touched methods. It then proves the rest of the dex is unchanged:
class set, superclasses, interfaces, fields, method signatures and flags, and an index-independent
hash of every other method body (opcodes and resolved references, try blocks) must match the input.

### Metadata rewrite

In place: write the new UTF-8 bytes at the literal's `dataIndex`, update its `length`, zero the rest
of the old range. Refuse if the new string is longer than the old one, or if any other literal's
byte range overlaps the old range. The checks then confirm that the file size, the header and every
other literal entry and byte are identical.

### Manifest edits

In place only (no chunk resizing): point a string attribute at an existing pool string (the
manifest already contains `""`) or flip a typed value. The edit must leave the file length and every
other byte unchanged; the checks decode the result and diff it against the input.

## Java helper commands

```
sgmo-helper dex-methods <in.dex>                       JSON lines: class, name, desc, flags, rawSha256, semSha256
sgmo-helper dex-patch   <in.dex> <out.dex> <spec.json>  spec: [{cls,name,desc,expectRawSha256,stub,required}]
                                                        prints JSON results; exit 2 if a required site mismatched
sgmo-helper dex-compare <a.dex> <b.dex> <exclude.json>  structural + semantic equality except excluded methods
sgmo-helper sign  --in <apk> --out <apk> --ks <p12> --ks-pass-file <file> --alias <alias>    v1+v2+v3
sgmo-helper verify <apk>                               JSON: v1/v2/v3 verified, signer cert SHA-256, errors
```

## Signing key

`python -m sgmo_patcher keygen` creates `~/.config/sgm-offline/sgm-offline-signing.p12` (RSA 4096,
validity 50 years) with a random password in `~/.config/sgm-offline/sgm-offline-signing.pass`
(mode 0600), using the JDK's `keytool`. Every build uses that key, so a new APK installs over the
old one and keeps the game's data. The key never enters the repo; `deliver/` is git-ignored.

## Verification of every output

1. zip: every stored entry 4-byte aligned, stored `.so` 16 KiB aligned, no duplicate names.
2. signatures: v1, v2, v3 verify (helper; plus Google's `apksigner verify` when `SGMO_BUILD_TOOLS` is set).
3. certificate digest equals the keystore's certificate.
4. fingerprints: every applied site re-read from the output shows the new value; every untouched
   dex method and metadata literal is identical to the input (see above).
5. patch report lists each patch, each site, expected vs actual fingerprint, and the result.

## Risks (to be confirmed on a device)

- PairIP may have checks we have not found (for example in native code). Expected: none. The
  device log will show it.
- Re-signing breaks Google sign-in and Play Integrity for this package. Guest login does not need them.
- The game may refuse a non-https base URL. Expected not (the URL is used as a plain string
  prefix). If it does, the fallback is https with a bundled certificate, which the client's
  certificate callback already accepts.
