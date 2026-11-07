# Implementation Plan: カメラロール表示の追補

Branch: `fix/roll-design-followup` / [spec](spec.md) / [Issue #187](https://github.com/reitojike/cinematic-camera/issues/187)。

## Summary / Technical Context
既存Kotlin/Compose/Material3。CameraX 1.6.2と依存関係は無変更。変更はCameraRollBrowser/CameraRollGridと合成UIテスト。

## Constitution Check
原本・保存・制御・権限判定を変更せず、表示の事実と合成/実機の検証を区別する。公開IssueとDraft PRの作成許可を確認済み。CI・canonical review・mergeの完了とは区別する。公開用文書へ個人パスや写真を含めない。

## Research / Design
- existing CameraRollAccessを入力として使用。images && videosを全面許可とし、それ以外でselectedがある場合は破線/アンバー。混合許可はaccess.descriptionをそのまま使い、選択のみだけ原典の短い説明へ置換。
- 全面許可の帯は省略。未許可・片方のみ許可・非対応の説明は残す。supportedやenabledの意味をUI側で上書きしない。
- 空状態の説明と2操作を専用のpresentation composableにする。読み込み中は空状態を表示しない。通常一覧のquery/state/callbackは触らない。
- 空状態を一覧領域の中央へ置き、小画面で必要なスクロールを確保。操作の上位owner、フッター、試験注意は残す。
- CameraRollGridのsurfaceVariantをCaptureDesign.Panelへ、代替文字をCaptureDesignへ。
- settingsOutlineの配置変更、選択部品や狭幅判定の一括統一は第三のまとまりへ保留した。[046-design-commonization](../046-design-commonization/spec.md) / Issue #195で対応する。
- データモデル・外部APIは追加しない。research/構造は本planに統合する。

## Validation / Quickstart
CameraRollBrowserAndroidTestを新設し合成状態で全面許可帯なし、選択/混合許可、空状態2操作の回数・無効時0回、読込中に空状態なし、サイズ別到達を確認する。実写真、権限を実際に変更するテスト、外部viewer起動は行わない。
Windowsで lintDebug/testDebugUnitTest/assembleDebug/assembleDebugAndroidTest とgit diff --check。今後のisolated instrumentationは[共通GMD](../../docs/MANAGED_DEVICES.md)の `pixel9aapi37atd` で対象classだけをホスト逐次排他で実行する。D上の共通cache/AVDを使い、旧手動AVDは起動しない。既存の権限query/unit回帰を維持。実機、実DNG、外部viewerは未確認。

## Dependencies / Delivery
044現像追補の表示部品と差分を確認し、共通ファイルは変更しない。別branch・別Draft PR。最終head CI・canonical/独立レビュー・thread0を確認し、マージ許可を改めて確認。APPROVE代行なし。