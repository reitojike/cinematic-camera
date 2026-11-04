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
| B R7土台 | 線形sRGB→R7確定の色・明るさ・肌 | 未移植。一般共通値と非公開の写真別露出・例外を分ける。後者を公開しない |
| C 段1〜5 | R7 base→glow→mist/Orton/vignette/vibrance | 未移植。Pillow BOX/bilinearと座標・丸めの一致を先に調べる。顔減光時の再計算境界だけ今回用意 |
| D 段6〜8 | C出力→顔cap・顔減光→shoulder | 今回の全画素合成golden、90百分位はNumPy既定のlinear補間、faces > .5・50画素以上 |
| E 出力 | display-referred linear sRGB→gamut/transfer/量子化 | 未実装。shoulderはdisplay輝度で計算するが出力は線形RGB。8bit画像の比較と混同しない |
| F 端末 | 実DNG・表示用/全解像度 | Pixel 9a未測定。PC並列比較、時間、peak memory、撮影中キャンセル/再開等の応答確認を別途実施 |

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
