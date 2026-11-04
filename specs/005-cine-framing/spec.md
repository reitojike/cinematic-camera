# Feature Specification: CINE画角一致と固定Preview

**Feature Branch**: `fix/007-cine-framing`
**Created**: 2026-11-02
**Status**: Draft
**Input**: Issue #38。CINEの4:3 previewと16:9 MP4を一致させ、設定開閉で画角を変えない。

## User Scenarios & Testing

### User Story 1 - 見える画角でCINE録画する (Priority: P1)

撮影者はpreviewで構図を決め、同じセンサー領域を録画する。
**Why this priority**: 構図と保存結果の相違を防ぐ。
**Independent Test**: 許可された実機検証で画面端の目印をpreview/MP4間で比較する。
**Acceptance Scenarios**:
1. Given CINE待機、When quality/DR/fpsを選択、Then同じ16:9撮影領域を照会・対応判定・bindへ適用する。
2. Given 録画STARTINGからFINALIZING、When 0/90/180/270へ物理回転、Then UIだけ回転し出力targetRotationは固定する。
3. Given STILL/RAW、When 撮影、Then CINE用cropを適用せず原本の既存動作を維持する。

### User Story 2 - 設定を重ねて構図を維持する (Priority: P1)

設定を開閉してもPreviewViewの寸法・画角・sessionを保つ。
**Why this priority**: 設定操作と構図の連続性を保つ。
**Independent Test**: レイアウト単体テストと実機開閉比較。実機操作は別途許可時のみ。
**Acceptance Scenarios**:
1. Given 各mode/方向、When 設定・診断を開閉、Then PreviewViewを維持し設定により寸法/inset/scale typeを変えない。
2. Given 設定表示、When 背景/項目/閉じるをtap、Then下のAF/AE要求は発生しない。
3. Given 設定/診断、When Back、Then待機中は一段閉じる。診断の背面撮影操作は拒否し、録画準備開始以降は診断を閉じて停止操作を覆わない。
4. Given Preview、When accessibility click、Then中央AF/AEを1回要求する。物理tapはその座標へ1回のみ要求する。

### Edge Cases

- 縦長/狭いwindowはFIT表示し、設定をscroll可能にする。
- 非対応quality/DR/fpsは既存fallbackで扱い、bind不能は説明する。
- backgroundでは既存stop動作を保つ。録画中は設定変更を禁止する。

## Requirements

### Functional Requirements

- **FR-001**: CINEは公開CameraX 1.6.2 APIでPreview/VideoCaptureへ共通16:9撮影領域を指定し、照会・判定・bindを一致させる。
- **FR-002**: STILL/JPEG/RAWの既存保存領域へCINE ViewPortを適用しない。
- **FR-003**: 設定は固定Preview領域内のoverlayとし、パネル状態で寸法/inset/scale type/sessionを変えない。
- **FR-004**: 設定pointer・accessibility・Backは下の測光を起動せず、Previewのclickは中央、tapは指定座標へ1回だけ測光する。
- **FR-005**: portrait Activity、4方向UI回転、STARTING〜FINALIZING出力回転固定、background停止、録画停止accessibilityを維持する。
- **FR-006**: bound resolution/cropRect/rotationDegreesと実MP4 metadataを区別する。対応照会やbuildを実機一致の合格としない。

### Key Entities

CINE framing、Preview geometry、Panel、Focus click origin。詳細はdata-model.md。

## Success Criteria

- **SC-001**: レイアウト/入力/Back/出力回転の単体テストとassembleDebug/testDebugUnitTest/lintDebug、diff checkが成功する。
- **SC-002**: 別途許可した実機で全4方向、SDR/対応HDR、quality/fps、設定開閉のpreviewとMP4の端の一致を確認し、未実施は未検証と明記する。

## Assumptions

Issue #37の即時露出を変更しない。新権限・keys・撮影・録音・LOOK・RAW engine・releaseは対象外。
003-capture-uiの「設定でpreviewを縮小/画像に重ねない」規則は本featureのFR-003で明示的に置換する。003は履歴として保持し、他の原本保持/回転/Back規則は継続する。Draftはmergeまでmainの契約を置換しない。
