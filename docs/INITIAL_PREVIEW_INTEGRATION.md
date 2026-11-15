# 初回案内表示の製品接続候補

Refs #233 / #241。最新ユーザー判断を基準に実装中。#442 の main `f2c94cc0` を統合済み。画面比較・実走・最終レビューは未完了で、採用/マージ済みを意味しない。exact は FULL AHD、原寸保存は不変。#438/#441 の PPG 判定には依存しない。

## 候補と根拠

| 優先 | ローカル候補の実装 | 制約 |
| --- | --- | --- |
| 保存済みcache | 照合済み保存JPEGの縮小像だけを1枚保持。hit時も原本/recipe/geometry/公開記録/JPEG SHAを再照合 | 新GPU readbackなし。未保存GPU編集のcacheではない |
| 保存済みJPEG | 正規publication v3/PUBLISHED、cursorと世代intent、完全recipe・既知renderer・原本SHA・geometry/fullCoverage・公開所有行・JPEG SHA/headerを照合 | read-only baseのみ、AtomicFile recovery/保存queue/世代作成/公開を使わない。未知・欠損・不一致はmiss |
| embedded JPEG | 同一照合済snapshot FDからLibRaw thumbs_listを得て直接slice。8MiB、4096辺/4MP、原本未回転寸法以下、range/SOI/EOI/SOS構造と明示向きを検査 | JPEG自身の向きでFit。RAW default cropとの画角/色一致を保証しない |
| half案内像 | 同じsnapshot FD、共有1枠の同期hook内で一度だけHALF_CANDIDATE、raster解放後にFULL AHDへ | exactの入力へ流用しない。追加遅延の採否は対照計測・本人判断前 |

saved JPEGのcrop座標が明示保存されていないことだけを理由に全件missとはしない。既知の整数DNG契約では、同じ原本bytesの一意CFA IFD/ActiveArea/DefaultCropOrigin/Sizeからcropを導出できる。履歴監査で既知contractと原本/寸法/向き/fullCoverageを照合する限定根拠を確認した。未知contractは推測しない。新manifestや保存codecは追加しない。

## snapshot / native / memory

`Original24PreviewSession` が作成・fingerprint照合したread-only FDを同期hookに貸し、guide処理が戻ってから同FDでFULLを実行する。入力IO workerと既存共有tokenを保持し、opaque native退出/cleanup確認前に解放しない。取消/half失敗を握りつぶしてFULLへ進むことは禁止。失敗したcloseは既存ownerで保持する。原本事後照合とFULL geometry検査は維持。

抽出器は `unpack_thumb_ex` を使わずsnapshot範囲を直接コピーする。既存appのLibRaw raw targetを再利用し、共通macro・dng_decoder本体は不変。入力64MiB、JPEG8MiB上限を維持する。旧実装もunpack呼出前に8MiB検査済みで、既定512MiBをそのまま確保していたという意味ではない。

LibRaw constructor flags/progress取消と `max_raw_memory_mb=512` は現行Original24経路に合わせる。WB metadataを捕捉するAsShot callbackはJPEG抽出には登録しない。先行preflightはcallback登録とgeometry検証を行うがWB意味検証前に戻るため、WBの正しさを検証済みとはしない。FULLが従来WB検証と原本事後照合を行う。全設定完全一致やmetadata累積hardcapは主張しない。2026-11-15の本人判断により、旧総hardcap必須条件をこの限定条件へ更新した。

JPEG完全entropy検出は要求しない。構造検査と標準codecが報告するエラー拒否は維持するが、回復可能なentropy警告は成功し得る。新codec依存・独自entropy parserなし。0.02 byte/pixel等の極端下限はPixel/SIGMA fpの承認済実データ未取得につき未採用。

各Bitmapは長辺1024以下/ARGB8888/最大4MiB。decode回転中は2枚、cache専有は1枚、現requestのUI公開は1枚。halfは最大1024のlinear入力と表示変換IntArrayを一時使用する。原寸Bitmapは作らない。cacheコピー/guide処理とFULL開始前に既存OS memory admissionを再確認する。これらは明示所有と配置上限であって、退場中Compose/RenderThreadやGC待ちを含むプロセス総量の厳密capではない。反復切替の保持/実メモリ観測を別途行う。

## 表示と寿命

photoroll側Compose Bitmap overlayを使い、GPU Surfaceは背面で維持する。GPU画素処理/presenterファイルは変更しない。保存像、撮影時案内像、RAW基準案内像を別ラベルで表示し、編集未反映を明示する。比較/編集/保存の有効化は従来exact gateのまま。

exact presentedの同request値を観測したらoverlayを不可逆に外す。選択/再試行/背景化/trimは旧handoffを閉じ、post失敗・取消・late結果の未公開Bitmapはrecycleする。公開済BitmapはCompose/RenderThreadが参照し得るので明示recycleせず参照を落とす。cacheは独立copyのみをrecycleし、trim/retryはcache世代を更新して遅い書込を拒否する。UIへ渡した結果を再cache化しない。

## 検証の区別

- 旧契約の `ffe3b5a6`：lint/unit/APK build成功、Android合成10件中9成功/1失敗。SOS後entropy2bytes＋EOIをImageDecoderが受理したため、完全拒否契約に失敗した。この履歴を消さず、新条件の合格には読み替えない。
- 新条件：直接sliceと構造/容量/向き、同snapshot half→FULL、原本不変・cleanup・取消・exact置換・late callback・cache寿命・saved lookup世代/TOCTOUを別試験する。
- 初回統合tree `28bcb734`：main/native/Kotlin/lint成功、unit1399件中fixture構築で1失敗・skip12。修正と再実行は別途記録する。最終合格ではない。
- half比較はlinear-ready、Bitmap作成、Compose draw記録（最終画面合成ではない）、exact native開始、exact GPU presentedを区別し、同入力/同条件のfallbackなし対照と比較する。pure GPU時間とwall timeを混同しない。
- GPU90/帯28、関連Android回帰、表示スクリーンショット比較と本人承認、最終CI/canonical/独立レビューが残る。実写真・Pixel・配布は別許可。

既存IssueとLiving Specを正本にし、重複する番号付きspec一式は生成していない。
