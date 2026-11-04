# Implementation Plan: PC保護処理とKotlinの比較境界

**Issue**: #78 / **Branch**: `feat/issue-78-stage-clear-kotlin`

## 設計と判断

純Kotlin CPUを最初の比較基準とする。既存JVMテストで全画素を観測でき、Android用コードと同じ実装を試せる。
GPU/GLSLは演算精度・readback・端末依存の追加確認が必要。3D LUTだけでは顔マスク・全画面百分位・空間ぼかしを表現できない。
今回はCPUの画質境界を先に確定し、速度上の採用判断は端末実測後に行う。NDK/LibRawや新依存は導入しない。

`tools/raw_baseline/export_android_protections.py`が正本の値からKotlin定数と合成CSVを生成する。
PC CIでは生成物の一致を検証し、Android CIは同じCSVをJVMから読み込む。生成Kotlinを手で調整しない。
定数は保護処理・共通色変換・版・既定強度のみを先行共有する。段1〜5はPCコードを引き続き正とする。

`LinearSrgbImage`と`FaceMask`は検証済みコピーを保持する。内部配列を公開しない。
`StageClearProtections.finish(glowed, faces, layer)`は段2〜5の純関数を外部から受け取り、段6→7→必要なら段2〜6再実行→8を実装する。
layerには同じ寸法を要求する。呼出側が強度0.5やskin weightを固定して渡す責務を持つ。このAPIはStage Clear全体のrendererではない。
テストでは非線形の合成layerを使い、減光時に前段が再計算されたことも検証する。

## 段階別の合否境界

| 境界 | 入力と出力 | 今回の証拠 / 次の確認 |
|---|---|---|
| A DNG decode | センサー値→WB・demosaic・camera/XYZ変換→線形sRGB/D65 | 未実装。PC decodeの設定・crop・WB・解像度を揃えて独立比較する。JPEGやBitmapの暗黙色変換で代用しない |
| B R7土台 | 線形sRGB→R7確定の色・明るさ・肌 | 純Kotlinの共通recipeと合成全画素比較。実入力のprofile/WB、外部露出・skin ROIの供給は別境界。写真別露出・例外を公開しない |
| C 段1〜5 | R7 base→glow→mist/Orton/vignette/vibrance | 未移植。Pillow BOX/bilinearと座標・丸めの一致を先に調べる。顔減光時の再計算境界だけ今回用意 |
| D 段6〜8 | C出力→顔cap・顔減光→shoulder | 今回の全画素合成golden、90百分位はNumPy既定のlinear補間、faces > .5・50画素以上 |
| E 出力 | display-referred linear sRGB→gamut/transfer/量子化 | 未実装。shoulderはdisplay輝度で計算するが出力は線形RGB。8bit画像の比較と混同しない |
| F 端末 | 実DNG・表示用/全解像度 | Pixel 9a未測定。PC並列比較、時間、peak memory、撮影中キャンセル/再開等の応答確認を別途実施 |

境界Bの純粋なR7実装・公開共通recipe・入力ROI/露出・WB未接続の契約は[r7-base.md](r7-base.md)を参照。

段Dは入力の正しい由来を証明しない。既存`golden/stage_clear_v1.json`は領域平均と顔係数のみ。
今回の合成CSVも段D限定であり、DNGや全Stage Clearの合格に読み替えない。
顔の90百分位は全顔をまとめた値で、小さい明るい顔が埋もれ得る。50画素未満はガード対象外。
最大cutの制限と再実行で輝度0.90への到達も保証しない。今回はそのまま互換性を検証する。

## 検証

`python -m unittest discover -s tools/raw_baseline -p test_*.py -v`（既存hash固定依存の専用環境）。
生成物更新は`python tools/raw_baseline/export_android_protections.py --write`。通常は`--check`。
`gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --no-daemon`と`git diff --check`。
許容差はspecの固定値で、失敗した場合は原因を調べる。goldenや閾値を都合よく更新しない。

## Constitution確認

新しい依存・権限・UI・撮影フックなし。原本や非公開素材・写真別設定を扱わない。
PCの処理を変えず、機械検証と実機確認を分離する。進捗・レビュー結果はPRに置く。

## 次の小縦切り: ぼかしの互換境界

`StageClearBlur`を純Kotlinで追加する。`FloatPlane`は線形輝度/マスク等の任意Float32平面で、RGBには既存`LinearSrgbImage`を使う。
PCの`cell=max(radius*longSide,1)`、`small=max(2,round(side/cell))`に従い、BOX→BILINEARを各チャンネル独立に適用する。
半径は有限かつ0以上。0でも最小辺2というPCの挙動を保持する。

