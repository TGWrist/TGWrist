<a href="https://github.com/TGWrist/TGWrist">
<img src="https://socialify.git.ci/TGWrist/TGWrist/image?description=1&descriptionEditable=Wear%20OS%20%E5%90%91%E3%81%91%E3%81%AE%E3%82%B9%E3%83%A0%E3%83%BC%E3%82%BA%E3%81%A7%E6%B4%97%E7%B7%B4%E3%81%95%E3%82%8C%E3%81%9F%E3%80%81%E3%82%BD%E3%83%BC%E3%82%B9%E3%82%B3%E3%83%BC%E3%83%89%E5%85%AC%E9%96%8B%E3%81%AE%E3%82%B5%E3%83%BC%E3%83%89%E3%83%91%E3%83%BC%E3%83%86%E3%82%A3%20Telegram%20%E3%82%AF%E3%83%A9%E3%82%A4%E3%82%A2%E3%83%B3%E3%83%88%E3%80%82&font=KoHo&forks=1&issues=1&logo=https%3A%2F%2Fraw.githubusercontent.com%2FTGWrist%2FTGWrist%2Fmaster%2FTGWrist.png&name=1&owner=1&pattern=Circuit%20Board&pulls=1&stargazers=1&theme=Auto" alt="TGWrist" />
</a>

<div align="center">
  <br/>
  <div>
      <a href="./README.md">English</a> | <a href="./README.zh-CN.md">简体中文</a> | <a href="./README.zh-TW.md">繁體中文</a> | 日本語 | <a href="./README.ru-RU.md">Русский</a>
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

![スクリーンショット](Screenshot.png)

## ダウンロード

TG Wrist は Google Play からダウンロードすることをおすすめします。

[Google Play でダウンロード](https://play.google.com/store/apps/details?id=com.tgwrist.app)

Google Play では、お使いのデバイスに最適なバージョンが提供され、通常はダウンロードサイズも小さくなります。また、手動で ADB インストールを行わなくても、インストールや今後のアップデートが簡単になります。

### ADB インストール

> ほとんどのユーザーにはおすすめしません。  
> 可能な限り Google Play からインストールしてください。

それでも APK を手動でインストールしたい場合:

1. GitHub Releases から APK をダウンロードします。複数の APK がある場合は、`full`、`arm32`、`arm64` など、ウォッチのアーキテクチャに合うものを選んでください。
2. ADB でインストールします。

```shell
adb install TGWrist.apk
```

## 機能

- スマートフォンに依存せず、Wear OS ウォッチ上で単独動作
- すべてのチャット、アーカイブ済みチャット、チャットフォルダーを閲覧でき、未読数、チャット画像、オンライン/メンバー情報の更新、スクロール位置の保持に対応
- メッセージ履歴の表示、未読メッセージへの移動、最新メッセージへのジャンプ、入力中/アップロード中/録音中などのアクション表示、安定したページ読み込みに対応
- FCM ベースの Telegram 通知に対応し、通知のグループ化、該当チャットへの安定した移動、通知内の既読化、ウォッチからのクイック返信が可能
- 時計からテキストメッセージ、音声メッセージ、写真または動画メッセージを直接送信でき、複数のメディアを選択した場合はメディアアルバムとして送信可能
- ウォッチ上でアカウントを管理でき、アカウント追加、ログアウト、ローカルアカウントデータの削除に対応
- メッセージを長押しして選択モードに入り、単一または複数選択、選択済みの返信/転送対象の解除、重複転送エントリの回避に対応
- メッセージへの返信、対応メッセージでの本文またはキャプション編集、選択した写真または動画による編集可能メディアの置き換え、Telegram が許可する場合の自分用または全員向け削除に対応
- 単一または複数メッセージの転送に対応し、対応している場合は送信者名の非表示やキャプション削除も可能
- `tg://`、公開チャット、メッセージリンク、ボット開始リンク、招待リンクなどの Telegram リンクを開き、招待プレビューの表示やウォッチ上でのグループ/チャンネル参加が可能
- Telegram VoIP 通話の発信と着信に対応し、着信通知、通話状態更新、音声出力の切り替えを利用可能
- メッセージ詳細で対応メディアのプレビュー、ダウンロード、外部アプリで開く操作、再生、保存が可能で、内蔵動画再生、音声ファイル、音声メモ、音声録音プレビューのタップ/ドラッグシークに対応
- 送信者、返信先、トピック、ピン留め状態、表示/転送数、リアクション、翻訳結果、選択またはコピー可能なリッチテキストをメッセージ詳細で確認可能
- 位置情報メッセージに対応し、緯度、経度、精度、ライブ位置情報の項目を表示でき、座標コピーと地図で開く操作も可能
- 投票とクイズの表示、単一選択と複数選択の投票、結果表示、クイズ解説、許可されている場合の再投票、編集可能な投票の停止に対応
- 設定からストレージを管理でき、写真、一時ファイル、ドキュメント、サムネイル、音声メッセージ、動画などの使用量確認と削除に対応
- 対応するメッセージ表示: テキスト、写真、動画、ドキュメント、GIF/アニメーション、アニメーション絵文字、ステッカー、音声メモ、ビデオメモ、音声ファイル、位置情報、ライブ位置情報、投票、クイズ、通話履歴、多くの Telegram サービスメッセージ
- ログイン画面と設定画面からネットワーク接続を管理でき、直接接続、SOCKS5、HTTP、MTProto プロキシに対応。プロキシ一覧は保存され、アカウントまたは TDLib の再起動後に自動で再適用
- チャットリスト、メッセージ履歴、メッセージプレビュー、メディアアルバム、メッセージ操作情報の更新、トリミングされたメッセージのシマー付きプレースホルダー読み込み、低メモリ端末向けのチャットメッセージキャッシュに対応

## デザイン詳細

- Wear OS ウォッチ専用に設計
- 小さな円形画面向けに丁寧に最適化
- Wear OS デバイスに合わせたレイアウトを使用
- Material Design 3 / Wear OS スタイルの UI を使用
- スムーズなスクロールと応答性の高いインタラクション

## プライバシーと透明性

- Android Keystore / TEE で機密性の高いローカルアカウントデータを保護
- アカウントデータをアプリ専用のローカルストレージに保存
- ログアウト後にローカルアカウントデータを削除
- FCM などの Google 関連サービスを有効にするかどうかをユーザーが制御可能
- 透明性と公開レビューのためにソースコードを公開

## コミュニティ

最も直接的で効果的なフィードバックには [issue](https://github.com/TGWrist/TGWrist/issues) の利用をおすすめします。
もちろん、以下の方法でもフィードバックできます。

- [Telegram Channel](https://t.me/tgwrist)
- [Telegram Group](https://t.me/TGwristChat)

## ライセンス

このプロジェクトはソースコードを公開していますが、オープンソースソフトウェアではありません。

ソースコードは、透明性、セキュリティレビュー、公開調査のみを目的として公開されています。
明示的な書面による許可なく、このコードをコピー、再利用、変更、再配布したり、派生作品を作成したりすることはできません。

## Star History

<a href="https://star-history.com/#TGWrist/TGWrist&Date">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=TGWrist/TGWrist&type=Date&theme=dark" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=TGWrist/TGWrist&type=Date" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=TGWrist/TGWrist&type=Date" />
 </picture>
</a>
