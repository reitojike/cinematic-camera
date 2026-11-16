# 本来のプレビュー入力の half_size 比較（未採用）

Issue #233。v0.1.x の評価であり、v0.1.0 の製品入力は FULL AHD、保存も原寸 AHD を維持する。

## 確認した dev.8 の経路

`Original24PreviewSession` は、原本照合と私有 snapshot の作成後に `decode24` で RGB16 を書き、
`OriginalFields.reduce` の BOX 縮小を 1 回行う。GPU 入力は長辺 1024、最大 786432 画素の両方で制限する。
24MP の RGB16 中間ファイルは約 144MB（十進）。Java に原寸 Float RGB を保持する方式ではない。
`HALF_CANDIDATE` は既存の `decodeHalfPreview` を使い、同じ原本・色・所有権の境界を保つ。

同梱 LibRaw 0.22.3 の `pre_interpolate` は `half_size` 時に `filters=0` とする。
そのため Bayer の AHD 補間は飛ばす。ただし RAW の読み込み/unpack、黒レベル・WB・色変換などは残り、
「画像処理も RAW 展開も一切ない」という意味ではない。元画素数が奇数なら半分の寸法は切上げとなる。

## 追加比較と既存 golden の区別

既存 golden 90 条件の入力は RAW ではなく合成 linear RGB で、256px の条件や export 条件も含む。
`PreviewHalfInputComparisonAndroidTest` は既存のケース ID、種類、縦横比、編集値を共有する追加実験であり、
既存 golden の置換・基準更新・製品採用ではない。

- 各条件から長辺 1024 / 786432 画素以下の比較寸法を求め、その 2 倍の偶数寸法で合成 RGGB DNG を生成する。
- 合成画像のチャンネルをカメラチャンネル刺激として使い、0〜4 を RAW の黒〜白に対応させる。実カメラの逆モデルではない。
  したがって元の RGB fixture の色や near-white 占有率をそのまま再現したという主張はしない。
- 同一 DNG の FULL と HALF_CANDIDATE をそれぞれ現行の snapshot・検証・RGB16・BOX 経路で読み、GPU の実際の写真単位解析から描く。
- 比較する両経路の寸法は assert する。色/WB、geometry、原本不変、資源解放を緩めない。
- 出力は両経路の PNG、8bit RGB の MAE/max/p99/RMSE/PSNR、変化した画素の割合。完全一致の PSNR は null とする。
- 数値だけで採否を決めず、差の大きい条件・細部/色/ハイライト/ノイズを本人に提示する。
- 元の golden 90 と帯 28 の回帰は別に維持する。追加実験の成功で代用しない。

## 区間計測の方針

既存 `PreviewTiming` の STATE_ADOPTED は物理的な表示完了ではない。
プレビュー入力の受付、照合/preflight、snapshot、候補表示、native decode（RGB16 書込みを含む）、
BOX 縮小、事後照合/解放、GPU 入力 upload/解析、最初の `swapBuffers` 後を区別する。
GPU の `onPresented` は swap 成功後だが、液晶に光った時刻ではない。
取消・失敗・世代交代は成功に数えず、URI・写真名・顔・原本 fingerprint を記録しない。

現在の追加試験にある SessionNs は snapshot/照合/解放を含む。GpuReadbackNs は検証用読戻しを含む。
FULL→HALF の固定順で品質比較するため、これらを速度の採用値・decode 単体・実画面初表示と呼ばない。
製品経路への工程計測配線、匿名記録の永続化、計測自体の回帰は次の実装段で行う。

## 実行と採否

専用 read-only GPU AVD を共有手順で確保し、`isolated=true`、`previewHalfComparison=true`、
重複しない `previewHalfRunId` を指定して追加試験だけを実行する。試験は実機を拒否する。
未実施・不完全な comparison.json を合格として扱わない。

Pixel の操作と配布は都度承認後。AHD/half は同じ写真、同じ編集値、同じ受付→初 swap 区間で ABBA 順などで比較し、
cold/warm、保存キャッシュ/埋め込み JPEG の表示有無、発熱/背景負荷を分ける。
保存は両版とも原寸 AHD とし、プレビュー候補を保存入力へ接続しない。
本人が品質差と実機時間を見て採用した場合だけ、仮画像がなお必要かを再検討する。

## 状態

合成 AOSP AVD の追加 90 条件は bdcddade で完走（1 test、560.703 秒）。起動の90秒安定・ANR 0、起動証跡の実行前後一致を確認。比較画像と全指標は [ui-review](ui-review/preview-half-input/README.md)。compileDebugAndroidTestKotlin と Debug/test APK assemble が成功。独立コードレビューで blocking finding なし。原本・保存の製品コード変更なし。既存 golden 90/帯28 の再実行、12/24MP入力、Pixel 時間、製品の工程計測配線は未実施。