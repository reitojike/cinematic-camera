# Feature Specification: 設定シートのデザイン統一

**Feature Branch**: `docs/design-settings-handoff`
**Created**: 2026-11-06
**Status**: 実装統合済み・実機未検証
**Input**: [Issue #172](https://github.com/reitojike/cinematic-camera/issues/172)。2026-11-06 design-handoff INSTRUCTIONS.md Aと共通仕様。基準mainは57bc2cf。

## User Scenarios & Testing

### User Story 1 - 画角を保ったまま設定する (Priority: P1)

撮影者は同じ画角を見ながら設定の効果と選択状態を確認できる。
**Why this priority**: 構図と既存設定の意味を守る。
**Independent Test**: STILL/CINEで開閉し各設定とプレビューを確認する。

**Acceptance Scenarios**:
1. 開閉・選択でもプレビュー寸法・画角は不変。空白を含むシート内操作が背後のAF/AEを起動しない。
2. STILLでは形式/解像度/カメラ/照明/AE-L・AWB-L/比率情報、CINEでは組合せ/音声/カメラ/照明/ロックに到達できる。
3. 再接続中は適用中を表示し、選択を接続成功や実測と同一視しない。

### User Story 2 - 変更できない理由を知る (Priority: P1)

**Why this priority**: デザイン変更で既存制御制限を失わない。
**Independent Test**: 録画/処理/非対応/能力未取得を表示して操作する。

**Acceptance Scenarios**:
1. 録画・処理中は理由帯と45%の本文を表示し、従来無効の操作は無効のまま。
2. 非対応は取消線と説明で区別し、色だけに依存しない。
3. AE-Lは露出AUTO、AWB-LはWB AUTOの制限と当該接続だけの要求という注記を保持する。

### User Story 3 - リセットと診断に到達する (Priority: P2)

**Why this priority**: スクロールしても管理操作を見つけられる。
**Independent Test**: 本文をスクロールしてリセット/診断と取消/戻るを操作する。

**Acceptance Scenarios**:
1. 下端固定のリセット確認で露出AUTO/EV 0.0/FOCUS AF/WB標準/照明既定/ロック解除を3列で示す。
2. 露出だけAUTOとは別と明示。取消では変更せず、全リセットは既存動作に従う。
3. Back/閉じる/診断戻りの意味を保持する。

### Edge Cases

- 横持ち・拡大文字・多数候補ではスクロールで全設定と固定操作へ到達できる。
- 接続失敗/能力未取得を成功/対応済みに見せず、カメラIDや組合せはモックを固定しない。
- 確認中に操作不可になった場合も全リセットの既存実行条件を再確認する。

## Requirements

### Functional Requirements

- **FR-001**: 引継ぎ共通色、Monospaceラベル11sp・字間0.06em/値16sp、説明Sans12〜13sp、角なしを採用。録画赤は録画以外に使わない。
- **FR-002**: 504dp overlay、48dp見出し（撮影設定/モード/実ID）、44dp閉じると44dp等幅選択を基準とする。短辺では可用高に収め本文scroll。
- **FR-003**: 形式/解像度/組合せ/カメラに再接続、照明に「即時 · AUTO露出のみ」、ロックに「即時 · この接続のみ」、音声に「録画開始時」のタグ。
- **FR-004**: 選択は明色反転、非対応は無効色と取消線・注記。再接続対象は破線アンバー枠と淡い背景、下端に実際の項目と変更前後の適用中帯。
- **FR-005**: 比率は「原画角 4:3 を保持」「未実装」の情報行。比率保存機能は追加しない。
- **FR-006**: リセット/診断は下端固定。確認は暗色角なしパネル、取消とアンバー全リセット。
- **FR-007**: 操作可否、要求/受付/実測、状態遷移、支援技術へ伝える意味を維持。RAW処理/原本/保存/cacheを変えない。

### Key Entities

- 設定項目: 現在の選択、対応能力、操作可否、効果の時点。
- 適用中表示: 変更項目と変更前後の要求。受付/実測の証拠を代替しない。

## Success Criteria

### Measurable Outcomes

- **SC-001**: STILL/CINEの全設定と管理操作へ412×924dp・横持ち・拡大文字で到達できる。
- **SC-002**: 開閉/選択によるプレビュー寸法・画角の変化と、シート操作による背後AF/AE起動が0件。
- **SC-003**: 待機/再接続/録画・処理/非対応/リセットで既存操作可否と結果が一致する。

## Assumptions

- 要確認1は既定504dp。344dp維持の指定があれば実装前に更新。
- 要確認2/3/4はC/Dの範囲。各機能のspec/planの実装前報告を経て実装する。Aの報告は完了し、未回答時の既定504dpで進める。
- INSTRUCTIONS.mdが数値/文言の正。PNGとAppScreensは見た目の参照で、runtimeを製品へコピーしない。
- B/C/Dは別Issue #173/#174/#175。新依存・RAW性能・保存lifecycle・merge/配布は対象外。
