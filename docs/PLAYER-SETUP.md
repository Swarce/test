# Player setup (b01)

> b01 is an early developer build. It needs a PC and a USB cable. Later builds will run the server
> inside the app with no PC. Nothing here redistributes the game: you patch **your own** copy.

## What you need

- Your own copy of Skullgirls Mobile **8.12.0** as an `.xapk` (APKPure), `.apks` (SAI/bundletool), or
  the `base.apk` + `config.arm64_v8a.apk` pulled from your phone.
- A PC with **Python 3.10+**, a **JDK 17+**, and **adb** (Android platform-tools).
- A phone (tested target: Galaxy S22, Android 16) with USB debugging on.

## 1. Patch your copy

```bash
python -m sgmo_patcher keygen                                   # once: makes your signing key
python -m sgmo_patcher patch your-skullgirls-8.12.0.xapk -o deliver/sgmo-b01.apk
```

Run these from the `patcher/` folder (or set `PYTHONPATH=patcher`). The patcher writes
`deliver/sgmo-b01.apk` and a report (`.report.txt`). Your signing key lives in
`~/.config/sgm-offline/` and never leaves your machine — keep it; every build uses it, so new builds
install over the old one and keep your progress.

## 2. Extract the game content for the server (once)

```bash
pip install -r tools/content-gen/requirements.txt
python tools/content-gen/extract_content.py your-skullgirls-8.12.0.xapk -o ~/sgm-content
```

## 3. Install and run

```bash
adb uninstall com.autumn.skullgirls          # first time only: the Play version signs differently
adb install deliver/sgmo-b01.apk
adb reverse tcp:47820 tcp:47820              # repeat after each USB reconnect

cd reference-server
python -m sgserver --content ~/sgm-content --db ~/sgm.db --host 127.0.0.1 --port 47820 -v
```

Start the game and choose **guest**. A new player skips the tutorial and starts with the tutorial's
final team and some currency (configurable).

## Where the logs are

- Server: the console where you ran `python -m sgserver` (add `2>&1 | tee server.log`).
- Device: `adb logcat -d -v time > device.log`.
- Patch report: `deliver/sgmo-b01.apk.report.txt`.

Please send all three after a session; the next build starts from what they show.

## Troubleshooting

- **"App not installed" / signature conflict** — uninstall the Play version first (step 3).
- **Stuck on a loading/title screen** — check `adb reverse` is active and the server is running on
  47820; look for `UNHANDLED` lines in the server log.
- **Error 867 on the title screen** — the game could not reach the CDN maintenance check. In b01 the
  CDN is left pointing at the official server, so this needs internet on first launch. If the
  official CDN is down, say so in your log; the CDN redirect lands in a later build.
- **A crash at launch** — this is the thing b01 is testing (PairIP's native component). Send
  `device.log`; search it for `pairip`, `SignatureCheck`, `VMRunner`, or a native abort.
