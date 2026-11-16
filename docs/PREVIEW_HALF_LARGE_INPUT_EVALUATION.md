# 12/24MP の FULL / half 入力補足（未採用）

Issue #233 / PR #455。先行する最大約 3.15MP の 90 条件とは別の追加実験。
製品・保存・原本コードは変更せず、保存の原寸 AHD と既存の上限を維持する。

## なぜ36条件か

大きな縮小率で、低周波の階調/色、RAW 画素単位の高周波、点光源、ノイズを観察する。
6 刺激 × 2 入力寸法 × 3 編集値を選んだ。既存90条件の代替や、全90条件を12/24MPで再実行した結果ではない。

| 入力 | RAW画素数 | GPU比較寸法 |
|---|---:|---|
| 4000×3000 | 12,000,000 | 1024×768 |
| 6000×4000 | 24,000,000 | 1024×682 |

縦横比は4:3と3:2で異なる。両サイズを「解像度だけが違う同一場面」とは比較しない。
各行で同一DNGからFULL/halfを作り、同じGPU入力寸法と編集値をassertして対にする。

編集値は `(strength, warmth, EV)` = `(0,0,0)`, `(0.5,0,0)`, `(1,100,2)`。
両経路とも写真単位の自動解析から実行し、その結果の変化もGPU最終画像の差に含める。

## 刺激の定義

`LargeBayerStimuli` は原寸の `(x,y)` から直接RAWサンプルを計算する。
低解像度RGBの引伸ばしや24MPのFloat RGB全保持はしない。DNG writerの行バッファは幅×2 bytes。

- `TONES`: 正規化位置の滑らかな階調と、その1/40の暗い階調。
- `COLOR_PATCHES`: 6色のcamera-channel刺激と暖色の滑らかな勾配。
- `MICRO_DETAIL`: RAWで1/2/4/8画素幅のバーと斜め境界。出力画素上の幅ではない。
- `LIGHTS`: RAW半径4/16/64/128画素の白い点光源。相対的な大きさは入力寸法で異なる。
- `NOISE`: 固定hashから生成する有界一様のscalar RAW noise。左側は暗く小さな振幅、右側は明るく大きな振幅。
- `MIXED`: 上記刺激をglobal座標の四分割で切り取る。独立した縮小画像を4枚合成したものではなく、点光源などはその領域に入るものだけが現れる。

DNGはRGGB、black64、white4095、identity ColorMatrix、neutral AsShotNeutral。
これはLibRawの同じWB/色変換を通すための**合成camera-channel刺激**であり、校正済みカメラ、実写肌色、センサー/ISOノイズのモデルではない。
初期90条件のNOISEは独立RGBの強い刺激で、今回のscalar RAW noiseとは分布・振幅が異なる。数値が小さくても「入力画素数や縮小率だけで誤差が減った」とは解釈しない。各同一DNG内のFULL/half対の差として読む。
RAWサンプルは64〜4095に限定する。近傍相関のないnoiseや極端なRAW周期はstress条件で、一般の写真の誤差分布ではない。

## 所有・出力

12/24MPのDNGは行単位で作り、元のsnapshot→RGB16→BOX→事後検証/解放をFULL/half各1回ずつ通す。
原寸RGB16はそれぞれ約72/144MB（十進）。入力上限64MiBと原寸24MP上限を緩めない。
GPUには1024px以下の入力だけを渡し、FULL/halfの縮小結果を保持した後に3設定を描く。
原本のfingerprintを最後に照合し、session・native出力・GPU資源を元の所有契約で解放する。

出力は両PNGとRGB8のMAE/max/p99/RMSE/PSNR/変化画素率。PSNR nullは完全一致。
GPU readback結果の品質比較であり、時間値は採用判断に使わない。Pixel速度と見た目の採否は本人確認後。

## 実行

既存read-only AOSP GPU AVDを共有手順で確保し、90秒の安定/ANR/起動証跡を確認する。
`LargePreviewHalfInputAndroidTest` に `isolated=true`, `largePreviewHalf=true`, 固有の `largePreviewHalfRunId` を渡す。
実機では拒否する。生成器の範囲/決定性/RAW高周波確認と、36条件比較の2試験を実行する。
`tools/preview-half/report.py` は `large-raw-36-v1` の36 IDと寸法/刺激/設定の全組合せが揃ったcomplete結果だけを受理する。

## 現在の検証

- Debug/test APK assemble成功。比較コードの独立レビューでblocking findingなし。
- レポート生成の正常・不完全/重複/不正寸法/パス/未知schema/既存出力の非上書きをPythonで確認。
- AVD 実走は 2 tests / 253.149s で成功。36 条件すべて complete、前後の起動・ANR 安定ゲートも成功。実走した比較コードは `d82d9960`。
- [36 条件の数値と差が大きい 4 組の画像](ui-review/preview-half-large-input/README.md)を保存。RGB8 MAE 最大は 12MP MICRO_DETAIL 25.346958、24MP MICRO_DETAIL 16.792429。12MP NOISE 最大は 10.468294。RAW 周期の細線で視認できる差があり、half の無条件採用を示す結果ではない。
- 12/24MP の COLOR_PATCHES の MAE はそれぞれ 0.056439〜0.068521 / 0.035769〜0.049385、TONES は 0.052354〜0.120261 / 0.030424〜0.074367。ただし実写の色・階調の保証ではない。
- 実写品質、Pixel速度、製品採用、既存golden90/帯28の代替合格を主張しない。