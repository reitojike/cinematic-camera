# AsShotWhiteXY の限定互換入力（#137）

## 目的と受入境界

[Issue #137](https://github.com/reitojike/cinematic-camera/issues/137) は、AsShotNeutral がなく
AsShotWhiteXY がある DNG を、メタデータに基づく撮影時 WB で試験表示する修正。
既存 AS_SHOT の有効な camera WB と W1 の計算・対応範囲を維持する。
検査を削除して自動 WB に任せず、未対応・曖昧・数値的に危険な入力は UNSUPPORTED。
マージと APK 配布はこの作業の承認に含めない。

- camera WB が不成立の場合だけ、Neutral 不在・WhiteXY 存在を要求する。
- IFD0 の主プロファイルの CM1/CM2 と既知の illuminant1/2 が揃い、最初の二つの一方が D65 の場合に限定する。
  単一 CM、D65 のない組、複数 IFD の競合、ExtraCameraProfiles は拒否する。
- AB は正値の対角、CC は省略時単位行列。非単位 CC は二つの calibration signature が完全一致する場合だけ使う。
  両 signature の省略は空文字同士の一致。非単位 CC の signature 不一致は、LibRaw が CC を無条件に使うため拒否する。
- 第三 illuminant の存在は主プロファイルの先頭二つを使う旧版互換として明示する。
  第三の色変換や W1 対応を意味しない。W1 は引き続き第三 profile と非単位 CC/AB を拒否する。
- ColorimetricReference は省略または scene-referred の0のみ。出力基準の1/2・未知値は拒否する。
  AsShotProfileName が非空なら、同じ IFD0 の主 ProfileName と完全一致を要求する。
  外部 profile 名しかない場合や不一致は拒否する。これらの文字列も signature と同じ境界で検査する。
- 固定サイズの LibRaw callback で型・個数・IFD・重複・offset/size・rational 分母・有限値を検査する。
  signature は ASCII/BYTE、終端 NUL を含め最大256 bytesの正しい UTF-8 のみ。
  例外は callback 内で受け止める。独自 TIFF scanner と元画像の書換えは追加しない。

## 計算と LibRaw の接続

WhiteXY を XYZ（Y=1）へ変換する。Colour v0.4.6 の Robertson CIE 1960 数値表から相関色温度を求め、
既知 illuminant の逆色温度で重みを決める。
対応コードと温度は A(17)=2850、D55(20)=5500、D65(21)=6500、D75(22)=7500、D50(23)=5000K。
各端点で `Mi = diag(AB) * CCi * CMi` を作り、`M = g*M1 + (1-g)*M2` としてから
`neutral = M * XYZwhite` を計算する。要求 user WB は `neutralG/neutralR, 1, neutralG/neutralB`。
有限・正値・非特異と Float32 変換後の係数を検査する。1700〜15000K の範囲と表内の交差を要求する。
これは未正規化の raw tag に基づく限定 WB。Adobe SDK の CM 正規化・丸めを含む WB 全体一致とは扱わない。
W1 の +22 mired は適用しない。数値表以外の Colour 関数・NumPy・Adobe SDK source/runtime は取り込まない。

LibRaw 0.22.3 は TIFF 処理で WhiteXY を cmatrix に折り込み、その後、二つの CM と illuminant が
同じ IFD にあると D65 の AB/CC/CM から cmatrix と pre_mul を作り直す。
従って最終 pre_mul を WhiteXY の係数と見なさない。D65 端点から再構成した正規化 RGB 行列が
実際の `rgb_cam` と `2e-5 * (1+abs(reference))` 以内で一致することを確認して後者の分岐を保証する。
単一 CM 等の WhiteXY 折込み分岐へ
user WB を重ねることはしない。同じ CM/CC/AB の AsShotNeutral 合成参照と画素を比較する。

互換経路では `use_camera_wb=0`、`use_auto_wb=0`、検査済み user_mul を指定する。
unpack 後に既存 W1 と同じ黒・白・係数比・RGB 行列の符号付き整数演算上限を検査する。
キャンセル・メモリ失敗をメタデータ失敗で覆わず、次の要求も実行可能にする。

## 結果の意味

品質は引き続き AS_SHOT_PRELIMINARY。別の型付き WB basis に WhiteXY の先頭二つを使う互換経路を記録し、
要求 user WB、第三 illuminant の有無、FM/Look/OpcodeList2 の観測を返す。
従来経路で未観測のタグは UNKNOWN のまま。OpcodeList2 の存在から GainMap を推定しない。
Pixel profile、第三 illuminant の変換、ForwardMatrix、LookTable、GainMap の適用済みとは扱わない。
出力の色変換自体は LibRaw の preliminary sRGB のままであり Adobe 全体一致や実機画質の保証ではない。
表の出典・固定版・BSD-3 の通知は [third_party/dng_temperature](../../third_party/dng_temperature/README.md) を参照する。

## 根拠と検証タスク

- [Adobe DNG 1.7.1 specification](https://helpx.adobe.com/content/dam/help/en/camera-raw/digital-negative/jcr_content/root/content/flex/items/position/position-par/download_section_733958301/download-1/DNG_Spec_1_7_1_0.pdf)
  の WhiteXY、calibration signature、色変換、先頭二つの後方互換を参照する。
- 固定 LibRaw の `metadata/tiff.cpp`、`metadata/identify.cpp`、`utils/utils_dcraw.cpp`、
  `postprocessing/postprocessing_utils_dcrdefs.cpp` が実際の接続の根拠。保持 upstream source は変更しない。
- [x] XY01 型・入力境界・色変換分岐・数値計算を固定する。
- [x] XY02 限定 callback と WB、immutable provenance を実装する。
- [x] XY03 非写真の合成 DNG で WB oracle、非単位 CC/AB、signature、第三 profile、既存 WB/W1、
  不正値、cancel、再実行、原本 hash 不変を検証する。
- [x] XY04 lint/unit/両 ABI app/test APK、合成 native 実行、依存・export・通知・16KB 境界を確認する。
- [ ] XY05 最終 head の CI・canonical・独立レビューを確認する。実写真/Pixel の性能は未検証として残す。
