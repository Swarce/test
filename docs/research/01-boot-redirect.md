# 01-boot-redirect.md (reviewed): how Skullgirls Mobile 8.12.0 finds its servers, and how to redirect it

Inputs (all under scratchpad `S` = /tmp/claude-0/-home-user/d83b0de3-2765-55a8-b8c4-0d0bca001742/scratchpad):
- `global-metadata.dat`, sha256 170245b15cecabc78f6cadb0646bdabe3bf0a0f78b9c0ff727a692be38bd29ae. Re-hashed; it also matches the copy read straight from the base APK.
- `libil2cpp.so` arm64, sha256 18363f8d7c4c10b4ceb0c87ac83d37ed0a727e9008ed581e88520bfbfd64375f (re-hashed).
- classes*.dex, res/xml, the decoded manifest, and globalgamemanagers from the base APK.

The original author's parsers are in `S/scripts-boot/`. The reviewer's independent re-checks are in `S/scripts-rev-redirect/` (lit.py, elf.py, slot.py, xr.py, got.py, dis.py, calls.py). No third-party RE binaries were used. No game server was contacted.

Confidence tags: **[H]** high, **[M]** medium, **[L]** low.

---

## 1. String-literal table and in-place rewrite

### 1.1 Layout [H, re-verified]
- Magic FAB11BAF, version 31.
- Header at offset 8: `stringLiteralOffset=0x100`, `stringLiteralSize=0x2f798`. That gives 24307 entries of `{uint32 length, uint32 dataIndex}`.
- `stringLiteralDataOffset=0x2f898` (194712) and `stringLiteralDataSize=0x97714` (620308).
- The data is packed and contiguous in table order:
  - `dataIndex[i]+length[i]==dataIndex[i+1]` holds for every i. There are 0 violations.
  - The sorted-interval scan finds 0 overlaps and 0 gaps.
  - Entry #0 is `(0,0)`. The last entry is `(len 3, dataIndex 620302)`.
  - The lengths sum to 620305, against a header size of 620308. The 3 spare bytes are padding before the next section (`string`) at 0xc6fac.
- There are no NUL terminators and no per-string alignment.

### 1.2 Relevant entries [H, every row re-computed]
All rows have no overlap with any other literal.

| # | value | tableEntryOffset | dataIndex | len | fileOffset |
|---|---|---|---|---|---|
| 19446 | https://skgm-dev-cdn.skgminternal.com/ | 155824 (0x260b0) | 555945 | 38 | 750657 (0xb7441) |
| 19447 | https://skgm-dev-game-api.skgminternal.com | 155832 (0x260b8) | 555983 | 42 | 750695 (0xb7467) |
| **19448** | https://skgm-prod-cdn.skullgirlsmobile.com/ | **155840 (0x260c0)** | **556025** | **43** | **750737 (0xb7491)** |
| **19449** | https://skgm-prod-game-api.skullgirlsmobile.com | **155848 (0x260c8)** | **556068** | **47** | **750780 (0xb74bc)** |
| 19450 | https://skgm-qa-cdn.skgminternal.com/ | 155856 (0x260d0) | 556115 | 37 | 750827 (0xb74eb) |
| 19451 | https://skgm-qa-game-api.skgminternal.com | 155864 (0x260d8) | 556152 | 41 | 750864 (0xb7510) |
| 19452 | https://skgm-test-cdn.skgminternal.com/ | 155872 (0x260e0) | 556193 | 39 | 750905 (0xb7539) |
| 19453 | https://skgm-test-game-api.skgminternal.com | 155880 (0x260e8) | 556232 | 43 | 750944 (0xb7560) |
| 19375 | http://localhost:9000 | 155256 (0x25e78) | 553077 | **21** | 747789 (0xb690d) |
| 19432 | https://gghukasyan-ws.eu.ngrok.io | 155712 (0x26040) | 555225 | 33 | 749937 (0xb7171) |
| 19370 | http://clients3.google.com/generate_204 | 155216 (0x25e50) | 552892 | 39 | 747604 (0xb6854) |
| 19428 | https://clients3.google.com/generate_204 | 155680 (0x26020) | 555067 | 40 | 749779 (0xb70d3) |

