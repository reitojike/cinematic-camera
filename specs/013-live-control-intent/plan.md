# Implementation Plan: ライブ操作の最新意図と主値の可読性

**Branch**: `fix/016-live-control-intent` | **Date**: 2026-11-03 | **Spec**: [spec.md](spec.md)
**Input**: [Issue #47追加スコープ](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6593446735)、関連Issue #37。

## Summary

最新の論理ControlSnapshotを単一queueのauthorityとし、UI intent・runtime API受付・実測を分離する。EV/WB/MFはpending中も更新を受け付ける。MFは即時previewへ変更する。AUTO正常実測をdiagnostics throttleより前で軸別集約し、近似説明と数値の独立した測定/配置で可読性を確保する。

## Technical Context

**Language/Version**: 既存Kotlin / Java 17。
**Primary Dependencies**: Compose、CameraX 1.6.2、Android標準lifecycle。追加/更新なし。
**Storage**: 既存MediaStore・アプリ内設定のみ。
**Testing**: JUnit pure policyとcallsite照合、Compose実描画/実機は別AC、通常Android CI。
**Target Platform**: minSdk 28 / targetSdk 37、Pixel 9aの実機結果は別確認。
**Project Type**: 単一Android app。
**Performance Goals**: runtime flight 1 + latest pending 1、32msの送信間隔。操作継続中にも送信し、ACK後に最新pendingを処理する。主値TTLは2000ms。
**Constraints**: RAW比較・回転・権限・依存・鍵/他repoを変更しない。CameraX管理sessionを直接操作しない。
**Scale/Scope**: AUTO表示、連続操作、MF即previewとその共通queue境界。

## Constitution Check

設計前後ともI〜VIに違反なし。仕様/設計/完了状態と実機証拠を分離し、標準CameraX/Compose/lifecycleと既存保存を使う。新依存・権限・カメラ基盤・公開情報を追加しない。実機の原因確定や解消済みを機械テストから主張しない。Draft/review/CIまででmerge/配布は未承認。

## Project Structure

### Documentation (this feature)

`specs/013-live-control-intent/{spec.md,plan.md,tasks.md}`。標準template resolver/setup-plan/setup-tasksを使用する。`docs/SPEC_KIT.md`に従いresearch/data-modelは本planへ統合、quickstartは既存README/P0_REPORTを参照する。外部API契約や別checklistは増やさず、spec品質は会話で照合する。旧009/012には置換リンクだけを追加し、時系列作業ログを蓄積しない。

### Source Code (repository root)

- `app/src/main/java/dev/cinematic/p0/LiveControlQueue.kt`、`CameraProbe.kt`、`ProbeControls.kt`、`CaptureState.kt`: logical intentとruntime要求/受付/失敗/lifecycleの境界。`ControlOperation.kt`の旧LatestExposureQueueを置換し重要テストを移植する。LiveControlQueue.ktはLatestLiveControlQueueと軸別正常観測のbounded PreviewExposureMailboxを収容し、ControlOperation.ktはdeadlineの責務を維持する。
- `SensorControlEditors.kt`、`CapturePanels.kt`、`SensorControlPresentation.kt`、`ExposureControls.kt`、`ControlScale.kt`: pending入力と選択投影、MF即previewとstable anchor。
- `ExposureValuePresentation.kt`、`CaptureScreen.kt`、`CaptureChrome.kt`、`CaptureChromeModel.kt`: 軸別観測と主数値/近似説明の測定・配置。
- `app/src/test/java/dev/cinematic/p0/`: queue・観測・表示・選択の対応テスト。sourceのファイル名は既存境界を優先し、不必要に分割しない。

## Phase 0: Research / Decisions

1. **Decision**: logical ControlSnapshot（ISO/SS/EV/WB/MF全体）を単一LatestLiveControlQueueのauthorityにする。**Rationale**: 個別queueやsnapshot ACKが別項目の新intentを失わせる境界をなくす。**Alternative**: EV/WB UI busy拒否やMF apply維持は新ACと矛盾する。
2. **Decision**: flight1 + pending1、32ms間隔、ACK後latestを直列処理する。**Rationale**: UIを応答待ちで止めず有限queueにする。**Alternative**: 全中間値をFIFO化、指離しのみcommit、無制限並列APIは不採用。
3. **Decision**: API failure/timeoutではqueueをcancelしてlatest intentを保持しUNKNOWN/失敗を表示、reset/rebindで明示復旧する。ISO旧UNKNOWN時の入力再retryもこの安全な統一契約へ置換する。**Rationale**: 自動retryによるloopと古いACKの巻戻りを防ぐ。
4. **Decision**: AUTOはdiagnostics throttle前に軸別valid accumulatorを更新する。**Rationale**: 表示へ届くdiagnostics frameに欠落が重なる場合でも正常観測の元timestamp/epochを保持できる。**Alternative**: TTL延長・旧値無期限保持は偽表示のため不採用。
5. **Decision**: 主数値は近似説明と分離し、実際のTextMeasurer/minWidthで測る。狭幅/fontScaleでは操作rail横scrollとoverlay高さを適応する。**Rationale**: 『約』追加や固定幅の文字数見積りで分母が消えることを避ける。preview resize・回転変更は行わない。

## Phase 1: Integrated Data Model / Contracts

- **Authority**: latest logical intentはUI選択を即反映する。active runtime API receiptとCaptureResultは別状態であり、ACKは受付履歴だけを更新する。最新intentより古いreceiptを主値や選択へ逆流させない。WB/EV/MFの変更は最新全体snapshotへmergeしISO/SSを保持する。
- **Dispatch**: logical capture targetは送信直前に既存STILL capture→preview補償へ変換する。queueへ補償値を書き戻さない。flight tokenはepoch/sequenceを持ち、pendingは最後の全体snapshotだけ保持する。32ms間隔でdrag継続中にもdispatch、flight完了後はlatestだけを送る。
- **Rollback / recovery**: failure/timeoutでflight/pendingをcancelしlatest intentは保持、UNKNOWNを通知して自動loopを止める。reset/rebindは明示復旧境界。epoch/backgroundで旧tokenを無効化し、STILLの全logical intentを能力に照合して復帰、locksをclearする。古いcallbackは現snapshot/receiptを変更しない。
- **復旧scope**: 失敗intentはmemory-onlyのcamera/mode key付きで保持し、明示bindでは同じkeyだけ再要求する。異なるkeyへ持ち込まない。bindの優先順はStillResumeIntent → PendingBindControls（明示AUTO）→ 同keyの失敗intent。復旧ACK後にmemory retry intentを解除する。露出AUTOは先に最新全体snapshotを取り、queue/scope/StillResumeをclearしてWB/MFを保持する。全resetは全制御を解除する。UNKNOWN後も設定の全リセットと診断の停止/開始へ到達できる。
- **保存の意味**: 再送用のfull intent/scopeはmemory-only。通常WB/MF/EVは既存の項目別requestedPrefs保存を維持し、非対応fallbackで未編集の保存要求を書き換えない。露出の受付保存とは分ける。明示full resetは独立設定の保存要求を空/EV0へし、seeded・nonseeded両経路で同じ扱いとする。APIの受付/失敗/実測はこの保存と独立して表示し、保存を適用成功の証拠にしない。
- **MF**: 即時選択callbackをqueueへ接続しapply/cancelを除く。候補はreported maxの100等分と正確なopening anchor。anchorはepoch/maxで固定し、各ACKで候補を作り直さない。非対応/不正/AF/MF/∞遷移を明示検査し丸めを送信しない。
- **AUTO**: current epoch、ISO/SSの元timestamp/valid valueを独立保持。正常PREVIEW/VIDEO_RECORDのみaccumulateし、欠落/不正/旧/逆転sampleで更新・延命しない。epoch/unbindでclear、表示時0〜2000msのみ有効とする。MANUAL target、RAW固定targetと補償の分離は維持する。
- **Presentation**: 数値、unit、source/近似説明を別フィールド/領域にする。TextMeasurerは実際のfontScale/style/constraintsで測定する。縦/左右横・fontScale 1.0/1.3/2.0・最長/最短値で数値を省略しない。操作領域/読み上げとpreview寸法を保つ。
- **Compatibility**: 009/012のMF apply/cancel・pending UI禁止と旧exposure queue UNKNOWN入力再retryだけを本設計へ置換する。回転実装やRAW比較の変更へ広げない。

## Validation / Quickstart References

既存[README](../../README.md)、[P0_REPORT](../../docs/P0_REPORT.md)、[BUILD_VALIDATION](../../docs/BUILD_VALIDATION.md)を参照する。機械確認は通常unit/lint/assemble、`git diff --check`と公開情報検査。queueは遅い中間ACK、200操作のcoalescing上限、失敗/timeout、epoch/background、ISO/SSとEV/WB/MFの相互保持を確認する。EV 0→+1/WB連続/MF往復、同値/端点/標準haptic、AUTO正常→欠落/期限/旧epoch、表示丸め非書戻しを回帰確認する。

実描画の可読性と実機のAUTOちらつき・連続操作・MF合焦探索は別AC。要求発行・API受付・実測・見た目の結果を分離し、Issue/PRへ要約だけ記録する。最終head必須CI・独立レビュー明示OK・canonical review・thread0が揃ってもmerge/配布は別承認まで停止する。

## Complexity Tracking

新基盤や憲法例外なし。旧queueと新queueを併存させずauthorityを一本化する。必要な観測は既存能力・epoch・単調時計に限定する。実機の根因解消と可読性合格は未確認。
