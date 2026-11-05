# 選択写真sessionの試験土台再利用（#81）

## 目的・対象範囲

強度変更ごとの再decode・R7再計算・比較decodeを省く。最大1024px・quality95・同じStage Clear・既定0.5とprivate JPEG表示を維持し、原本確認や公開条件は変えない。
一枚の写真を選んで結果表示・強度編集を行う間だけ、縮小R7土台と比較画素を保持する。解像度/色/露出/ルックの変更、設定永続化、共有保存、原寸部分描画、GPUは対象外。

## 要件・受入条件

- FR-001: process内でactive sessionは一つ。immutable RGB float32のR7土台一枚と比較ARGB画素一枚の保持payloadを合計16MiB以下にする。generation/backendごとに保持して累積させず、RAW/as-shot画像・mask/ROI・FD・stream・Bitmap ownerはcacheへ保持しない。作業配列やnative領域はこの保持量と区別する。
- FR-002: 原本参照全体（URI/bytes/hash/grant）、input/controls契約、maxEdge、R7/geometry版をkeyにする。実寸法・物理的向き・元寸法・診断EVを検証する。強度だけは土台keyから除外できるが、結果recipe・世代・表示ticketでは引き続き一致を必須とする。
- FR-003: cache hitでも従来の全原本pre/post確認とJPEG生成・検査・fresh readbackを維持する。正常結果・原本再照合・実資源close成功後だけ候補を登録する。未完了・内容変更・実エラー・close不明の候補は登録せず、既存entryも無効化する。通常の要求取消だけでは、以前に正常完了した同じ原本のentryを破棄しない。取消中の新候補は登録しない。
- FR-004: requestのIO-owner bindingとsession epochを毎回検証し、旧leaseから新session/世代へ登録・破棄できない。別写真選択、一覧/写真画面離脱、pause/dispose、メモリ通知、撮影予約、既存admission条件の悪化/未確認では全sessionを閉じる。閉じたentryが非同期完了で復活してはならない。
- FR-005: 比較画素は同じ原本・同じinput/geometryから生成した検証済み値だけを使う。取り出し時は独立arrayとsoftware sRGB Bitmapへコピーし、cacheとUI/queueのrecycle責務を共有しない。hitしない場合は既存decodeへ戻し、任意のサムネイルや前の強度画像で代用しない。
- FR-006: 再利用はopt-inのprivate試験だけ。legacy entryと完成/公開renderは再利用を参照せず、CompletedRender・公開owner・journal/schema・画質を変えない。既存coordinatorが処理と実資源解放まで撮影優先の枠を所有する。cache破棄はclose完了の代わりではない。

## 検証・停止条件

unitでkey/binding/epoch、比較copy独立、保持上限、実行中断とclose失敗を確認する。Android合成DNGでcache有無のJPEG/After/Before一致、hitのdecode省略、再選択/原本変更/破損/取消/撮影・メモリ失効を確認する。
同条件で初回と強度変更後の時間を測り、Look内訳と縮小演算比較は製品採用と区別する。AVD・合成測定をPixel実機性能/実写画質の合格にしない。画素不一致・原本検査省略・旧結果復活・資源解放や撮影優先の後退があれば停止する。
