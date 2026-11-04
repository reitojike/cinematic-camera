# Implementation Plan: ライブ操作の最新意図と主値の可読性

**Branch**: `feat/025-focus-auto-observation` | **Date**: 2026-11-03 | **Spec**: [spec.md](spec.md)
**Input**: [Issue #47追加スコープ](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6593446735)、関連Issue #37。

現行の限定補正は[Issue #47 横一列・ショートカット・歯車](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6595243467)と[Issue #37 手動再接続](https://github.com/reitojike/cinematic-camera/issues/37#issuecomment-6595251915)。既存013内でspecify → plan → tasks → analyzeを行い、新しいfeature文書を増やさない。

表示/触覚の追加は[Issue #47 v19 UI](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6595777813)。SSの可視単位なし/読み上げ秒、mode switch、RESET/AUTOの役割表示、同期受付時hapticに限定し、一般的な既存ユーザー報告をv19実機合格へ流用しない。

現行の追加は[Issue #47 v20 共通見出し・触覚](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6596333540)。5目盛りの共通見出しと受付haptic種別に限定し、既存guard/読み上げを保つ。追加PO指示のない関連外UI差分を作らない。

[Issue #61](https://github.com/reitojike/cinematic-camera/issues/61)は外側preview tapの閉じる専用routingに限定する。設定/診断パネル自体の無回転はユーザーの明示指定として維持する。

[Issue #47 v22 横持ち項目幅](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6599683392)は横持ちの見かけ幅共通化に限定する。ユーザーの縦持ち確認を横持ち実機合格へ転用しない。

今回の明確化対象は開いた5ダイヤルとし、6キーの既存共通化は維持する。キー側のsource/テストをダイヤル幅の検証へ流用しない。

FOCUS追加はAF CaptureResult近似/明示MF選択/AUTO復帰に限定し、幅とRAW等の既存処理を維持する。実測・target・API受付と実機受入を分ける。

## Summary

最新の論理ControlSnapshotを単一queueのauthorityとし、UI intent・runtime API受付・実測を分離する。EV/WB/MFのpending連続入力とMF即時previewを維持する。AUTOの軸別観測/TTLを維持し、観測更新から入力readiness・gesture identityを分離する。近似説明と数値を別に測定し、6キー同時表示へ配置を補正する。

## Technical Context

**Language/Version**: 既存Kotlin / Java 17。
**Primary Dependencies**: Compose、CameraX 1.6.2、Android標準lifecycle。追加/更新なし。
**Storage**: 既存MediaStore・アプリ内設定のみ。
**Testing**: JUnit pure policyとcallsite照合、Compose実描画/実機は別AC、通常Android CI。
**Target Platform**: minSdk 28 / targetSdk 37、Pixel 9aの実機結果は別確認。
**Project Type**: 単一Android app。
**Performance Goals**: runtime flight 1 + latest pending 1、32msの送信間隔。操作継続中にも送信し、ACK後に最新pendingを処理する。主値TTLは2000ms。
**Constraints**: RAW比較・回転・権限・依存・鍵/他repoを変更しない。CameraX管理sessionを直接操作しない。
**Scale/Scope**: 6等幅横一列/SS略記/歯車、EV0・MF解除AFショートカット、設定UNKNOWN時の安全な手動再接続に限定する。AUTO表示/入力、共通queue、撮影中の停止/復旧の既存契約を維持する。

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

追加の入力回帰は`AutoExposureInputTest.kt`で、CameraProbeの既存選択式をLiveControlQueue.ktのpure `liveExposureSelection`へ最小抽出したproduction境界を通す。60fps相当のAUTO更新→MANUAL移行、pending最新pair優先、同indexの初回選択、stale/future/epoch/intent拒否を確認する。core guardの一律解除や新しいseed APIは追加しない。

## Phase 0: Research / Decisions

1. **Decision**: logical ControlSnapshot（ISO/SS/EV/WB/MF全体）を単一LatestLiveControlQueueのauthorityにする。**Rationale**: 個別queueやsnapshot ACKが別項目の新intentを失わせる境界をなくす。**Alternative**: EV/WB UI busy拒否やMF apply維持は新ACと矛盾する。
2. **Decision**: flight1 + pending1、32ms間隔、ACK後latestを直列処理する。**Rationale**: UIを応答待ちで止めず有限queueにする。**Alternative**: 全中間値をFIFO化、指離しのみcommit、無制限並列APIは不採用。
3. **Decision**: API failure/timeoutではqueueをcancelしてlatest intentを保持しUNKNOWN/失敗を表示、reset/rebindで明示復旧する。ISO旧UNKNOWN時の入力再retryもこの安全な統一契約へ置換する。**Rationale**: 自動retryによるloopと古いACKの巻戻りを防ぐ。
4. **Decision**: AUTOはdiagnostics throttle前に軸別valid accumulatorを更新する。**Rationale**: 表示へ届くdiagnostics frameに欠落が重なる場合でも正常観測の元timestamp/epochを保持できる。**Alternative**: TTL延長・旧値無期限保持は偽表示のため不採用。
5. **Decision**: 6キーは各向きで等幅の横一列に固定し、旧横scroll/狭幅2段方針を置換する。SS略記と主数値/説明別のTextMeasurer適合で各cellを使う。**Rationale**: ユーザー指定の同時配置を維持し、長い説明による主数値縮小を分離する。preview resize・回転変更は行わない。
6. **Decision**: 表示更新に用いるclock/sampleと、ISO/SSの目盛り候補/gesture状態のidentityを分離する。freshnessは元timestampと現在の単調時計で判定し、readinessはcamera/epoch/制御状態から判定する。**Rationale**: 正常な観測更新がgesture再生成や一時的な候補消失を起こしてはならない。**Alternative**: TTL延長、readiness guardの全解除、丸め値からの設定復元は安全境界を壊すため採用しない。表示clockとsampleの更新順は調査/回帰対象であり、これだけで実機原因の確定とはしない。

## Phase 1: Integrated Data Model / Contracts

- **Authority**: latest logical intentはUI選択を即反映する。active runtime API receiptとCaptureResultは別状態であり、ACKは受付履歴だけを更新する。最新intentより古いreceiptを主値や選択へ逆流させない。WB/EV/MFの変更は最新全体snapshotへmergeしISO/SSを保持する。
- **Dispatch**: logical capture targetは送信直前に既存STILL capture→preview補償へ変換する。queueへ補償値を書き戻さない。flight tokenはepoch/sequenceを持ち、pendingは最後の全体snapshotだけ保持する。32ms間隔でdrag継続中にもdispatch、flight完了後はlatestだけを送る。
- **Rollback / recovery**: failure/timeoutでflight/pendingをcancelしlatest intentは保持、UNKNOWNを通知して自動loopを止める。reset/rebindは明示復旧境界。epoch/backgroundで旧tokenを無効化し、STILLの全logical intentを能力に照合して復帰、locksをclearする。古いcallbackは現snapshot/receiptを変更しない。
- **復旧scope**: 失敗intentはmemory-onlyのcamera/mode key付きで保持し、明示bindでは同じkeyだけ再要求する。異なるkeyへ持ち込まない。bindの優先順はStillResumeIntent → PendingBindControls（明示AUTO）→ 同keyの失敗intent。復旧ACK後にmemory retry intentを解除する。露出AUTOは先に最新全体snapshotを取り、queue/scope/StillResumeをclearしてWB/MFを保持する。全resetは全制御を解除する。UNKNOWN後も設定の全リセットと診断の停止/開始へ到達できる。
- **保存の意味**: 再送用のfull intent/scopeはmemory-only。通常WB/MF/EVは既存の項目別requestedPrefs保存を維持し、非対応fallbackで未編集の保存要求を書き換えない。露出の受付保存とは分ける。明示full resetは独立設定の保存要求を空/EV0へし、seeded・nonseeded両経路で同じ扱いとする。APIの受付/失敗/実測はこの保存と独立して表示し、保存を適用成功の証拠にしない。
- **MF**: 即時選択callbackをqueueへ接続しapply/cancelを除く。候補はreported maxの100等分と正確なopening anchor。anchorはepoch/maxで固定し、各ACKで候補を作り直さない。非対応/不正/AF/MF/∞遷移を明示検査し丸めを送信しない。
- **AF観測**: `FocusObservation.kt`のAutofocusObservationsとbounded mailboxで、現epoch・AF対応・受付時のMF overrideなし/最新intentもAFの場合だけ観測を開く。受付より後の取得時刻・新frame・PREVIEW/VIDEO_RECORD・AF mode 1〜4・finite非負値を採用し、0〜2000msだけfreshとする。報告MF最大値が正なら範囲内を検査、最大値なしを固定焦点の証拠にせず、0/不正最大値やOFF/EDOF等は有効AF値としない。欠落で時刻を延命せず、MF要求/失敗/背景化でsuspendし新AF受付前の値を捨てる。旧ACKで最新MF中の観測を再開しない。
- **AF表示/明示選択**: `focusAutoPresentation`はAF時だけ100等分の101候補へ表示専用近似し、LU/AF近似、0候補は∞を示す。LUは未校正のレンズ単位で物理距離/合焦保証ではない。freshなしは?・AF待機、候補範囲なしは?・AF候補なし。MFはlatest logical intentを優先し、受付履歴と観測を分ける。FOCUS目盛りはnumeric onlyとし、最初のfresh AFをepoch/max/AF-MF切替ごとのseedへ固定する。中心候補も明示選択でMF要求するが、同期最新intentを再検査して同値要求を重複しない。最初のdetentでAF観測をsuspendしてもMF intentが立った後はdrag継続を許可する。AUTOはheaderから既存override解除経路へ接続し、新AF受付後の実測を待つ。
- **AUTO**: current epoch、ISO/SSの元timestamp/valid valueを独立保持。正常PREVIEW/VIDEO_RECORDのみaccumulateし、欠落/不正/旧/逆転sampleで更新・延命しない。epoch/unbindでclear、表示時0〜2000msのみ有効とする。MANUAL target、RAW固定targetと補償の分離は維持する。
- **Presentation**: `captureKeyRows()`は常に6キー1行、各キーは等weightとする。SHUTTERの視覚ラベルはSS、元の意味はsemanticsに保持する。歯車はローカルvector drawable `ic_settings.xml`で表し新依存を使わない。TextMeasurerはheading/tag各11sp・主数値16spを上限に行ごとに適合させ、長いtagの幅で数値を縮小しない。固定最小spはなく極小化し得るため、狭幅/高fontScaleの可読性は未保証。目盛りの104dp優先領域・固定preview寸法を維持する。縦/左右横・fontScale 1.0/1.3/2.0は受入対象だが実viewportの描画は未確認で、純粋幅テストを可読性合格にしない。
- **横持ち共通寸法**: CaptureControlKeyがTextMeasurerで候補/現値/heading/tagを含む自然寸法を測り、回転後footprintの自然高さ（最小72dp）をSideEffectで6キー分報告する。CaptureScreenは最大値を`captureKeyCommonHeight`で共有し、横持ちだけ`captureKeyHeight`で全キーへ適用する。物理高さが回転後の見かけ横幅となるため、Rowの等weight物理幅とは別に扱う。縦持ちは各キー従来の自然高さを使い、既存`keyMaxHeight`共通上限・104dp目盛り優先領域と行別text fitを維持する。短高では最大自然寸法をそのまま使えず文字縮小が残る。SideEffect集約により初回再構成や値/向き変更時に寸法変化があり得るため、過渡表示と実寸の収まりは実描画未確認とする。tap/clickable/semanticsとcamera guardは変更しない。
- **横持ちダイヤル寸法**: `landscapeDialSizingLabels`はsessionのISO/SS/EV/FOCUS/WB候補と有効なMF exact anchor履歴を集約し、CaptureScreenで18sp SemiBoldの実TextMeasurerにより先行測定する。本体要求高さは`max(56dp, 最大候補幅 + 12dp)`、共通header 48dpを足した外枠を横持ちだけ全5ダイヤルへ共有する。回転後は本体高さが見かけ横幅になる。待機/非対応も共通外枠を使い、親preview制約は同じ要求寸法へ適用する。縦は従来の個別寸法、キー側の共通化は既存のまま。長SS/WB・MF exact値も測定に含めるが選択肢や送信値を増やさず、MF履歴はepoch/maxで区切る。長候補/高文字倍率/短い親領域では既存text fit/制約の限界があるため、全条件での無欠落/可読性を機械測定だけで保証しない。
- **Shortcuts**: SensorControlEditorsのEV0/AFボタンは`dispatchSensorShortcut`で押下時の現guardを再評価し、EVは`selectEv(0)`、MF解除は`restoreAutofocus()`へ接続する。latest aggregate intentへ変更項目だけをmergeし、旧ACK/同時ISO/SS/WBを後退させない。EV0は報告range内かつfiniteな正step、AUTO露出時だけ有効。要求1回をAPI受付/実測/合焦の証拠にしない。
- **v19表示/触覚**: SS見出しはSSだけとし、短秒/長秒とも可視値と幅測定候補へsを付けない（例1/100、1、2）。後続の明示指示が旧数値s付記を置換する。秒の意味は元のunit/semanticsへ保持し、camera設定値や他項目の単位は変更しない。STILL/CINEはRole.Switchと現在modeのstateDescriptionを持つスイッチとし、既存mode callbackとbusy/recording/撮影専有guardを維持する。EV0ボタンはRESET、MF解除AFボタンはAUTOと表示し、semanticsで対象を明示する。全resetとISO/SSの露出AUTOの責務は変更しない。
- **シャッター受付policy**: 押下直前/処理直後の`ShutterPressState`を比較する。STILLは非CAPTURING/SAVING→CAPTURING、CINEはIDLE→STARTINGおよびSTARTING/RECORDING→STOPPINGだけを受付とし、同modeでの成立時に`VIRTUAL_KEY`をflags 0で1回要求する。同期受付を確認してから鳴らし、遅い非同期通知で二重に鳴らさない。目盛りの段変更hapticは`CLOCK_TICK`/flags 0を維持する。端末/設定が効果を決めるため強度は保証しない。独自振動基盤・権限・依存を追加しない。
- **共通目盛り見出し**: `CaptureRulerHeading`は高さ48dp・3等幅slot、左action・中央title・右44dp closeを共用する。ISO/SS=AUTO、EV=RESET、FOCUS=AUTO、WB=actionなし。左右slotを等幅に保ちWBでも中央を維持する。タイトルは回転後の空間を実測fitし、closeには共通の読み上げ名を持たせる。ISO/SSの通常補足説明を外し、鮮度待ちは「ISO/SS 待機」とsemantics理由で伝える。callsiteにあるaction/close/UNKNOWN復旧/epoch/pending guardと目盛りgestureを変更しない。
- **外側tap**: CaptureStateのpure `previewTapAction`で設定/診断表示中はIGNORE、目盛り選択中はDISMISS_RULER、閉じていて既存focus guardを満たす場合だけFOCUSへ排他的に振り分ける。CaptureScreenは最新stateを参照するcallbackをComposeのpreview内閉じる面とPreviewViewのfocus入口に共用し、閉じる分岐でcamera操作を呼ばない。閉じる面はプレビュー領域に限定し、上位の状態バー/別キー/シャッター/設定入口の既存経路を優先する。目盛り/キー/状態操作より背面に置き、目盛りdrag、設定入口、録画停止の操作を妨げない。`editor.close()`の既存submission guardを保ち、live intent/receiptやcamera設定へ取消を書き戻さない。回転/Preview寸法・設定/診断の無回転は変更しない。
- **手動再接続**: `ControlRecoveryState.canReconnect(expectedEpoch)`を表示可否と押下時に共用し、UNKNOWN（controlまたはstill preview）かつ現epoch・bound・前面・非closed・非busy・非recording・capture tokenなし・撮影専有なしだけ許可する。CameraProbe.recoverControlsは既存bindを呼び、camera/modeを変更しない。既存memory-only intent/scope・bind優先順・旧token無効化・新API受付/preview readinessを使い、自動retryを追加しない。撮影専有中は既存停止/復旧を維持する。
- **入力と観測**: AUTOの新sampleは表示専用の正常slotを更新し、それだけでbusy/controlReady/epochを変えない。ISO/SS操作は有効な元実測pairを基点にする一方、候補listとgesture保持のkeyは表示値/sample時刻に依存させない。callbackは最新selection/能力/epoch/readinessを参照し、操作中の正常再compositionを越えてdragを継続する。旧epoch/能力変更・実際の鮮度不足は理由付きで拒否する。入力の候補選択をpure境界へ抽出する場合もLiveControlQueue.kt/CameraProbe.kt等の既存責務内に限定する。
- **Compatibility**: 009/012のMF apply/cancel・pending UI禁止と旧exposure queue UNKNOWN入力再retryだけを本設計へ置換する。回転実装やRAW比較の変更へ広げない。

## Validation / Quickstart References

既存[README](../../README.md)、[P0_REPORT](../../docs/P0_REPORT.md)、[BUILD_VALIDATION](../../docs/BUILD_VALIDATION.md)を参照する。機械確認は通常unit/lint/assemble、`git diff --check`と公開情報検査。queueは遅い中間ACK、200操作のcoalescing上限、失敗/timeout、epoch/background、ISO/SSとEV/WB/MFの相互保持を確認する。EV 0→+1/WB連続/MF往復、同値/端点/標準haptic、AUTO正常→欠落/期限/旧epoch、表示丸め非書戻しを回帰確認する。

追加回帰は正常AUTO更新streamと押下/dragの交差、表示clockより新しいsampleが届く境界、更新中のISO/SS候補維持、真に古いpairの拒否、MANUAL移行後の最新intent保持を扱う。6キーは縦/左右横×fontScale 1.0/1.3/2.0×最長/最短値で各cellと合計幅、LOOK端部、ラベル/数値/単位と操作領域を確認する。機械テストで使う非線形fontScaleモデルは実TextMeasurerや実描画の代替証明にしない。

`SensorShortcutTest`でEV両端→0/近距離・∞MF→AFの1要求、他intent保持、旧ACK、無効/変化後guardを確認する。`ControlRecoveryTest`はAPI/preview UNKNOWN、ready/pending/unbound、epoch/lifecycle、busy/recording/capture ownership、期限後の旧tokenを検証する。production callsiteと復旧UI導線、独立レビュー、最終head CIは別確認し、純粋テストだけで実機の停止原因や再接続成功を確定しない。

`CaptureChromeRegressionTest`で短秒/長秒の可視単位なし・読み上げ秒あり/mode状態、`ShutterHapticPolicyTest`で静止画/全録画段階/mode変更の受付行列を検証する。触覚端末設定・体感・v19実描画は未確認とし、unit pass/独立レビュー/CIを代替証明にしない。

v20は共通title/待機表示の純粋policy、5 callsiteの共通header接続、通常説明の除去、既存action/close guardとhaptic定数/flagsを照合する。実機の描画・触覚強度は未確認であり、source/純粋テスト/独立レビューを実機合格にしない。

Issue #61は`CaptureStateTest`の5項目/FOCUS可否/パネル表示のrouting行列と、closeがepoch/受付経路を変更しないことを確認する。縦/左右横・pending・録画/保存・パネル重なり、AndroidView境界のtap消費、目盛りdrag/close/Back/停止・a11yは接続と実描画/実機を分けて検証する。状態保護との競合が判明した場合はtap機能を広げず停止理由を報告する。

v22の`CaptureChromeRegressionTest`は模擬した自然測定高さ・複数文字倍率相当・左右横回転・空集合/長いラベル消失をpure policyで検証する。これは実Android TextMeasurer/viewportの測定結果ではない。6キーの最大寸法/共通上限・初回/回転途中、portrait回帰、touch/a11yを実描画/実機で別確認する。

ダイヤルは`ControlScaleTest`で全5候補family・長いSS/WB・有効/不正MF exact anchor・非対応候補非追加、最大測定幅+padding/下限・左右横・縦個別維持・親制約をpure policyとして確認する。実TextMeasurerを呼ぶproduction接続と、実際の左右横/fontScale/短高/待機描画は別確認であり、実機未確認を維持する。

FOCUSは`FocusObservationTest`でTTL/epoch/用途/範囲/AF mode/受付境界・AF→MF→AF・旧ACK/失敗/背景化/mailboxを、`SensorControlPresentationTest`でAF近似/未確認・MF優先・numeric seed/center tap/最初のdetent後drag・AUTO復帰を確認する。実LUの校正や物理距離変換は追加しない。source接続・純粋テスト・独立レビューと、実描画/実機合焦探索は別判定とする。

実描画の可読性と実機のAUTOちらつき・連続操作・MF合焦探索は別AC。要求発行・API受付・実測・見た目の結果を分離し、Issue/PRへ要約だけ記録する。最終head必須CI・独立レビュー明示OK・canonical review・thread0が揃ってもmerge/配布は別承認まで停止する。

## Complexity Tracking

新基盤や憲法例外なし。旧queueと新queueを併存させずauthorityを一本化する。必要な観測は既存能力・epoch・単調時計に限定する。実機の根因解消と可読性合格は未確認。
