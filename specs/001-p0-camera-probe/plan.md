# Implementation Plan: P0 RAW・マニュアル露出の成立性検証

**Branch**: `chore/p0-repository-status` | **Date**: 2026-11-01 | **Spec**: [spec.md](spec.md)

**Input**: 合意済みP0ブリーフと既存P0の実装・ビルド証拠。仕様を新規の実装済み機能へ広げない。

## Summary

Pixel 9aでCameraXのRAW静止画とCamera2Interopの手動ISO/露光時間が成立するかを測る。
最小の単一app、PreviewView、能力レポート、MediaStoreへのDNG/JPEG保存、AUTO/MANUAL、要求と実測ログを使う。
このplanは既存P0の設計を定義する。最新の受入結果は[P0_REPORT](../../docs/P0_REPORT.md)、残件は
[Issue #4](https://github.com/reitojike/cinematic-camera/issues/4)を参照する。

## Technical Context

| 項目 | 採用・状態 |
|---|---|
| 言語/UI | Kotlin 2.4.21 / Jetpack Compose BOM 2026.09.00 |
| カメラ | CameraX core / camera2 / lifecycle / viewを全て1.6.2に固定 |
| Android | compile/target 37、min 28。最初の実機対象はPixel 9a |
| ビルド | AGP 9.4.1、Gradle 9.8.1、JDK 25、Build Tools 36.0.0 |
| 保存 | 新撮影はMediaStoreの`Pictures/Cinematic Camera/`、ログはprivate `files/reports/`。Roomは未導入 |
| テスト | JUnit 4で能力/値変換4件・共有保存7件、build / lint、実機手動マトリクス |
| 種類/規模 | Android技術検証、1 module、最小の1画面、バックエンドなし |
| 性能目標 | 定量最適化は対象外。DNG5回・露出各2値を検証し、fps/誤差/制約を記録 |

## Constitution Check

設計前・設計後とも次を確認した。

- I: specのFR/ACをtasksとP0_REPORTへ対応させ、実装より前に文書化したと偽らない。
- II: PreviewViewとCameraX lifecycle、必要箇所だけのInteropを使用。Camera2のdevice/sessionを直接操作しない。
- III: Future受付・CaptureRequest・CaptureResult・保存・viewer読み込みを分ける。実機合格の代用にCIを使わない。
- IV: 新撮影はユーザー指定の共有画像フォルダー、ログはprivate。写真・完全な実機ログ・秘密・個人用絶対パスをGitへ公開しない。
- V: CameraX 1.6.2とP0範囲を維持し、NDK/GPU/動画/独自回避を導入しない。

未解決の技術選定の曖昧さはない。RAW/手動制御の成立性は実機で測る事項であり、オンライン調査で埋めない。

## Project Structure

```text
app/src/main/java/dev/cinematic/p0/
  MainActivity.kt   permission / Compose / PreviewView / lifecycle表示
  CameraReport.kt   CameraCharacteristicsの各ID能力
  CameraProbe.kt    CameraX bind / 出力能力 / 撮影 / Interop / callback
  Exposure.kt       ISOとms→nsの範囲検証
  ProbeLog.kt       private sessionログ
  PhotoPublisher.kt バイト不変の公開・失敗cleanup（JVM検証可能）
  AndroidImageStore.kt 標準MediaStoreのpending/URI操作
app/src/test/java/dev/cinematic/p0/ExposureTest.kt
app/src/test/java/dev/cinematic/p0/PhotoPublisherTest.kt
specs/001-p0-camera-probe/{spec.md,plan.md,tasks.md,checklists/requirements.md}
docs/{P0_REPORT.md,BUILD_VALIDATION.md,OPERATIONS.md,REPOSITORY_STATUS.md}
```

### 採用判断と公式API

- [CameraX 1.6.2](https://developer.android.com/jetpack/androidx/releases/camera#1.6.2)を指定どおり採用。
  最新referenceにある1.7系APIへ置き換えず、1.6.2 artifactでcompileを確認済み。
- `ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats`を先に確認し、
  対応する場合だけ`OUTPUT_FORMAT_RAW`または`OUTPUT_FORMAT_RAW_JPEG`で構築する。
  Camera2のRAWフラグとCameraXの形式集合は別々に記録する。
- 保存はCameraXの`takePicture(OutputFileOptions, ...)`、同時出力はRAW/JPEGの2出力overloadを使用。
  UUID別ファイルへ保存し、同時出力は2callbackと実在・非ゼロbyteを確認する。viewer有効性は別判定。
- `Camera2CameraControl.setCaptureRequestOptions`でAE_OFF / ISO / exposure timeを指定し、
  `clearCaptureRequestOptions`でAUTO復帰する。再bind後も明示clearし、受付前・失敗時は撮影を抑止する。
- PreviewとImageCaptureの観測callbackで、request/resultのAE・ISO・時間、frame/sensor timestamp、
  frame durationを記録。通常frameは約1秒間隔、stillは都度。desiredはUI受付時点のラベルなので、
  境界frameの判定ではrequest値とsensor timestamp、安定後の複数frameを優先する。
- bind世代をepochで区別し、停止前のcallbackが再開後のbusy/測定状態を書き換えないようにする。
  保存本体はCameraXへ任せ、独自のCameraDevice / session / callback executorを所有しない。
- 直接Camera2、LibRaw/JNI、GPU、動画は代案/後続のみ。実測した問題がない段階で移行しない。

### データと状態の設計

能力は`camera ID → Camera2 report + CameraX formats`の組。ISO/時間範囲が不明なら手動操作不可。
露出は`desired → pending → accepted / failed`と実測結果を分離する。設定受付の成功は値一致を保証しない。
撮影はtoken別に`開始 → 各出力の保存 / error → complete`を記録する。部分保存・LATEは成功5回に数えない。
DNG/JPEGを編集せず保持し、位置情報を要求しない。F値は表示のみ。

### 共有画像保存の追加設計

CameraX 1.6.2の実ソースではDNGも共通FileUtilでMediaStoreへ到達でき、MIMEのJPEG強制はない。
ただしコピー失敗時にもpendingを解除し、作成rowの削除は行わない。そのためユーザー要求のcleanupを
管理する最小方式として、CameraXは`cacheDir/capture-staging`へ出力し、標準MediaStoreへバイトコピーする。
確認したsource JAR SHA-256: `9803a0c47248a567c99dbc1b95e07afb8ccc5a7e097ce3f76dd126be3a53ee44`。

API29以上で`MediaStore.Images`のprimary external volumeを使い、RELATIVE_PATHは
`Pictures/Cinematic Camera/`、MIMEはJPEGが`image/jpeg`、DNGが`image/x-adobe-dng`。
IS_PENDING=1で作成→全ストリーム書込み完了→全行を0へ更新する。失敗時は今回の全行を削除する。
RAW+JPEGはcallback順序に依存せず、両方の一時ファイルが揃ってからIO threadで公開する。
一時cacheだけを後処理し、旧`files/captures`を読み出す移行処理や一括削除は作らない。

minSdk28は維持し、API29未満では共有保存の非対応理由を示す。Pixel 9aが今回の対象であり、
古い端末向けにWRITE/READ_EXTERNAL_STORAGEやREAD_MEDIA_IMAGESを追加しない。
JPEGの標準ギャラリー可視性と、DNGの閲覧アプリ対応は別々に実機確認する。

既存版と署名が異なる更新は自動適用しない。今回の旧テスト写真は消失許可があるが、利用者へ署名差と
手動再インストールの影響を説明する。固定鍵生成/継続利用は別の承認なしに行わない。

### 検証と停止条件

受入条件は[既存P0 spec](spec.md)、結果は[最終検証レポート](../../docs/P0_REPORT.md)を使う。
許可・lifecycle、AUTO JPEG、RAW5回、RAW+JPEG、ISO2値、時間2値、AUTO復帰、背景復帰、エラーを含む。
DNGはPC viewerで全件開く。実機AC3/4が仕様上不可能なら根拠付きBLOCKEDとし、例外的な回避を追加しない。
P1のGO / GO_WITH_LIMITATIONS / NO_GOは証拠と制約を基に判断し、最新の判断案は最終レポートを参照する。
後続の製品優先順位とP0.5計画は[002 plan](../002-live-capture/plan.md)で扱い、P0のACを変更しない。

### 文書の最小化

ユーザー指定に従い、researchの判断とデータ構造は本planへ統合した。quickstartは既存のREADMEと
P0_REPORTへリンクし、内容を複製しない。外部API契約がないためcontractsは作らない。
これは文書構成の省略であり、RAW・露出・privacy・ACの検証を省くものではない。
