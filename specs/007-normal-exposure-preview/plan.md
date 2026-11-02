# Implementation Plan: 通常STILLの二段階露出プレビュー自動追従

**Branch**: `fix/010-normal-exposure-preview` | **Date**: 2026-11-02 | **Spec**: [spec.md](spec.md)
**Input**: [Issue #44](https://github.com/reitojike/cinematic-camera/issues/44)

## Summary

固定pair PoCの二段階policyを通常STILL MANUALの即時編集へ適用する。撮影targetとpreview要求/受付/実測を分離し、待機previewは補償pair、シャッターから全format terminalまでは固定撮影pairをruntimeで専有する。ImageCaptureのstatic optionsへ撮影用露出を保持しない。

## Technical Context

**Language/Version**: 既存Kotlin / Jetpack Compose構成。
**Primary Dependencies**: CameraX 1.6.2の標準公開APIとCamera2Interop / Camera2CameraControl。依存更新なし。
**Storage**: 既存MediaStore JPEG/DNG、アプリ専用診断ログ。補償pairは撮影preferencesへ保存しない。
**Testing**: 既存JUnit unit、Android lint/build、設定構成の静的確認。修正版の実機手順は本plan内。
**Target Platform**: 既存Android 28+。実機ACはユーザー確認まで未確認。
**Project Type**: 単一Android app。
**Performance Goals**: 編集queueは最新要求を1件保持し、通常編集ごとの全unbind/rebindを0回にする。センサー物理待ちの秒数を保証しない。
**Constraints**: 固定1s閾値/第3描画mode/AUTO previewへの逃げなし。CINE/RAW/MediaStore/即時反映/overlay維持。
**Scale/Scope**: 通常STILL露出制御、撮影との競合、診断表示と限定unit。HTML操作部整備は#45。

## Constitution Check

Phase 0前/Phase 1後とも適合。公開CameraX APIのみでCameraDevice/sessionを直接操作しない。要求・受付・実測・保存・実機合格を別扱いにする。依存/SDK/権限/署名秘密を増やさない。日々の進捗はIssue/PRへ置く。最終headの独立/canonicalレビュー・必須CI・thread 0をDraft PRで確認し、merge/配布は別承認。constitution変更や例外正当化は不要。

## Project Structure

### Documentation (this feature)

`specs/007-normal-exposure-preview/{spec.md,plan.md,tasks.md,checklists/requirements.md}`。
research/data model/validationを以下へ統合し、外部APIを追加しないためcontractsを増やさない（`docs/SPEC_KIT.md`の既存小規模運用）。

### Source Code (repository root)

- `app/src/main/java/dev/cinematic/p0/ExposureSimulationPolicy.kt`: 既存二段階計算。
- `app/src/main/java/dev/cinematic/p0/StillExposureState.kt`: 通常要求/受付/実測/撮影snapshotと状態遷移。既存PoCのExposureSimulationRunとは独立する。
- `app/src/main/java/dev/cinematic/p0/CameraProbe.kt`: builder/runtime設定、latest-wins、撮影専有phase、世代破棄。`PhotoCallbackGate.kt`の純粋format集約を本番callbackで使い、partial/duplicateは保存・復帰を開始しない。terminalかつ非publishing時だけ遅延callback由来のstaging cleanupを行い、publish中のIO所有を壊さない。
- `app/src/main/java/dev/cinematic/p0/CaptureScreen.kt`: 通常自動適用、busy、待ち理由と区別表示。
- `app/src/test/java/dev/cinematic/p0/ExposureSimulationPolicyTest.kt` / `StillExposureStateTest.kt` / `ControlOperationTest.kt`: 境界/世代/競合/最新要求置換のunit。`StillExposurePresentation.kt` / `StillExposurePresentationTest.kt`は表示の意味を分離する。`PhotoCallbackGateTest.kt`はformat順不同/重複/部分完了/失敗とpublication所有を、`PendingBindControlsTest.kt`はAUTO引継ぎのkey/epoch/one-shotと明示stopの破棄を検証する。`StillResumeIntent.kt` / `StillResumeIntentTest.kt`は未受付の最新論理targetを背景復帰の同scope再試行まで保持し、要求identity一致の新ACKだけで解除する境界を検証する。

**Structure Decision**: 既存の単一appと責務を再利用し、汎用camera abstractionや別moduleを追加しない。

## Phase 0: Research / 採用判断

### 固定露出の所有者

**Decision**: ImageCapture.BuilderにはAE mode/ISO/exposure time/postRAW boostの固定露出optionsを入れない。AE_OFFもruntimeのみとする。待機時runtimeは補償preview pair、撮影時runtimeは固定capture pairへ切り替える。Preview.Builderへseedが必要なら安全な補償pairだけを使う。
**Rationale**: 固定撮影long pairがsession repeatingや初期/再bindへ混入する経路を断ち、通常編集をruntime変更だけで処理する。CameraX 1.6.2の公開APIと親担当の公式source解析を設計入力とする。
**Alternatives considered**: ImageCapture固定撮影pair維持は待機preview混入のため不採用。編集ごとrebindは遅延のため不採用。AUTO preview置換は明るさ要件に反する。

### AUTOとreset

**Decision**: Preview.Builderにmanual seedを置いたsessionからAUTO/全resetへ移る場合、runtime clearだけに頼らずseedなしで再bindする例外を認める。
**Rationale**: runtime optionsのclearはbuilder由来の固定options消去を保証しない。通常manual編集の全rebind禁止とは区別する。
**Alternatives considered**: 無条件clearだけは固定AE_OFF等が残り得るため不採用。

### 単発撮影の専有とtimeout

**Decision**: シャッター時のrevision/pairを固定→runtime撮影pairのACK→takePicture→全出力terminal→runtime最新previewのACKとする。flash OFF / MINIMIZE_LATENCY、ZSLなし。
**Rationale**: runtime ACKはCaptureResult待ちであり、準備/撮影/復帰のいずれにも長秒の物理待ちが発生し得る。ACKは実測一致や画質一致の証拠ではない。takePictureに個別cancelがないため、timeout後も撮影が生きている間はpreviewを上書きしない。
**Alternatives considered**: timeout直後のpreview復帰/編集再開は遅延撮影へ競合するため不採用。Camera2直接制御は範囲外。

## Phase 1: Data Model / 状態設計

- `capture target`: 通常のISO/SSとrevision。preview受付値と呼ばない。
- `preview request`: capture revision、session epoch、preview request id、policy stageと補償pair。連続編集はpending最新1件に集約し、32msのdispatchで旧in-flight要求を置換する。旧FutureのACKを待って次要求を送るqueueにはしない。
- `preview receipt`: 同一epoch/request idのAPI完了だけ。旧完了は最新receiptへ昇格しない。
- `observation`: epoch/frame/intent、実測ISO/SS/frame duration/boost。撮影intentはpreview実測へ採用しない。値から一対一のファイル対応や一致を推定しない。
- `capture snapshot`: shutter時のcapture revision/pair、処理epoch、formatごとのterminal。撮影開始後にpreview変更で更新しない。
- `phase`: PREVIEW_PENDING/READY→CAPTURE_PREPARING→CAPTURING→RESTORING→READY。timeout/不明結果はUNKNOWN、背景化/stopはepoch切断しSUSPENDED。名称は既存状態へ対応付けてよい。制御phaseであり第3描画modeを意味しない。

### Invariants

1. current epoch/revision/requestに合わないcallbackは表示/次の撮影/保存成功へ採用しない。
2. CAPTURE_PREPARING/CAPTURING/UNKNOWNでは専有を保持し、既存busy UIで競合編集/連打を止める。takePicture発行後はterminalまたは明示stopの世代切断まで保持する。発行前の受付失敗は撮影未開始として、旧control tokenを無効化してpreview ACK/新実測を得てから解放できる。
3. 全format terminalを一度だけ消費し、preview復帰失敗を撮影保存失敗と混同しない。復帰先は保持済みの最新通常targetから再計算する。
4. 通常manualの背景化は明示stopでepochを無効化し、保持したInterop optionsをclearしてunbindする。foregroundでは受付済みpreferencesとは別に保持した最新の論理targetから安全にbindする。32ms集約待ち/未受付の最新値も対象とし、camera/modeキー付きmemory-only要求を新sessionのACKまで保持し、同scopeのbind/受付失敗後の再試行でも同じ最新要求を使う。ACK時は要求identityを照合して解除し、旧ACKで新しい要求を消さない。未受付値を受付済みとして保存せず、新sessionのACK後にのみ撮影設定へ保存する。明示Stop/close/AUTO/resetでは背景復帰要求を破棄し、AUTO/resetを受理する境界では失敗後に残った旧live targetもpending状態によらず破棄する。AUTOの受付失敗/UNKNOWNも最新の明示要求として背景復帰へ保持し、その後の新MANUAL要求は優先する。locksは新sessionへ継承しない。bind前も旧held optionsを同期clearし、旧Futureの成功を新sessionの証拠にしない。CINEへ固定撮影/補償settingsを持ち込まない。
5. 無効/未知rangeは送信しない。第2段階の正当な長めpreviewと撮影long pair混入を診断上区別する。

## Validation / 修正版の限定手順

機械検証は `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --no-daemon`（Windowsは `.\gradlew.bat`）と `git diff --check`。初期/再bind builderの露出options、通常編集の再bind呼出し、takePicture後timeoutの専有保持も静的確認する。AUTO/背景復帰はautoControlSnapshot、PendingBindControlsのkey/epoch/one-shot、StillResumeIntentの未受付/再試行/identity/ACK解除、StillExposureStateのsuspend/epoch、latest-winsの純粋境界unitと、CameraX 1.6.2のseed/clear/stop/foreground標準API callsiteの静的照合で機械側を確認する。新Android mock基盤や実装を写すだけのテストは導入せず、CameraXの実呼出順・実復帰は実機ACへ分離する。

実機操作はユーザー実施。v13の追加確認を前提にせず修正版だけで以下を確認する。現時点は全項目未確認。

| 操作 | 確認する結果 | 根拠を分ける点 |
|---|---|---|
| 通常MANUALでISO/SSを即時連続変更 | 明示PoCなしで二段階へ自動追従し最新値で落ち着く | target / preview要求 / ACK / 実測 / 見え方 |
| ISO余裕内と上限到達の長秒 | 短露光から明るさ優先の長めpreviewへ移る | 固定1sで切らない。noise/tone差は残り得る |
| 長秒の保存設定で起動、ISO/SS変更直後・受付待ちの背景復帰 | 撮影専用long pairが待機previewへ混入しない | 正当なstage2長めpreviewと区別 |
| 長秒→短秒/AUTO | 最新設定へ復帰し旧補償/seedを残さない | センサー待ち、ACK待ち、queue/rebind待ちを別記 |
| JPEGおよび対応するRAW+JPEGを単発撮影 | snapshot露出で撮影、全format後に最新previewへ戻る | 保存成功とpreview復帰、DNG有効性は別 |
| 撮影中背景化/失敗/timeout | 旧callbackを除外し不明状態を成功としない | 不可避待ちを隠さない |
| CINE往復/overlay/通常即時設定/MediaStore | 現契約を維持 | 全回帰合格には実機証拠が必要 |

#42は定性的ユーザーOKによる完了でありT009全実機証明ではない。#37通常経路を包括解決済みにしない。成立しない標準API制約は事実と最小代案をIssue/PRへ報告し、GPU/NDK/Camera2直接/新権限へ進まない。
