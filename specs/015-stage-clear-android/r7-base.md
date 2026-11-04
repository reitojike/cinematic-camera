# R7土台の純粋な数値境界（#78）

`R7Base.render(input, foundationSkinRoi, externalBaseEv)`はplan境界Bを扱う。
入力は意図したcamera profileとWBを適用済みの線形sRGB/D65、出力はgamut mapping前の線形sRGB。
負のRGB・1を超えるRGBを暗黙にクリップしない。DNGの読み取り、WB、露出推定、顔検出、保存、画面は含めない。

## 共通recipeと入力

公開の正本は`tools/raw_baseline/r7_base.py`。採用済みR5共通clear recipeの引継ぎ値は
`ev=.3 / contrast=1.23 / saturation=1.22 / skin_saturation=1.02 / yellow_rgb=[1,.995,1.2]`。
global RGB tintは`r7_final`と同じく`[1,1,1]`へ上書きする。写真別のEV・ROI・例外は定数へ入れない。
設定は`export_android_r7.py`から`R7BaseV1.kt`へ生成し、既存Stage Clearの版・goldenは変更しない。

処理順はP2（青紫−24度）→I1 local tone（highlights .35 / shadows .3）→foundation→I1 vibrance .6
→K1（black .004 / contrast 1.12 / chroma .10）→S2。R7の各層はStage Clearの強度とは独立する。

外部の`externalBaseEv`は必須。foundationの露出指数は`externalBaseEv + E2(.6) + common ev(.3)`。
呼び出し元でE2を再加算しない。Pixelの中央97百分位によるPCの仮露出推定はこのAPIへ取り込まない。
有限でないEV、ゼロ/無限になる露出gain、寸法不一致または0〜1外のROIは拒否する。

`foundationSkinRoi`はR5/R6 foundationの局所調整へ渡す同寸法のsoft ROI。
PC比較では公開の合成楕円を`r6_diagnose.skin_mask`へ渡して作る。
foundation内で`ROI * clip(Y/.025,0,1)`とし、局所contrast・黄味・彩度へ用いる。
R7の色相ベース肌重み、および後段の検出顔`FaceMask`とは別物である。
ゼロROIはfoundation局所調整を無効にする試験入力として使えるが、顔保護済みの証拠にはならない。

W1（+22mired）はdecode前のWB設定であり、このrendererには適用処理を持たない。
LibRawのpreliminary linear sRGBをつなぐ場合も、PCのPixel ForwardMatrix/LookTable/GainMap、
BaselineExposure、AsShotWhiteXYとW1を適用した入力と同等とは扱わない。

## 数値処理と検証

local toneはFloat32 log2輝度のBOX縮小→BICUBIC復元。
`cell=.04*longSide`、縮小寸法は各辺`max(4, Python互換の偶数丸め(side/cell))`。
既存`StageClearBlur.plane`の最小辺2・BILINEARと混同せず、共通resizeへBICUBICだけを追加する。
Pillow固定版のcubic（a=-.5）、support=2、端の係数正規化、double加算、各軸Float32出力を保つ。
既存Pillowライセンス表示を維持する。local toneの92百分位と中央値はNumPy既定のlinear補間。

`export_android_r7.py --write`は写真を開かず、7種の合成入力4908画素について入力・ROI・log base・
6段の全RGBをlossless gzipへ保存する。single/tiny、縦長/横長、グラデーション、肌色、青紫、
黒、白、負の成分/1超の成分、ゼロROI/soft ROI、正負の外部EVを含む。
同じ共通設定・EV・楕円を既存`r7_final.render`へ渡した結果と完全一致することもPCで確認する。

`test_r7_base.py`は公開設定と再生成全画素を照合し、`R7BaseTest`はlog baseと各段の全画素を比較する。
暫定の絶対許容差は比較前に`2e-5`（相対差0）とする。既存のOrton合成境界と同程度のFloat32演算累積を
検出するための移植回帰予算であり、実写の画質受入値ではない。失敗時は原因を調べ、黙って緩和しない。
fixtureの再生成差とAndroidの段別最大差をテスト出力へ記録する。

Pixel実DNGでのPC並列比較、端末時間/peak memory、全解像度、キャンセル時の応答、
W1/Pixel色入力と実際のROI供給は未確認のまま区別する。表示用1枚のための部品であり、T006全体を完了にはしない。
