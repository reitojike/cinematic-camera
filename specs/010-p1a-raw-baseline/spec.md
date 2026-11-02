# Feature Specification: PC RAW基準現像と比較PoC

**Feature Branch**: `feat/013-p1a-raw-baseline`
**Created**: 2026-11-02
**Status**: Draft（Issue #49の承認範囲）
**Input**: [Issue #49](https://github.com/reitojike/cinematic-camera/issues/49)。002のP1候補をPC限定の基準現像へ切り出す。

## User Scenarios & Testing

### User Story 1 - 原本を保持して基準比較を再生成する (Priority: P1)

評価者は許可したDNGから基準版とトーンのみの比較版を作り、同じ入力と設定で再生成する。

**Why this priority**: 画作りの前に、入力・処理条件・再現性を確認する。
**Independent Test**: 同入力・設定から生成した出力のハッシュが一致し、原本のハッシュが変化しない。

**Acceptance Scenarios**:
1. **Given** 許可したDNG、**When** 基準現像、**Then** 原本を書き換えず比較画像と再生成定義をローカルに保存する。
2. **Given** 再生成定義、**When** 同じ入力で再実行、**Then** 同一環境では画像のハッシュが一致する。
3. **Given** 未知camera・WB不足・壊れた入力・I/O失敗、**When** 実行、**Then** 成功を装わず、個人パスや生metadataを含まない理由を返す。

### User Story 2 - 機種と評価の限界を分ける (Priority: P2)

評価者はPixel 9aとSIGMA fpの結果を分離し、技術品質と好みを別に評価する。

**Why this priority**: 別cameraの成功や保存bit数を、Pixel品質・HDR保持と取り違えない。
**Independent Test**: camera不一致を拒否し、評価枠に未実装・未測定を明示する。

**Acceptance Scenarios**:
1. **Given** camera別入力、**When** 比較、**Then** WB・色変換・noise特性が別であることを明示し、他機種へ合格を移さない。
2. **Given** Stage Clear未実装、**When** 比較枠を開く、**Then** 基準版とトーン版だけが生成され、Stage Clear成功とは表示しない。

### Edge Cases
- リンク・junction・相対パス脱出、出力先重複、入力の途中変更は拒否する。
- RAWが未提供なら合成検証のみ。実DNGのdecode・色・clip・Pixel成立は未確認とする。
- 飽和／黒つぶれ指標は処理後の数値指標であり、センサー飽和や情報復元の証拠としない。

## Requirements

### Functional Requirements
- **FR-001**: 明示された許可root内だけを非破壊で確認し、リンク先へ追跡しない。
- **FR-002**: 入力同定・camera別profile・WB・露出基準・色空間・gamma・brightness・黒白level方針を再生成定義へ記録する。
- **FR-003**: 基準版／トーンのみ版を同じ現像結果から生成し、Stage Clearは未実装の予約枠とする。
- **FR-004**: 原本・派生画像・private相対パスをGitと外部送信から除外し、出力画像へEXIF/GPS/固有IDを複写しない。
- **FR-005**: CLIの入力検証・失敗・再現性を合成テストで検証する。実DNG品質は別判定する。
- **FR-006**: 複数照明、等倍／大crop／SNS縮小の評価で、技術的問題と好みを分離する。

### Key Entities
- 入力: 許可root内のDNG、内容ハッシュ、既知camera分類。
- 現像定義: 固定版環境、WB・色・階調条件。
- 比較run: 基準／トーン画像、数値指標、再生成manifest。全てprivate。

## Success Criteria

### Measurable Outcomes
- **SC-001**: 合成入力の同一設定で出力ハッシュが一致する。
- **SC-002**: 範囲外入力・未知camera／WB・破損・重複出力の失敗を成功扱いせず、既存出力を変更しない。
- **SC-003**: 出力・manifest・環境が公開対象から除外され、出力画像に原本EXIFを含めない。
- **SC-004**: 実素材がある場合だけcamera別にdecode結果を記録し、未説明の色転び／clipがあれば画作りへ進まない。

## Assumptions
- 本人が許可したlivepicture/noteditedのRAWを基準比較に使い、adopted/originalのRAWとadopted/editedのユーザー編集JPEGを好みの参照として使う。ファイル名・撮影metadataで対応を確認し、crop/回転/サイズ/色空間差を区別する。画素単位一致や編集設定を推測せず、基準現像をJPEGへ自動fittingしない。
- Android、NDK/GPU、動画、顔AI、multi-frame、大型独自NR、grain/halationは対象外。
- merge・Android移植・Releaseは別承認。独立レビューは実装者以外が行う。後続のアプリ内選択現像UXは[002計画](../002-live-capture/plan.md)を参照し、本PoCへ先行実装しない。
