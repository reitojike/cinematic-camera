# 高解像度化に向けた中間バッファ（#78）

## 要件と境界

- MEM-001: 外部入力を受ける `LinearSrgbImage` / `FloatPlane` のconstructorとcopy取得は防御コピーを維持する。kernelが新規生成した排他的所有の配列だけを、再複製せずに内部画像へ移譲できる。移譲後の配列へ書き込まない。形状・有限値検査を省かない。
- MEM-002: R7/Stage Clearの演算・順序・強度・色空間・負値/HDR・golden許容差・停止境界を変えない。別画像/別呼出しへの共有scratchや独自pool、largeHeap、強制GC、黙示縮小を追加しない。
- MEM-003: 標準Runtime/Debug APIで同じ合成入力を変更前後に実行し、経過時間・ART累積割当/GC差分・sampled managed heap/PSSを記録する。処理単体の測定とDNG→JPEGの縦断試験を区別する。sampled値を真のpeakや端末性能の保証にしない。
- MEM-004: 1024/1536/2048の負荷試験は明示opt-in、専用エミュレータ、1件ずつ、同じ通常heapで行う。OOM/失敗は記録し、既定1024を自動的に変更しない。全解像度・顔検出・W1/Pixel profile採用・UI/保存契約は別判断とする。

## 設計と代案

Kotlin標準FloatArrayの所有を内部factoryで移譲する。外部境界の不変性を保ちながら、各kernelが完成させた配列の二重コピーを除去する。途中で同じ配列を変更するglowの最初の画像化など、snapshotが必要な箇所はコピーを残す。glowの光加算と拡散、Ortonの混合と肌chroma保護の計算frameも分け、それぞれ前半だけで使う大きな一時配列を後半まで保持しない。回収時期の保証はせず、効果は同条件の実測で判断する。新しい公開mutable配列getterは作らない。

Bitmap/標準補間への置換はFloat32の負値/HDRと演算を変え、顔SDKは別の容量/通信判断を必要とする。本変更は既存計算を保つ小さいメモリ削減とし、その後の解像度判断の材料を作る。数値完全一致は最終製品目標ではなく、今回は意図しない画素変更の検出に既存goldenを使う。

測定対象は既に線形sRGBへなった合成配列→R7→Stage Clearの処理単体。アプリの実条件、native DNG/codec、Compose、撮影復帰、release版の最適化とPixelの時間・熱・メモリは別に確認する。入力はHDR/負値を含む固定texture、ROIは0.2、顔maskは明示ゼロ（検出未実行の現在の試験経路相当）。顔ありでの減光再実行はこの負荷測定に含めない。入力・mask/ROI生成と結果hashは時間/割当区間外。samplerの実行負荷は区間に含まれ、A/Bの環境差・JIT/GC変動も残る。

API根拠: [Debug](https://developer.android.com/reference/android/os/Debug)、[MemoryInfo](https://developer.android.com/reference/android/os/Debug.MemoryInfo)、[ART GC](https://source.android.com/docs/core/runtime/gc-debug)。

## 再現手順

通常のdebug app/test APKを同じ専用AVDへinstallし、各サイズを独立したinstrumentation processで実行する。`$validationSerial`は当人が所有する使い捨てエミュレータだけを指す。`isolated=true`に加えてHARDWAREも検査し、物理端末を受け付けない。サイズは1024/1536/2048を明示する。flagなしではこの測定はskipする。

```powershell
adb -s $validationSerial shell am instrument -w -e isolated true -e stageClearLoad true -e edge 1024 -e class dev.cinematic.p0.raw.StageClearLoadTest dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
```

warmup 1回も含む計4回のJSONを記録する。本計測は残り3回、ART counterは差分、heap/PSSはsampling最大値、時間はsamplerを含む経過時間。結果hashも比較する。入力寸法を変えた自動retryや失敗のPASS化はしない。OOM時はJUnit失敗として残し、完走した既定経路の回帰と区別する。

## Tasks

- [x] MEM-T001 変更前の同一合成測定を保存し、kernelの配列所有を点検する。
- [x] MEM-T002 防御コピー/不正値拒否/入力不変を維持して所有移譲を実装し、既存全段goldenと停止の回帰を検証する。
- [x] MEM-T003 同一heapで変更後の負荷とDNG→private JPEG縦断を検証し、OOM/未測定を区別する。
- [ ] MEM-T004 公開検査、最終CI・canonical・独立レビューをDraft PRへ記録し、merge承認を確認する。承認前は保留とし、最終headの証拠はPRで確認する。
