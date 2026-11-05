# 実装計画

新raw/original package、独立native原寸RGB16 FD出力、行単位のFloat32私有raster、標準Bitmap JPEG圧縮を接続する。
既存R7点演算とPillow係数を再利用し、Look性能担当のアルゴリズム置換を重複実装しない。
global log-luma/blur/肌重み・段2/3の自動強度・顔減光再計算・最終shoulderの依存を維持する。
percentileはFloat bit radixの2-pass選択とDouble線形補間で全面sort配列を避ける。
scratchは2GiB、同時生存面とdisk/managed/native/Bitmap/codecを別予算として検証する。新codec依存や製品largeHeapを先行採用しない。

まず小型の全段・中間結果を既存実装と比較し、nativeのcrop/向き/色・全行、故障/取消を試験する。
その後12MP/24MPへ同じ経路を拡大する。原寸JPEG artifact→保存owner→同じ結果の表示までをMVPの完了境界にする。
native/重いビルド・実機は他担当の診断と重ねず、未実施を明記する。

## UIとの境界

`AndroidManualOriginalTrialSaves.renderPreview(context, source, strength, conditions, adjustments, shouldStop)`
は既存queueの`CompletableFuture<ManualOriginalTrialPreview>`を返す。
deliveryの`save`は同一recipe/世代の`ManualOriginalTrialHandle`、`claimBitmap()`は独立software sRGB表示を一度だけ移譲する。
`claimComparisonBitmap()`は向き/寸法が同じ別所有のBeforeを移譲し、`comparisonDescription`で撮影時WBのRAW基準表示
（R7・ルック・手動調整なし、暫定色）と明示する。撮影時JPEGと混同しない。
未受領deliveryは`close()`で両方を解放する。既存16MP private rendererへ24MPを通して代用しない。
BeforeのBitmap確保/変換だけのOOMでは、原本・close・取消確認に成功すれば比較を省略しAfterと保存handleを残す。
その場合`claimComparisonBitmap()`と`comparisonDescription`はnullとなり、UIで比較不可を案内する。

`prepare(...)`だけでも同じ原本・強度・全調整値のretained handleへ冪等に戻るが、JPEG生成や共有行insertはしない。
header preflightの寸法は`handle.geometry.outputWidth/outputHeight`であり、完成画素の証拠ではない。
handleは`request`、`appliedStrength`、`appliedAdjustments`、`state`、`matches(source)`を公開する。
手動保存・同じ共有結果の再確認は`advance(freshConditions, shouldStop)`を呼び、同じURI/同じhashを既存publisherで再検査する。
raw ownerを取り出してqueue/admissionを迂回する入口は公開しない。

UIはMANUAL_TRIAL専用bridgeで全設定・原本・世代を照合し、縮小プレビュー／原寸は保存時生成・顔保護なし・暫定色を表示する。
保存中や結果不明時に編集を進めず、UNKNOWN/HOLDから別insertを始めない。
Future取消だけをclose確認としない。保存取消時は`handle.requestStop()`、renderer/lifecycle破棄時は`discardPreview(context)`を呼ぶ。
個々の旧previewの遅延取消からglobal discardを呼ばず、その呼出しの`shouldStop`で世代を照合する。
capture/条件不成立はnative取消とcache epoch無効化を行い、実資源close後だけpermitを返す。

縮小previewはAS_SHOT1024とR7 base一つを保持し、強度のみの変更ではbaseを再利用する。
warmth/EV変更はR7を更新する。未変更AS_SHOTで診断測光→相対WB→R7の外部EVへuser EV加算の順を共用する。
原寸saveは同じ値を行単位で適用し、表示用Bitmapの拡大で作らない。

## 置換可能な境界

数値比較は回帰検出であり、PCとのbit一致は製品合格条件ではない。
`OriginalFields`の再構成/ぼかし、`OriginalStageClear24`の点演算/行passは独立して置換できる。
現行はメモリ節約のため全面Floatを私有ファイルへ退避するため、pass間の読書き・rowコピーの時間も測定対象とする。
LibRaw出力RGB16と標準JPEG圧縮用Bitmapは全面native bufferが残る。managed/native/PSS/一時保存領域を分けて測る。
標準RenderEffect/HardwareRendererやGPU候補は、見た目・線形/HDR保持・コピー/同期・メモリ・端末時間を比較して採否を決める。
標準APIへの変更だけで高速化や画質互換を保証しない。

## 原寸の段階別計測と重複処理削減（Issue #164）

顔画素countは幅/高さとmaskだけで全行を検査する。RGB検証はp90/最終shoulderの実読込で維持し、
不正・短い・非finite rasterを成功出力としない。50到達で打ち切らず後方の不正maskも検出する。
補間fieldはimmutable値を所有し、横方向のDouble積和→FloatだけをLINEAR最大2行/CUBIC最大4行保持する。
縦方向のdy順・Float丸めを維持し、任意行順・巻戻し・並行読込をinstance lockで保護する。
tone25×17→6000×4000の1chでは追加Float payloadは最大96,000bytes。field数やchannel数を含む総量とは区別する。
全出力planeやfield値の複製は追加せず、cache対象外の軸順/縮小/1pxを従来経路へ戻す。

