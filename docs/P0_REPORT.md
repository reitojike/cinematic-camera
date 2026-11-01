# P0 技術検証レポート

作成日: 2026-11-01 / アプリ: `0.0.1-p0` (`dev.cinematic.p0`) / CameraX: **1.6.2固定**

**状態: 実装・ビルド・ユニットテスト・lint完了 / Pixel 9a実機未検証。P1判定は未判定（NOT_EVALUATED）。**

GO / GO_WITH_LIMITATIONS / NO_GOは実機証拠を得てから選ぶ。実機がないことを端末非対応やNO_GOの根拠にしない。

## 実装した検証導線

- Camera permission、PreviewView、Activity lifecycleへのbind、手動Stop / Start。
- CameraManagerのcameraIdList全体についてCameraCharacteristicsを表示・ログ保存。個別取得に失敗したIDもエラー記録。
- レンズ向き・焦点距離・physical IDs、ハードウェアレベル、RAW / MANUAL_SENSOR、ISO / 露光時間範囲、最長フレーム時間、最小フォーカス距離、F値、RAW_SENSORサイズ。
- CameraX availableCameraInfos別にsupportedOutputFormatsを記録。Camera2のRAWフラグからCameraX対応を推測しない。CameraXから選択できないIDはその旨をログに記録。
- JPEGのAUTO基準撮影、RAW対応時のDNG保存、RAW+JPEG対応時の同時保存を別々に選択。各形式を選択すると再bindしてAUTOに戻す。非対応の形式のImageCaptureは構築しない。
- MANUAL_SENSOR・AE_OFF・request keys・範囲が全て確認できるときだけ手動露出を許可。msを浮動小数点を介さず整数nsへ変換し、範囲外は拒否（黙って丸めない）。
- 手動時はCamera2CameraControl.setCaptureRequestOptionsでAE_OFF / ISO / 露光時間を設定。AUTO時はclearCaptureRequestOptionsで全Interop上書きを解除。Future受付とセンサー結果は別々に記録。
- 再bind時にもInterop設定を明示解除し、受付完了までは撮影を抑止。解除失敗時は撮影不可のまま理由を表示する。選択するIDごとにCameraX形式集合を初期化し、別IDの能力を流用しない。
- Preview / ImageCaptureに観測用session capture callback。CameraDevice / CameraCaptureSessionを操作しない。通常結果は最大約1回/秒、still intentはその都度ログ。request側とresult側のAE/ISO/ns、frame number、sensor timestamp、frame durationを記録。
- logcat `CinematicP0`とprivate `files/reports/`にセッションログ。画面は最近の16,000文字、privateファイルは全記録。DNG / JPEGはprivate `files/captures/`。UUIDで上書きを避け、出力形式・ファイル名・サイズ・未検証の読み込み状態を記録。

## 環境・ビルド結果

| 項目 | 結果 |
|---|---|
| 作業環境 | Windows / PowerShell、独立フォルダー `cinematic-camera-p0` |
| 指示ファイル | 作業フォルダーと祖先のAGENTS.md、およびMyProject/.agentsを確認。該当ファイルなし |
| Android SDK | Platform 37.0 / Build Tools 36.0.0あり |
| JDK / Gradle / AGP | Oracle JDK 25.0.4.1 / 9.8.1 / 9.4.1 |
| wrapper生成 | 成功 |
| assembleDebug | 成功。debug APK生成 |
| testDebugUnitTest | 成功。4件、失敗0、error 0、skip 0 |
| lintDebug | 成功。error 0、warning 1（Gradle 9.8.2更新の案内。検証版9.8.1を維持） |
| 1.6.2実API検証 | 解決済みartifactでcompile成功。runtime依存のCameraXは全て1.6.2、1.7系なし |
| ADB端末一覧 | daemon起動失敗: `cannot connect to daemon`。接続端末を確認できず |
| 実機撮影・インストール | 未実施 |

初回のsandbox内Gradleはlocalhostのdaemonへ接続できず失敗。ビルド目的の昇格実行でwrapper生成に成功。端末操作・撮影・公開は行っていない。

その後、利用者が`reitojike/cinematic-camera`の非公開repo作成とAPK配布を承認した。branch / main保護 / 最小CI / 一時debug署名でのpre-release配布の方針は[OPERATIONS.md](OPERATIONS.md)に記録する。初版のローカルAPKと、CI / Releaseで配布するrun別APKのhashを混同しない。実機ACとP1判定は変更しない。

詳細なコマンド、成果物のSHA-256、テスト範囲は[BUILD_VALIDATION.md](BUILD_VALIDATION.md)を参照。

### 納品前の独立レビュー

RAW判定・保存callback、AUTO解除、request/resultの区別、lifecycle / callback race / cleanup、エラーUIを1巡確認した。再bindでInterop設定を明示解除しない問題と、Camera2-only ID選択失敗時に前IDのRAW対応表示が残る問題の2件を修正。修正後にbuild / unit / lintを再実行し、全て成功。実機成立性を保証するレビューではなく、実機ACは引き続き未検証。

