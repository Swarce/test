# SGM Manager — the private server as an Android app

Runs the [single-player private server](../sgm-server) **on the same phone as the game**, so
Skullgirls Mobile works without the official servers and (after one online session) without
internet. It also includes an **account editor** for currencies, fighters, moves, items and
progress.

```
 Skullgirls ──DNS──► 10.111.222.2 (manager's resolver) ── game API host → 10.111.222.3
     │                                                    other names → your real DNS
     └─TLS──► 10.111.222.3:443 ──(VPN interface, address rewrite)──► embedded server
```

The manager opens a local VPN that **only the game is routed through**, and only for a few
virtual addresses. The game's own lookups for its API host are answered with a virtual address;
connections to it are handed to the embedded server by rewriting packet addresses on the VPN
interface (no traffic leaves the phone). Everything else the game or any other app does uses the
normal network. No root and no APK modification: the game accepts the server's self-signed
certificate (see `../sgm-server/docs/PROTOCOL.md`, *TLS*).

## Using it

1. Install the game (Skullgirls 8.11.0, `com.autumn.skullgirls`) and the manager APK.
2. Open **SGM Manager → Import game data from installed Skullgirls**. The manager reads the
   game's design data (characters, acts, loot tables…) straight from the installed APK — about
   a minute. Alternatively import a zip of the JSON files made with
   `sgm-server/tools/extract_content.py`.
3. Tap **Start server** and accept Android's VPN prompt.
4. Close Skullgirls completely and launch it (the button in the manager works too). Log in as
   guest: a new account is created with the starting team.

**Offline-ready mode** (checkbox) additionally routes the game's asset CDN and its "is there
internet?" checks through the manager: every file the game downloads while online is cached on
the phone and served from there later, and the connectivity checks get a local answer, so the
game can start with no connection once its downloads have been cached. Leave it off if you only
want the private server.

### Account editor

**Open account editor** (or, on a desktop, the URL the server prints) shows:

| Tab | What you can change |
|---|---|
| Accounts | several accounts; choose which one the game logs into; create, delete, export/import saves (JSON) |
| Profile & currency | name, player level, every currency (coins, theonite, keys, shards, essences, tickets, refills…), tutorials, energy, daily challenges and event runs |
| Fighters | add any fighter (tier, level, shiny, full skill tree, copies), "one of every fighter", max all; edit tier/level/shiny/lock/super abilities/prestige/skill tree; remove; set the team |
| Moves | add any special move, blockbuster or assist at any level; change levels; remove |
| Items | consumables, relics (any relic, opened from the game's relic inventory), skill points and reroll runes per character, catalysts |
| Campaign | complete or reset each story act (or all) |
| Server | XP/coin rates, event rotation, starting resources, log and status |
| Raw save | the full save document as JSON |

Names come from the game's English string table. The game caches your profile: after editing,
return to the title screen or restart the game to see the changes.

## Caveats

* **Not yet tested on a physical phone.** Every part was tested here (see *Testing*), including
  the VPN address rewriting against a real Linux kernel TUN device, but Android adds its own
  routing policy on top. Please report what you see in the manager's log if it does not work.
* Android **Private DNS** set to a specific provider (*Settings → Network → Private DNS →
  hostname*) bypasses the manager's resolver; use *Automatic* or *Off*. The manager warns about it.
* Only one VPN app can be active at a time.
* Offline mode can only serve assets that were downloaded through the manager at least once
  (or that the game had already stored). Updates the game wants that were never cached fail.
* Same limits as the server: no PvP, guilds, chat, raid boss or Mission Control.

## Desktop server

The same Java code runs on a PC (Java 8+), as an alternative to the Python server:

```sh
./build.sh                                   # builds build/sgm-core.jar and the APK
java -jar build/sgm-core.jar --content content --data sgm-data --port 443
#   prints:  account editor: http://127.0.0.1:8080/admin/?token=…
```

Options: `--http` (no TLS), `--host`, `--admin-port N` (0 disables the editor), `--config FILE`,
`-v`. Saves are plain JSON files in `--data` (`users/<id>.json`, same shape as the Python
server's), so they are easy to back up. Redirect the game exactly as described in the server's
README.

The importer also runs on a PC:
`java -cp build/sgm-core.jar sgm.core.extract.ExtractMain app/assets/typetrees.json content base.apk`.

## Building

`./build.sh` uses plain JDK + Android build tools (no Gradle): `javac` against the API 23
platform jar → `dx` → `aapt` → `zipalign` → `apksigner`. On Debian/Ubuntu:
`apt install openjdk-17-jdk-headless android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner`.
A signing key is created in `build/debug.keystore` on first build (set `KEYSTORE`/`KS_PASS` to
use your own; keep it to be able to update the app). The app targets API 29 and runs on Android 7+.

`app/assets/typetrees.json` holds the field layouts of the ~190 game classes the importer reads
(names and types only — the build strips them from the game files). Regenerate it for a new game
version with `python tools/dump_typetrees.py <game.xapk> -o app/assets/typetrees.json`.

## Testing

| What | How |
|---|---|
| Server behaviour | the Python server's end-to-end suite against the Java server: `SGM_CONTENT=content SGM_JAVA_CP=sgm-manager/build/core-classes python -m unittest discover -s sgm-server/tests` |
| Game math | `ParityDump` + `sgm-server/tests/parity.py`: XP ladders, stats and fighter scores of every variant/tier/level identical to the Python port (13,310 values) |
| VPN path | `sudo python tools/tun_shuttle.py <classpath> content ../sgm-server [offline]`: creates a TUN device and drives DNS → TLS login → fight, a 6 MB CDN download, range requests and the connectivity check through the exact packet engine the app uses |
| Importer | `tools/compare_content.py`: the on-device importer's output is identical to `extract_content.py`'s (16,100 objects, 172 classes) |

Layout:

```
core/src/sgm/core/      server (Java port), Admin (editor API), Tls, HttpServer, Store
  handlers/             endpoints, 1:1 with sgm-server/sgserver/handlers
  vpn/                  PacketEngine (DNS + address rewriting), Gateway, CdnCache, UdpDnsUpstream
  extract/              UnityExtractor: SerializedFile reader for the on-device import
core/web/admin.html     account editor (single page)
app/                    Android app: MainActivity, SgmVpnService, EditorActivity, Engine, GameImporter
tools/                  build/test helpers (typetree dump, TUN test bench, content comparison)
```
