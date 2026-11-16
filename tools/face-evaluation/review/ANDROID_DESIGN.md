# Android 内部の実写評価導線（評価専用・実機未検証）

## 選択

評価専用projectの追加 `comparison` flavorで、同一画像に ML Kit FAST / ACCURATE と
YuNetを順に実行し、そのapp-private領域に結果・人ラベルを保持する。
別APK間で写真/顔枠を受け渡す仕組みは作らない。baseline/bundled/Play/YuNetの
容量比較用4flavorは残し、両SDK同梱comparisonの容量を単体増分へ流用しない。
製品root settings/app/GPU/原本保存への接続なし。製品の顔枠接続は別判断。

## ローカル処理と画面

- Android標準の選択UIで、本人が指定する静止画のみ選択。広い写真ライブラリ権限や
  カメラ権限は要求しない。原本には書かない。最初はJPEG/PNGの共通入力を対象とし、
  DNGのデコードは既存製品経路へ安易に接続せず別に設計する。
- ImageDecoderで向き・色を適用した同一の不透明sRGB bitmapを最大4MPに固定。
  オリジナル名/URIをSDK・ログ・集計へ入れず、ランダムな評価IDと画素SHAで対応する。
- 一度に1枚/1方式を処理。3方式の入力bitmapは同じ正規化結果。
  AndroidX ViewModel等の通常ライフサイクル所有者で処理と資源を保持し、画面回転で
  再推論・ラベル消失・二重実行しない。元画像は処理単位でclose、bitmapは完了後release。
- 画像上に方式別の枠を切替表示し、各枠の誤検出と見逃し数を本人が指定。
  顔なしも明示確定が必要。未確認/エラー/取消/モデル未取得を区別する。
- 人ラベルはapp-privateで原子的保存。process再起動後に同じhash/設定との対応を確認。
  明示的な「集計を書き出す」以外にShare/写真export/生JSONL exportを作らない。
  集計はdetector/設定、確認枚数、検出数、見逃し、誤検出、時間分布のみのallowlist。
- 静止画の検出比較専用。撮影プレビューのFPS/熱/消費電力を推測しない。

## ローカルHTMLとの関係

先行するreview/index.htmlは判定操作/集計契約の試作。Androidはnative画面を本線とし、
file URLを扱う操作ツールの制限を迂回するためのWebView/サーバーは作らない。
同じ手元PC上でのHTML確認と、PixelからPCへの写真/結果移動は区別する。
Pixelから写真・URI・顔枠を持ち出してHTMLへ入力する運用は禁止。

## 実装前の確認・完了条件

1. 両SDK用adapterを評価専用sourceSetsで共有し、baseline/単体APKの依存を汚さない。
2. 元JPEGの向き/色/サイズが3方式で同一、画像SHAが判定と一致。
3. 読込失敗、検出失敗、取消、画面回転、Back、再起動、重複選択、保存拒否の合成回帰。
4. 実際のSDK呼出し成功と検出精度は区別。人が未確認の画像は集計対象外。
5. APK/manifest/ソース監査、個別情報を含まない集計の検査、独立レビュー。
6. 本人の実機導入/操作承認を得てから30〜50枚の評価。製品採用は結果を見て本人判断。

## 残る設計上の論点

全て小さい顔を落とさず比較するには最大4MPの正規化とML Kit minFaceSize=0.1の
固定値が不利になる場合がある。最初の固定条件での見逃しを記録し、閾値や解像度を
後から変えるなら別設定として分ける。結果をよくするために同じ評価表へ混在させない。
初回/温間/SDK内/変換込みの時間を別々に保持し、1枚の代表値を勝手に混ぜない。

## Android comparison 初期実装（実機・実写・画面操作は未検証）

- comparison は bundled ML Kit FAST/ACCURATE + YuNet の別 application ID。
  Play 版は含めない。容量用の既存4flavorは維持し、アダプター実装のみsourceSetで共有する。
- 標準 ACTION_OPEN_DOCUMENT + EXTRA_LOCAL_ONLY でJPEG/PNGを1枚選択。URIは一時的に読取へ使うだけで
  保存・ログ・SDKへ渡さない。EXTRA_LOCAL_ONLYはproviderへの要求であり、任意providerの無通信保証ではない。
- ImageDecoder（向き適用、sRGB、software）を最大4MP・各辺8192pxに縮小し、透明部分は黒へ合成。
  3方式とも同一opaque ARGB_8888。画像別のdecode_nsとinitialization_nsは内部記録に残す。
- ViewModelの1workerが逐次処理。画面回転で再実行しない。取消はSDK完了まで待ち、
  未完了の画像を採点対象へ追加しない。Back/Activity終了でも入力をSDK処理中に破棄しない。
- noBackupFilesDirに正規化PNGとAtomicFileの記録を保存。原本は書き換えない。
  画像確定後のDB更新失敗時は未参照PNGを削除し、再起動では正常記録を検証した後にorphanだけ除去する。
  記録破損・画素SHA不一致はロックして上書き/集計しない。アプリ削除時は判定も消える。
- 各方式の枠番号、誤検出チェック、見逃し整数を明示保存。0も明示入力。
  未保存の入力は回転用Bundleに保持し、保存した判定は再起動後も保持する。
- native Viewの画像fit範囲から枠を変換。これは評価用の別Viewで、製品GpuPreviewSurfaceへ接続しない。
  将来製品接続時は固定Surfaceのviewportへ合わせ、パネル開閉の両方を検証する。
- FLAG_SECUREで画面キャプチャを抑制。自動写真export/共有/FileProvider/外向きHTTP実装はない。
  書出しは本人が押した集計JSONだけ。ID/SHA/URI/枠/画像をallowlistへ入れない。
- 時間は手動評価済みSUCCESSのみ、first-callとwarmを分け、SDKとadapterの中央値/件数を記載。
  first-callはこのownerでの最初の呼出しで、プロセスcold startや実機性能の保証ではない。
  未評価、FAILED、CANCELLED、NOT_RUNを区別する。画像の途中取消は全体を未追加とする。

### 現段階の未確認

標準ピッカーを含む端末内UI実走、EXIF回転と色を含む合成画像の実行試験、
取消/Back/回転/再起動/ストレージ失敗のAndroid実行試験、30〜50枚の実写精度は未実施。
ビルド・純粋な記録/集計の単体試験はこれらの代替ではない。配布・実機操作前に本人の承認が必要。

参照: [ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder)、
[SAF](https://developer.android.com/training/data-storage/shared/documents-files)、
[ViewModel](https://developer.android.com/topic/libraries/architecture/viewmodel)。