## ACの状態

| AC | 状態 | 必要な証拠 |
|---|---|---|
| AC1 | 未検証 | Pixel 9aで許可・preview・Stop/Start・復帰 |
| AC2 | 未検証 | Pixel 9aからの実測能力ログ |
| AC3 | 未検証 | DNG 5回以上保存、PC viewerで各ファイルを開く |
| AC4 | 未検証 | ISO 2値以上・露光時間2値以上のrequest/result比較 |
| AC5 | 未検証 | AUTO→MANUAL→AUTOで無停止・無クラッシュ |
| AC6 | 実装済み・実機未検証 | 非対応、範囲外入力、APIエラー時のUI / log / 非クラッシュ |
| AC7 | 文書作成済み・部分完了 | ビルド結果を記録し、実機ログとP1判断を追記する |

**実機ACの合格は0件。** 非対応が実測された場合は該当ACを`BLOCKED / 実機非対応`にし、camera ID・対応フラグ・CameraX形式・エラー・再現条件を残す。回避策の追加やCamera2全面移行は行わない。

## 実機結果記入欄

| 項目 | 実測値 |
|---|---|
| 端末名 | 未確認（対象はGoogle Pixel 9a） |
| Android version / build / fingerprint | 未確認 |
| アプリバージョン / 検証日 | `0.0.1-p0` / 未実施 |
| Camera ID / lens / hardware level | 未確認 |
| RAW / MANUAL_SENSOR (Camera2) | 未確認 |
| CameraX output formats | 未確認 |
| ISO / exposure range / max frame duration | 未確認 |
| 最小フォーカス距離 / F値 / RAWサイズ | 未確認 |
| AUTO JPEGとpreviewの一致 | 未確認。JPEGは処理済み、RAWは未現像なのでRAWの見た目の一致を要求しない |
| AUTO / RAW / MANUAL要約 | 全て未実施 |
| CameraX / Interop制御競合 | 未確認。競合なしとは判断していない |

### DNG検証（同じcamera IDで5回以上）

| 回 | Camera ID / UUIDファイル名 | byte数 | Viewer名/版 | 読込成功・寸法・内容確認 |
|---|---|---|---|---|
| 1 | 未取得 | — | — | 未確認 |
| 2 | 未取得 | — | — | 未確認 |
| 3 | 未取得 | — | — | 未確認 |
| 4 | 未取得 | — | — | 未確認 |
| 5 | 未取得 | — | — | 未確認 |

SAVED / 非ゼロbyteだけではDNG有効と判断しない。RAW+JPEGも別途、同じUUIDの両ファイルを確認する。

### 手動露出の記入欄

範囲内でISOを2値、露光時間を2値以上選ぶ。例は端末範囲を確認してから決める。ISO変更中は時間を固定し、時間変更中はISOを固定。安定後の複数frameとstill結果を記録し、AUTO復帰後はAEが自動に戻ることを確認する。

| 条件 | 指定ISO/ns | request AE/ISO/ns | actual AE/ISO/ns | frame duration | 誤差/安定までの時間 |
|---|---|---|---|---|---|
| AUTO基準 | AUTO | 未確認 | 未確認 | 未確認 | 未確認 |
| ISO A | 未選定 | 未確認 | 未確認 | 未確認 | 未確認 |
| ISO B | 未選定 | 未確認 | 未確認 | 未確認 | 未確認 |
| exposure A | 未選定 | 未確認 | 未確認 | 未確認 | 未確認 |
| exposure B | 未選定 | 未確認 | 未確認 | 未確認 | 未確認 |
| AUTO復帰 | clear | 未確認 | 未確認 | 未確認 | 未確認 |

誤差は実測-指定のns/ISOと相対差を記録する。採否に使う許容差は実測と端末制約を根拠に説明し、都合よく変更しない。

## 実機検証手順

