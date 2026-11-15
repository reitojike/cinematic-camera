# 実写 AHD / PPG 比較版

## 現在地
記載した範囲のローカル検証が完了。GitHub CI の最終状態は PR #441 を参照。#438 上の積み重ね Draft であり、main への採用変更ではない。
実写アクセス、Pixel操作、比較APKの配布/公開はまだ行っていない。本人が接続時に明示承認してから開始する。

## 安全境界
- 別アプリ ID: dev.cinematic.p0.previewcompare。通常の dev.cinematic.p0 と既存データを上書きしない。
- 明示の -PcinematicPreviewCompare=true だけで比較画面/JNIを含める。
- ephemeral_debug 署名のみ。製品署名モードは禁止、Release variant は作らない。
- 既存 LibRaw 一つを再利用。比較用PPG入口だけを追加。通常プレビューと保存JPEGはAHDのまま。
- 本人がFilesで選ぶ1枚をread-onlyで読む。永続URI許可を取得せず、名前/URIを記録しない。
- 一時コピーは別アプリの内部cacheのみ。終了/取消時に閉じて削除する。
- cleanup失敗後は同じプロセスで比較を再開しない。強制停止後の再起動で前プロセスの固定名残件を整理する。
- OSに終了された場合は一時cacheが残り得る。次の比較開始前に整理し、比較アプリのアンインストールでも削除できる。
- 写真の共有/公開/JPEG保存機能はない。INTERNET/ACCESS_NETWORK_STATEなし、backup無効、FLAG_SECURE。
- AndroidXの非公開初期化provider等は依存から残るが、写真共有providerや製品の撮影/保存serviceは公開しない。
- 本人の製品アプリの調整設定・台帳・既存JPEGには触れない。

## 比較条件と操作
原寸AHD/原寸PPGを順に展開し、既存BOX縮小とGPUパイプラインで1024px以下にする。
強さ0.5、暖かさ0、手動露出0/-1/-2EV。製品に保存済みの調整値は使用しない。
AHD/PPGを同じ拡大位置で切り替える。ピンチで最大6倍に拡大し、ドラッグで移動する。
結果はメモリだけに保持する。背景化/回転後は写真を選び直す。露出条件は画面再生成時に保持する。
原本が選択後に変わった場合は比較結果を破棄する。providerの既にブロック中のreadは即時取消を保証しない。

## ローカル検証
- 通常版lint、単体試験1380件（失敗0・既定skip12）、Debug/Releaseビルド成功。
- opt-in比較版app/testAPKと限定manifest用lint成功。
- 通常Debug/Releaseのarm64-v8a・x86_64とも比較専用JNIなし。通常Debug APKに比較Activityなし。
- 比較APKの権限はAndroidXの署名限定内部receiver用だけ。通信/カメラ/マイク/共有ストレージ権限なし。
- AOSP AVD合成11試験成功：世代、正常AHD/PPG表示、原本不変、開始前の取消、原本変更検知、読取不能、cleanup失敗後の再開禁止、Activity再生成/背景化。
  cleanup故障試験は合成の削除障害を作る小試験であり、全種類のOS/native故障の網羅ではない。
  GPU の R7_FINAL 工程境界での取消、旧処理を待機させた状態での次画像選択、最終再読込の SecurityException 注入により、画像の取り違え・private 一時ファイル・FD の残留がないことを確認した。
  実行中の GL 命令/native 内部の即時中断、OS による実際の URI 権限剥奪、OOM、native close 失敗は未検証。完了済み旧画像が UI キューで遅延到着する全順序も網羅しない。
- 実操作：FilesのDownloadsから合成DNGを選択→AHD完了→PPG切替を確認。写真の実物は使っていない。
- 修正版の縦横UI領域を確認し、system bars/cutoutとの重なりを除去。
- GPU90/帯28回帰成功。919af45fの通常製品出力は採用済み51b691adと全画素差0。
  以後の変更はopt-in専用UI/試験/文書だけで通常製品経路は不変。環境差はAOSP/default API37 rev6、Emulator37.2.13、同一NVIDIAドライバーで記録。
- AVDは90秒安定ゲート、試験前後最初の system_server690、追加実行の664とも不変/boot1/ANR0。一時コピーなしを確認して自分の5610だけ停止/返却済み。
- dot独立レビューで指摘修正。通常製品へのPPG採用や基準画像の更新は行っていない。

### 途中の不成立も含む記録
追加試験の初回コンパイルは protected onActivityResult の直接呼出しで失敗し、試験専用 reflection に修正した。
11件の初回実走は10件成功・権限喪失1件失敗。pair は未公開だが setImageBitmap(null) が空の BitmapDrawable を保持したため、比較専用画面で setImageDrawable(null) を明示する修正を行った。928ae1d の再実走は11件成功（76.64秒）、lint/app/testAPKビルドも成功。この秒数は合成の正しさ検証の時間で、Pixel の性能値ではない。
限定manifestの最初のlintは、到達不能な製品CineRecorder/ForegroundExportServiceの2件で失敗。
初回のファイル指定が効かず再度失敗したため、比較版だけのlint.xmlで対象2ファイル名と検査種別に限定したパターンへ修正し、再実行成功。
通常製品lintには例外を追加していない。
UIの日本語XMLをPowerShellの文字列経由で読んだ際に転送時の文字コードで解析失敗した。XMLをadb pullでバイトのまま取得し直してUTF-8解析した結果で確認した。アプリ内文言は正常。
合成生成RGBを単純な正解画像と扱うことはしていない。忠実なGT導出は小さな作業ではないため深追いしない。

## 比較APK（製品とは別）
実装source: 928ae1d627c8fa0c2a097a735f621ead5932b147。
後続commitは合成試験と文書のみ。APKはこの実装で固定する。
versionCode=10 / versionName=0.0.10-p05。通常リリースの番号系列とは別。
Android Debug証明書SHA-256:
0e6fc2b5e12b4bcf5142592992f3b4879b44ca849c6c26c14464003ca0a35a8c
APK SHA-256:
27ce512be19f94469450b7a0298c965192980b6af2a459bc4ded29a024578d31

## 本人へ提示する手順案
1. PC接続時に、比較用の別アプリを追加してよいか確認する。製品アプリはそのまま残す。
2. 本人が端末内のSIGMAステージDNGを1枚ずつ選ぶ。
3. AHD/PPGを切り替え、照明の縁→顔や髪→衣装の細かい模様→暗部の順に見る。
4. 必要なら露出を-1/-2にして同じ写真を選び直す。
5. 「差なし」または気になる場所/色/縁の違いを文章で報告する。写真の送付は不要。
3枚で10〜15分程度の見込み。これは本人の目視を含む段取りの見積もりで、Pixelでの処理時間の実測ではない。
確認終了後は比較アプリを削除できる。普段の撮影/編集/保存は既存の製品版を使う。
実写を見ずにPPGを採用したり、保存AHDを変えたりしない。
