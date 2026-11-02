# Feature Specification: 撮影UI Final v1

**Feature Branch**: `feat/003-capture-state`（後続は依存PR）
**Created**: 2026-11-02
**Status**: 採用仕様・実装対象
**Input**: ユーザー承認の撮影状態整理、Final v1案3・2a系レールUI、縦固定と要素回転。

## User Scenarios & Testing

### User Story 1 - 安心して設定を編集する (Priority: P1)
撮影者は編集中・送信中・受付済・実測を見分け、露出だけAUTOへ戻せる。
**Why this priority**: 受付と反映を混同すると撮影条件が分からなくなる。
**Independent Test**: 設定状態遷移の自動検証と、既存画面からの露出AUTO確認。
**Acceptance Scenarios**:
1. **Given** WB/MF/照明対策が設定済、**When** 露出AUTO、**Then** ISOと時間のみ解除し適格な他設定を保持する。全リセットは別操作。
2. **Given** 未適用draft、**When** 取消、**Then** 最後の受付値へ戻し選択を維持。閉じる/別グループ/再接続では破棄。ISO/SS間では保持。
3. **Given** API受付、**When** 実測到着、**Then** 実測値と要求値を別表示し、世代が異なる応答を現在値にしない。
4. **Given** 入力不正/非同期API失敗、**When** 失敗通知、**Then** 前者は拒否、後者は適用状態不明として撮影を止める。

### User Story 2 - 画面を保ちながら撮る (Priority: P2)
撮影者はフレーム全体と6キー/ルーラー/中央シャッターを使い、詳細は設定と診断で確認する。
**Why this priority**: 設定が増えてもライブ像が見える。
**Independent Test**: レイアウト/Back/編集テストとSTILL/CINE操作。
**Acceptance Scenarios**:
1. **Given** STILL/CINE、**When** 設定を開く、**Then** 全フレームを残り領域へFITし、設定本文のみスクロールする。
2. **Given** draft、**When** STILL撮影、**Then** 受付値で撮りdraftを保持。CINEのRecorder Start通知を受けた時点で破棄。権限拒否では保持。
3. **Given** 録画/保存中、**When** 設定等を操作、**Then** 無効理由と状態を表示。停止と最終保存を分ける。
4. **Given** LOOK/比率出力/最新写真、**When** 入口を確認、**Then** 未実装と明示し効果や閲覧を偽装しない。

### User Story 3 - 横持ちでも継続する (Priority: P3)
撮影者は端末を持ち替えても配置/編集内容を保ち、録画を継続する。
**Why this priority**: 動画を回転のために失わない。
**Independent Test**: 向き・ヒステリシス・出力固定の自動検証と実機録画。
**Acceptance Scenarios**:
1. **Given** 縦画面、**When** 物理向きが0/90/180/270度へ変化、**Then** 配置を動かさず文字/数値/アイコンを最短経路で回転する。
2. **Given** 録画開始、**When** 向き変更、**Then** 録画とdraft/panelを保持し、ファイルの向きは開始時のまま。背景化は既存どおり停止する。

### Edge Cases
入力途中の連打/取消/Back、旧世代応答、実測欠落、保存失敗、再接続、マイク拒否、熱SEVERE、狭い画面・システムinsetsを扱う。

## Requirements
### Functional Requirements
- **FR-001**: 指定/受付/実測は構造化値で扱い、診断文字列を解析して撮影UIを作らない。
- **FR-002**: 露出AUTOと撮影制御リセットを分離し、後者は二段階確認する。
- **FR-003**: ISO/SHUTTER/EV/FOCUS/WB/LOOKの6キー、選択時のみルーラー、中央シャッターとSTILL/CINE切替を設ける。
- **FR-004**: 適用/取消/閉じる/Backの結果を一定にし、録画・保存中は誤操作を防ぐ。
- **FR-005**: 原画角をFIT表示し、プレビューを独自にクロップしない。原本/MediaStore保存を維持する。
- **FR-006**: OS熱/エラー/非対応/接続状態を実情報から示す。架空の温度や出力能力を表示しない。
- **FR-007**: 縦配置を固定して要素を回転し、撮影出力の向きを撮影時に確定する。録画途中では変更しない。
### Key Entities
編集draft、操作受付、実測sample（世代/frame/受信時刻）、撮影/保存段階、物理向き。

## Success Criteria
### Measurable Outcomes
- **SC-001**: 取消/閉じる/別グループ/撮影/録画の全遷移でdraftと受付を混同しない。
- **SC-002**: 412×924相当の画面で6キーと44dp以上の主操作領域、設定時の全フレームを保持する。
- **SC-003**: 4方向へ持ち替えても録画ファイルを1件として完了できる（実機未確認は残す）。
- **SC-004**: 独立レビュー、canonicalレビュー、必須CI後にマージ判断へ提示する。

## Assumptions
Final v1 READMEとCamScreen variant Dを採用。旧HTMLの「回転で停止」は採用しない。v1設定/診断本文は縦向きのまま。
新LOOK/比率保存/最新写真閲覧/権限/署名変更は対象外。標準アイコン/フォントを用い、参考HTMLや支援ランタイムを製品へコピーしない。