1. APKをPixel 9aへ手動インストール。撮影に人・画面内の個人情報・位置情報が含まれない被写体を用意する。ログのDEVICE行で端末ビルドとアプリ版を控える。
2. 初回拒否→再許可、許可済み起動を確認。Preview開始→Stop→Start、ホームへ移動→復帰、画面回転・再生成を確認。各操作のCAMERA_STATEとLIFECYCLEを残す。
3. Camera2全IDとCameraX形式を採取し、この文書へ転記。各CameraX IDを選んでpreview / JPEGを確認。非対応IDや形式はUI / logに理由が出ることを確認。
4. AUTO JPEGを撮影。previewの構図・露出と保存画像を比較する（RAWに同一の見た目は要求しない）。
5. RAWがadvertiseされたIDでRAWを選択、AUTOで5回以上Capture。各SAVED / CAPTURE_COMPLETE / byte数を確認。連続5回の意味は各保存完了後に次を撮ること。無制限のburst性能は評価しない。
6. RAW+JPEGがadvertiseされれば別途選択して撮影。UUIDが一致するDNGとJPEGの2つの完了を確認する。未advertiseならBLOCKED/非対応として記録。
7. 能力範囲内でISO A→B（時間固定）、時間A→B（ISO固定）を適用。PENDING→ACCEPTEDとRESULTを比較。プレビュー安定後のframeとstill intentの結果を残す。長時間露光ではframe durationとpreview低fpsも記録する。
8. AUTO→MANUAL→AUTOを複数回実施。AUTOはclearCaptureRequestOptionsで解除し、request / actual AEとISO/時間の自動変化を確認。MANUAL→出力変更、MANUAL→Stop/Startでも明示解除の受付後にAUTOへ戻るか確認。停止・クラッシュ・CameraX上書きがあれば再現手順を記録する。
9. 範囲外ISO、負の時間、不正文字列、未対応機能、カメラ占有/切断等を安全に再現できる範囲で確認し、エラーUI/logを残す。撮影中・適用中は他操作を抑止するがStopは利用可。Stop後に遅れて来た撮影結果はLATEとして記録し、新しいセッションの操作状態を書き換えない。背景復帰後にも撮影可能か確認。
10. privateログと撮影物をPCの**リポジトリ外**へ取得し、各DNGをRAW viewerで開く。ファイル名・byte数・viewer・読み込み成否だけを記入。ログを公開する前に端末ビルドなどの共有可否も確認する。

debug APKのファイル取得例（実機操作は利用者が実行）:

```powershell
adb shell run-as dev.cinematic.p0 ls -l files/reports
adb shell run-as dev.cinematic.p0 ls -l files/captures
# Android Studio Device Explorer: data/data/dev.cinematic.p0/files からPCへ保存。
# PowerShellでバイナリstdoutをテキストとしてリダイレクトしないこと。
```

## 失敗・制約・停止条件

- 実機の画質・性能・RAW有効性・制御競合は未評価。Camera2にRAWがあってもCameraXの同時stream構成でbindできるとは限らない。bind失敗は自動でJPEGにフォールバックせず理由を表示する。
- ISO/nsは範囲内でもquantization、frame duration、HAL、CameraXの静止画処理により実測が違い得る。frame数・sensor timestamp・requestとresultを照合し、受付成功と一致を混同しない。長時間露光でUIが待機する可能性も実機確認する。
- RESULTのdesiredはcallbackをUIへ届けた時点の操作ラベル。操作境界付近のframeには遅延があり得るので、sensor timestampとrequest値を優先し、安定後の複数frameを採用する。CaptureResultと保存ファイルの厳密な1対1対応は保証せず、撮影token/時刻/intentで調査する。
- 通常のCaptureResultログは約1秒間隔。無期限に撮影する製品用途を想定しない。必要な検証後はStopで止める。
- RAW+JPEG失敗時に片方だけ保存され得る。部分ファイルは証拠として残り得るため、成功回数へ含めない。
- callbackはアプリのmain executorへ配送し、保存本体のIOはCameraXへ任せる。独自executorやCameraDevice / sessionの所有は追加しない。実機での停止中撮影やlate callbackも検証対象。
- インストール、カメラ撮影、外部viewerでの検証は未実施。バックエンド・独自回避・Camera2直接制御は追加していない。

| 失敗 | 再現手順 | 結果 / 次の対応 |
|---|---|---|
| sandbox内Gradle daemon接続 | 初回wrapper生成 | localhost接続不可。ビルド昇格実行でwrapper成功 |
| ADB daemon起動 | `adb devices -l` | daemonへ接続不可。実機接続状態は不明、再試行/撮影はしていない |
| 実機CameraX / Interopの失敗 | 未検証 | 実測後記入 |

## P1判定と次の一手

**NOT_EVALUATED（未判定）**。まずPixel 9aで上記マトリクスを実施し、DNG 5件と露出2値以上の実測を揃える。AC3/4がBLOCKEDの場合はログを根拠に、制限付きで進むか・Camera2で問題となる最小ケースだけ比較するかを相談する。現時点でCamera2への移行を提案する実測根拠はない。

## 確認した公式資料

- [CameraX release notes: 1.6.2](https://developer.android.com/jetpack/androidx/releases/camera#1.6.2) — 1.6.2固定。サンプルの1.7 rc指定は採用しない。
- [ImageCapture](https://developer.android.com/reference/androidx/camera/core/ImageCapture) / [ImageCaptureCapabilities](https://developer.android.com/reference/androidx/camera/core/ImageCaptureCapabilities) — RAW / RAW_JPEG / 2出力takePictureは1.5.0から。
- [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)
- [CameraX architecture](https://developer.android.com/media/camera/camerax/architecture)
- [Camera2CameraControl](https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2CameraControl) — 1.6.2のset/clearCaptureRequestOptionsを使用。1.7のapplyCamera2Interopは採用しない。
- [AGP 9.4 compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes)

API referenceは最新版も含むため、実際に解決した1.6.2 artifactでのcompile結果を別途記録する。
