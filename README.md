<h1 align="center">PIIK TV</h1>
<p align="center"><strong>Watch <a href="https://github.com/TNTcraftHIM/Piik">Piik</a> rooms on Android TV and Google TV, smooth and with low latency.</strong></p>
<p align="center">English · <a href="./README.it.md">Italiano</a> · <a href="./README.zh-CN.md">简体中文</a></p>

PIIK TV is an Android TV app that joins a Piik room as a viewer and plays it full
screen. It is made for showing a PC on the living-room TV over the home network:
hardware video decoding straight to the screen, frames paced on the display's
refresh, and an interface built for the remote.

> PIIK TV is an independent app. It is not made by, or affiliated with, the Piik
> project.

## Features

- **Finds the PC by itself.** It scans the local network for Piik (port 8787),
  then joins with the 4-digit room code. Next time it reconnects to the last PC
  and room automatically (optional). From 1.7.0, with VibePiik 1.2.0 it joins the room
  that is open on the PC even if the code has changed; if that room is gone it asks
  for the new code instead of retrying the old one.
- **Invitation links**, also for Public invite rooms reached from outside the
  home (`https://….trycloudflare.com/r/1234#v=…`).
- **Links sent from the PC.** While the app is open, the TV listens on TCP 8790:
  [VibePiik](https://github.com/Kaitonume/VibePiik) (Piik with PIIK TV support, for Windows; optional: PIIK TV also works with official Piik) sends the room link with one
  click, and any browser can send one from `http://<TV address>:8790`.
- **Hardware decoding** of H.264, HEVC (from 1.7.0), VP8, VP9 and AV1 when the TV supports them,
  rendered directly to the screen in low-latency mode where available; software
  decoding as a fallback for VP8, VP9 and AV1.
- **Frame pacing** on the display refresh: frames that arrive irregularly over
  Wi-Fi are shown at a steady rhythm, with the 60 Hz display mode selected when
  the TV offers one.
- **Three latency modes**: minimum (no buffer, drops late frames), balanced and
  maximum smoothness.
- **On-screen statistics** (OK on the remote): resolution, fps, bitrate, loss,
  RTT, jitter, decoder, buffer and smoothness counters.
- **English, Italian and Chinese.** Automatic (the TV's language, English for any other) or chosen in Settings → Language.
- **Updates from the TV** (from 1.6.0). When a new release is out, a button on the start
  screen shows its changelog and installs it on the spot, after checking its SHA-256
  (Android asks once to allow PIIK TV to install apps). Important updates open by
  themselves once. Some TVs (TCL, for example) block apps from starting by themselves:
  there, open PIIK TV again after the update. Settings → Updates shows the installed
  version and checks right away (from 1.7.1).

## Requirements

- Android 5.0 or later (API 21): Android TV, Google TV, Fire TV, TV boxes and
  sticks. It can also be installed on phones and tablets (landscape only).
- ARM (32 or 64-bit) or x86 (32 or 64-bit).

Tested on a TCL Google TV (Android 12, ARM 32-bit, Realtek decoder) with Piik
hosted from Windows and from the Piik Android app. Other devices and Android
versions are supported by the build but have not been tested yet: reports are
welcome.
- A Piik room on the same network (Piik App or VibePiik on a PC, the Piik Android
  app, …) or a Piik invitation link.

## Install

Download an APK from the [Releases page](https://github.com/Kaitonume/Piik-TV/releases):

| File | For |
| --- | --- |
| `PiikTV-<version>-universal.apk` | Every device (recommended). |
| `PiikTV-<version>-armeabi-v7a.apk` | ARM 32-bit: most TVs and sticks. |
| `PiikTV-<version>-arm64-v8a.apk` | ARM 64-bit. |
| `PiikTV-<version>-x86.apk`, `-x86_64.apk` | Intel/AMD devices and emulators. |

Copy it to the TV (USB stick and a file manager, a "send files to TV" app, or
`adb install`) and allow installing apps from unknown sources. Every APK of the
same version can update any other.

## Use

1. On the PC open Piik (Local room or Public invite) and start sharing.
2. Open PIIK TV: choose the PC it found and type the room code, or use
   **Address or link…** for an invitation link, or send the link from the PC.

Remote control while watching:

| Key | Action |
| --- | --- |
| OK | Show or hide statistics |
| Up | Ask the PC for a keyframe |
| Back | Close the stream and go back to the menu (Back again exits) |
| Home | Close the stream; reopening the app reconnects to the last PC |

Settings: latency mode, audio, statistics at startup, audio/video sync (off =
the picture is shown as soon as possible), automatic connection, the TV's name in
Piik, VP8 decoder (hardware or software), language.

For the smoothest picture use H.264 on the PC (the TV decodes it in hardware) and
a 5 GHz or wired connection for the PC; on a busy 2.4 GHz network lower the
bitrate or resolution in Piik. With [VibePiik](https://github.com/Kaitonume/VibePiik)
1.2.0 you can also share in HEVC or AV1: about half the bitrate for the same
picture, and up to 4K where the TV's decoder allows it (many TVs decode H.264 only
up to 1080p).

## Link receiver

While the app is open on the TV it answers on TCP port 8790:

- `GET /info` → `{"app":"piik-tv","name":"<TV name>"}`
- `POST /link` with the form field `link=<Piik room link>` → the TV opens that
  room (also while watching another one).
- `GET /` → a small page to paste a link from any browser (English or Italian,
  from the browser's language).

Only Piik room links are accepted (`http(s)://host[:port]/r/1234…`, with an
optional `#v=…` invitation). The receiver stops when the app goes to the
background.

## Build

Requirements: JDK 17 and the Android SDK (platform 35). Set `sdk.dir` in
`local.properties` or `ANDROID_HOME`, then:

```sh
./gradlew assembleRelease
```

The APKs (four architectures and the universal one) are written to
`app/build/outputs/apk/release/`.

Release signing reads a `keystore.properties` file (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`; `storeFile` relative to that file) from, in order: the
path in the `PIIKTV_KEYSTORE_PROPERTIES` environment variable,
`../keystore/keystore.properties` next to the project folder, or
`keystore/keystore.properties` in the project. Without one, release APKs are
signed with the debug key. Never commit a keystore.

## Publishing a release

- Raise `versionCode` and `versionName` in `app/build.gradle.kts`; tag `v<versionName>`.
- Attach the five APKs with their names unchanged (`PiikTV-<version>-<abi>.apk`):
  the app picks the one for its architecture and checks its SHA-256.

The apps read the release notes this way:

- `<!-- importance: important -->` anywhere in the notes marks the update as
  **important** (the line is invisible on GitHub): the update window then opens by
  itself once. Without it the update is optional (only the button).
- The changelog shown in the app is the summary line in its language, then the main
  English text. Summary lines go after a `---` line and start with `**EN**`,
  `**IT**` or `**中文**`. Sections titled `### Install` and code blocks (checksums)
  are not shown.
- Leave the release as a normal release (not draft, not pre-release).
## License

PIIK TV is released under the [MIT License](./LICENSE). It includes WebRTC,
OkHttp, Okio and the Kotlin standard library: see
[THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md).
