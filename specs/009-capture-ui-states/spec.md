# Feature Specification: 撮影UIの状態・制御値・診断整理

**Feature Branch**: `feat/012-capture-ui-polish`
**Created**: 2026-11-02
**Status**: Draft
**Input**: [Issue #47](https://github.com/reitojike/cinematic-camera/issues/47)、追加PO契約[6583372005](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6583372005) / [6583452413](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6583452413)。#46 merge後main f1cb97dを基準とし、競合時は後のPO指示を優先する。

本書のpreview系実測はCaptureResult intentがPREVIEWまたはVIDEO_RECORDの待機/録画frameを指す。CINE repeatingのVIDEO_RECORDを含み、STILL_CAPTURE/UNKNOWNは採用しない。intent metadataの観測追加はcamera設定/pipeline変更を伴わない。

## 後続の仕様変更

US2-5/FR-003/SC-003のMF draft適用/取消は[013 ライブ操作の最新意図](../013-live-control-intent/spec.md) FR-003〜006のMF即時previewへ置換する。EV/WB/MFはpending中も次の有効な選択を受け付け、遅いACKで最新intentを巻き戻さない。主値の近似説明分離と可読性は013 FR-001/002で補正する。013はmergeまでmainへ適用済みとは扱わない。

主値の常時preview実測（FR-010/US2）は[012 v15フィードバック](../012-v15-capture-feedback/spec.md) FR-001/002で、AUTO正常値の軸別保持とMANUAL最新撮影targetへ置換する。012はDraftであり、mergeまでmainの契約を変更しない。

## User Scenarios & Testing

### User Story 1 - 進捗と失敗を一か所で判断する (Priority: P1)

撮影者は状態バーの録画/停止/保存進捗とnoticeの失敗/無効理由から、待つ・停止する・復旧する判断を行う。
**Why this priority**: 重要な状態と操作を重複説明で埋もれさせないため。
**Independent Test**: 全RecordingStage/PhotoStage、失敗/権限/熱/未知値と表示先を純粋policy・callsiteで照合する。

**Acceptance Scenarios**:

1. **Given** STILL/CINE待機、**When** シャッターを見る、**Then** STILLは大きい白内円、CINEは一回り小さい白内円。STARTINGも白とし、実RECORDINGだけ赤い録画表示とする。
2. **Given** 録画/停止/保存進捗、**When** 状態が変わる、**Then** 状態バーへ集約し、シャッター下captionを描画しない。必要な状態はaccessibilityへ維持し、停止/復旧/Backを隠さない。
3. **Given** busy/非対応/権限/熱/失敗、**When** 操作できない、**Then** notice一か所へ理由を出し、失敗色と警告を区別する。保存成功を撮影露出/画質/DNG妥当性の証明にしない。
4. **Given** camera/形式/動画quality・fps/熱、**When** 状態バーを見る、**Then** 選択/bindと実測/実出力を混同せず、未知値を補完しない。露出modeも共通状態領域へ一回だけ示す。

### User Story 2 - 実測を読み、撮影targetを選ぶ (Priority: P1)

撮影者はキーの近似preview実測を読み、編集目盛りで独立した撮影targetを即時選択する。
**Why this priority**: preview補償と撮影露出を区別しながら操作する中心契約。
**Independent Test**: 現epoch/鮮度/候補丸め、target不変、項目別pending、距離蓄積detentと段変更のみのhapticをpure境界とcallsiteで確認する。

**Acceptance Scenarios**:

1. **Given** 現epochのpreview系実測が取得後2000ms以内、**When** ISO/SS主値を見る、**Then** 標準候補へ表示だけで丸め「約/≈」を示す。欠落/旧epoch/期限超過は「—」。補償preview実測を撮影targetと呼ばず、表示値を設定や候補へ書き戻さない。
2. **Given** ISO/SS目盛りを開く、**When** 候補を見る、**Then** 撮影targetの選択操作に限定し、主値・実測/API説明を重ねない。mode囲みは文字と一体で中央配置し、AUTO文字の重複をなくす。
3. **Given** targetまたはoverrideの変更、**When** 送信中/不明となる、**Then** 影響項目だけを共通領域一か所へまとめ、キーは必要な色/選択feedbackを保つ。内部ISO+SS pairや全snapshot受付を理由に無関係なWB/MFをpendingにしない。
4. **Given** ISO/SS drag、**When** 一定距離が蓄積して実際の段が変わる、**Then** 共通選択actionへ送信し標準の軽いhapticを要求する。端点/同値ではstep/hapticを出さず、タップ/矢印/accessibilityも同じactionを使う。
5. **Given** MF/WB操作、**When** MF適用/取消またはWB選択、**Then** MF draftとLU/AF・MFの意味、既存送信挙動、WB即時選択を維持し、表示丸めを送信値へ逆流させない。

### User Story 3 - 詳細を診断へたどる (Priority: P2)

撮影者は設定を操作し、必要な場合だけ診断で要求・受付履歴・preview補償・実測・保存を比較する。
**Why this priority**: 撮影画面の情報を一か所に保ちながら根拠を確認するため。
**Independent Test**: 詳細の配置先と重複、レポート保存入口、既存layout/rotation/editorを静的照合・回帰確認する。

**Acceptance Scenarios**:

1. **Given** 設定/診断、**When** 詳細を確認する、**Then** 詳細比較は診断へ集約する。settingsの詳細状態列や横持ち専用多段露出説明を撮影画面へ重複表示しない。操作に不可欠な選択肢ラベルは保つ。
2. **Given** 診断、**When** レポート保存/停止復旧/Backを使う、**Then** 既存入口と動作を維持し、target/要求/受付履歴/実測/保存を独立ラベルで扱う。
3. **Given** 縦/左右横持ちの撮影/録画、**When** 要素回転やoverlay開閉、**Then** 固定配置・preview寸法・録画継続を保つ。STILL/CINE切替外観や細余白を変更しない。

### Edge Cases

未接続/欠落実測、旧epoch、2000ms境界と期限超過、PREVIEW以外のintent、補償ISO、同値/端点/方向反転detent、haptic無効設定、全snapshot ACKで無関係項目が変化しない場合、MF draft/不正値、WB失敗、STARTING失敗、STOPPING/FINALIZING、保存/復帰UNKNOWN、縦/左右横持ち、Back/復旧。

## Requirements

### Functional Requirements

- **FR-001**: シャッターはSTILL大白/CINE待機小白/STARTING白/実RECORDINGのみ赤とし、下captionを一切描画しない。進捗・結果を状態バー、失敗/無効理由をnoticeへ集約し、accessibilityと停止/復旧を維持する。
- **FR-002**: 同じ情報は原則一か所とする。主数値はキー、露出modeと送信状態は共通領域各一か所、目盛りは撮影target選択、要求/受付履歴/preview補償/実測/保存の詳細比較は診断とする。未知値は捏造しない。
- **FR-003**: MF数値/LU/AF・MFタグとWB選択を読み取れる形で示し、MF draft適用/取消とWB即時選択を維持する。pending/不明は論理target/overrideが変化した項目だけへ適用し、全snapshot受付と区別する。
- **FR-004**: 録画失敗を含む失敗色と無効表現を統一し、busy/非対応/権限/熱の理由をnotice一か所へ示す。停止/復旧/Backを維持する。
- **FR-005**: 設定は選択操作へ、診断は詳細比較へ整理する。target/要求/受付履歴/実測/保存を混同せず、既存レポート保存を維持する。
- **FR-006**: ISO/SS即時反映・反映ボタンなし、AUTO/reset分離、overlay、固定配置、要素回転、録画継続、RAW/MediaStore保存を後退させない。
- **FR-007**: 見せ方は最新PO指示を最優先し、旧READMEの赤待機/caption配置は採用しない。STILL/CINE切替外観・細余白・LOOK・写真viewer・比率出力は対象外。
- **FR-008**: HTML file表示のsecurity拒否を迂回・再試行しない。構造/CSS・pure models/build/lintと実描画/実機を分離する。
- **FR-009**: 新camera処理・権限・依存更新・鍵/Secrets・独自UI/振動基盤を追加しない。標準hapticは端末/ユーザー設定を尊重し、素材/写真/GPS/個人パスを公開しない。
- **FR-010**: ISO/SS主値は現epochで取得後2000ms以内のpreview系実測を標準候補へ表示だけで丸め「約/≈」を示す。欠落/古い実測は「—」、preview補償とcapturetargetを区別し、表示からcamera設定/候補を更新しない。
- **FR-011**: ISO/SS dragは一定距離を蓄積するdetentとし、実段変更時だけ標準haptic（flags 0）を要求する。端点/同値はstep/hapticなし、tap/矢印/accessibilityも同じ選択actionへ接続する。

### Key Entities

主値用preview実測（epoch/intent/取得時刻/値）、独立したcapturetarget、項目別変更/受付履歴、detent距離残量/選択index、共通notice/状態バー、診断比較。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 全録画/静止画/失敗/非対応/busy状態表で表示・操作矛盾0件、下caption描画0件、実録画前の赤い成功表示0件。
- **SC-002**: 鮮度/旧epoch/欠落/補償/無関係項目を含むテストで未知捏造・情報区分混同・不要pending・端点/同値hapticを0件にする。表示先の独立レビューで不要重複0件を確認する。
- **SC-003**: 既存即時設定/MF draft/AUTOreset/layout/overlay/回転録画/RAW保存を維持し、縦/左右横持ち・haptic体感・実描画/実機は確認まで未確認とする。
- **SC-004**: 最新headでpure policy/状態回帰・既存unit/build/lint/diffcheck/公開scan、独立/canonicalレビュー・必須CI・thread 0を確認する。merge/配布は未承認。

## Assumptions

追加PO指示の後の全体基準が優先するため、露出modeはキーごとに重ねず共通領域へ一回。近似preview値は表示専用であり露出/画質の一致保証ではない。HTML実描画・暗所可読性・両横持ちの重なり/FOV・実機回帰は未確認。RAW現像/RAW-only方針は別Specで扱う。
