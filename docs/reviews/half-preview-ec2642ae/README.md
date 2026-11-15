# half_size 候補の比較（合成画像のみ・未採用）

まず **[00-overview.png](00-overview.png)** を開いてください。PR の Files changed でも確認できます。
左＝現行 full プレビュー、中央＝half 候補、右＝原寸 GPU 保存 JPEG95 を表示寸法に縮小した画像です。
上は全体、下は中央160×80画素を2倍にした表示です。製品既定は FULL のままです。

## 先に分かったこと

- 階調・色境界・スポットライトの全体差は比較的小さいものの、境界に局所差があります。
- **02・05 の細線では、half の偽色とモアレが明確に増えています。** この結果だけで既定化は勧めません。
- 02・05 は3px/11pxの格子という極端な合成条件です。実写で同じ程度に起こると断定はできませんが、差は隠さず提示します。
- 現行プレビューにも保存との差があります。下表はその既存差とhalfの追加差を分けています。

| 条件 | half対現行 MAE | 現行対保存 MAE | half対保存 MAE |
|---|---:|---:|---:|
| 01 階調・色 | 0.114 | 0.372 | 0.388 |
| 02 細線 | 46.539 | 19.616 | 43.813 |
| 03 暗部・スポットライト（EV -2） | 0.386 | 0.169 | 0.390 |
| 04 色境界 | 0.347 | 0.544 | 0.665 |
| 05 奇数寸法・縦向き細線 | 50.551 | 19.403 | 48.861 |

値はRGB8のチャンネル平均絶対差（0〜255）で、知覚的な色差や採用の閾値ではありません。
画像寸法、p99・最大差・差がある画素率、自動露出のbase EVは [comparison.json](comparison.json) にあります。
02・05のbase EVは3者で同じなので、上記の大きな差は自動露出差だけでは説明できません。

## 原寸の確認

各行のPNGが比較の表示寸法です。JPEGは原寸保存出力です（個人写真ではありません）。

| 条件 | 現行full PNG | half候補 PNG | 保存を表示寸法にしたPNG | 原寸保存JPEG |
|---|---|---|---|---|
| 01 | [現行](01-gradients-colors-full-preview.png) | [half](01-gradients-colors-half-preview.png) | [保存表示](01-gradients-colors-save-display.png) | [JPEG](01-gradients-colors-full-save.jpg) |
| 02 | [現行](02-fine-detail-full-preview.png) | [half](02-fine-detail-half-preview.png) | [保存表示](02-fine-detail-save-display.png) | [JPEG](02-fine-detail-full-save.jpg) |
| 03 | [現行](03-stage-lights-full-preview.png) | [half](03-stage-lights-half-preview.png) | [保存表示](03-stage-lights-save-display.png) | [JPEG](03-stage-lights-full-save.jpg) |
| 04 | [現行](04-color-edges-full-preview.png) | [half](04-color-edges-half-preview.png) | [保存表示](04-color-edges-save-display.png) | [JPEG](04-color-edges-full-save.jpg) |
| 05 | [現行](05-odd-portrait-full-preview.png) | [half](05-odd-portrait-half-preview.png) | [保存表示](05-odd-portrait-save-display.png) | [JPEG](05-odd-portrait-full-save.jpg) |

## 成立した範囲と未確認

- 24MP6000×4000、half長辺1023/1024/1025、奇数寸法、全8方向、画素数上限の15ケース成功。
- 原本fingerprint不変・出力全値有限・所有者/一時ファイル解放を確認。GPU基盤8件も成功。
- 5条件の比較生成が成功。source `ec2642aef5749be3dbc0b2d354b31edb0561b107`、APK hashは [host.json](host.json)。
- AOSP API37 default x86_64 revision6、Emulator37.2.13.0、NVIDIA RTX3070 driver32.0.16.2114、host + GuestAngle,Vulkan。
- boot1、system_server前後不変、ANR0。実行時間をPixelの性能指標には使いません。
- **実写画質・SIGMA fp・実機速度は未確認。製品既定の切替、基準画像の更新はしていません。**

[比較方法と再現手順](../../../tools/halfsize-preview/COMPARISON.md)。
このフォルダは本人確認用の一時資料です。確認後、不要な一覧・代表画像はマージ前に除きます。
