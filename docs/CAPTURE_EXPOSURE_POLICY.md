# 撮影露出policyの設計と検証入口

通常MANUAL撮影targetの暫定1秒上限の現行契約は [撮影と制御](../specs/capture/spec.md) を正本とする。
新しい直接入力を黙ってclampする方式ではなく、過去の長秒保存/復帰だけをISO保持で移行する。
実センサー能力、要求、API受付、実測は分離する。

## 責務と接続

- [NormalExposurePolicy](../app/src/main/java/dev/cinematic/p0/NormalExposurePolicy.kt) は暫定定数、新規入力の上限検証、旧値と移行先を持つ `NormalExposureRestoration` を所有する。`restoreSaved` は保存pairの構文・正時間・現ISO範囲を先に確認し、旧長秒を移行した後で現在の露光範囲を検証する。
- [ExposureStops](../app/src/main/java/dev/cinematic/p0/ExposureStops.kt) は端末範囲・最大frame周期・要求fps・暫定上限を標準候補へ交差させる。元の長秒ラベル系列は診断と旧値表示にも必要なため、通常候補から外れることと定義削除を混同しない。ISOを露光補償のために変更せず、存在しない端点候補も作らない。
- [CameraProbe](../app/src/main/java/dev/cinematic/p0/CameraProbe.kt) の `plannedStill`、初期preview seed、`setExposure` の直接入力/保存復元はこのpolicyを使う。runtime受付後のcallbackで移行したpairを保存する。AUTO/resetは既存の保持意図を破棄し、所有状態と世代の境界を維持する。
- 移行要求の一回通知と診断状態は、既存の [CaptureScreen](../app/src/main/java/dev/cinematic/p0/CaptureScreen.kt) / [CapturePanels](../app/src/main/java/dev/cinematic/p0/CapturePanels.kt) の表示責務へ渡す。表示をセンサー反映の証明にはしない。

## 検証入口と限界

- [NormalExposurePolicyTest](../app/src/test/java/dev/cinematic/p0/NormalExposurePolicyTest.kt)：1秒/1ns超過、端末上限未満の最長標準SS、候補なし、ISO保持、不正保存、現在の端末上限を超える旧値、移行の冪等性。
- [ExposureStopsTest](../app/src/test/java/dev/cinematic/p0/ExposureStopsTest.kt)：最短SS/ISO系列、標準候補の交差、CINE 60fpsの既存制約（24/30/60/120fpsの各値はpolicyテストで確認）。
- [StillResumeIntentTest](../app/src/test/java/dev/cinematic/p0/StillResumeIntentTest.kt) / [CaptureStateTest](../app/src/test/java/dev/cinematic/p0/CaptureStateTest.kt) と接続コードを照合し、保存/未受付resume→seed→runtime→新ACK後保存、AUTO/resetの境界を追う。
- 実行は [運用手順](OPERATIONS.md) と [Android CI](../.github/workflows/android.yml) に従う。純粋状態テストやコード照合だけでは、実機の移行notice、露出actual、撮影/復帰時間、RAW回帰、長秒freezeの改善は確認できない。実機未確認を合格へ読み替えない。

## 根拠と履歴

当初の目的と実機上の制約は [Issue #37](https://github.com/reitojike/cinematic-camera/issues/37) / [#44](https://github.com/reitojike/cinematic-camera/issues/44)、今回の移行対応と検証結果は [#358](https://github.com/reitojike/cinematic-camera/issues/358) に残す。
旧011のspec/plan/tasks/checklistは [移行前の固定コミット](https://github.com/reitojike/cinematic-camera/tree/a1225a2cb4618a70e92b4d59c651af96f08883c6/specs/011-one-second-exposure-cap) から参照・Git復元できる。当時のDraft・許可・完了印・未完タスクは当時の記録であり、現在の進捗や試験成功を表さない。
