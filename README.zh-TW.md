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

1. 從 GitHub Releases 下載 APK。如果提供多個 APK，請選擇與手錶架構相符的版本，例如 `full`、`arm32` 或 `arm64`。
2. 使用 ADB 安裝：

```shell
adb install TGWrist.apk
```

## 功能

- 無需依賴手機，可在 Wear OS 手錶上獨立執行
- 瀏覽全部聊天、封存聊天和聊天資料夾，支援未讀數量、聊天頭像、線上/成員資訊更新和捲動位置保持
- 查看訊息歷史，支援未讀訊息定位、跳轉最新訊息、正在輸入/上傳/錄製等會話動作狀態，以及更可靠的分頁載入
- 透過 FCM 接收 Telegram 通知，支援訊息通知分組、穩定跳轉到對應聊天、通知內標記已讀和快捷回覆
- 直接從手錶傳送文字訊息、語音訊息，以及圖片或影片訊息；選取多個媒體時可傳送媒體相簿
- 在手錶上管理帳號，支援新增帳號、登出和刪除本機帳號資料
- 長按訊息進入選取模式，支援單選或多選、取消已選回覆/轉傳目標，並避免重複加入轉傳訊息
- 支援訊息回覆、在支援的訊息類型上編輯文字或說明文字、用選取的圖片或影片替換可編輯媒體，以及在 Telegram 允許時為自己或所有人刪除訊息
- 支援單則/多則訊息轉傳，可在支援時隱藏傳送者名稱或移除說明文字
- 支援開啟 `tg://`、公開聊天、訊息連結、機器人啟動連結和邀請連結等 Telegram 連結，並可預覽邀請資訊、直接在手錶上加入群組或頻道
- 支援 Telegram VoIP 通話，可撥打和接聽電話，並提供來電通知、通話狀態更新和音訊輸出切換
- 可在訊息詳情中預覽、下載、用外部應用程式開啟、播放和儲存支援的媒體內容，支援內建影片播放，音訊檔案、語音訊息和語音錄製預覽支援點按或拖曳進度
- 可查看訊息傳送者、回覆目標、話題、置頂狀態、查看/轉傳次數、表情回應、翻譯結果，以及可選取或複製的富文字內容
- 支援位置訊息，顯示經緯度、精確度、即時位置欄位，可複製座標並開啟地圖
- 支援查看和參與投票/測驗，包括單選、多選、結果顯示、測驗解釋、允許時撤回投票，以及停止可編輯投票
- 支援在設定中管理儲存空間，可查看並清理照片、臨時檔案、文件、縮圖、語音訊息和影片等目錄
- 目前支援渲染的訊息類型包括文字、圖片、影片、文件、GIF/動畫、動畫表情、貼圖、語音訊息、圓形影片訊息、音訊檔案、位置、即時位置、投票、測驗、通話記錄和多種 Telegram 服務訊息
- 支援在登入和設定中管理網路連線，可使用直連、SOCKS5、HTTP 和 MTProto 代理；代理列表會持久儲存，並在帳號或 TDLib 重新啟動後自動重新套用
- 支援聊天列表、訊息歷史、訊息預覽、媒體相簿、訊息互動資訊更新、被裁剪訊息的微光佔位載入，以及適配低記憶體裝置的聊天訊息快取

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
