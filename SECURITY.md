# Security

## What the local server exposes

- The server (the reference server on a PC in b01; the embedded server from M2) speaks plain HTTP
  and is meant to listen on **127.0.0.1 only**. In the dev loop the phone reaches the PC through
  `adb reverse`, which also stays on loopback on both ends.
- The reference server's default bind address is `0.0.0.0` for historical reasons. In the dev
  loop always pass `--host 127.0.0.1`. Binding to a LAN address exposes your local account and
  its unauthenticated admin-like endpoints to everyone on that network.
- There are no real accounts or payments: logins are local, IAP is disabled.

## Signing key

- Your signing key lives in `~/.config/sgm-offline/` (outside the repo). Anyone with it can build
  an APK that installs over yours and reads the game's private data. Do not share it.

## Reporting

Open a private security advisory on the repository, or an issue without exploit details.
