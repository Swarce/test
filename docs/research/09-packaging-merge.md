# APK packaging and split merge (base + config.arm64_v8a -> one re-signed APK) — REVIEWED

Independently re-verified with my own AXML parser (`$S/scripts-review/ax.py`) and Python `zipfile`
local-header reader (`$S/scripts-review/zi.py`), plus `apksigner.jar` from
`$S/tools/android-16/lib/apksigner.jar`. Every patcher-critical claim below was re-checked from the
real bytes; corrections are flagged inline and summarized in "Review notes". Target: Galaxy S22 /
Android 16, targetSdk 36, minSdk 24, arm64-v8a only, `extractNativeLibs="true"`.

File identities (my hashes):
- base `com.autumn.skullgirls.apk` sha256 `b8e963af8ed8652e012504af7d074fc60704cb067977bf352633cce2349b97bb` (133,384,256 B)
- `config.arm64_v8a.apk` sha256 `8373cc68cfa5b46b72429e133cb5697309cd0d80d2cdfaa35660e144f7193a0e` (31,770,375 B)
- base `AndroidManifest.xml` sha256 `9e776c9cf417c2be02c719e42227d3eadd42c2b8a8eb7c54c2732290382469b1` (48,504 B)

## 1. Binary AndroidManifest.xml (base APK) — VERIFIED

- RES_XML type 0x0003, header 8, total 48504 bytes. (verified)
- String pool at offset 8, **count 302**, styleCount 0, flags **0x0 => UTF-16LE**, strings data at
  pool-offset 1236. (verified)
- Empty string `""` is present and is the **only** empty entry, at **index 52 (0x34)**. (verified:
  `EMPTY_STRING_INDICES==[52]`)
- Resource-ID map: **52 entries** (indices 0..51). `requiredSplitTypes`=0x0101064e (pool str 49),
  `splitTypes`=0x0101064f (pool str 50), `versionCode`=0x0101021b, `name`=0x01010003,
  `value`=0x01010024, `resource`=0x01010025. (verified)
- **No `isSplitRequired` (0x01010591) and no `isFeatureSplit`** in this manifest. (verified: neither
  resid nor string present)
- `uses-sdk`: minSdkVersion data 0x18 = **24**, targetSdkVersion data 0x24 = **36**. (verified)

AXML attribute struct = 20 bytes: ns(+0) nameIdx(+4) rawValueIdx(+8) size(+12,2) res0(+14,1)
dataType(+15,1) data(+16). For STRING-typed attrs the string index sits in BOTH rawValue(+8) and
data(+16). (verified against live attrs)

### Attributes that matter (confidence: HIGH — bytes verified)

| attribute | element chunkOff | attrOff | rawValIdx | dtype | data | in-place edit |
|---|---|---|---|---|---|---|
| `manifest@requiredSplitTypes` | 24160 | 24296 | 128 (`base__abi`) | STRING(0x03) | 0x80 | **YES** -> repoint to `""`(52) |
| `manifest@splitTypes` | 24160 | 24316 | 52 (`""`) | STRING(0x03) | 0x34 | already `""` (leave) |
| meta-data `com.android.vending.splits.required`@value | 47932 | 47988 | -1 | BOOL(0x12) | 0xffffffff | **YES** -> 0x00000000 |
| meta-data `com.android.vending.splits`@resource | 48232 | 48288 | -1 | REFERENCE(0x01) | 0x7f150003 | leave (harmless) |
| meta-data `com.android.vending.derived.apk.id`@value | 48332 | 48388 | -1 | INT_DEC(0x10) | 0x3 | leave (harmless) |
| meta-data `com.android.stamp.source`@value | 48032 | 48088 | 266 (`https://play.google.com/store`) | STRING(0x03) | 0x10a | leave (harmless) |
| meta-data `com.android.stamp.type`@value | 48132 | 48188 | 57 (`STAMP_TYPE_DISTRIBUTION_APK`) | STRING(0x03) | 0x39 | leave (harmless) |
| `application@android:name` | 28796 | 28892 | 249 (`com.pairip.application.Application`) | STRING(0x03) | 0xf9 | **NO** (see below) |

