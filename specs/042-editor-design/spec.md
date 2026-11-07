# Feature Specification: DNG現像画面のデザイン反映

Branch: feat/design-dng-editor / Issue #175 / Status: 実装統合済み・実機未検証

## User Scenarios & Testing

### US1 状態と画像を区別する (P1)
現像中、表示中、未反映、比較、停止、保存状態を別々に理解できる。
受入: 状態帯44dp・黒背景FIT画像・状態別caption。5段工程はPhotoRollRenderStageの実値に対応し、未知の段階を完了扱いしない。BLOCKEDは理由と再試行。

### US2 既存の編集操作で調整する (P1)
受入: キー列72dp、目盛り104dp、ドラッグ連続値、離して確定、左右タップ1段、RESET既定値。MANUAL_TRIALは3項目、STAGE_CLEAR/PRIVATE_TRIALは強度のみ。他のキーは未対応。指定と表示中の値を別表示し未反映はアンバー。

### US3 保存を誤認せず操作する (P1)
受入: 撮影列96dp、保存76dpリング。READY灰白/↓、SAVING破線アンバー、PUBLISHED緑/開く、UNCONFIRMED赤橙/再照合。PRIVATE_TRIALは保存不可。取消は編集開始値へ戻し、表示/比較切替と長押しを維持。

## Functional Requirements

- FR-001: Aの共有色/書体、指示書Dの44/72/104/96dpの帯構成、FIT画像を採用。PNGの寸法・例示値を根拠にしない。
- FR-002: ファイル名・原本情報・モード・顔保護なし・保存状態を状態帯へまとめる。試験品質の注意を消さない。
- FR-003: PhotoEditBar/032/036の連続入力、確定、token/interactionKey、accessibilityを保持。目盛り部品の選択は要確認3の判断後に実装する。
- FR-004: requested/displayed/comparisonと保存照合済みを別状態として表示する。
- FR-005: 保存の表示変更のみ。既存のsave owner/ticket/lifecycle/bitmap解放/原本検査/24MP寸法検査/再照合を変更しない。
- FR-006: 小画面、拡大文字、横持ちで全操作へ到達可能。実機確認は未確認として手順を記載する。

## Success Criteria

- SC-001: モード/phase/save phase別の意味と有効条件が既存と一致し、原本を変更しない。
- SC-002: lint/unit/assembleとdiff check成功。既存032/036の入力回帰を維持。
- SC-003: 412×924dp、拡大文字、短辺で状態・保存・取消・再試行へ到達可能。

## Assumptions / 明示判断

要確認3は「planで比較して報告し、判断を待つ」。推奨はPhotoEditBarの入力処理を保った描画変更。POが案1（PhotoEditBarの見た目変更）を明示選択済み。入力契約が異なる撮影部品へ統合しない。要確認4は既定の同位置/リング形状、↓と色で撮影から区別。
