# Implementation Plan: 撮影UIの状態・制御値・診断整理

**Branch**: `feat/012-capture-ui-polish` | **Date**: 2026-11-02 | **Spec**: [spec.md](spec.md)
**Input**: Issue #47。#46 merge後main f1cb97dを基準とする。

## Summary

承認5項目を既存Compose画面へ限定適用する。表示policyは構造化状態を入力とする純粋モデルへ寄せ、既存CameraProbe/Recorder/editorの操作と保存を変更しない。README Final / CamScreen variant Dの構造/CSSを参照し、後合意の即時設定・overlay・固定配置を優先する。

## Technical Context

- **Language/Version**: 既存Kotlin / Compose Material3・Foundation。
- **Primary Dependencies**: CameraX 1.6.2と既存Android/Jetpack。更新・追加なし。
- **Storage**: 既存MediaStore/レポート保存を利用。新保存先なし。
- **Testing**: JUnit純粋表示policyと既存state/layout/rotation/editor回帰、lint/build、callsite静的確認。
- **Target Platform / Project Type**: 既存Android 28+、単一app。
- **Performance Goals**: 表示変更によるcamera rebind・撮影/録画再開を0件にする。
- **Constraints / Scope**: シャッター、状態バー、MF/WB、失敗/無効、設定/診断の5項目のみ。新camera pipeline/権限/独自UI基盤なし。

## Constitution Check

事前・設計後とも適合。Issueをauthorityとし、表示と実測合格を分離する。標準Compose部品と既存構造化stateを用い、診断文字列を解析して状態を作らない。RAW原本/保存境界と固定版依存を維持。公開素材/個人パス/写真/GPSを追跡しない。Draft PRの最終headレビュー/CI/threadを確認し、merge/配布は未承認。

## Project Structure

- `app/src/main/java/dev/cinematic/p0/CaptureScreen.kt`: シャッター/状態chip/MF・WB/無効理由/設定・診断のCompose接続。
- `app/src/main/java/dev/cinematic/p0/CaptureState.kt` / `VideoPolicy.kt` / `StillExposurePresentation.kt`: 既存構造化状態の情報源。操作stateの意味は変更しない。
- `app/src/main/java/dev/cinematic/p0/CaptureChromeModel.kt` / `CaptureChrome.kt`: シャッター/状態バーの純粋policyとCompose描画。`ShutterCaptionLayout.kt`はcaption配置計算。
- `app/src/main/java/dev/cinematic/p0/CaptureFeedback.kt` / `CapturePanels.kt`: 無効理由・失敗色・診断source分離の純粋policyと設定/診断セクション。
- `app/src/main/java/dev/cinematic/p0/SensorControlPresentation.kt` / `SensorControlEditors.kt`: MF/WBの純粋表示と既存編集actionへの接続。
- `app/src/test/java/dev/cinematic/p0/CaptureChromeModelTest.kt` / `CaptureChromeRegressionTest.kt` / `CaptureFeedbackTest.kt` / `SensorControlPresentationTest.kt`: 状態表/欠落/情報区分/操作可能性/四半回転caption配置/MF・WB境界。
- `app/src/test/java/dev/cinematic/p0/CaptureStateTest.kt` / `CaptureLayoutTest.kt` / `CaptureOrientationTest.kt`: 既存回帰。
- `specs/009-capture-ui-states/{spec.md,plan.md,tasks.md,checklists/requirements.md}`: 採用契約とAC対応。

既存単一appを維持する。外部APIがないためcontractsは作らず、research/model/validationは本planへ統合する（docs/SPEC_KIT.md）。

## Research / 採用判断

**Decision**: 純粋policyがshape/tone/caption/enabled/reasonと表示情報区分を返し、Composeは描画・既存action呼出しだけを担当する。
**Rationale**: 全stage/失敗/未知情報の意味をunitで検証でき、camera処理を変更せず5項目を揃えられる。
**Alternatives considered**: 各Composableへ同じ条件式を分散すると停止可否や失敗色が食い違う。新UI framework、camera mock基盤、mockデータ注入は不要。

