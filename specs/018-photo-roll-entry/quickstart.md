# 入口と接続の確認

この段階の製品rendererは未接続。DNG選択と原本のバイト同一性確認までを扱い、現像できたと表示しない。
機械的検証は既存の `:app:lintDebug :app:testDebugUnitTest :app:assembleDebug` と `git diff --check`。

## 後続rendererとの接続点

`photoroll/PhotoRollActivity.kt`の依存1箇所を、#78で成立した`PhotoRollRenderer` adapterへ置換する。
入力は`PhotoRollRenderRequest.original.uri`とサイズ/SHA-256、Stage Clear v1・強度0.5・最大辺1024。
read-only FD/streamで扱い、未知の機種/metadata/顔/露出は`Unavailable`を返す。返却は向きを適用した
ソフトウェアsRGB Bitmap。UIは寸法/色空間と原本hash再照合後だけ表示する。JPEG公開APIとは接続しない。

ActivityのRESUMEDだけでは、熱・メモリ予算・カメラ解放が実測済みとは扱わない。実rendererを有効化する接続PRでは
#89の開始条件と撮影優先へ整合させ、decode中のBack・背景化で協調停止を確認する。非協調read/decodeは戻るまで
permitを保持するが、撮影への復帰をその完了待ちにしない。実際の競合/応答は測定する。

## 本人操作で確認する項目（すべて未確認）

1. 許可された破棄可能な端末内DNGだけを用意する。既存写真の消去やSD取り込みはしない。
2. 左下の写真枠→一覧。自アプリ所有・公開済みDNGが候補に出るか確認する。Android 9ではFiles経路だけ。
3. 「Filesから端末内DNGを選ぶ」→「内部ストレージ」から選択する。「最近使った項目」/SD/クラウドは
   現段階では停止理由になる。DNGのMIME差があるためFilesは全形式表示。非DNG・サイズ不明・空・256 MiB超は拒否する。
4. キャンセル、Back、連打、別画像、背景復帰、画面回転/Activity再生成を確認する。
   旧結果が別画像に反映されず、再開時に原本を確認し直すことを見る。権限が失われたら再選択する。
5. renderer未接続では接続準備中の理由になり、画像や成功JPEGが作られないことを確認する。
6. 実renderer接続後に、処理中/結果を区別し、表示の向き・色・画角、時間/メモリ/熱、処理中Backからの撮影応答を測る。
   選択中の原本の外部削除・同サイズ内容変更・権限失効で成功表示を停止することを確認する。
7. 撮影・保存・録画・Finalize・状態不明中の入口無効化、入口押下直後のシャッター連打、
   撮影への復帰と通常JPEG/RAW+JPEG/動画の保存を確認する。

原本のbytesが不変、恒久DNGコピー0、既存写真/仮JPEG/共有公開の変更0を確認する。
原本照合は最初の観測以後の同一性確認であり、その前の完全性やDNG decode成功を証明しない。
