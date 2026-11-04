# W1だけを適用するDNG試験入力（Issue #114）

## 契約と対象範囲

既定は従来の撮影時WB。`DngWhiteBalanceMode.W1_ONLY_TRIAL`を明示したときだけ、DNGの白色点から
PC公開コードのW1（+22mired、tint保持）を計算してLibRawのuser WBへ渡す。UI・撮影・保存へ接続しない。
出力は`W1_ONLY_PRELIMINARY`であり、R7土台済み・Pixel profile適用済み・PC画像一致とは呼ばない。

- 固定LibRaw 0.22.3の`set_exifparser_handler`を使う。独自TIFF走査、LibRaw patch、新依存は追加しない。
- 白色点はIFD0のAsShotWhiteXYまたはAsShotNeutralのどちらか1つ。ColorMatrix1と既知のCalibrationIlluminant1が必要。
  ColorMatrix2とIlluminant2は対で任意。単灯は同じmatrixを使い、二灯はPCどおり逆CCTで補間する。
  対応灯種はPCの17/20/21/22/23。AB/CCは欠落時のDNG既定identityまたは明示identityだけを受け付ける。
- 使用するタグの型・個数・IFD・重複・offset/stream範囲・分母・有限性を検査する。IFD0以外の同名coreタグ、
  三灯/追加profile、非identity AB/CC、特異/数値不安定matrix、白色点の不成立はnative固定`DNG_W1_METADATA`失敗（Kotlinでは既存`UNSUPPORTED`へ対応）。
  LibRawのopen自体のcancel/memory/入力失敗は既存の失敗を優先する。機種名やLibRawのfallback値から欠落を補わず、W1失敗を撮影時WBへ黙って戻さない。
- `r9_pixel.py`/`r6_diagnose.py`のPlanck近似、4000点mired探索（1700〜15000K）、tint法線を使用する。
  Robertsonへの置換はしない。探索の端点が最小となる白色点、+22後が1700〜15000K外、反復不収束は拒否する。
  単に端へclampした値を対応済みとして返さない。matrixのpivotは最大要素の1e-12以下を拒否する。
- W1では`use_camera_matrix=1`をopen前に明示し、計算したR/G/B/Gを`user_mul`へ設定する。
  `use_camera_wb=0`/`use_auto_wb=0`で上書きとfallbackを防ぐ。通常経路の設定・cam_mul検証は維持する。
- requested user WBのR/G/Bをimmutableな値で返す。これはLibRawが内部正規化した後のscaleではない。
  callbackで観測したFM/LookTable/OpcodeList2の存在を別々に返す。OpcodeList2の存在からGainMapの存在を断定しない。
  通常経路はメタデータを追加検査しないため、存在情報はUNKNOWNとする。
- W1のprocess直前にrawdata.colorを検査する。最大値は16bit以内、black/チャンネル/repeat-gridの保守的合計は最大値未満。
  この合計を引いた最小headroomとWB最大/最小比から、`65535² × 比 / headroom <= INT_MAX/2`を要求する。
  sRGB変換matrixの各行の絶対値和にも`65535 × 和 <= INT_MAX/2`を要求し、LibRawのclip前のint変換を保護する。
  既存のadjust_maximum_thr=0/exp_shift=1/threshold=0を前提とし、WB適用済み入力や不正black-gridは拒否する。

## 保持する制約と未適用の処理

64MiB/16MP/長辺1024、正方画素/単一3色Bayer、read-only snapshot、直列/cancelの契約は[dng-input](dng-input.md)と同じ。
callbackは固定長の値だけを保持し、例外をLibRawの境界へ投げない。IFDの循環やサイズは既存LibRawの解析と入力上限を併用する。
明示W1でも現行の`output_color=1`とhalf-size/16bitを使う。LibRawの色変換はPCのW1後FM補間経路と一致しない。
PCのPixel FM/LookTable/GainMap、crop・全解像度AHD・BaselineExposure等の処理は新実装せず、
存在する入力を受け付けても、部分処理の由来を維持する。実DNGの互換、Pixel画質/性能は未確認。

## 検証とタスク

公開合成metadataをPC関数へ渡してrequested WBを生成し、Androidで同じmetadataの人工DNGを作る。
WB係数は事前定義の`abs 2e-5 + rel 2e-6`で照合する（Float32 user_mul境界の回帰用、画質合格ではない）。
適用経路はCM1/CM2を同一に固定し、requested係数の逆数を別DNGのAsShotNeutralへ符号化して照合する。
色calibrationを固定したWB経路の検査であり、PCの全色処理一致ではない。分母100万の有理数と16bit出力の差だけを`8/65535`以内で検査する。
XY/Neutral、単灯/二灯/順序反転、tint、補間端点、欠落/重複/型/個数/分母/範囲/非有限/特異/IFD/AB/CC/探索域を検証する。
W1係数がcamera WBで上書きされないこと、cam_mul不足でも検証したW1で成功すること、既定出力不変、
FM/Look/OpcodeList2存在時の部分処理由来、原本hash保持・cancel・失敗後再実行を確認する。

- [x] W101 Issue/PC公開コード/LibRaw APIと限定契約を確認する。
- [x] W102 公開合成PC fixtureと再照合を追加する。
- [x] W103 bounded callback・PC WB計算・opt-in/immutable由来を追加する。
- [x] W104 既存native8件とW1合成試験、PC/lint/unit/両ABI/test APK、秘密除外を確認する。
- [ ] W105 最終headの独立レビュー・実CI/canonicalを確認する。公開/mergeは親の承認範囲に従う。

参照: [DNG 1.7.1 Chapter 6](https://helpx.adobe.com/content/dam/help/en/camera-raw/digital-negative/jcr_content/root/content/flex/items/position/position-par/download_section_733958301/download-1/DNG_Spec_1_7_1_0.pdf)、
[LibRaw callback/parameters](https://www.libraw.org/docs/API-datastruct-eng.html)。
