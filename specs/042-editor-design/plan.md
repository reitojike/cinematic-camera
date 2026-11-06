# Implementation Plan: DNG現像画面

Issue #175 / specs/042-editor-design/spec.md

## Technical Context / Constitution

既存Kotlin/Compose/CameraX 1.6.2。新依存なし。A/B/Cの表示部品を前提とし、独立ブランチ/PR。RAWパイプライン・保存owner・ticket・bitmap/lifecycleは対象外。

## 調整UIの比較（要確認3）

| 選択肢 | 利点 | 必要な変更・リスク |
| --- | --- | --- |
| PhotoEditBarの見た目だけ変更（推奨） | 032/036の連続入力・離して確定・相対値・session token・キーボード/accessibilityをそのまま保てる | 描画だけを5候補/補助目盛り/左右操作へ変更。撮影の見た目と共通tokensを使うが入力部品は別 |
| ExposureSelectionScale系へ寄せる | 撮影との描画共通化 | 撮影側の離散候補/即時操作と現像側の連続値/確定の違いをadapterで吸収する必要。共通化範囲と回帰試験が広がる |

推奨理由は保存/現像意味論を維持して見た目を合わせる依頼に対し、入力契約を変更せずに済むため。POの選択前にはどちらも実装しない。

## 構成と順序

1. PhotoRollScreenの選択後UIを状態帯・画像・調整・保存列へ分割。既存callback本体/guardを保持する。
2. 状態/画像caption/工程表示を既存phase/stageから構成。原本情報と試験注意は開閉可能な表示領域へ。
3. 選択後にPhotoEditBarの調整描画とTrialStrengthEditorPaneのキー/目盛りを実装。入力token・確定と取消を維持。
4. 保存列は既存ボタンのcallbackを移動し、READY/SAVING/PUBLISHED/UNCONFIRMED/HOLDの意味を保持。編集未反映や処理中に保存を有効化しない。編集を閉じて保存する既存境界を黙って省略しない。
5. 比較の長押しに加え表示切替を接続。比較画像がない場合は無効。所有権と破棄を変えない。
6. mode/phase/save phaseの表示と入力回帰、lint/unit/assemble/diff、可能なAVD表示、Draft PR。

## 検証上の制約

AVDは表示確認のみ。実DNG・24MP保存・実機の描画/触感は未検証。RAW性能の測定や試作採用をこのIssueへ混在させない。