**Decision**: 状態バーはcamera/形式/選択quality・fpsまたはbind構成の出典を明示する。構造化入力がないactual/実出力は「—」等とし、選択値や文字列ログで捏造しない。
**Rationale**: quality/fps選択・bindは実出力の保証ではない。熱もOS状態を使い架空温度を表示しない。
**Alternatives considered**: requested fpsをactual fpsと短縮表示する案は証拠区分を壊す。

**Decision**: 参照はREADMEの確定規則とFinal/CamScreen variant Dの構造/CSS。HTML file表示拒否の迂回・再試行を行わず、既存の構造監査を用いる。
**Rationale**: 実描画比較は未実施であり、CSS値の照合を視覚一致の証拠にできない。精細余白・switch装飾等は範囲外。

## Data Model / 表示契約

- **ShutterPresentation**: mode、録画/静止画stage、形状（内円/停止四角）、tone、caption、action、enabled、理由。CINE開始/録画/停止/保存を別状態とし、停止可能stageのactionをbusyだけで消さない。既存stageを全列挙する。録画停止の四角はREADME優先の26dp。captionは76×96dpの固定領域内へscrim付きoverlayとして置き、円中心を動かさない。四半回転の実測寸法を制約内へclampするが、ringとの完全非重複・回転途中角・実画面の可読性は保証しない。
- **StatusPresentation**: camera ID、still形式、video quality/fps情報のsource（要求/選択/bind/実測/実出力/未知）、record/save/thermal。欠落・非該当は別扱い。録画失敗をwarning色へ吸収しない。静止画の準備失敗と撮影後失敗は保持したcapture receiptで区別し、captionと状態chipの両方へ反映する。復帰後にfalseへ戻る一時的なsingleIssuedを過去の失敗分類へ使わない。
- **ControlPresentation**: MF表示丸めとLU、AF/MF tag、WB選択、pending/accepted/rejected/unknown、draft/target/receipt/actual。表示丸めを送信値へ逆流させない。
- **DisabledReason**: busy、unsupported、permission、thermal、unknown/recovery等の既存事実。停止/復旧入口は主操作の無効と独立に保つ。
- **DiagnosticSection**: target/要求、受付履歴、実測、撮影・保存、能力・端末等の見出しとラベル。過去receiptを現要求の成功へ昇格しない。既存レポートexportを維持。

## Validation / 限定手順

1. 全RecordingStage・PhotoStageをpolicy unitへ列挙し、形状/tone/caption/停止可否、録画失敗、busy/非対応/権限/熱、未知値/旧受付を検証する。
2. MF draft適用/取消、WB即時選択、ISO/SS即時・反映ボタンなし、AUTO/reset分離、Back、overlay/固定配置/向き変更の既存unitとcallsiteを回帰確認する。純粋境界と静的接続照合を用い、実装を写すだけのテストを増やさない。
3. 担当者が `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --no-daemon`（Windowsは `.\gradlew.bat`）、`git diff --check`、公開scanを実行する。これはHTML/Android UI実描画や実機録画/RAW合格の証拠ではない。
4. 実機ではSTILL/CINE×縦/左右横持ち、設定・診断overlay、全撮影/録画/停止/保存/失敗、MF/WB、停止/復旧/Back、文字欠け/暗所可読性/重なり、captionとringの重なり・回転途中角、録画中回転継続とRAW保存を限定確認する。許可なくカメラ/マイクを操作しない。未実施は未確認のまま残す。
5. Draft PRの最終headに独立/canonicalレビュー・必須CI成功・thread 0を揃える。merge/配布は本件で行わない。

## Complexity / Stop Conditions

新camera処理、権限、依存更新、独自UI基盤、拒否されたHTML表示の迂回が必要なら停止して根拠を報告する。STILL/CINE切替外観、細余白、LOOK、photo viewer、比率出力へ拡大しない。
