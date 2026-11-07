# Feature Specification: カメラロールのデザイン統一

Branch: feat/design-camera-roll / Created: 2026-11-06 / Status: 実装統合済み・実機未検証
Input: [Issue #174](https://github.com/reitojike/cinematic-camera/issues/174)、INSTRUCTIONS.md C。A→B後の独立PR。

## User Scenarios & Testing

### US1 写真を探す (P1)
理由: 一覧を写真中心にする。独立確認: 種類/フォルダ絞り込みと日付別3列表示。
AC: 見出し/更新、種類セグメント/フォルダ、日付/件数、DNG/RAW/動画時間、直近1枚の内枠が見える。読み込み中は既存一覧を消さず進捗線を表示。

### US2 権限と入力へ到達する (P1)
理由: 部分アクセスや空一覧から復帰できる。独立確認: 部分許可/未許可/空結果とFiles導線。
AC: 権限の説明/選び直す/設定、空結果の説明/許可/Files、footerの件数内訳とFilesへ到達。試験品質の注意を失わない。

### US3 既存の開く動作を守る (P1)
理由: 原本とキャンセル所有を保つ。独立確認: DNG/他メディア/さらに読み込む/Back。
AC: DNGは既存現像、他は既存標準viewer、Files/権限/ページングとforeground/cancelの既存条件が変わらない。

### Edge Cases
拡大文字/横持ち、長いフォルダ名、取得日不明、部分読込、権限失効、空一覧、未知RAW、試験モード。

## Requirements

FR001: 52dp見出し/44dp更新、48dp絞り込み、2dpアンバー進捗。FR002: 3列/2dp間隔、日付と件数、左下tag、最新1枚の緑内枠。FR003: 部分アクセス帯と権限動線、64dp基準footer、空状態。FR004: 試験注意は一覧1行を残し詳細を現像側にも保持。FR005: 状態所有/権限/ページング/原本/RAW処理を変更しない。

## Success Criteria

SC001: 全導線に412×924dp・拡大文字・横持ちで到達。SC002: 既存条件とDNG/viewer/Files/Back動作が一致。SC003: 表示件数は取得済み範囲、日付不明を架空日付にしない。

## Assumptions

要確認2は既定の移動＋MANUAL/PRIVATE一覧1行を採用。C時点でD未完でも詳細注意を消さない。PNGはモック、寸法は指示書、実機は未検証。新しい取り込み/保存機能は追加しない。
