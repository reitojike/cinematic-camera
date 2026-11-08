# 実装計画

## 技術と根拠
Kotlin/既存Jetpack Compose/coroutines。新規依存、executor、GPU、保存処理を追加しない。#78の履歴と#204–208/#217/#219を確認し、既存asShot/R7/Glow field cacheと直列受付を再実装しない。

## データと入口
`StageClearDecisions`はMist初回自動強度、顔cut係数、顔cut後Mist自動強度を保持する不変値。再処理の有無と値を検証する。既存renderシグネチャを保ち、明示overrideを受ける入口を追加する。未指定は従来の判定関数を同じ順番で呼ぶ。戻り値から実際に使用した値を取得できる。

`Original24PreviewSession`の写真同一性と既存IO所有権の中にexact判定とR7の外部露出判断を保持する。256側でcentralP97を再測定せず、最後のexactで計測したbase EVに現在の手動EVを加える。exact成功時だけ更新し、失敗した結果や別写真へ移さない。長辺256px用input/base/fieldsは1組までとし、色温度/明るさ変更で対象baseを作り直す。既存1024入力から縮小し原本decodeを重複しない。保存の入口/出力は切り替えない。追加の保持は256px input/base計1.5MiB以下と縮小Glow field435,692byte以下（object/一時配列/表示Bitmapを除く）。既存1024px input/base計24MiB以下は変更しない。小画像はinputを別copyせず共有する。

## 操作と所有権
photorollはdraftとcommitted revisionを分離する。既存coroutinesを使い、ドラッグ受付は最新1件に畳み、処理中1件のowner完了を待つ。release/写真切替/終了のepochで古い結果を拒否して回収する。確定renderは正確像を採用し、256像からsave準備や保存設定を書き換えない。比較Beforeとsave handleの所有権を維持する。

## 保存側との分離
raw/original/OriginalStageClear24、R7/Mist行ループ、原寸worker候補は編集しない。判定測定は既存stage callbackを観測するテストのみ。現在openの#218はandroidTest-only GLES候補であり別ファイル。main#213のRAW4候補は実機採否待ちのまま。

## 検証計画
1. 既定raw-bit一致、same-size override一致、顔cut両pass、無効判定、取消をJVMで確認。
2. 合成DNG sessionでexact→256→exact、写真/調整値、Bitmap/owner回収。Composeは連続draft/終了/再開/releaseの採用順を確認。sessionはActivityを除去したoriginal24Probe variant、実画面はActivityを持つ通常Debug variantの別GMD実行に分ける。
3. 同一合成入力の1024/原寸のdecision値を観測し、入力縮小/R7/各判定境界を明記。大きな実行は専用worktreeで単独、GMDは既存枠運用。
4. lintDebug/testDebugUnitTest/assembleDebug/compileDebugAndroidTestKotlin、diff/公開除外検査、CIと両レビュー。追加の実機確認を実施済みにしない。

## Constitution確認と分析
仕様/plan/tasksにUS1–3を対応。標準coroutines/Composeと既存ownerを再利用。秘密/個人パス/写真は公開しない。テスト用fixtureは合成のみ。外部APIがないためcontracts等は増やさず、本planに設計/データ/検証を統合。clarifyの未決事項なし。merge権限は別途承認待ち。実測/未測定を分ける。
