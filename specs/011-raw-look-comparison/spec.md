# Feature Specification: PC RAW 4案ルック比較

**Feature Branch**: feat/017-raw-look-comparison
**Created**: 2026-11-03
**Status**: Draft
**Input**: [Issue #54](https://github.com/reitojike/cinematic-camera/issues/54)

## User Scenarios & Testing

### User Story 1 - 明るい肌の好みを選ぶ (Priority: P1)

評価者は同じ許可RAWを、基準と明確に異なる4案で比較する。
**Why this priority**: 旧比較は暗さと差の小ささから判断できなかった。
**Independent Test**: 少数の異なる照明例で4案を切り替え、全体と等倍を確認。

1. **Given** 監査済み基準、**When** 4案を生成、**Then** 明るい肌を共通土台に色・柔らかさ・舞台の強さを選べる。
2. **Given** 肌/衣装/照明の観察点、**When** 比較操作、**Then** 同じRAW内では位置と倍率を同期できる。
3. **Given** 本人JPEG、**When** 参照表示、**Then** crop差を明示し、正解・自動fitの目標としない。

### User Story 2 - 原本を守り再生成する (Priority: P2)

評価者は全設定と手動領域から画像を再生成する。
**Why this priority**: その場限りの加工と基準不良の隠蔽を防ぐ。
**Independent Test**: 同一環境で出力画像hash一致、原本hash/size/mtime不変。

1. **Given** 許可素材と定義、**When** 再生成、**Then** 入力外の新folderへ出力し原本不変。
2. **Given** 範囲外・リンク・出力重複、**When** 実行、**Then** 既存入力/出力を変えず失敗を報告。

### Edge Cases

色照明を一律に無彩色化しない。説明不能のWB/gamma/level異常はlook前に停止。
手動領域境界・顔首腕・灰唇・白衣装階調・noise/clipは好みと別に評価。
描画未検証、Pixel素材なし、自動肌検出なし、Stage Clear未完成を明示。

## Requirements

- **FR-001**: 基準のWB/色空間/gamma/固定露出/黒白levelを監査し記録する。
- **FR-002**: 標準クリア/寒色ソフト/暖色自然/ステージ鮮明、基準、任意本人JPEGを比較する。
- **FR-003**: 全体/等倍/肌/衣装/照明、同期移動と倍率を提供。
- **FR-004**: 全設定/手動領域/入出力hash/環境をprivate再生成記録に保存。
- **FR-005**: 原本保持、外部通信なし、表示画像へEXIF/GPSを複写しない。
- **FR-006**: 不正値/リンク/範囲外/重複出力を拒否し、未検証を成功扱いしない。

### Key Entities

許可素材、scene、手動領域、4案設定、再生成記録、観察点と好み。

## Success Criteria

- **SC-001**: 3代表以内で同4案を比較し、肌/衣装/照明へ一操作で移動。
- **SC-002**: 同一環境の画像hash一致、全使用原本hash/size/mtime前後一致。
- **SC-003**: 全表示画像にsRGB profile、EXIF/GPSなし、素材が公開対象に混入しない。
- **SC-004**: 技術検証/未描画/好み/未完成機能を区別。

## Assumptions

許可素材のみ。比較画像と素材別記録はprivateかつ入力外。
4案は写真別手動領域を使う画質PoCであり汎用処理成立の証拠ではない。
Android/NDK/GPU/gallery/重いAI/multi-frame/merge/Releaseは対象外。
