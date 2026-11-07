<h1 align="center">PIIK TV</h1>
<p align="center"><strong>在 Android TV 和 Google TV 上观看 <a href="https://github.com/TNTcraftHIM/Piik">Piik</a> 房间，流畅、低延迟。</strong></p>
<p align="center"><a href="./README.md">English</a> · <a href="./README.it.md">Italiano</a> · 简体中文</p>

PIIK TV 是一款 Android TV 应用，以观众身份加入 Piik 房间并全屏播放。它专为通过家庭网络把电脑画面
投到客厅电视而设计：视频硬件解码后直接输出到屏幕，按显示器刷新率调度帧，界面为遥控器操作而打造。

> PIIK TV 是独立应用，并非由 Piik 项目开发，也与其无关。

## 功能

- **自动找到电脑。** 在本地网络中扫描 Piik（端口 8787），然后输入 4 位房间号加入。下次会自动重连到
  上次的电脑和房间（可关闭）。从 1.7.0 起，配合 VibePiik 1.2.0，即使房间号已变化也会加入电脑上
  当前打开的房间；如果该房间已不存在，会提示输入新房间号，而不是反复尝试旧的。
- **邀请链接**，也支持从家庭网络之外访问的 Public invite 房间
  （`https://….trycloudflare.com/r/1234#v=…`）。
- **从电脑发送链接。** 应用打开期间，电视在 TCP 8790 端口监听：[VibePiik](https://github.com/Kaitonume/VibePiik)（可选，PIIK TV 也支持官方 Piik；支持 PIIK TV 的 Windows 版
  Piik）一键即可发送房间链接，任何浏览器也可以通过 `http://<电视地址>:8790` 发送链接。
- **硬件解码** H.264、HEVC（1.7.0 起）、VP8、VP9 和 AV1（需电视支持），在可用时以低延迟模式直接渲染到屏幕；
  VP8、VP9 和 AV1 可回退到软件解码。
- **按刷新率调度帧**：通过 Wi-Fi 不规律到达的帧会以稳定的节奏显示；电视提供 60 Hz 显示模式时会自动选用。
- **三种延迟模式**：最低（无缓冲，丢弃迟到的帧）、均衡和最流畅。
- **屏幕统计信息**（按遥控器 OK 键）：分辨率、帧率、码率、丢包、RTT、抖动、解码器、缓冲和流畅度计数。
- **英文、意大利文和中文。** 自动（跟随电视语言，其他语言显示英文），也可在 设置 → 语言 中选择。
- **在电视上更新**（1.6.0 起）。有新版本时，首页会出现一个按钮，显示更新日志，并在校验 SHA-256 后立即安装
  （Android 会请求一次允许 PIIK TV 安装应用）。重要更新会自动弹出一次。部分电视（例如 TCL）禁止应用自行启动：在这些电视上，更新后请重新打开 PIIK TV。

## 系统要求

- Android 5.0 或更高版本（API 21）：Android TV、Google TV、Fire TV、电视盒子和电视棒。
  也可以安装在手机和平板上（仅横屏）。
- ARM（32 或 64 位）或 x86（32 或 64 位）。

已在 TCL Google TV（Android 12，ARM 32 位，Realtek 解码器）上测试，Piik 分别由 Windows 和 Piik
Android 应用开启房间。其他设备和 Android 版本在构建上受支持，但尚未测试：欢迎反馈。
- 同一网络中的 Piik 房间（电脑上的 Piik App 或 VibePiik、Piik Android 应用……）或 Piik 邀请链接。

## 安装

从 [Releases 页面](https://github.com/Kaitonume/Piik-TV/releases) 下载 APK：

| 文件 | 适用于 |
| --- | --- |
| `PiikTV-<version>-universal.apk` | 所有设备（推荐）。 |
| `PiikTV-<version>-armeabi-v7a.apk` | ARM 32 位：大多数电视和电视棒。 |
| `PiikTV-<version>-arm64-v8a.apk` | ARM 64 位。 |
| `PiikTV-<version>-x86.apk`、`-x86_64.apk` | Intel/AMD 设备和模拟器。 |

把它复制到电视上（U 盘加文件管理器、“发送文件到电视”类应用，或 `adb install`），并允许安装未知来源的应用。
同一版本的任意 APK 都可以互相覆盖更新。

## 使用

1. 在电脑上打开 Piik（Local room 或 Public invite）并开始分享。
2. 打开 PIIK TV：选择找到的电脑并输入房间号，或使用 **Address or link…** 输入邀请链接，
   或者从电脑发送链接。

观看时的遥控器按键：

| 按键 | 操作 |
| --- | --- |
| OK | 显示或隐藏统计信息 |
| 上 | 向电脑请求关键帧 |
| 返回 | 关闭视频流并返回菜单（再按一次返回退出） |
| 主页 | 关闭视频流；重新打开应用会重连到上次的电脑 |

设置项：延迟模式、音频、启动时显示统计信息、音视频同步（关闭 = 画面尽快显示）、自动连接、电视在 Piik
中显示的名称、VP8 解码器（硬件或软件）、语言。

想要最流畅的画面，请在电脑上使用 H.264（电视以硬件解码），并让电脑使用 5 GHz 或有线连接；
在拥挤的 2.4 GHz 网络上，请在 Piik 中降低码率或分辨率。使用 [VibePiik](https://github.com/Kaitonume/VibePiik)
1.2.0 还可以用 HEVC 或 AV1 分享：相同画质只需约一半码率，在电视解码器支持时最高可达 4K（许多电视的 H.264
解码最高只到 1080p）。

## 链接接收器

应用在电视上打开期间，会在 TCP 端口 8790 上响应：

- `GET /info` → `{"app":"piik-tv","name":"<TV name>"}`
- `POST /link`，表单字段为 `link=<Piik room link>` → 电视打开该房间（正在观看其他房间时也可以）。
- `GET /` → 一个小页面，可从任意浏览器粘贴链接（英文或意大利文，跟随浏览器语言）。

只接受 Piik 房间链接（`http(s)://host[:port]/r/1234…`，可带 `#v=…` 邀请信息）。应用切到后台时，
接收器会停止。

## 构建

要求：JDK 17 和 Android SDK（platform 35）。在 `local.properties` 中设置 `sdk.dir` 或设置
`ANDROID_HOME`，然后运行：

```sh
./gradlew assembleRelease
```

APK（四种架构和通用版）输出到 `app/build/outputs/apk/release/`。

发布签名会按以下顺序读取 `keystore.properties` 文件（`storeFile`、`storePassword`、`keyAlias`、
`keyPassword`；`storeFile` 相对于该文件）：环境变量 `PIIKTV_KEYSTORE_PROPERTIES` 指定的路径、
项目文件夹旁的 `../keystore/keystore.properties`，或项目内的 `keystore/keystore.properties`。
都没有时，发布版 APK 使用调试密钥签名。切勿提交 keystore。

## 许可

PIIK TV 采用 [MIT 许可](./LICENSE)发布。其中包含 WebRTC、OkHttp、Okio 和 Kotlin 标准库：见
[THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md)。