(Correction vs original note: `stamp.source`@value attrOff is **48088** with rawValIdx **266**, not
the "~48052 / play URL" placeholder. Verified.)

### Exact in-place byte edits (no chunk resizing) — VERIFIED

1. **Neutralize required-split check** — point `requiredSplitTypes` at empty string (idx 52):
   - offset **24304** (rawValue): `80 00 00 00` -> `34 00 00 00` (confirmed current bytes = `80000000`)
   - offset **24312** (typed data): `80 00 00 00` -> `34 00 00 00` (confirmed current bytes = `80000000`)
   - 8 bytes total; both must match. `base__abi`(str 128) becomes unreferenced but stays in the pool
     (no resize). This is the edit that fixes install.
2. **(Defense-in-depth) splits.required -> false**:
   - offset **48004** (typed data of the BOOL attr): `ff ff ff ff` -> `00 00 00 00`; dataType stays 0x12.
     (confirmed current bytes at 48004 = `ffffffff`)
3. **`application@android:name` is NOT in-place editable to a working class.** The 302-entry pool
   contains no `android.app.Application` or other valid substitute (only matches: idx 196
   `com.facebook.sdk.ApplicationId`, idx 249 `com.pairip.application.Application`); the empty string
   (idx 52) is not a valid Application class. A real swap needs a string-pool resize — out of scope
   here; PairIP neutralization is the dex agent's job. (verified: pool scan)

## 2. Framework behaviour — install vs harmless

- **`requiredSplitTypes="base__abi"` on a single merged APK -> INSTALL_FAILED_MISSING_SPLIT on API
  31+.** The config split is what *offers* `base__abi` (verified: split manifest has
  `splitTypes="base__abi"`, `split="config.arm64_v8a"`). Merging removes that provider, so the
  requirement must be cleared. **Confidence: symptom HIGH; exact AOSP method names MEDIUM** (cited
  from knowledge — ParsingPackageUtils / ApkLiteParseUtils.parseApkLite / InstallPackageHelper
  reconcile — not from framework source in this repo; downgraded from the original note's
  MEDIUM-HIGH).
- **`com.android.vending.splits.required=true` meta-data is NOT read by the OS installer.** It is a
  Play-Store / Play-Core concern (`MissingSplitsManagerImpl.disableAppIfMissingRequiredSplits()`
  only if the app invokes it). Harmless to leave for install; flipping removes any Play-Core
  missing-splits kill. Whether this PairIP app calls it is unverified. **OS ignores it HIGH;
  usefulness of the flip LOW/unverified.**
- **`com.android.vending.splits` (@7F150003), `derived.apk.id`, `stamp.source`/`type`, and
  `stamp-cert-sha256` are harmless** — Play-distribution / source-stamp metadata, inert once we
  re-sign with our own key and drop the stamp block. **Confidence: HIGH.**
- **PairIP** (`com.pairip.application.Application`, LicenseActivity, VMRunner) is an app wrapper, not
  a split mechanism — out of scope for the merge; handled in the dex work.

## 3. APK inventory (my `zipfile` pass) — VERIFIED

### Base `com.autumn.skullgirls.apk` (5832 entries total)
- **resources.arsc**: 1 entry, **STORED (method 0)**, data offset **131,741,308**, **4-aligned
  (off%4==0)**, size 1,046,116. (verified by reading the local header) Meets targetSdk>=30 already.
- **dex**: `classes.dex..classes5.dex` **all STORED**; `assets/audience_network.dex` **DEFLATE
  (method 8)**. (verified)
