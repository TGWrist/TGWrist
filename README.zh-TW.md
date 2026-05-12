<a href="https://github.com/TGWrist/TGWrist">
<img src="https://socialify.git.ci/TGWrist/TGWrist/image?description=1&descriptionEditable=%E4%B8%80%E5%80%8B%E6%B5%81%E6%9A%A2%E3%80%81%E7%B2%BE%E7%B7%BB%E3%80%81%E9%96%8B%E6%94%BE%E5%8E%9F%E5%A7%8B%E7%A2%BC%E7%9A%84%20Wear%20OS%20%E7%AC%AC%E4%B8%89%E6%96%B9%20Telegram%20%E7%94%A8%E6%88%B6%E7%AB%AF%E3%80%82&font=KoHo&forks=1&issues=1&logo=https%3A%2F%2Fraw.githubusercontent.com%2FTGWrist%2FTGWrist%2Fmaster%2FTGWrist.png&name=1&owner=1&pattern=Circuit%20Board&pulls=1&stargazers=1&theme=Auto" alt="TGWrist" />
</a>

<div align="center">
  <br/>
  <div>
      <a href="./README.md">English</a> | <a href="./README.zh-CN.md">简体中文</a> | 繁體中文 | <a href="./README.ja-JP.md">日本語</a> | <a href="./README.ru-RU.md">Русский</a>
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

![截圖](Screenshot.png)

## 下載

我們建議從 Google Play 下載 TG Wrist：

[在 Google Play 下載](https://play.google.com/store/apps/details?id=com.tgwrist.app)

Google Play 會提供最適合你裝置的版本，通常下載體積也更小。它也能讓安裝和後續更新更輕鬆，無需手動使用 ADB 安裝。

### ADB 安裝

> 不建議大多數使用者使用。  
> 請盡可能從 Google Play 安裝。

如果你仍想手動安裝 APK：

1. 從 GitHub Releases 下載 APK。
2. 使用 ADB 安裝：

```shell
adb install TGWrist.apk
```

## 功能

- 直接從手錶傳送文字訊息
- 接收文字、圖片、影片和其他訊息
- 無需依賴手機即可獨立執行
- 透過 FCM 及時取得訊息通知
- 支援聊天列表、訊息歷史和媒體預覽

## 設計細節

- 專為 Wear OS 手錶設計
- 針對小型圓形螢幕精心最佳化
- 使用適配 Wear OS 裝置的版面配置
- 使用 Material Design 3 / Wear OS 風格介面
- 平滑捲動和響應式互動

## 隱私與透明度

- 使用 Android Keystore / TEE 保護敏感的本機帳號資料
- 將帳號資料儲存在應用程式私有的本機儲存空間中
- 登出後刪除本機帳號資料
- 允許使用者控制是否啟用 FCM 等 Google 相關服務
- 開源程式碼，用於透明化和公開審查

## 社群

我們建議使用 [issue](https://github.com/TGWrist/TGWrist/issues) 提供最直接、
有效的回饋。當然，也可以透過以下方式回饋：

- [Telegram Channel](https://t.me/tgwrist)
- [Telegram Group](https://t.me/TGwristChat)

## 授權

本專案原始碼可公開查看，但不是開源軟體。

原始碼僅為透明度、安全審查和公開檢視而公開。未經明確書面許可，
不得複製、重用、修改、再散布或基於此程式碼建立衍生作品。

## Star History

<a href="https://star-history.com/#TGWrist/TGWrist&Date">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=TGWrist/TGWrist&type=Date&theme=dark" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=TGWrist/TGWrist&type=Date" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=TGWrist/TGWrist&type=Date" />
 </picture>
</a>
