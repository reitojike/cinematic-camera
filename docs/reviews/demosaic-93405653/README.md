# 原寸デモザイク比較：AHD・双線形・PPG（未採用）

まず [00-demosaic-overview.png](00-demosaic-overview.png) をPRの Files changedで確認してください。左から現行AHD、双線形（user_qual=0）、PPG（user_qual=2）。各段の上は全体、下は中央160×80画素の2倍表示です。

half_sizeは本人判断により編集用プレビューには不採用としました。この比較は原寸で色を復元してから既存の1024px・画素上限へ縮小する別候補です。製品は引き続きAHD、保存JPEGもAHDです。実写・Pixelは使用していません。

## 見ていただきたい点

- **02/05：細かい模様の色、モアレ、細部の崩れ**。PPGはhalfや双線形より現行との差が小さい一方、特に3pxの格子で緑寄りの違いが残ります。現行AHD自体もこの人工条件では無欠点ではありません。
- **03：明るい楕円の縁のギザギザ・色付き**。PPGはこの条件ではAHDとの差が非常に小さく、双線形は色付きが見えます。小さい平均値だけで局所差の合格とはしません。
- 01/04は滑らかな階調・色境界の補足。PPGは現行に近いものの全画素一致ではありません。

## AVDの展開時間（各方式warmup1回＋測定3回の中央値）

約3.15MPの合成DNGです。各反復で方式の順序を回転させ、単独AOSP AVD・他AVD/Gradleなしで計測しました。これは **native展開＋RGB16一時ファイル書込** の区間で、原本コピー・1024縮小・GPU描画・画面表示は含みません。Pixelの時間、24MPの時間、選択から編集可能になるまでの時間には読み替えません。小規模な探索結果です。

|条件|AHD ms|双線形 ms|PPG ms|双線形 MAE|PPG MAE|
|---|---:|---:|---:|---:|---:|
|01-gradients-colors|1072.03|429.37|512.55|0.00562|0.00159|
|02-fine-detail|1087.76|428.99|514.73|27.73793|11.76620|
|03-stage-lights|1062.87|428.23|512.79|0.27873|0.00468|
|04-color-edges|1061.30|420.62|506.45|0.25466|0.00120|
|05-odd-portrait|1095.31|452.34|532.79|27.76714|11.69158|

MAEはAHDに対するRGB8の平均絶対差。時間・画質とも採用基準を数値だけで決めません。PPGは本AVDのnative区間で約51〜53%短く、双線形は約59〜61%短い結果ですが、編集待ち全体の改善率ではありません。

## 原寸比較ファイル

|条件|現行AHD|双線形|PPG|保存JPEG（AHD）|
|---|---|---|---|
|01-gradients-colors|[ahd](01-gradients-colors-ahd.png)|[bilinear](01-gradients-colors-bilinear.png)|[ppg](01-gradients-colors-ppg.png)|[JPEG](01-gradients-colors-ahd-save.jpg)|
|02-fine-detail|[ahd](02-fine-detail-ahd.png)|[bilinear](02-fine-detail-bilinear.png)|[ppg](02-fine-detail-ppg.png)|[JPEG](02-fine-detail-ahd-save.jpg)|
|03-stage-lights|[ahd](03-stage-lights-ahd.png)|[bilinear](03-stage-lights-bilinear.png)|[ppg](03-stage-lights-ppg.png)|[JPEG](03-stage-lights-ahd-save.jpg)|
|04-color-edges|[ahd](04-color-edges-ahd.png)|[bilinear](04-color-edges-bilinear.png)|[ppg](04-color-edges-ppg.png)|[JPEG](04-color-edges-ahd-save.jpg)|
|05-odd-portrait|[ahd](05-odd-portrait-ahd.png)|[bilinear](05-odd-portrait-bilinear.png)|[ppg](05-odd-portrait-ppg.png)|[JPEG](05-odd-portrait-ahd-save.jpg)|

## 保存・通常経路の不変性

- 同じ5入力について、AHDプレビューPNGとAHD保存JPEGは、先に公開した ec2642ae 比較のものと**ファイルSHA256が全5件一致**しました。合成入力のSHAも一致しています。
- 新規JNIはDebugビルドだけに存在し、Kotlin宣言はandroidTestのみ。通常のプレビュー・保存呼び出しは既定quality=-1のままです。
- x86_64・arm64-v8aのrelease/debug .soをllvm-nmで確認し、releaseに試験用JNIがないことを確認済み。LibRawは既存1つを再利用。

## 記録と限界

- source `93405653`、環境とAPK hashは [host.json](host.json)、反復値・全画像hash・露出値は [demosaic.json](demosaic.json)。
- lint/unit 1380/debug APK/test APK/release native build成功、dot独立レビューP0/P1/P2なし。
- 比較試験は1件・5条件×3方式×4回成功。ANR0、system_server/boot ID前後不変。
- 画質の本人承認前に採用・既定切替・基準更新をしません。実写の見え方は別確認です。
- dev.7の保存NATIVE_DECODEは原寸展開の重さの参考ですが、初回表示全体の分母やキャッシュ状態がないため、その記録だけで初回表示に占める割合は算定できません。
- 本資料は一時的な確認用。確認後、不要な画像はマージ前に取り除きます。
