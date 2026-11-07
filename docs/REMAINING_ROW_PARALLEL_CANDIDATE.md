# 残る行並列化の比較候補（Issue #78）

## 範囲と履歴照合

#78の履歴一覧・コメント、既存Glow診断、Mist共有走査試作、R7行並列とtone係数再利用を先に照合した。
本候補はR7 tone適用とMistの最終合成行だけを、既存の有界2workerへ委譲する。
Mistの2半径blurは個別生成のまま。停止済みblurMany共有走査案は再実装・再測定しない。
Glow2は既存比較を利用する対象であり、今回変更しない。Foundation・Orton・Characterも変更しない。
近似計算、段間融合、GPU、製品factoryの設定変更は含まない。

## 設計

- parallelToneWorkers と parallelMistWorkers はそれぞれ既定1、明示2だけを追加する。
- tone解析・percentileは従来の順序で完了してから適用する。Float丸め、RGBで共有するpow係数、出力式を維持する。
- Mist field生成と自動強度の解析順は不変。顔減光でlayerが再実行される場合も同じ並列条件を使う。
- OriginalField.row の既存instance lockを維持する。出力行はworker専有、読み書きは既存executorのordered I/O。
- 2worker/最大4 in-flight行。取消・例外は既存workspace ownerが終了を待つ。終了を確認できないworkerはownerに残す。
- workerは開始/終了と64pixelごとに停止・割込みを確認する。外部mask callbackの実行条件・順序は変えない。
- 全面配列・新executor実装・独自pool・新SDKは追加しない。

## メモリの見込み

幅Wに対し、専有補助Float配列のpayloadはtoneで2×W×4 bytes、Mistで2×(2×3+1)×W×4 bytes。
これはそれぞれ並列worker2本の合計であり、serialはtone1行、Mist7行。tone解析用の既存行は別に残る。
共通executorの最大4組の入力/出力RGB行を保守的に数えるとさらに96W bytes。
読取・書込buffer、field補間cache、配列header、executor、GC未回収分、native/JPEGはこの数字に含まない。
実測managed/native/PSSのpeak上限やメモリ削減の証明ではない。

## 確認と採用条件

OriginalRemainingRowsTest は基準1/1、tone2のみ、Mist2のみ、両方2を比較する。
横長/非整数寸法/1pxの合成HDR・負値、強度0/0.5/1、外部顔maskと顔減光後の再適用を含め、
通知された各段・最終rasterをraw-bitで照合し、取消とworker例外後のowner回収を検査する。
既存goldenは変更しない。変更が必要になったら変更前に報告して停止する。

この候補は既定serialのまま。JVM成功だけで速度改善や製品採用としない。
Android上で同APKの個別arm、各arm warm-up、AB/BA反復、全体/対象段を区別し、
samplerなしtimingと別memory観測、合成JPEG一致、原本不変、取消/回収を確認して採否を判断する。
GMDは共通枠の予約と単独高負荷実行規則に従う。AVD値を実機へ外挿しない。
差分・取消/回収不成立・OOM・速度根拠不足なら採用を止め、数値やgoldenで隠さない。

## 合成Android試験の境界

OriginalRemainingRowsAndroidTest は隔離original24Probe/ATDで256×192の私有Float入力を使う。
現行通常保存と同じFoundation2・Orton4・Character2・Glow1を固定し、tone/Mistの4条件を比較する。
各条件1回warm-up後、正順/逆順/正順で各3回を測り、別のsampler付き試行でメモリを観測する。
時間はkernel renderだけで、raw-bit hash、テスト専用8bit変換/JPEG、入力照合とcleanupを含まない。
同じ最終rasterのテストJPEG一致は確認するが、DNG decode/実factory/公開JPEGの一致へ読み替えない。
取消・故障と再試行を検査し、実機速度・瞬間peak・撮影復帰は未確認のまま残す。

## 限定評価後の判断

[Issue #78の結果](https://github.com/reitojike/cinematic-camera/issues/78#issuecomment-6709302793)に条件と元観測値を記録した。
小型合成では全armのraw-bit/テストJPEG一致と取消・故障回収を確認したが、対象段の安定した改善は確認できなかった。
256×192・各3回中央値はtone適用45.619→52.866ms、Mist全段120.914→240.203ms（それぞれ単独2worker）。
小型debug/ATDの結果を原寸/実機の改善・悪化へ外挿せず、既定serialを維持する。
追加重計測・製品採用・PR公開は現段階で見送り、比較候補と限定証拠だけをローカル保存する。
再開する場合は最新実機の支配段階、今回不足した条件、同じ案を再測定する理由を先に確認する。


RAW4一括診断ではこの既定off候補を再利用する（docs/RAW_DIAGNOSTIC_BUILD.md）。
小型単独試験の結果を本採用へ変更せず、固定した正逆10%基準で当該条件の採否を区切る。
