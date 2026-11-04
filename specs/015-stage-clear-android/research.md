# 性能検証の入口と既存API候補（#78）

## 現在の判断

最終目標はPC版との**全体の雰囲気・印象**をそろえること。まず現行方式で各段をつなぎ、
その後Pixel 9aの性能と、標準API/ライブラリへの置換時の画像変化を比較して採否を判断する。
厳密goldenは現在の移植で意図しない変化を検出する暫定の回帰基準であり、永続的な仕様制約や最終画質の合格基準ではない。
数値の完全一致を理由に標準実装を除外したり、不要な精度・独自実装を固定化したりしない。

段2までのmain `8e15604`と今回の段3 Ortonを対象に整理する。フル解像度の時間・ピークメモリ・
Pixel 9aの撮影応答は**未測定**。小さい合成検証で現行方式を進め、全体接続後の比較判断とは分ける。
新依存・描画API・manifest・署名や実機操作は追加しない。

## コード量とfixture容量（性能実測ではない）

#88/#91/#92/#93/#94の追加45,184行のうち42,973行（約95%）は合成golden CSV。
main `8e15604`の4 CSVは計4,425,831 bytesで、標準gzipでは1,011,423 bytesとなり、解凍後のbyte一致を確認した。
これはGit blobを対象とした容量比較で、APKサイズや実行時メモリ/時間の測定ではない。
fixtureの行数と、製品実行時の全フレーム配列の問題は分ける。

今回のOrton fixtureは全33ケース/12,540画素をgzipで保存する。標準ライブラリで解凍して同じ全画素を検証し、
縮小やケース削除による削減はしない。長辺191など、境界や小半径の効果を成立させる寸法を維持する。
既存4 CSVの形式変更はこの段には含めない。

## 配列の棚卸し（実測ではない）

計算用の12MPを4000×3000とする。実DNGの寸法をこの値と仮定しない。オブジェクトヘッダー・
アラインメント・係数表・decode/カメラ/Bitmap/graphics bufferは下の値に含めない。

| ペイロード | 計算 | bytes / MiB |
|---|---|---|
| Float32 RGB 1枚 | N×3×4 | 144,000,000 / 137.33 |
| Float32平面またはmask 1枚 | N×4 | 48,000,000 / 45.78 |
| 全画素が顔の場合のDouble百分位配列 | N×8 | 96,000,000 / 91.55 |

| 現行箇所 | 確認した割当/コピー |
|---|---|
| `LinearSrgbImage` / `FloatPlane` / `FaceMask` | constructorは入力をcopy、copyPixels/copyValues/copyWeightsもcopy |
| `StageClearBlur.rgb` | RGB出力、各色の平面抽出、平面のcopy、BOX/復元の軸出力、RGBへ再格納と最後のcopy |
| `StageClearGlow` | source/sourceImage、bloom、excess/halation/skin/protect、glowed/soft/最終出力等 |
| `StageClearMist` | source/sourceImage、halo、mist出力、混合出力と各imageのcopy |
| `StageClearOrton` | encoded/bright/soft/mixed、candidate、OKLab変換後/混合出力と各imageのcopy。OKLab用scratchは画素ごとに作らず再利用 |
| `StageClearProtections.faceCut` | 顔画素数分のDoubleArrayとin-place sort。顔が少ない場合と全顔stressを分ける |

根拠は`app/src/main/java/dev/cinematic/p0/raw/`の該当関数。上記は割当箇所と単一配列の容量であり、
合計を足して「同時生存量」や「実測peak」にしない。参照の寿命、JIT、GCで生存期間は変わる。
一方、互換性のための防御的copyをそのまま12MPの製品実装として採用できる証拠もない。

## 標準API・既存実装の候補

