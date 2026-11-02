# Feature Specification: 撮影UIの状態・制御値・診断整理

**Feature Branch**: `feat/012-capture-ui-polish`
**Created**: 2026-11-02
**Status**: Draft
**Input**: [Issue #47](https://github.com/reitojike/cinematic-camera/issues/47)のPO承認5項目。参照監査は[#45](https://github.com/reitojike/cinematic-camera/issues/45#issuecomment-6581270902)。#46 merge後のmain（f1cb97d）を基準とする。

## User Scenarios & Testing

### User Story 1 - 撮影・録画・保存の状態を見分ける (Priority: P1)

撮影者はシャッターと状態バーから待機、撮影、録画、停止処理、最終保存、失敗を識別し、停止と復旧へ到達できる。
**Why this priority**: 撮影を続ける/止める/待つ判断に直結する。
**Independent Test**: 全録画stageと静止画保存stageの純粋表示policy、状態バーの欠落値、停止到達性を状態表とcallsiteで照合する。

**Acceptance Scenarios**:

1. **Given** STILL待機/撮影/保存、CINE待機/録画開始/録画/停止/最終保存、**When** 状態が変わる、**Then** シャッターの内円/四角・色・captionを一貫して切り替え、録画停止を可能なstageで無効化しない。
2. **Given** camera ID、撮影形式、動画quality/fps、録画/保存/熱の情報、**When** 状態バーを見る、**Then** 現在の要求/選択/bindの情報を区別し、実測fpsや実出力と断定しない。未知は「—」等を示す。
3. **Given** busy/非対応/権限/熱/録画失敗、**When** 操作できない、**Then** 無効の視覚表現と理由を示す。失敗は失敗色、警告は警告として区別し、停止/復旧/Backを隠さない。

### User Story 2 - MF/WBの値と受付を読み取る (Priority: P2)

撮影者はMFの数値・LU単位・AF/MFタグとWBの現在選択・受付状態を見分け、従来どおり設定する。
**Why this priority**: 値と動作状態の誤認を減らし、操作の手順は維持する。
**Independent Test**: MF draft/要求/受付/実測とWB選択/送信/受付/失敗の表示入力を純粋テストし、既存編集経路への接続を確認する。

**Acceptance Scenarios**:

1. **Given** AFまたはMF draft/受付値、**When** FOCUS表示を見る、**Then** 数値とLU単位を分けて読み取れ、AF/MFタグを区別する。表示丸めは送信値を変更しない。
2. **Given** MF未適用draft、**When** 適用/取消/Backを操作する、**Then** 現行の編集・送信・取消規則を維持し、draftを受付や実測と表示しない。
3. **Given** WBを選択、**When** 即時送信/受付/失敗となる、**Then** 現在選択と受付状態を示し、古い受付履歴を現在要求の成功として示さない。

### User Story 3 - 設定と診断をたどる (Priority: P2)

撮影者はセクションで整理された設定・診断からtarget、要求、受付履歴、実測、保存を区別して確認し、既存レポートを保存できる。
**Why this priority**: 詳細確認と復旧判断を撮影画面から行えるようにする。
**Independent Test**: セクションと情報源の静的照合、欠落値/旧受付を含む表示policy、既存レポート保存入口を確認する。

**Acceptance Scenarios**:

1. **Given** 設定/診断を開く、**When** 項目を読む、**Then** 撮影・制御・保存/端末情報等を見出しで整理し、target/要求/受付履歴/実測/保存の独立ラベルを保つ。
2. **Given** 診断、**When** レポート保存/復旧/Backを使う、**Then** 現行の入口と動作を維持し、保存成功を露出/画質/DNG妥当性の証拠としない。
3. **Given** 縦/左右横持ちで録画中、**When** 要素回転やoverlay開閉を行う、**Then** 固定配置・preview寸法・録画継続を維持し、設定でpreviewを縮めない。

### Edge Cases

起動/未接続、情報欠落、旧受付履歴、MF長い小数/不正draft、WB送信失敗、録画STARTING/STOPPING/FINALIZINGと録画失敗、静止画保存失敗、マイク拒否、熱による開始不可、撮影所有のUNKNOWN、縦/左右横持ち、狭い幅、長いcaption、Backと復旧。

## Requirements

### Functional Requirements

- **FR-001**: STILL待機・撮影・保存、CINE全stageのシャッター形状/色/captionを決定稿の状態表に揃え、caption位置と停止操作の到達性を維持する。
- **FR-002**: 状態バーにcamera ID、撮影形式、動画quality/fps、録画/保存/熱を適切に示す。要求・選択/bindと実測/実出力を区別し、未知値を補完しない。
- **FR-003**: MF数値/LU/AF・MFタグ、WB現在選択/受付状態を読み取れる形で示し、MF draft適用/取消とWB即時選択を変えない。
- **FR-004**: 録画失敗を含む失敗色と無効時の視覚表現/理由を統一し、busy/非対応/権限/熱を区別する。停止/復旧/Backを維持する。
- **FR-005**: 設定・診断をセクション整理し、target/要求/受付履歴/実測/保存を独立表示する。既存レポート保存を維持する。
- **FR-006**: ISO/SS即時反映・反映ボタンなし、AUTOと全resetの分離、overlay、固定配置、要素回転、録画継続、RAW/MediaStore保存の契約を後退させない。
- **FR-007**: 決定稿README/Final/CamScreenの構造・CSSを参照し、後合意の現行挙動を優先する。STILL/CINE切替外観・細余白・LOOK・写真viewer・比率出力を本件に含めない。
- **FR-008**: HTML file表示のbrowser security拒否を迂回・再試行しない。構造/CSS・純粋モデル・機械検証と、未実施の実描画/実機確認を分離する。
- **FR-009**: 新camera処理・新権限・依存更新・鍵/Secrets・独自UI基盤を追加しない。必要になれば根拠を報告して停止し、素材/写真/GPS/個人パスを公開しない。

### Key Entities

- **シャッター表示**: mode、撮影/録画stage、形状、色、caption、操作可否、理由。
- **状態表示**: camera/形式/quality/fpsの情報源と有効性、録画/保存/熱状態。
- **制御表示**: MF/WBのdraft・要求・受付履歴・実測を区別した値/単位/tag。
- **診断セクション**: 見出し、情報区分、値の有無、既存操作への入口。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 全録画stage/静止画保存/失敗/非対応/busyの状態表で、表示と操作可否が矛盾する組合せを0件にする。
- **SC-002**: 欠落値と旧受付を含む表示テストで、未知の捏造・要求/受付/実測/保存の混同を0件にする。
- **SC-003**: 既存の即時設定、MF draft、AUTO/reset、固定layout/overlay/回転録画継続、RAW保存への変更を0件に保ち、縦/左右横持ちの実描画・実機結果は確認するまで未確認とする。
- **SC-004**: pure policy/状態回帰、既存unit、build/lint、diffcheck、公開scanを実行し、Draft PRの最終headで独立/canonicalレビュー・必須CI・thread 0を確認する。merge/配布は本件で実施しない。

## Assumptions

- #46 merge後の通常二段階previewとISO/SSコントロールを前提とし、本UI変更で配布済み機能を再設計しない。
- READMEの確定規則とCamScreen variant Dが見せ方の参照。mockの時間/結果/値や古い挙動は移植しない。
- HTML実描画比較はsecurity拒否により未実施。文字欠け、暗所可読性、左右横持ちの重なり、preview位置/FOVと実機回帰は未確認のまま扱う。
