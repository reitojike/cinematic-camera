# half_size 候補の境界・画質比較

#233 / #241 / #436 / #437 の後続です。**試験コードだけ**で、製品の FULL 既定や画面は変えません。
合成画像の比較は、SIGMA fp などの実写画質・Pixel の速度確認の代わりにはなりません。

## 比較するもの

同じ生成 Bayer DNG・同じ調整設定で、次の3つを並べます。

1. **現行 full プレビュー**：原寸展開 → 既存 BOX 縮小 → 製品 GPU プレビュー。
2. **half 候補**：明示の HALF_CANDIDATE → 既存 BOX 縮小 → 同じ製品 GPU プレビュー。
3. **full 保存 JPEG**：原寸展開 → 製品と同じ解析縮小・自動露出 → `Original24GpuOutput` → JPEG95。
   公開処理・保存状態・EXIFの書き換えは使わず、合成試験の私有ファイルだけへ書きます。

プレビュー2種は、それぞれの入力から製品どおり自動露出を計算します。保存も現行保存と同じ入力から計算します。
したがって、自動露出の差も含めた候補の見え方です。3者の base EV は JSON に残します。
JPEG は一覧用に表示寸法へ Android の bilinear で縮小します。原寸JPEG・PNGも残します。

- 5条件：階調・色、細線、暗部とスポットライト（EV −2）、高彩度境界、奇数寸法の縦向き。
- 一覧PNGは実際の出力を並べたもの。上段は全体、下段は中央160×80画素を2倍で表示します。
- RGB8 MAE / p99 / 最大差 / 差がある画素率を、half対現行・現行対保存・half対保存で別々に記録します。
- 数値だけで合格とはしません。細線のつぶれ・偽色、色境界、明部/暗部の破綻、全体の明るさを目視します。
- 元々存在するプレビューと保存の差と、halfによる追加の差を分けて判断します。

## 境界試験

`HalfPreviewBoundaryAndroidTest` は24MP、half長辺1023/1024/1025、奇数寸法、全8方向、画素数上限を確認します。
期待寸法は製品fit関数を使わず算出し、全値有限・原本fingerprint不変・所有者と一時ファイルの解放を検証します。
24MPは追加の `halfPreview24Mp=true` が必要です。時間・メモリの性能評価ではありません。

## 実行

承認済みの合成専用 AOSP AVDを通常のwrapperで起動し、boot・system_server・ANR成立条件を確認してから実行します。
実機は除外。個人写真は使いません。独立worktree・対象serial指定・通常debug/test APKを使用します。

```text
adb -s <emulator> shell am instrument -w -r -e isolated true -e gpuInputSafety true -e halfPreview24Mp true -e class dev.cinematic.p0.jpegrender.original.HalfPreviewBoundaryAndroidTest dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
adb -s <emulator> shell am instrument -w -r -e isolated true -e gpuInputSafety true -e halfPreviewComparison true -e halfPreviewRunId <unique-run> -e halfPreviewSourceCommit <clean-40-character-head> -e class dev.cinematic.p0.jpegrender.original.HalfPreviewComparisonAndroidTest dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
```

成果物はアプリ外部私有領域の `half-preview-review/<unique-run>`。既存runは上書きしません。
manifestは完了までincomplete。失敗時の一時ファイルは消さず、試験と所有者の状態を確認します。
ホスト側でもhead、APK hash、instance証跡と実行前後の環境成立条件を保存してください。
画像manifestには生成元hash、PNG/JPEG hash、run/head、Android/GL環境を含めます。

## 提示と採用

合成画像だけをPRの Files changed に置き、スマホ向け一覧と代表画像を本人が確認できるようにします。
確認用画像は採用基準ではありません。不要な一覧・代表画像は確認後、マージ前に外します。
画質の本人判断と必要な回帰が済む前に、halfの製品既定化・基準画像更新は行いません。