- **lib/**: **0 entries**. (verified)
- **META-INF**: 70 entries, **no MANIFEST.MF / *.SF / *.RSA** -> base not v1-signed. (verified)
- Approximate breakdown from the original note (res/ 1024, assets/bin/Data 4629, assets-other 37,
  other 65, META-INF 70, dex 6, arsc 1, lib 0) sums to 5832, consistent with my total; I did not
  re-bucket each of those sub-counts (LOW confidence on the exact per-folder splits, HIGH on the
  total and on the arsc/dex/lib/META-INF facts the patcher depends on).
- **Signatures (apksigner verify, exact r36 jar):** v1=**false**, v2=**true**, v3=**true**,
  v3.1=**false**, v4=**false**, SourceStamp=**true**. Signer `CN=Clark Kromenaker, O=Hidden Variable
  Studios`, RSA **1024**, cert SHA-256 **b83b1504f2aec247daaeaf04470fa41a9873774b4027226efc917382f43ef17f**.
  Stamp signer = Google (`CN=Android, O=Google Inc.`, RSA 4096, SHA-256 3257d599...). (verified)

### Split `config.arm64_v8a.apk` (23 entries total)
- **18 `.so`** under `lib/arm64-v8a/`, **ALL DEFLATE (method 8)**. (verified; full list includes
  libil2cpp.so, libunity.so, libmain.so, libpairipcore.so, libsigner.so, libggpo.so, libntex.so,
  Firebase/Crashlytics/AppLovin libs, etc.)
- **META-INF**: `BNDLTOOL.SF`, `BNDLTOOL.RSA`, `MANIFEST.MF` -> split **is v1-signed** (bundletool).
  (verified)
- **No resources.arsc, no res/, no assets/, no dex.** (verified)
- Its own `AndroidManifest.xml` and a `stamp-cert-sha256`. (verified) Split manifest has
  `split="config.arm64_v8a"`, `splitTypes="base__abi"`; **no `configForSplit` attribute is present**
  (correction: original note's "split/configForSplit" parenthetical is imprecise — only `split` and
  `splitTypes` exist).

### Name conflicts base vs split — CORRECTED
Exact-filename overlap between base and split is **`AndroidManifest.xml` AND `stamp-cert-sha256`**
(both files exist in both APKs). The original note listed "AndroidManifest.xml and META-INF/*"; in
fact the two META-INF trees share **no** filenames (base = androidx `.version` + `services/*`; split
= `BNDLTOOL.*` + `MANIFEST.MF`) — they overlap only in *purpose*, and `stamp-cert-sha256` was the
real second name collision that was missed. **None of this affects the merge**: the split's unique
payload we add is only the 18 `lib/arm64-v8a/*.so`, and **none of those 18 `.so` names exist in the
base** (verified: intersection empty). Base has zero `lib/` entries, so there are **no data-file
collisions** in the actual merge.

### Merge plan (unchanged, verified sound)
Copy all base entries verbatim (preserving STORED+alignment for resources.arsc and classes*.dex) +
add the 18 `lib/arm64-v8a/*.so` from the split (DEFLATE ok); drop the split's AndroidManifest.xml,
both META-INF trees, and `stamp-cert-sha256`; apply the two in-place base-manifest edits; re-sign
fresh.

### Output requirements — VERIFIED
1. **Signing**: targetSdk 36 (>=30) => **v2 mandatory**; sign v2+v3 with our own key. Original
   v1/v2/v3 + Play source stamp are discarded/replaced. (HIGH)
2. **resources.arsc**: keep **STORED + 4-byte aligned**; already compliant, must be preserved. (HIGH)
3. **.so**: with `extractNativeLibs="true"`, DEFLATE `.so` are valid and need no APK alignment.
   Carry split `.so` as-is (DEFLATE). (HIGH)
4. **Page alignment**: zip page-alignment of `.so` only matters for STORED+mmap'd libs
   (`extractNativeLibs="false"`) — not our case. 16KB-page-device support is an ELF `p_align`
   property of the game's own `.so` builds, independent of packaging; **S22 is 4KB-page, unaffected**.
   (HIGH on packaging logic; the game libs' actual p_align not checked — LOW/unknown.)

## 4. Does apksigner r36 re-align stored entries itself? YES — VERIFIED in the exact jar

From `apksigner.jar sign --help` and decompiled `com.android.apksig.ApkSigner`:
- `--alignment-preserved` default **false**; help text: when false, "--lib-page-alignment will be
  used to page align native library files and all other files will be aligned to 4 [bytes]".
  (verified verbatim)
- `--lib-page-alignment` default **16384**. (verified verbatim: "the default value is 16384")
- `--align-file-size` pads total APK to 4K multiples (off by default). (verified)
- Class carries `mAlignmentPreserved`, `mLibraryPageAlignmentBytes`, `ANDROID_FILE_ALIGNMENT_BYTES`,
  `createExtraFieldToAlignData`, `getInputJarEntryDataAlignmentMultiple`. (verified via `strings`/
  `javap`)

**Conclusion (HIGH):** apksigner r36 re-aligns while signing by default — keeps STORED entries
(incl. resources.arsc) 4-aligned and would 16KB-align any STORED `.so`. A separate `zipalign` pass
is **not required** as long as we do NOT pass `--alignment-preserved`. DEFLATE `.so` are not aligned,
fine under extractNativeLibs=true.

## Recommended merge recipe (summary)
Copy all base entries verbatim (preserve STORED+alignment for resources.arsc and classes*.dex) + add
the 18 `lib/arm64-v8a/*.so` from the split (DEFLATE ok); drop split manifest, both META-INF trees,
`stamp-cert-sha256`; apply the two in-place base-manifest edits (requiredSplitTypes -> "" at
24304/24312; optionally splits.required -> false at 48004); sign v2+v3 with our key via apksigner r36
(no `--alignment-preserved`).

## Review notes
Verified from the real files (not trusting the original note):
- **AXML**: encoding UTF-16LE, string count 302, empty string unique at index 52, resid count 52,
  absence of isSplitRequired/isFeatureSplit, minSdk 24 / targetSdk 36 — all confirmed.
- **Every in-place edit offset**: 24304 and 24312 currently `80000000` (base__abi, idx 128); 48004
  currently `ffffffff` (BOOL true) — confirmed byte-for-byte, so the three writes are correct and
  non-resizing.
- **Zip inventory**: base total 5832 entries, resources.arsc STORED at data offset 131741308 and
  4-aligned, classes*.dex all STORED, audience_network.dex DEFLATE, base lib/ = 0, base META-INF has
  no MF/SF/RSA; split 23 entries, 18 `.so` all DEFLATE, no arsc/res/assets/dex, split v1-signed —
  all confirmed.
- **Signatures**: apksigner verify on the exact r36 jar confirms base v2+v3+SourceStamp (no v1/v3.1/
  v4), signer Clark Kromenaker / Hidden Variable Studios RSA1024, cert SHA-256 b83b1504..., Google
  source stamp — confirmed.
- **apksigner defaults**: `--alignment-preserved` false and `--lib-page-alignment` 16384 confirmed
  verbatim from the jar's help and bytecode.

Changes I made to the note:
1. **Corrected the name-conflict claim**: exact-filename overlaps base vs split are
   `AndroidManifest.xml` AND `stamp-cert-sha256` (not "AndroidManifest.xml and META-INF/*"). The two
   META-INF trees share no filenames. The merge itself is unaffected (we only add the 18 `.so`, none
   of which collide with base).
2. **Corrected `com.android.stamp.source`@value**: attrOff is 48088 with rawValIdx 266
   (`https://play.google.com/store`), replacing the "~48052" estimate in the original table.
3. **Noted the split manifest has no `configForSplit` attribute** (original "split/configForSplit"
   parenthetical was imprecise).
4. **Downgraded the AOSP enforcement method names to MEDIUM** (symptom stays HIGH) since I could not
   verify them against framework source in-repo.
5. **Flagged the base per-folder sub-counts (res/ 1024, assets 4629, etc.) as LOW confidence** — I
   verified the total (5832) and the patcher-critical buckets (arsc/dex/lib/META-INF), not each
   folder tally.
Everything else in the original note matched the bytes and is kept.