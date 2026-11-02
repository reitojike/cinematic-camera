# Implementation Plan: 通常MANUAL撮影targetの暫定1秒上限

**Branch**: `feat/014-one-second-exposure-cap` | **Spec**: [spec.md](spec.md)

## Summary / Technical Context

既存Kotlin/Compose・CameraX1.6.2単一appを維持する。NormalExposurePolicyに暫定1秒定数・新入力検証・旧長秒復元変換を置く。ExposureStopsは候補上限へ同定数を交差させる。CameraReportは実能力のまま不変。依存/権限/保存先/署名変更なし。

## Constitution Check

既存#37/#44と今回のユーザー承認を根拠とする。新Issue投稿拒否は再試行しない。指定/受付/実測を分離し、AEやfreeze解消を保証しない。RAW/MediaStoreと標準APIを維持する。実機未確認と最終headレビュー/CIを分ける。今回の限定merge/配布承認は親担当が適用する。

## Project Structure / Design

- `app/src/main/java/dev/cinematic/p0/NormalExposurePolicy.kt`: 暫定上限、新入力validate、旧値→新targetのNormalExposureRestoration。旧保存の構文/現ISO/正時間を先に検証し、旧長秒が現在sensor upperより長くても正規化後のtargetを現在nsRangeへ照合する。ISOは保持し、SSは端末range/maxFrame/fps/1秒の交差内の最長標準候補へ移行する。合法候補なしは例外で送信を止める。
- `ExposureStops.kt`: 上限へ定数を交差。元のshutters/labelは長秒の診断・旧値表示のため保持し、最短端点/ISO系列を変えない。
- `CameraProbe.kt`: plannedStillで上限を防御、初期Preview seedとruntime復元を同policyで正規化。直接入力は1秒超を拒否。移行保存はruntime ACK後のみ。AUTO/resetは既存解除/復帰破棄のまま。
- `CaptureScreen.kt`: 移行要求を既存共通noticeへ一回渡し、診断へpolicy/移行履歴を表示。UI48統合後は同診断表示をCapturePanelsへ移す。
- `app/src/test/java/dev/cinematic/p0/NormalExposurePolicyTest.kt` / `ExposureStopsTest.kt`: cap/旧値/端末交差/候補なし/冪等/最短/fpsのpure検証。既存AUTO/reset/resume/receipt unitとcallsite静的照合で接続を確認する。

短いsource名はすべて`app/src/main/java/dev/cinematic/p0/`配下。research/model/validationは本planへ統合し、外部contractsは追加しない。

## Research / Rationale

上限はsensor capability書換えではなく通常MANUAL targetの暫定policyとする。新入力を黙ってclampする案は不採用。旧保存の復元だけISOを維持して合法標準SSへ明示移行し、旧値/新target/ACK/実測を別にする。AUTOは手動override解除で、AEのセンサー露光上限を保証しない。

## Validation

pure unitで1秒境界/1ns超過、端末上限未満の最長標準候補、候補なし、旧保存変換と再parse/rebind冪等、最短SSとCINE24/30/60/120fps候補の不変を確認。旧保存/未受付resume→bindseed→runtime→新ACK後保存、AUTO/resetの呼出境界を静的照合する。標準assemble/unit/lint・diffcheck・公開scanを親担当と調整して実行。最終head独立/canonical/CI/thread0は親担当が確認する。実機の移行notice、露出actual、撮影/復帰時間、RAW回帰は未確認を維持する。長秒freezeの原因解消とは扱わない。