Pillow固定版の[Resample.c](https://github.com/python-pillow/Pillow/blob/13.0.0/src/libImaging/Resample.c)で
ピクセル中心、端の正規化、doubleの重み・加算から各軸出力をfloatへ戻す点を確認した。
縮小する高さの差が幅の差の2倍を超える場合は縦軸を先に処理する。BOXは単純な面積積分へ置き換えない。
Android標準Bitmapはfloatの線形平面・同じカーネル/丸めを保証する比較境界ではないため、この限定した数値カーネルをCPUで実装する。
汎用画像ライブラリ・新依存・GPU/NDKの導入には広げない。
Pillowの著作権・許諾表示は`app/src/main/assets/third_party/Pillow-LICENSE.txt`に保持し、APKにも含める。

`export_android_blur.py`はPCのblurとPillowから公開可能な合成CSVのみを生成する。
各ケースの入力、縮小寸法、BOX中間出力、BILINEAR復元出力を全画素で検証する。RGB独立性は3チャンネルの異なる入力で別途確認する。
fixture変更時もPC CIが走るようpathsへ追加する。既存の保護段golden・正本・版・既定強度0.5を変更しない。

このPRはplan境界Cの**ぼかし共通基盤のみ**。glow/mist/Orton等の移植、R7、DNG、全Stage Clearの強度別一致、
表示/全解像度での端末時間・メモリと撮影応答は引き続き未検証とする。

## 次の小縦切り: 段1（にじみ）

`StageClearGlow.render(base, strength=0.5)`はR7 base相当の線形sRGBを受け取り、段1だけを返す。
`StageClearBlur`でブルーム（.006/.02/.06の重み付き合成）、ハレーション、肌色保護の重み、ディフューズを計算する。
PCのGLOW設定・半径・合成比・暖色tintは`export_android_glow.py`から`StageClearGlowV1.kt`へ生成する。
保護段で使っていたOKLabの行列計算は`StageClearColor`へ移し、同じ順序・丸めを使う。既存goldenで変更がないことを確認する。
色によるskin weight（`r7b_skin.skin_weight`）と、その`.01`ぼかしは別に照合する。検出顔の有無を仮定しない。

`stage_clear.render`は強度0でglowを呼ばずbaseを保持する。この境界に合わせ、0では不要なぼかしを実行しない。
強度0.5と1の段1出力を、既存の合成ステージ、縦横の細長い入力、暗面/黒/しきい値、負値/1超、端の点光源、1画素入力で全画素比較する。
長辺191の入力で最小半径.006も実際に縮小されることを保つ。合成fixtureはPC正本から再照合し、生成物だけの変更でもPC CIを走らせる。

この段の出力を段2〜5へ渡すのは後続。顔減光時にはglowedを係数倍して段2〜6を再実行する既存契約を保ち、glowを再実行しない。
全ルックのrendererを完成したようなAPI名や撮影フックは追加しない。
[保存契約](../016-raw-capture-lifecycle/contracts.md)との境界として、後続decodeは共有領域の単一原本DNGへの読み取り専用URIを参照する。
URIの権限・寿命・cancel/解放はdecodeアダプター側で扱い、純粋な画素処理へI/Oや恒久的DNGコピーを持ち込まない。
仮JPEGは内部、完成JPEGの共有公開は保存側。今回URI層や保存型は追加しない。

## 次の小縦切り: 段2（ブラックミストと自動強度）

`StageClearMist.render(glowed, skinWeight, strength=0.5)`はブラックミスト適用後の画像と、
後続Ortonでも再利用する`lightAddingStrength = auto_strength(glowed) * strength`を返す。
ブラックミスト単体の境界もPCと照合し、sourceの輝度重み→.025/.07ぼかし→肌色保護→.32の追加を維持する。
`export_android_mist.py`がPCの関数内定数・NEAR_WHITEから`StageClearMistV1.kt`と合成fixtureを生成する。

自動強度の面積判定は`r10h_refine.near_white_fraction`の実コードを正とする。符号化した表示値ではなく、
線形の`values @ LUMA > .9`である。初期のPC仕様表の「表示値」という説明から実装を推測して変更しない。
厳密な比較とFloat32輝度計算を合成境界で検証し、epsilonやしきい値変更で合わせない。
肌色重みはR7 baseからの`StageClearGlow.skinWeight(base)`を渡す。顔減光時にもこの重みを保持し、
scaled glowedで自動強度と段2以降を再実行する。段2出力から自動強度を再計算するとPCと異なるため、後続には結果の値を渡す。

合成fixtureは強度0/.5/1についてbase、肌色重み、PCの段1出力、ブラックミスト単体、段2出力と自動強度を持つ。
段2単体の比較とKotlin段1→2の累積比較を分け、既存保護/ぼかし/段1goldenも維持する。
段3以降の組立て、URI/decode、永続状態、MediaStore、撮影の開始条件には手を入れない。

## 性能と画質の判断順序

最終目標は数値の完全一致ではなく、PCとの全体の雰囲気・印象をそろえること。
現行方式で一通りつないだ後、Pixel 9aの性能と、標準API/ライブラリ置換による画像変化を比較してメリデメを判断する。
既存goldenは移植中の回帰検出として維持するが、永続的な精度制約・最終画質合格にはしない。
[性能検討](research.md)にcopy/中間配列の棚卸し、コード量と可逆圧縮、既存API候補・測定手順を整理する。
容量計算は実測peakではない。今回、新依存・置換実装・測定入口は追加しない。

## 次の小縦切り: 段3（Orton）

`StageClearOrton.render(mistResult, originalSkinWeight)`は段2の画像とlightAddingStrengthを組で受ける。
自動強度は再計算せず、元baseの肌色重みを維持する。顔減光時にもscaled glowedから段2/3を再実行する契約は変えない。
PCのdisplay域へのclamp付きsRGB転送→明るい合成→.012ぼかし→表示輝度.75〜.95でのfade→線形へ復帰、
1超チャンネルの復元→OKLabで肌の彩度増加抑制→段2と同じ量で混合を移植する。
Orton内のclampは正本の意図的な境界であり、入力負値が全工程でそのまま保持されるという意味ではない。
既存shoulderのclampしない転送関数とは分離する。小さいOKLab scratchを再利用し、フル画像のOKLab配列は作らない。

PCから定数と合成fixtureを生成する。11場面×強度0/.5/1で単体と段1→3を全画素比較し、
明部fade・肌・HDR・負値・入力保持・強度の受渡しも確認する。
新fixtureは標準gzipで可逆圧縮し、PC/Python・JVMの標準ライブラリで展開する。再照合は解凍後の同じ画素値を対象とし、
縮小・ケース削除で行数を減らさない。PC CIのpathsにも定数とgzip fixtureを追加する。
段4/5・全renderer、DNG/R7、保存URI、実機の画質・時間・peakは後続として区別する。

### 固定goldenへのPC再照合

Orton単体/累積出力のPC再生成も、SC-010の既存2e-5（rtol=0）で同じ固定goldenと比較する。
入力列（base/skin/misted）は従来2e-6を維持し、段2は独立exportでも検証する。定数/metadata/形状と全画素比較を維持し、非有限値は拒否する。
従来のPC出力検査2e-6より緩くなるため、2e-6超〜2e-5以内のPC再生成差に対する検出力は下がる。
これは意図的な検証境界の整合であり、golden画素・Kotlin境界・製品処理を変えない。独自ULP基準やCPU固定は導入しない。
runnerによる末尾差の原因は未断定。失敗runと最大差・採否根拠はPRへ記録する。NumPyの[CPU dispatch](https://numpy.org/doc/stable/reference/simd/build-options.html)も参照。
PCとKotlinが各々固定goldenに2e-5以内でも、両者を直接比較した差が必ず2e-5以内になるという保証ではない。
これらは暫定の移植回帰であり、最終の雰囲気/印象や実機性能の受入条件ではない。

## 既存合成fixtureの可逆圧縮（#98、移植と別PR）

既存4 CSVの作業ツリー上の改行ではなく、Git blob bytesをgzipへ変換する。解凍後のbyte/SHA-256一致を検証する。
Pythonはgzip.compress(mtime=0)/decompress、JVMはGZIPInputStreamで同じデータを読み、全画素の照合方法・境界は維持する。
exporter --writeの一時出力を展開して生成textと照合し、既存check_exportsも通す。生成textはUTF-8/LF。
.gitattributesで生成CSV/gzipをlinguist-generatedとして扱い、gzipはbinaryにする。定数や手書きコードは折りたたまない。
PC CI pathsはgzip resourceと属性を監視する。ケース削除・寸法縮小・新依存・runtime変更は含めない。
#96で追加したOrton gzipは変更せず、既存4件を別Draft PRで検証する。mainへのmergeは別承認。
