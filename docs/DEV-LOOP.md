# Developer loop

The b01 dev loop: a patched APK whose game API points to `http://127.0.0.1:47820`, a server
on your PC, and `adb reverse` joining the two. The phone needs no Wi-Fi changes and no DNS tricks.

```
phone: game -> http://127.0.0.1:47820 --(adb reverse)--> PC: 127.0.0.1:47820 reference server
```

## One-time setup

1. Python 3.10+, a JDK 17+ (`java`, `javac`, `keytool`), and `adb` (Android platform-tools).
2. Extract the game content from your copy (needs `pip install -r tools/content-gen/requirements.txt`):
   ```bash
   python tools/content-gen/extract_content.py ~/games/skullgirls-8.12.0.xapk -o ~/games/sgm-content
   ```
3. Create your signing key once (stored in `~/.config/sgm-offline/`):
   ```bash
   python -m sgmo_patcher keygen
   ```
   Run the `sgmo_patcher` commands from `patcher/`, or set `PYTHONPATH=patcher`.

## Each build

1. Patch your copy:
   ```bash
   python -m sgmo_patcher patch ~/games/skullgirls-8.12.0.xapk -o deliver/sgmo-b01.apk
   ```
   This writes `deliver/sgmo-b01.apk` plus `deliver/sgmo-b01.apk.report.txt` and
   `deliver/sgmo-b01.apk.report.json`.
2. Start the server on loopback:
   ```bash
   cd reference-server && python -m sgserver --content ~/games/sgm-content --db ~/games/sgm-dev.db --host 127.0.0.1 --port 47820 -v
   ```
3. Join phone and PC (repeat after every USB reconnect):
   ```bash
   adb reverse tcp:47820 tcp:47820
   ```
4. Install. The first time, the Play version must be uninstalled, because the signature differs.
   After that, new builds install over the old one:
   ```bash
   adb install -r deliver/sgmo-b01.apk
   ```
5. Watch the logs:
   ```bash
   adb logcat -v time Unity:V AndroidRuntime:E SignatureCheck:V VMRunner:V '*:S'
   ```

## Logs to send after a session

- the server console output (or run it with `2>&1 | tee server.log`);
- `adb logcat -d -v time > device.log` taken right after the problem;
- `deliver/sgmo-b01.apk.report.txt`.
