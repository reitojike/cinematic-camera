# PR-E カメラロール・取り込み表示の確認準備

Issue #385。指示書は [DEVELOP_UI_REFACTOR_INSTRUCTIONS](DEVELOP_UI_REFACTOR_INSTRUCTIONS.md) 3.3。

## 現在の区切り

純粋な `libraryMessages` / `foregroundSaveMessages` と保存状態の購読・操作bindingを準備。
**画面切替はまだ行っていない。完成・目視承認済みではない。**
B所有の共通バリデーション変更とは別ファイル。Cでは `save.foreground_progress` と
`preview.export_paused` を別の事実として組み立て、中央のプレビュー停止表示を残す。

## 次の接続と確認

- CameraRollBrowserに候補を一括で渡す。失敗 > 保存進行 > 権限warning、バナーは1つ。
- 現在のForegroundSaveNoticeを購読bindingへ置換。GPU/CPU画面を含む全呼出箇所を確認してから旧Composableを除去。
- DngImportPanelのeffect/owner lifetimeを維持し、入口だけを下段と空状態へ移す。
  dialogの開閉状態を上位へ移す場合もrememberSaveable、pause/dispose、picker、consumeReady条件を維持する。
- 取り込みdialogはCaptureDialog、説明/結果はINLINE、進行はAmber/Line。
  state.messageは自由文しか持たないため、文言一致から成功を推測しない。結果の表示種別をどう渡すか確認してから接続する。
- 項目名は「取り込み n · 完了 / 中断 / 権限の解放待ち」など状態を添える。
  DngImportItemにはURI/ファイル名がない。表示のためにjournalへファイル名を保存しない。
- 非選択ボタンはCaptureTextAction。フィルタの選択surfaceは維持。

## 文言変更予定

| 旧 | 新 |
|---|---|
| Filesから DNG… / Filesから選ぶ | Filesで開く |
| 外部DNGを取り込む | 取り込む |
| JPEGを保存中です。アプリを離れても継続します。 | JPEGを保存中です。アプリを離れても続きます。 |
| 保存の停止処理中です。資源の解放を確認しています。 | 保存を止めています。 |
| 保存処理の終了を確認できないため停止中です。次の保存は開始しません。 | 保存の終了を確認できないため停止しています。次の保存は始めません。 |
| 保存通知は無効です。取消はこの画面から行えます。 | 保存の通知はオフです。取消はこの画面から行えます。 |
| 保存通知を許可 | 通知を許可 |

## 承認前の証拠

合成状態のスクリーンショット：通常一覧、アクセス限定、空一覧、一覧失敗、保存中、停止中、終了未確認、通知拒否、取り込み説明/準備/進行/失敗/完了/権限解放待ち。
通常幅・狭幅・大きな文字で操作が隠れないことを確認。
ボタンのenabledとhandler条件の差分照合、単体試験、lint/unit/assemble、Android表示回帰、最終head CIと独立レビュー。
スクリーンショットと最終文言一覧に対する本人承認前はマージしない。
