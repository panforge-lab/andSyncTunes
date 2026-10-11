# andSyncTunes

**iTunesとAndroid端末の間で、楽曲・プレイリストを快適にワイヤレス同期する超軽量Wi-Fi同期ツール。**  
Windowsタスクトレイ常駐エージェントと、Androidネイティブクライアントで構成されています。

[![GitHub release](https://img.shields.io/badge/release-v0.9.0%20(Beta)-blue.svg)](https://github.com/panforge-lab/andSyncTunes/releases)
[![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Android-lightgrey.svg)](#動作環境)
[![OFUSE](https://img.shields.io/badge/OFUSE-ぱぱんぱを応援-orange.svg)](https://ofuse.me/panforge)

---

## 🌟 特徴・こだわり

- **🚀 ケーブル不要の高速 Wi-Fi 同期**  
  PCとスマホが同じWi-Fiに繋がっていれば、ワンタップで高速同期。テザリング（P2P）環境でもギガ消費ゼロで使えます。
- **⚡ 超軽量・爆速設計**  
  無駄なライブラリや広告SDKを一切排除。Android APKサイズは驚異の **1.96 MB**、Windowsエージェントは **740 KB** の超コンパクト設計。
- **🛡️ 途切れない堅牢なバックグラウンド通信**  
  Androidの省電力（Dozeモード）や画面消灯時でも安全に完走。電波が途切れても自動リトライ＆中断箇所からのレジューム（HTTP Range）再開に対応。
- **🎵 万全のプレイヤー互換性 (UTF-8 M3U8)**  
  同期したプレイリストは標準M3U8形式でmicroSDまたは本体ストレージへ直接保存。Poweramp、Musicolet、GoneMADなどの高音質プレイヤーで即座に反映されます。
- **📱 QRコードで一発ペアリング**  
  PCの画面に表示されるQRコードをスマホのカメラでかざすだけ。面倒なIPアドレス入力は不要です。

---

## 📥 ダウンロード

最新の安定版は [**GitHub Releases**](https://github.com/panforge-lab/andSyncTunes/releases) よりダウンロードいただけます。

| プラットフォーム | パッケージ | 要件 |
|---|---|---|
| **Android アプリ** | `andSyncTunes-v0.9.0.apk` (1.96 MB) | Android 7.0 (Nougat) 以上 |
| **Windows PC エージェント** | `andSyncTunes-Windows-v0.9.0.zip` (740 KB) | Windows 10 / 11 (64-bit) |

---

## 🚀 クイックスタート

### 1. Windows PC 側の準備
1. 配布ZIPを展開し、`andSyncTunes.Desktop.exe` を実行します。
2. タスクトレイ（画面右下）にアイコンが常駐します。
3. 初回起動時、iTunesの `[編集] ➔ [環境設定] ➔ [詳細] ➔ 「iTunesライブラリXMLを他のアプリケーションと共有」` にチェックが入っていることをご確認ください。
4. トレイアイコンをダブルクリックすると、接続用QRコード付きのWebダッシュボードが開きます。

### 2. Android 側の準備
1. APKを端末にインストールして起動します。
2. 初回起動時に音楽の保存先フォルダ（microSDや内蔵ストレージのMusicフォルダ等）を選択します。
3. 「サーバー接続」からPC画面のQRコードをスキャンします。
4. 同期したいプレイリストにチェックを入れて **「同期開始」** をタップするだけ！

---

## ☕ 開発応援・ドネーションについて

andSyncTunes は、すべての機能を **完全無料・広告なし・トラッキングなし** で提供しています。

もしこのツールが気に入っていただけたり、日々の音楽ライフが便利になったと感じていただけましたら、クリエイター応援プラットフォーム **OFUSE（オフセ）** より温かいメッセージやコーヒー代をご支援いただけますと、今後の開発・アップデートの大きな励みになります！

👉 **[ぱぱんぱ の OFUSE プロフィールをみる（https://ofuse.me/panforge）](https://ofuse.me/panforge)**

---

## 📜 ライセンス・クレジット

- **制作**: panForge / ぱぱんぱ ([@panforge-lab](https://github.com/panforge-lab))
- **仕様詳細**: [SPEC.md](SPEC.md) をご参照ください。
