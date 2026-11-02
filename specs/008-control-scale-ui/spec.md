# Feature Specification: 撮影コントロールの選択スケール

**Feature Branch**: `feat/011-control-scale-ui`
**Created**: 2026-11-02
**Status**: Draft
**Input**: [Issue #45](https://github.com/reitojike/cinematic-camera/issues/45)。動作は現行のまま、配置・目盛り・文字・選択状態を採用HTMLへ寄せる。変更即反映でISO/SSの反映ボタンは設けない。

## User Scenarios & Testing

### User Story 1 - 露出値を目盛りから選ぶ (Priority: P1)

撮影者はISO/SHUTTERを開き、中央の現在値と隣接する標準段階を見ながら値を選ぶ。
**Why this priority**: 撮影を続けながら設定位置と変更方向を読み取れる。
**Independent Test**: 中央選択、隣接候補、両端移動を確認し、追加の反映操作なしに要求される。
**Acceptance Scenarios**:
1. **Given** 露出候補がある、**When** ISO/SHUTTERを開く、**Then** 現在値中央の5候補と左右移動・補助目盛りを表示する。
2. **Given** 変更可能、**When** 隣接候補・矢印・ドラッグで変更、**Then** 現行即時反映へ渡し、要求・受付・実測を混同しない。
3. **Given** 候補端・単一候補・実測待ち、**When** 操作、**Then** 範囲外や推定値を作らず無効な方向を無効表示する。

### User Story 2 - ISO/SSの操作状態を撮影画面から読む (Priority: P2)

撮影者はISO/SHUTTERの2項目キーのラベル・値・単位・タグから選択と操作可否を読み取る。他の項目の見せ方・操作は変更しない。
**Why this priority**: 小さい領域で撮影中の状態を判断するため。
**Independent Test**: STILL/CINE、縦/左右横持ち、送信中・エラー・撮影/録画/保存中の表示と操作可否を確認。
**Acceptance Scenarios**:
1. **Given** キー未選択、**When** 選択/閉じる、**Then** レールとプレビュー寸法を変えず選択上辺・背景・区切りを切り替える。
2. **Given** 横持ち、**When** 露出操作、**Then** 配置固定・文字回転、要求/受付/実測はプレビュー上で読む。
3. **Given** 録画/撮影/保存中、**When** ISO/SSを表示、**Then** 制限を示す。LOOKを含む他項目は現行の未実装表示・操作を維持する。

### Edge Cases

- 空候補・範囲外index・単一候補は範囲外要求を出さない。
- SHUTTERは短秒`1/n s`、長秒`s`。端末未報告値を候補にしない。
- 最新要求追随・AUTO復帰・エラー時受付値保持は既存動作に従う。
- Back、パネル、目盛り空白・ドラッグがプレビューAFへ透過しない。

## Requirements

### Functional Requirements

- **FR-001**: 現在値中央5候補、左右各48dp相当、補助目盛り、選択3dpインジケータを表示する。
- **FR-002**: ISO/SSは選択/ドラッグ時に即時反映し、反映/取消ボタンを追加しない。
- **FR-003**: 現行端末/session標準段階とISO/SS表記・範囲制限を保持する。
- **FR-004**: 現行6等分72dpレール中、ISO/SHUTTERの2キーだけをラベル11sp・値16sp・単位/タグ11sp、選択背景・上辺3dpで表示する。
- **FR-005**: 要求・受付・実測、反映中・失敗・未実装を文字と色で区別する。
- **FR-006**: プレビュー寸法、固定縦配置と要素回転、STILL/CINE、撮影/録画・Back・tap遮断を維持する。

### Key Entities

- 選択スケール: 既存候補一覧・現在index・操作可否。カメラ状態を持たない。
- 項目表示: ラベル・値・単位・タグ・選択/無効状態。要求/受付/実測は独立情報。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 通常・両端・単一・空候補の全ケースで範囲外要求0件。
- **SC-002**: ISO/SS変更から要求まで追加反映操作0回、選択値を常に中央に表示。
- **SC-003**: 同一画面寸法/モードで目盛り開閉時のプレビュー寸法変化0dp。
- **SC-004**: 縦/左右横持ち×STILL/CINE全組合せでキーと目盛りを収める。実機未確認は未検証と記録。

## Assumptions

- 参照優先順はhandoff README、Final.dc.html、CamScreen.dc.html採用variant D/scrim。比較旧案は採用しない。
- HTMLの反映ボタン・仮の遅延/失敗/能力値は最新合意と既存動作を置き換えない。
- 新依存・権限・独自UI基盤・LOOK処理・バックエンド制御変更は対象外。
- MF既存編集/適用は維持。即時反映合意は現在即反映のISO/SSへ適用。
- ISO/SS以外のHTML差分は読み取り監査とPO判断まで修正しない。共通部品化・広域tap遮断・他キーの外観変更も行わない。