| 候補 | 利用できるもの / v1比較の課題 | 今回の扱い |
|---|---|---|
| [Bitmap scaling](https://developer.android.com/reference/android/graphics/Bitmap#createScaledBitmap(android.graphics.Bitmap,int,int,boolean)) | filter=trueはbilinear。v1はBOX縮小＋bilinear復元で、BOX/座標/端/float丸めが同じとは言えない | 小goldenで検証する候補。置換実装なし |
| [RGBA_F16](https://developer.android.com/reference/android/graphics/Bitmap.Config#RGBA_F16) | 1画素8 bytes、各色は半精度。Float32線形RGBの精度・負値/1超・転送/alphaを別確認 | 格納形式候補。数値差と印象/性能を評価する |
| [RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect#createBlurEffect(float,float,android.graphics.Shader.TileMode)) | API31のblur/chain。指定radius/edge処理がv1のBOX/BILINEARと一致する保証はない。出力読戻しも比較が必要 | 製品CPU経路の代替として未採用 |
| [RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader) | API33。描画先の色管理を受ける。setInputBufferは色変換/alpha処理を避ける入力手段だが、格納精度・出力読戻し・画面全体の集計は別課題 | 候補。既存標準APIを調査し、自作GPU基盤を先行追加しない |
| [RenderScript置換Toolkit](https://developer.android.com/guide/topics/renderscript/migrate) | 公式移行例のblurはBitmap/byte array。[README](https://github.com/android/renderscript-intrinsics-replacement-toolkit)でもfloat対応やin-place出力は未対応の項目。v1のfloat経路と同一ではない | 新依存/NDKとして無断導入しない。非推奨RenderScriptへ新規移植しない |
| [BitmapCompat](https://developer.android.com/reference/androidx/core/graphics/BitmapCompat#createScaledBitmap(android.graphics.Bitmap,int,int,android.graphics.Rect,boolean)) / [OpenCV INTER_AREA](https://docs.opencv.org/4.x/da/d54/group__imgproc__transform.html) | 線形拡縮/F16や面積補間を利用できる候補。Float32 Pillow BOXと数値契約は異なる。Androidの色管理/HDR、依存容量/ライセンスを確認する | 独立調査の候補。導入/速度測定なし |
| 現CPU経路の所有権/作業buffer整理 | 精度を変えずに中間copyやRGB/plane往復を減らせる可能性。ただしimmutable入力、同時要求、cancel後の参照寿命を保つ必要がある | 割当profile後の小さい候補。unsafeな共有scratchを先行導入しない |

精度上の算術例として、Float32の0.7は0.699999988079071、binary16へ丸めると0.7001953125となり、
差は約1.95e-4。これはAndroid APIの実測ではなく、数値が変わり得るという算術例で、F16の不採用理由ではない。
候補は端・点光源・負値/1超・skin・強度0/.5/1の既存全画素goldenで**差を把握**し、
同じ場面の全体/肌/明部の印象、HDRや色管理、Pixel性能、依存/保守/ライセンスを併せて判断する。
現行回帰テストの失敗を黙って閾値緩和で隠さず、意図的な変更は差と採否理由をレビュー可能にする。
現在の2e-5をすべての将来候補へ永続的に課すものではない。

## 測定方法（手順案。まだ実行できる測定入口はない）

現行APKの画素モジュールは撮影にも測定UIにも未接続。まず既存の計測ツールを使う最小の呼出入口を選び、
入力/出力を消費する範囲と段境界を固定する。今回、その入口・新benchmark依存・profileable設定は追加しない。
[Jetpack Microbenchmark](https://developer.android.com/topic/performance/benchmarking/microbenchmark-overview)はwarmup・時間・割当数を扱う既存候補だが、
先にprofileし、単発12MPを無条件に反復させない。依存追加を決めずに独自benchmark基盤を作らない。
既存RAW作業、実写真、端末の撮影データを負荷試験へ巻き込まない。

1. **差を把握**: 現goldenを回帰確認に使い、候補の最大絶対誤差・該当画素と画像の印象を確認する。
   大画像の比較用expected/actualを端末のtimed区間で二重保持しない。大型合成の全画素比較は別の非計時runで行う。
2. **入力/範囲を分離**: 決定的な合成入力で、640×480→1920×1080→4000×3000の順に単発で成立確認する。
   段1〜3の単体/累積、保護段を区別する。未実装の全Stage Clearを測定済みとしない。
   入力生成/コピー、kernel、出力消費を分け、最終的には全体も測る。DNG decodeとJPEG/共有公開は後続の別区間。
3. **時間run**: 同一SHA/ビルド設定/OS・ABI/入力/強度/温度・電源条件でcold 1回とwarm 5回を別記録する。
   coldは測定用process起動後の当該kernel初回呼出とし、JIT/AOT状態も記録する。GPU cacheまでcoldとは仮定しない。
   warmの前に選定した既存基盤のwarmup条件を固定し、条件/回数を記録する。
   初回単発が失敗したサイズを反復しない。全run値・中央値・最大値を残す。合成入力/検証/ファイル保存をkernel時間へ混ぜず、全体時間も隠さない。
   単調時計[elapsedRealtimeNanos](https://developer.android.com/reference/android/os/SystemClock#elapsedRealtimeNanos())と
   [system trace](https://developer.android.com/topic/performance/tracing)で区間、CPU、GC、待ちを確認する。
   GPU候補はsubmit完了だけを終端にせず、出力が利用可能になる完了/同期・readbackまでの境界を固定する。
   upload・kernel・readbackの区間とend-to-endを別記録し、CPUとの比較条件をそろえる。
4. **割当runは別**: [Android Studio Profiler](https://developer.android.com/studio/profile)を使い、
   時間の比較は低overheadなprofileable/release相当、Java/Kotlinの割当追跡やheap dumpはdebuggableで分ける。
   [全割当の記録は実行を遅くし得る](https://developer.android.com/studio/profile/record-java-kotlin-allocations)ため、その時間を通常性能と混ぜない。
5. **メモリの定義**: Java/Kotlin live heap・割当総量・GC、native/graphics、process RSS/PSSを混同せず記録する。
   [heap dump](https://developer.android.com/studio/profile/capture-heap-dump)は時点の保持関係であり、取得自体もメモリを増やし得る。
   [native/graphics profiling](https://developer.android.com/topic/performance/memory/guide/native-memory)も必要に応じ別採取する。
   sampledな最大値は「観測peak」とし、取り逃がし/計測overheadを明記する。前後2点の差をpeakにしない。
6. **撮影応答は別**: kernelの速さだけでは撮影優先を証明できない。接続後に本人の許可した操作で、撮影要求から
   処理停止/参照解放までの遅延、preview/保存への影響、連続時の熱とメモリを確認する。policy unitの成功を実測にしない。

タイル/分割を試す場合も元画像全体の座標・半径・端・丸め・顔全体の百分位を維持し、
タイルごとの半径/percentileへ変えない。OOM/ANR、非有限値や意図しない画像破綻、原本や撮影への影響があれば該当候補を停止する。数値差のみで候補を棄却しない。
`largeHeap`、強制GC、縮小だけで問題を隠して12MP合格とはしない。

## 採否と未取得証拠

| 判定項目 | 状態 |
|---|---|
| 段1/2・ぼかし・保護の合成精度 | 既存CIの証拠。段3は今回の回帰検証。性能/最終画質の証拠ではない |
| ホスト/Pixelの12MP時間・観測peak、OOM/GC/熱 | 未測定。今回12MP負荷を実行していない |
| 標準API/ライブラリ候補の画像変化・速度 | 未測定。採用なし |
| 測定入口・時間/メモリ/撮影応答の受入予算 | 未選定/未合意。測定値と独立調査を揃えて別途判断 |

次は現行方式の段4/5・保護の組立てを小さく進める。全体接続後に最小の測定入口と比較対象を選び、
まずcopy/中間配列の観測peakを優先して調べる。新依存・算法変更・撮影操作の採用をこの文書だけで決めない。
実測未取得のままフル解像度や撮影接続を合格にしない。