Formula: `tableEntryOffset = 0x100 + 8*i` and `fileOffset = 0x2f898 + dataIndex`. The length is the first uint32 of the table entry.

### 1.3 How the runtime reads a literal [H, re-disassembled]
- Call chain: `il2cpp_codegen_initialize_runtime_metadata` 0x1917f58 → 0x1925a68 (`mov w1,#1`) → resolver 0x195f7c0.
- If bit 0 of the slot is clear, the slot already holds a resolved pointer and is returned as is.
- Otherwise the resolver computes `type = bits 29..31` and `index = ubfx(w0, 1, 28)`, then dispatches through a jump table.
- StringLiteral case at 0x195f87c:
  1. It checks a cache array (`[0x42d9c70][index]`).
  2. On a miss it reads `hdr+8` (stringLiteralOffset) and `hdr+0x10` (stringLiteralDataOffset).
  3. It does `ldp w1(length), w11(dataIndex)` from `meta+stringLiteralOffset+index*8`.
  4. It calls the string constructor 0x18ee8d8 with `(meta+stringLiteralDataOffset+dataIndex, length)`.
- It never reads a terminator or a neighbouring literal, and there is no checksum in this path.

### 1.4 Conclusion: an in-place shrink-rewrite is safe [H]
- **#19449 → `http://127.0.0.1:47820`** (22 bytes, 22 ≤ 47):
  - Write the 22 bytes at file offset 750780 (0xb74bc).
  - Set the uint32 at 155848 (0x260c8) to 22.
  - Keep dataIndex unchanged.
  - The 25 leftover bytes are never read. Zero-filling them is optional.
  - No trailing slash: `SetBaseUrl` appends "/v1" (literal #1741), so a slash would give `//v1`.
- **#19448 → `http://127.0.0.1:47820/cdn/`** (27 bytes, 27 ≤ 43). This is for the final design only; b01 leaves the CDN alone.
  - Length goes at 155840 and the bytes at 750737.
  - The trailing `/` is required. `DownloadManager.GetDownloadUrl` (0x1b5a8c4) computes `Concat(Concat(this.cdnUrl[+0x38], "v1/" #23376), file)`, so the request path is `/cdn/v1/<file>` (re-verified).
- Never make a literal **longer** than its original. It would overwrite literal i+1.
- **Correction: #19375 `http://localhost:9000` is only 21 bytes, so the 22-byte target does NOT fit.** Leave it alone. Its environment (Local=0) is reachable only from the debug UI, never from `ResetToDefaultBackend` (§2.3). #19432 (33) fits.
- Other copies [H for location, M that they are unused]:
  - `fieldAndParameterDefaultValueData` (section at 0x741d50, size 0x66850) holds two more copies.
  - `ClientAPI.autumnReleaseUrl` has its compressed-length byte 0x5e (= 47) at 0x743b79, with the string at 0x743b7a.
  - `AWSCdnConfig.kBaseUrlProd` has its length byte 0x56 (= 43) at 0x742869, with the string at 0x74286a.
  - These fields have default-value blobs, so they are C# `const` and inlined at compile time. Only reflection would read them. Leave them.
  - `skgm-prod` occurs nowhere else in the metadata file and in none of the 18 .so files (grep). It also does not appear in any other extracted base file.
- Metadata in the APK [H]: `assets/bin/Data/Managed/Metadata/global-metadata.dat` is stored **deflated** (method 8, 13880352 → 3940419 bytes, CRC 0xae68dc55). The patcher rewrites the entry, which recomputes the CRC. No alignment requirement was found for it (it is not an uncompressed resource).
- Recommended for robustness [H for the reason]:
  - Also rewrite the API variants #19447, #19451, #19453 and #19432 to `http://127.0.0.1:47820`.
  - In the final design, also rewrite the CDN variants #19446, #19450 and #19452.
  - Reason: the defaults in both `ResetToDefaultBackend` and `DownloadManager.Initialize` fall back to **Dev**, not prod (§2.3). Prod depends on an unresolved build-config value.

## 2. Code that uses #19449 / #19448

### 2.1 Usage-slot encoding [H, re-verified]
- `.data` file offset 0x40253f0 maps to va 0x40313f0 (delta +0xC000).
- Each tested literal's token `(5<<29)|(idx<<1)|1` appears exactly once as a uint64 in `.data`:
  - #19449 = 0xA00097F3 at va **0x415ab38** (file 0x414eb38).
  - #19448 = 0xA00097F1 at va **0x415ab30** (file 0x414eb30).
  - The others are listed in the machine data.
- Observation [M]: TypeInfo-style slots (for example the ones behind GOT 0x3facc10, 0x3fa9468 and 0x3fa3fd8) hold values that are not tokens, such as 0x5e0abb8c9ae68c13. Decoded as tokens, their index field is out of range. The string-literal slots are plain tokens. See §6 for a possible link to PairIP.

### 2.2 References [H, re-verified with a register-clobber-safe ADRP+LDR scan]
- Each slot has exactly one `R_AARCH64_RELATIVE` relocation, in a GOT entry:
  - #19449: GOT **0x3fbbeb8**.
  - #19448: GOT **0x3fad4f8**.
  - #19448 also has a `.data.rel.ro` table at 0x3dc1368 = [#19450 qa, #19452 test, #19448 prod].
- Code references:
  - GOT 0x3fbbeb8 is loaded only at 0x1d9b000 (init) and 0x1d9b1b4 (use), both in **`ClientAPI::SetEnvironment`** (0x1d9af70, token 0x600414e).
  - GOT 0x3fad4f8 is loaded only at 0x1b59238. The table is used at `add` 0x1b592a0. Both are in **`DownloadManager::Initialize`** (0x1b591f8, token 0x6001fbb).
- **Correction:** the earlier hit at 0x1eea820 ("GuildLeaderboardScreen") was a scanner false positive. That `ldr x0,[x0,#0x4f8]` follows `adrp x0,#0x3fc4000`, so it loads GOT 0x3fc44f8, a different entry. GuildLeaderboardScreen does not touch #19448 at all.
- Names were mapped with `scripts-boot/mapm.py`, using CodeGenModule method pointers to the metadata method RID (92 of 96 modules found). Its output was re-run and matches.

### 2.3 What the code does
- **`ClientAPI.SetEnvironment(BackendEnvironment env, string overrideUrl)`** 0x1d9af70 [H]:
  - It stores env at static+0x20.
  - If `overrideUrl != null`, it calls `SetBaseUrl(overrideUrl)` and stores the URL at static+0x10 (`customOverrideUrl`).
  - Otherwise it switches on env (byte jump table at 0xb65acd, re-decoded) and calls SetBaseUrl with a literal:
    - 0 Local → #19375
    - 1 Dev → #19447
    - 2 LiveDebug → #19432
    - 3 QA → #19451
    - 4 Staging → #19453
    - 5 AutumnRelease → **#19449**
    - greater than 5 → exception built from #14737 "Unaccounted for backend: "
  - Finally it clears or sets static+0x10 and invokes a static delegate at static+0x0 with env.
- **`ClientAPI.SetBaseUrl(url)`** 0x1d9b288 [H]: `static+0x8 (baseUrl) = String.Concat(url, "/v1")`. It is called only from SetEnvironment (direct-call scan).
- **`ClientAPI.GetRelativeUrl(x)`** 0x1d9b4ac [H]: `IsNullOrEmpty(x) ? "/v1" : "/v1/" + x` (#1741 and #1742). It does not depend on the host. The docs say request signing uses this path, so signing survives the host change [M for the use in signing].
- **`ClientAPI.IsolateEndpoint`** 0x1d9b52c [M]: reads baseUrl (static+8) and is length-based.
- **`ClientAPI.GetUrl`** 0x1d8fbc8 [M, not re-verified]: base + "/" + endpoint.
- **`ClientAPI..cctor`** 0x1d9ad04 [H]: sets static+0x20 = 1 (Dev), then tail-calls **`ResetToDefaultBackend`** 0x1d9ae48.
- **`ResetToDefaultBackend`** [H, re-decoded byte table at 0xb65ac9]. **CORRECTED mapping.** It reads int `cfg` at static+0x28 of a build-config class (GOT 0x3fa3fd8, unresolved) and then calls `SetEnvironment(env, null)`:
  - cfg 3 → env 3 QA
  - cfg 4 → env 2 LiveDebug
  - cfg 5 → env 4 Staging
  - **cfg 6 → env 5 AutumnRelease (prod)**
  - **any other value → env 1 Dev**
  - The earlier note said "3→QA, 4→Dev, 5→LiveDebug, 6→Staging, else→AutumnRelease". That is wrong.
- **`DownloadManager.Initialize`** 0x1b591f8 [H]:
  - If `this.cdnUrl` (+0x38) is non-null, it returns.
  - Otherwise it reads the same config class at static+0x30: 1 → qa #19450, 2 → test #19452, 3 → prod #19448. Any other value → dev #19446.
  - It then tail-calls `SetCdnUrl` 0x1b5a538.
  - Release evidently uses cfg+0x28 == 6 and cfg+0x30 == 3 [M, behavioural, not resolved statically].
- Callers of SetEnvironment, from the direct-branch scan [H]:
  - `DebugServerSection.UGUI_EnvironmentDropdownChanged` (0x1b4c118)
  - `DebugServerSection.UGUI_OverrideServerButtonClicked` (0x1b4c338)
  - `MaintenanceModeManager.RedirectIfNeedBe` (0x1bca190)
  - the `ResetToDefaultBackend` tail call
  - Also, `DebugServerSection.UGUI_ClearServerOverrideButtonClicked` (0x1b4c424) calls ResetToDefaultBackend. The earlier note did not list it.
- **`MaintenanceModeManager.RedirectIfNeedBe`** 0x1bca050 is called only from `<>c__DisplayClass29_0.<CheckForMaintenanceMode>b__0` [H]. Exact logic [H for the code, M for mapping offsets to field names]:
  - `info = this+0x30`, with `redirectVersion`@+0x88, `redirectApiUrl`@+0x90 and `redirectCdnUrl`@+0x98.
  - It returns unless `redirectVersion` and `redirectApiUrl` are both non-empty **and** `redirectVersion == VersionData.GetReleaseVersionString()`.
  - Then it logs #11412 "REDIRECTING DUE TO VERSION. YOU HAVE BEEN WARNED" and calls `SetEnvironment(currentEnv, redirectApiUrl)`.
  - If `redirectCdnUrl` is non-empty, it also calls `DownloadManager.Instance.SetCdnUrl(redirectCdnUrl)`.
  - **So the server must leave redirectVersion and redirectApiUrl empty or absent.** The reference server contains no "redirect" fields (grep of reference-server/sgserver).
- PlayerPrefs `savedBackend` and `savedBackendOverrideUrl` (#21930, #21931) [H for the references]:
  - Their GOT entries (0x3fbbed8, 0x3fbbed0) are used only in `ClientAPI.RememberCustomBackendConfig` (PlayerPrefs set) and `ForgetCustomBackendConfig` (DeleteKey).
  - There is no reader in compiled code, so they cannot serve as a no-patch redirect.

## 3. Host inventory [mostly not re-verified by the reviewer; kept from the original at its stated confidence]
- **Game API**: `skgm-prod-game-api.skullgirlsmobile.com` via Mono HttpWebRequest. Redirect via #19449 [H]. Alternates are the dev, qa and test `*.skgminternal.com` hosts, ngrok, and `localhost:9000`; rewrite all of them except #19375 (too short).
- **Game CDN**: `skgm-prod-cdn.skullgirlsmobile.com/` (FileDownloader → WebRequest). b01 leaves it untouched. The final design rewrites #19448 to `/cdn/`.
- **Connectivity checks** (`InternetReachabilityVerifier.getCaptivePortalDetectionURL` 0x1e715f4, which loads #19370 and #19428 [H, re-verified]): clients3.google.com/generate_204, www.google.com/blank.html, captive.apple.com, www.apple.com/library/test/success.html, start.ubuntu.com/connectivity-check, and the msftconnecttest/msftncsi hosts.
  - `HttpManager.HasInternetConnection` 0x1bb8364 compares the time without connection against **10.0 s** [H, re-verified `fmov s1,#10.0; fcmp`].
  - Its callers were not re-verified [M]: it is said to select only the error message text, not gate requests.
  - Do not remove the IRV component: a null Instance would cause an NRE [M].
  - For fully offline play: rewrite #19370 and #19428 to `http://127.0.0.1:47820/generate_204` (35 bytes, which fits in 39 and 40) and return 204. Which check method is active is a serialized field [L].
- Third parties, all left to fail or debloated [M as inventoried; the reviewer did not re-scan dex, res or arsc]:
  - Photon `ns(.cn).exitgames.com`, zeuz `api.zeuz.io`, Bolt `localhost:3000`, STUN `stun*.l.google.com`
  - PubNub `ps.pndsn.com`, Helpshift (`autumngames.helpshift.com`, plus SDK hosts)
  - Unity Services / IAP hosts, the Aghanim webshop and hub, external links (WWWUtil.Open*)
  - SES SMTP (purpose unverified [L]), Firebase, Adjust, AppLovin, Google Ads, Vungle, BidMachine
  - `d2duuy9yo5pldo.cloudfront.net` (unattributed [L]), Facebook, Google Sign-In
  - Doc and namespace strings are never contacted.
  - The reviewer only confirmed that the `http://skullgirlsmobile.com/{aos,feedback,privacy,support,terms,updates}` and `hub.skullgirlsmobile.com` literals exist (#19386–#19391, #19435, #19436). These are external links, not the API.

## 4. Cleartext (http://) policy
- Manifest [H, re-checked]:
  - targetSdk 36, minSdk 24, debuggable false, `extractNativeLibs="true"`.
  - There is **no `usesCleartextTraffic` and no `networkSecurityConfig`**.
  - res/xml contains only ad_services_config, com_android_billingclient_phenotype, image_share_filepaths and splits0.
- Game API and CDN [H for the code path, M/H for the conclusion]:
  - Both use Mono `HttpWebRequest` over IL2CPP managed sockets. Android NSC is a Java-level policy that only NSC-aware Java stacks consult. So `http://127.0.0.1:47820` needs no NSC.
- UnityWebRequest [H for the setting]:
  - `PlayerSettings.insecureHttpOption = 2` (AlwaysAllowed), re-read with UnityPy from the APK's globalgamemanagers.
  - The backend is native (libunity curl) [M].
- Insurance [M]: add `android:usesCleartextTraffic="true"` while b01 rewrites the manifest anyway. It only matters for Java stacks.
- Android 16 / Galaxy S22 [L-M]: loopback via `adb reverse` should not count as "local network" traffic.
- Reference server [H, re-checked]:
  - `reference-server/sgserver/server.py serve()` wraps TLS only if `--cert` is given.
  - `__main__.py` defaults are `--host 0.0.0.0 --port 443`.
  - For b01, run with `--port 47820`, no `--cert`, and `adb reverse tcp:47820 tcp:47820`.
  - The repo uses `reference-server/` (docs in `reference-server/docs/`), not the BRIEF's `sgm-server/`.

## 5. Recommended b01 patch (metadata only)
1. Set the uint32 at 0x260c8 to 22. Write `http://127.0.0.1:47820` at 0xb74bc. Optionally zero bytes 0xb74d2..0xb74ea (the remaining 25).
2. Recommended: do the same for #19447 (0x260b8 / 0xb7467), #19451 (0x260d8 / 0xb7510), #19453 (0x260e8 / 0xb7560) and #19432 (0x26040 / 0xb7171). The default-case fallback is Dev, so #19447 matters most. **Do not touch #19375** (21 bytes is less than 22).
3. Leave the CDN literals alone in b01. In the final design, #19448, #19446, #19450 and #19452 become `http://127.0.0.1:47820/cdn/`.
4. Patcher self-check: re-parse after writing and confirm that each patched entry's (dataIndex, length) yields the new string, and that entries i-1 and i+1 are byte-identical to the original.
5. The server must never populate `MaintenanceModeInfo.redirectVersion` or `redirectApiUrl`, and should leave `redirectCdnUrl` empty as well.
6. Do not remove InternetReachabilityVerifier.

## 6. New finding relevant to install/run: libil2cpp.so is itself hooked by PairIP [H for facts, L for implications]
- `.dynamic` of libil2cpp.so:
  - The DT_NEEDED list starts with **`libpairipcore.so`**, followed by liblog, libm, libdl and libc.
  - **DT_INIT = 0x4500000**, which sits in an extra RX segment (file 0x44ec000, size 0x1000).
  - Further appended segments carry `.dynsym`, `.hash` and `.dynamic` at 0x4508000+. This is post-link modification.
- The DT_INIT stub (`bti c; ...; blr x17`) calls the PLT slot at 0x44f8000, a JUMP_SLOT for the symbol **`ExecuteProgram`**. Its argument points at the string **"EE9dHUAG90EJYLZv"** (va 0x4500040).
- The base APK contains `assets/EE9dHUAG90EJYLZv`, an encrypted PairIP VM program.
- Implications for the patcher:
  - If PairIP neutralisation removes or stubs libpairipcore.so, libil2cpp.so will fail to load unless an `ExecuteProgram` export stays available or this DT_NEEDED/DT_INIT pair is neutralised.
  - What the program does is unknown [L]. It could check integrity, which might cover global-metadata.dat. It could also be what fills the non-token TypeInfo slots noted in §2.1.
  - This is not documented in `docs/design/patcher.md` (grep).

## Review notes
Verified independently, with my own scripts in `S/scripts-rev-redirect/`:
- The sha256 of both files.
- The metadata header values, and that the literal data is contiguous with no overlaps or gaps. Also the sum of lengths and the 3-byte padding.
- Every tableEntryOffset, dataIndex, length and fileOffset in §1.2, plus #1741 "/v1", #1742 "/v1/", #1627 "/" and #23376 "v1/".
- The default-value copies.
- The .data mapping, each usage-slot token and its single RELATIVE relocation/GOT entry, and the code references (with a register-clobber-safe scanner).
- The resolver disassembly, SetEnvironment and its jump table, SetBaseUrl, GetRelativeUrl, the cctor, ResetToDefaultBackend and its jump table, and DownloadManager.Initialize with its table.
- GetDownloadUrl, the direct callers of SetEnvironment, SetBaseUrl, SetCdnUrl and ResetToDefaultBackend, the RedirectIfNeedBe logic and its caller, and the savedBackend references.
- The HasInternetConnection 10 s threshold, the manifest attributes, res/xml, insecureHttpOption=2, the reference-server CLI and serve(), and the metadata zip entry method and CRC.

Changed:
1. **The ResetToDefaultBackend mapping was wrong.** The default is Dev, and prod requires cfg==6. As a result the dev/qa/test rewrites went from "nice to have" to "recommended", with #19447 the most important.
2. **#19375 does not fit the 22-byte URL** (it is 21 bytes). It is removed from the rewrite list.
3. The 0x1eea820 "GuildLeaderboardScreen" hit is not a reference at all. It was a scanner false positive (GOT 0x3fc44f8), not "slot only initialised".
4. The default-value offsets point at the compressed-length byte. The string starts at +1.
5. Added `UGUI_ClearServerOverrideButtonClicked` as a caller of ResetToDefaultBackend.
6. The RedirectIfNeedBe trigger condition is now exact. The redirect needs `redirectVersion == GetReleaseVersionString()`.
7. Added §6: libil2cpp's DT_NEEDED libpairipcore plus a DT_INIT → `ExecuteProgram("EE9dHUAG90EJYLZv")` hook.
8. Added that the metadata entry is deflated, and a post-patch self-check.

Not re-verified, so kept at the original's confidence or downgraded:
- Most of §3: the dex, res and arsc host attribution.
- GetUrl.
- The HasInternetConnection callers.
- The UnityWebRequest backend.
- The Android 16 local-network reasoning.
