# Implementation Plan: デザイン共通化
[spec](spec.md) / [Issue #195](https://github.com/reitojike/cinematic-camera/issues/195)

## Technical Context / Constitution Check
Kotlin/Composeの既存単一app。依存を追加せず、原本/保存/現像/入力へ触れない。最終headのCI・canonical/独立レビュー・thread0と個別merge承認を必要とする。実機/GPU合成をATD合成で代用しない。

## Design
1. CaptureDesignへ不足色を同値で追加、全Color(0x…)とprivate色別名を置換。PanelHeader/Gearの未使用を検索確認して削除。
2. settingsOutlineをCaptureControlVisualsのcaptureOutlineへ移動。反転型captureChoiceSurfaceとタブ型captureControlSurfaceは別系統。クリック/semanticsは呼び出し側に保持。
3. StatusChipを内部共通化し、回転なしの現像利用時も既存padding/文字/枠とsaveColorの意味を保つ。撮影既存トーンは変更しない。
4. 比較キーへ区切り、無効切替全体へalpha .4を適用。
5. 純粋captureNeedsFullScroll関数へ委譲。設定は幅判定無効・fontScale非考慮、診断/ロール520、現像560と展開条件を保持。
6. 042/044/045の完了証拠を確認して更新。042 T007は全mode/phase未確認を合成サイズ検証の成功に読み替えない。

## Validation / Delivery
まず基準eb99733で同じfixtureを作り前画像を保存し、候補で同じfixtureを使って比較する。必要ならテストだけを基準worktreeへ移す。写真はすべて合成。
対象画面はspecの全matrix。ATDソフト描画は表示合成/GPU/実機の検証ではない。
RAW診断の重い検証と競合させず、共有枠はMANAGED_DEVICES.mdに従う。lintDebug/testDebugUnitTest/assembleDebugとdiff check、追加unitを実行。
1 PRを目標。スコープが大きくなれば色/outline/dead codeと残りの2本に分け、未完了を明示。
研究/データ構造は表示用の既存stateのみで本planへ統合、外部契約追加なし。
## 検証範囲の段階化
依頼者は追加の表示state分離より診断版・実機確認を優先した。既存の純合成入口で撮影共有表示部品の縦横、ロール3状態、現像5状態を同一fixtureで比較する。
撮影全画面・設定4状態・診断各タブの純合成注入にはCameraProbeの表示依存分離が必要で、今回は追加しない。これらは未検証として実機確認へ残す。合成部品の成功を全画面成功に読み替えない。
将来の自動化には表示adapter/preview slot分離を別途検討する。今回の実装・受入状況ではこの残件を明示する。