nullableな固定enum observerを既存original attempt/行rendererへ渡す。未接続では時計・event生成をしない。
各段の処理失敗をprimaryとし、observer失敗はそれを隠さない。ログはopt-inの合成診断私有JSONだけへ記録する。
native区間はLibRaw読込/処理/RGB16書込を含む一括区間で、内部の個別時間を推測しない。
R7、skin field、Glow、near-white、Mist、Orton、Character、顔count/p90、shoulder、Bitmap、JPEG、
再読込、原本再照合、close/cleanupを分ける。再実行の同名区間は別レコードとし、observer IOや未計測処理の時間を合算へ紛れ込ませない。

mask-only/旧RGB通読、横補間cache/旧行式のmicrobenchmarkは同じ入力・交互順で測り、処理全体やPixelへ換算しない。
全体合成12MP/24MPは同じnative/原寸/JPEG/再読込の経路で段階別に測り、旧縦断値との環境差・samplingを明記する。
プレビューfield再利用/GPUの担当範囲と重複させず、利益が実測できない変更は採用しない。

### 診断の再現入口

`-PcinematicOriginal24Probe=true` の独立application ID `dev.cinematic.p0.original24probe` と専用エミュレータを使う。
通常の撮影アプリへ診断APKを上書きせず、AGP・ABI・AVD設定をbaseline/candidateで揃え、各APK hashを固定・記録する。
各instrumentationは新しい32桁lowercase hexの`runId`を要求し、私有`files/`の同じIDのJSONだけを回収する。
`status=success`と`complete=true`、instrumentation成功を照合し、途中レポートを成功扱いにしない。

- `OriginalFieldRowBenchmarkTest`: `originalFieldRowBenchmark=true`。25×17 CUBIC fieldを6000×4000へ各3回走査し、
  旧式/再利用を交互順で比較する。全画素raw-bit checksum、wall/thread CPU時間、追加payloadを記録する。
- `OriginalMaskCountBenchmarkTest`: `original24=true`、`maskCountSize=12MP|24MP|BOTH`、
  `maskCountMask=NONE|MIXED|BOTH`。同じ合成raster/maskで各3組を交互順に比較する。
  論理RGB読込量は物理ディスクのcold-read量を意味しない。
- `Original24PipelineTest`: `original24=true`、`original24Stages=true`、`size=SMALL|12MP|24MP`。
  native原寸処理・全段Look・JPEG圧縮・同じJPEGの検査と再decodeを含む。UI表示/共有公開の時間は含まない。

全クラスのpackageは`dev.cinematic.p0.jpegrender.original`、runnerは`androidx.test.runner.AndroidJUnitRunner`。
fixture作成/原本識別は全体timerより前であり、sampler負荷は全体時間に含む。段階合計と全体時間は同値を要求しない。
メモリ値はsampling間隔とmanaged/native/PSSを併記し、瞬間的な真のpeakや他端末の上限を保証しない。

## bloom fieldの共有走査（Issue #167）

`OriginalFields.reduce`の係数/Contributor/accumとaccept/finishを小さなgrid stateへ移し、単独reduceでも共用する。
`blurMany`はBLOOMの同channels/common projectの最大3本だけを同じ`input.rows`で集計する。
各gridのdx積和→Float→yのDouble加算と最後のFloat化を維持し、field自体や全面planeを複製しない。
halationはchannels/projectが異なるため別走査のままとする。

通常の両軸strict縮小かつhorizontal-firstだけを共有し、1px/拡大/同寸法/vertical-firstは順次blurへ戻す。
全accum要素は既存16M以下、sum-maxの追加Double payloadは512KiB以下とし、超過は走査開始前に判断する。
6000×4000のBLOOM3本はDouble accum合計488,976 bytes、最大単体との差44,088 bytesを見込む。
係数/Contributor/field/row cache等の保持は別であり、managed/native/PSSも測る。
cancelをgrid accept/finish前に確認し、input.rowsがcloseを確認して戻った後だけ結果を返す。
処理・project・read/closeの失敗から順次経路へretryせず、部分結果を公開しない。

まず旧順次blurとのraw-bit一致、callback/走査回数、容量fallback、取消/失敗をunitで検証する。
同APKの交互microbenchmarkでfield生成時間/CPU・payload・観測メモリを比較し、
全体は同じ合成DNG SHA/強度・AGP/JDK/ABI/debug flags/AVD設定をそろえたbaseline/candidateで測る。
全体とkernelの差、host/JIT/sampling状態、測定source/APK hashを記録する。
条件が異なる旧#166全体値をA/B結果として流用せず、画像差や資源・取消の問題、利益が乏しい場合は採用しない。
