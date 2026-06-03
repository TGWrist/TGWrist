<a href="https://github.com/TGWrist/TGWrist">
<img src="https://socialify.git.ci/TGWrist/TGWrist/image?description=1&descriptionEditable=A%20smooth%2C%20polished%20open-source%20third-party%20Telegram%20client%20for%20Wear%20OS.&font=KoHo&forks=1&issues=1&logo=https%3A%2F%2Fraw.githubusercontent.com%2FTGWrist%2FTGWrist%2Fmaster%2FTGWrist.png&name=1&owner=1&pattern=Circuit%20Board&pulls=1&stargazers=1&theme=Auto" alt="TGWrist" />
</a>

<div align="center">
  <br/>
  <div>
      English | <a href="./README.zh-CN.md">简体中文</a> | <a href="./README.zh-TW.md">繁體中文</a> | <a href="./README.ja-JP.md">日本語</a> | <a href="./README.ru-RU.md">Русский</a>
  </div>
  <br/>

<div>
    <a href="https://github.com/TGWrist/TGWrist/releases">
      <img
        src="https://img.shields.io/github/downloads/TGWrist/TGWrist/total?style=flat-square"
      />  
    </a >
    <a href="https://github.com/MShawon/github-clone-count-badge">
      <img
        src="https://img.shields.io/badge/dynamic/json?color=success&label=Views&query=count&url=https://gist.githubusercontent.com/gohj99/323da380ac4f041b13845b74e058ab33/raw/traffic.json&logo=github&style=flat-square"
      />  
    </a >
    <a href="https://play.google.com/store/apps/details?id=com.tgwrist.app">
      <img
        src="https://img.shields.io/endpoint?color=green&logo=google-play&logoColor=green&url=https%3A%2F%2Fplay.cuzi.workers.dev%2Fplay%3Fi%3Dcom.tgwrist.app%26gl%3Dus%26hl%3Den%26l%3DWearOS%26m%3D%24name&style=flat-square"
      />
    </a >
  </div>
</div>

![Screenshot](Screenshot.png)

## Download

We recommend downloading TG Wrist from Google Play:

[Download on Google Play](https://play.google.com/store/apps/details?id=com.tgwrist.app)

Google Play provides the version best suited for your device, usually with a smaller download size. It also makes installation and future updates easier, without requiring manual ADB installation.

### ADB Installation

> Not recommended for most users.  
> Please install from Google Play whenever possible.

If you still want to install the APK manually:

1. Download the APK from GitHub Releases.
2. Install it with ADB:

```shell
adb install TGWrist.apk
```

## Features

- Run independently on Wear OS without relying on your phone
- Browse chat lists, read message history, and receive timely message notifications through FCM
- Send text messages, voice messages, and photo or video messages directly from your watch
- Select one or more messages with a long press for quick actions
- Reply to messages and forward one or multiple messages, with options such as hiding the sender name or removing captions when supported
- Make and receive Telegram VoIP calls, with incoming call notifications, call status updates, and audio output switching
- Preview, download, play, and save supported media from message details
- Supported message rendering includes text, photos, videos, documents, GIF/animations, animated emoji, stickers, voice notes, video notes, audio files, and call records
- Supports chat list, message history, message previews, media albums, and interaction info updates

## Design Details

- Designed specifically for Wear OS watches
- Carefully optimized for small round screens
- Uses layouts tailored for Wear OS devices
- Uses a Material Design 3 / Wear OS style UI
- Smooth scrolling and responsive interactions

## Privacy & Transparency

- Protects sensitive local account data with Android Keystore / TEE
- Stores account data in app-private local storage
- Deletes local account data after logout
- Lets users control whether Google-related services such as FCM are enabled
- Open source code for transparency and public review

## Community

We recommend using [issue](https://github.com/TGWrist/TGWrist/issues) to provide the most direct
and effective feedback. Of course, the following options for feedback are also available:

- [Telegram Channel](https://t.me/tgwrist)
- [Telegram Group](https://t.me/TGwristChat)

## License

This project is source-available, but it is not open-source software.

The source code is publicly available for transparency, security review, and
public inspection only. You may not copy, reuse, modify, redistribute, or create
derivative works from this code without explicit written permission.

## Star History

<a href="https://star-history.com/#TGWrist/TGWrist&Date">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=TGWrist/TGWrist&type=Date&theme=dark" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=TGWrist/TGWrist&type=Date" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=TGWrist/TGWrist&type=Date" />
 </picture>
</a>
