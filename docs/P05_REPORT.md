# P0.5 検証レポート

対象: `dev.cinematic.p0` / `0.0.5-p05` / CameraX **1.6.2**

関連: [Issue #8](https://github.com/reitojike/cinematic-camera/issues/8) / [spec](../specs/002-live-capture/spec.md) / [plan](../specs/002-live-capture/plan.md)

**実装候補と自動検証の結果であり、Pixel 9aの動画・音声・fps・HDR・画質・熱の実機合格ではない。**
P0のユーザー成功報告と不足資料は[P0_REPORT](P0_REPORT.md)とIssue #4を維持する。

## 実装した範囲

- 同じPreviewView上のSTILL/CINE切替。録画はRecord操作でだけ開始し、モード切替・起動だけでは記録しない。
- CameraXの具体的dynamic range、quality、Preview+VideoCapture組合せ別fpsとsession対応を照会。
  未設定時は対応SDRの最高解像度→高fpsを優先する。保存済みユーザー候補が有効なら優先し、
  非対応/失敗時は希望値を残したまま代案と理由を示す。
- HDRは列挙された具体的rangeと対応sessionだけを候補にする。Pixel 9aでHDRが使えたとは判断していない。
  Quality.QHDやRestrictTo API、1.7の新APIは使用しない。
- STILLは利用可能な高解像度とsensor aspectを優先し、bind失敗時は同じ出力形式のCameraX defaultへfallbackする。
  公称48MPや最高画質は保証しない。保存前の横長cropは追加していない。
- mode/camera別のISO/シャッター、カメラ/出力形式、動画候補、音声/解像度の希望をローカル保持。
  bind時はInteropを解除してから、当該modeで受付済みの設定を復元する。未適用の入力draftを勝手に適用しない。
- 動画は選択fpsに対して長すぎる手動露光を拒否する。実センサーのframe durationと保存MP4のsample timestampは
  要求値とは別に記録する。
- マイク権限は音声ありのRecord操作時に要求。拒否時は録画を開始せず、許可または明示的な音声offを案内する。
  音声state/amplitudeを表示するが、振幅は音割れがない証拠ではない。
- MP4はMediaStoreの`Movies/Cinematic Camera/`へpending row/descriptorで保存。
  Finalize後にdescriptorを閉じ、非ゼロbyte・寸法・duration・video sampleを検査してから公開する。
  失敗時は今回作ったrowだけをcleanupし、cleanup失敗も報告する。
- JPEG/DNGは既存の`Pictures/Cinematic Camera/`を維持。既存原本や旧内部データの移行/削除なし。

## 状態・出力と制約

Recorderの完了と、MP4検査/MediaStore公開完了は別の状態。Finalizeと出力処理が終わるまで次の録画や
カメラ/mode/候補変更を抑止する。録画中の背景化では停止を要求し、背景録画は行わない。
close/unbindに伴うsource inactive等は通常成功へ混ぜず、結果とcleanupをログに残す。
遅れた旧tokenのイベントは新しい録画状態を書き換えない。

音声source/encoderが途中で失敗しても有効な映像と音声trackが残る場合がある。
そのためエラー・source silencedを録画単位で保持し、最終UI/logへAUDIO_WARNINGとして残す。
音声失敗だけを理由に有効な映像を必ず削除する設計にはしていない。

要求したquality/fps/range、bindで選ばれた構成、保存MP4の寸法/回転/duration/codec/color、
最大30,000 video samplesのtimestampから求めた平均fpsを区別する。
sample metadata検査は全フレームのdecode・再生品質・音声品質を証明しない。
JPEGはheader寸法、DNGはExif寸法を記録するが、DNG全体のdecode証明には使わない。

## 機械的検証

- ローカル `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` を実行。
- unit **30件、failure/error/skip 0**。既存11件に、能力候補選択・希望値fallback・録画状態/旧token隔離・
  音声途中失敗/無音警告・出力close/検証/公開/cleanupの12件、EV/thermalの3件、override能力/範囲/fallback/保存保持の4件を追加。
- lint **error 0 / warning 9**。Gradle更新案内1、任意のKTX提案5、metadata記録に使う標準ExifInterfaceへの
  Jetpack推奨3。baselineや抑制は追加しない。Exif読み込みは素材の現像/有効性判定に使用しない。
- API1.6.2の実artifactでcompileし、MediaStore/音声/SessionConfigの署名を確認。
- 独立レビューで適用済み表示のsession分離と音声警告の保持を確認する。最終headのcanonical結果・CI・threadは
  PR/Issueの証跡を使い、古いheadの結果を再利用しない。

unitは実カメラ・Android MediaStore provider・マイク・encoderの代用ではない。
実機操作、追加GMD、実写素材取得、NDK/GPU/現像/製品UIは行っていない。

## ユーザーが確認する実機条件

| 対応 | 確認内容 | 現状 |
|---|---|---|
| V01 | STILL/CINE往復、mode別ISO/時間、設定保持、切替の待機/blackout/画角 | 未確認 |
| V06 | Record/Stop、音声許可/拒否/off、MP4再生、実寸法/fps/range、音声/同期/歪み | 未確認 |
| V08 | 録画中/停止後の背景化、Finalize/音声解放/保存、復帰後の撮影 | 未確認 |
| P0回帰 | JPEG/RAW/RAW+JPEG、AUTO/MANUAL、既存共有保存と実測ログ | 新実装での再確認は未実施 |
| 高能力/HDR | 実カメラの対応一覧、初期選択、4K60等の実出力、HDR codec/transferと閲覧 | 未確認 |
| V02〜V05/V07 | 暗所/動体AF/色照明/LED/応答/発熱の品質 | 未確認、後続の限定検証 |

エラー時はState/Recent logの種類・選択候補・再現手順を控える。写真/音声本体・未編集ログをGitへ公開しない。
実機未確認を理由に合意済みの次の限定開発を止めないが、重大欠陥・テスト失敗・権限判断・大幅scope変更時はHOLDする。

## B1: 標準AF/AEと照明・熱テレメトリー

[Issue #13](https://github.com/reitojike/cinematic-camera/issues/13)の範囲で、Camera2のAF/AWB/anti-banding mode、
AF/AE領域、AE補正範囲/stepと、CaptureResultのAF/AWB状態・focus diopters・EV・anti-bandingを追加した。
値が取得できなければnull/unknownとし、能力・要求・結果を別に扱う。

- Center AF/AEはPreviewViewのmetering pointとCameraX標準APIを使用。対応照会後にユーザー操作で要求し、
  Futureの合焦結果を動体追従や画像品質の合格にしない。MANUAL AEではAFだけを要求し、AF非対応なら理由を示す。
- EVは受理済みAUTO・対応範囲内だけで操作。mode別希望を保持し、AUTO clear→EV受付→必要なら保存済みMANUALの
  復元を直列化する。無効な保存値はneutral fallbackと理由を示し、希望値を黙って書き換えない。
- focus/EVのFutureはepoch/closedで隔離し、bind/stop時は現在sessionの表示を初期化する。
- PowerManager thermal statusを観測し、SEVERE以上で録画停止/Finalize、新規録画の抑止を行う。
  listenerはcloseで解除する。OS severityを温度℃や安全温度へ変換しない。

B1では新依存・新権限、WB/MF/anti-bandingの手動上書き、フリッカー解消アルゴリズムは追加していない。
26件のunitとbuild/lintは成功したが、AF追従・LED・EV反映・熱応答の実機条件は未確認。

## B2: WB preset・MF・AE anti-banding

[Issue #15](https://github.com/reitojike/cinematic-camera/issues/15)の範囲で、available modes/request keysに対応する
標準WB presetとAE anti-banding、MANUAL_SENSOR/AF OFF/focus key/有限rangeを満たすMFを追加した。
WB OFF/gains/transform/Kelvin指定は含めない。MFはlens unitsで、未校正値からメートルを保証しない。

露出・WB・MF・anti-bandingは一つのInterop snapshotを置換する。個別変更で他の受付済み制御を保持し、
MF変更前はCameraX cancelFocusAndMetering完了を待つ。AF復帰時はAF_MODE/LENS_FOCUS_DISTANCEを除外する。
MF中はCenterからAFを要求せず、AUTO AEが有効な場合だけAE測光する。
明示的な`AUTO reset all`はfocus/Interop全解除とneutral EV。mode復帰はclear→対応する保存control→EV→
必要なら保存MANUAL露出の順で受理を待つ。非対応の保存値は項目別fallbackと理由を示し、希望値を保持する。
変更中/録画中/Finalize中は次のcontrol操作を抑止し、古いFutureはepoch/closedで隔離する。
個別操作はその項目だけを保存し、他項目が非対応fallback中でも元の保存希望を消さない。

要求WB/focusと実結果、scene flickerを観測する。scene flickerはLED/PWM除去の証拠ではなく、
AE anti-bandingはMANUAL AEで効果を持たない。30件のunit/build/lint成功と実機品質を区別する。

追加の手動検証は、STILL/CINEごとのWB/MF/anti-banding復元、WB変更後もmanual ISO/時間が残ること、
MF/AF往復、AUTO全解除、色照明下のpreset比較、50/60Hzとmanual shutter比較。
すべて実機未確認であり、原本/素材の自動撮影・公開はしていない。
