# 埋め込み JPEG 抽出部品のホスト試験

Refs #241。固定 LibRaw 0.22.3 に対する C++ 関数と単体試験。
Android の CMake / Gradle / JNI / 画面からは参照していない。製品の初回表示や RAW 展開は変更しない。
新しい依存を製品へ追加せず、リポジトリの公式アーカイブを hash 照合して使う。
CDDL の選択とソース提供は `../../third_party/libraw/README.md` に従う。

## 契約

- 呼出元が不変の snapshot と生存期間を所有する。64MiB を超える入力は拒否する。
- `open_buffer` → `thumbs_list` → `unpack_thumb_ex` のみ。RAW `unpack()` / `dcraw_process()`、
  再圧縮、`dcraw_make_mem_thumb`、RAW からの代替サムネイル生成は行わない。
- JPEG 候補の offset/length、8MiB、4096各辺かつ4,000,000画素、JPEG SOF の寸法を確認する。
  有効な候補で画素数が最大のものを選び、同数なら先の候補を選ぶ。不正候補しかなければ Invalid、
  未対応候補しかなければ Unsupported、候補なしなら Absent。存在する JPEG の entropy decode は行わない。
- 固定版の `unpack_thumb` は先頭2byteを `FF D8` に上書きする。元入力で SOI を確認し、
  抽出後に元snapshotの該当範囲との全byte一致も確認する。暗黙の修復や EXIF 追加は許可しない。
- 返す JPEG は未回転。選択した thumbnail の tflip を EXIF 1〜8 に変換し、元の classic TIFF の
  同じ offset/length の IFD に明示された妥当な Orientation と照合する。LibRaw は欠落や不正値を
  valid-looking tflip へ丸めるため、tflip 単独では向きの証拠にしない。
  TIFF の next/SubIFD は最大64個・各4096entryまで確認し、循環・曖昧さ・非TIFF形式では Unknown。
  JPEG IFD0 の Orientation が不正・重複・矛盾する場合や tflip 不明時は Unknown。
  RAW 全体の向きや DefaultCrop は流用しない。向きの適用や画像 decode は行わない。
- 各 LibRaw 呼出しの前後とコピー前後で取消を確認し、LibRaw progress callback も使用する。
  opaque 呼出し中の即時取消は保証しない。LibRaw と出力は RAII で解放し、例外を型付き結果へ変換する。

## 実行

```text
cmake -S tools/embedded-jpeg -B build/embedded-jpeg
cmake --build build/embedded-jpeg --config Debug --parallel 2
ctest --test-dir build/embedded-jpeg -C Debug --output-on-failure
```

Windows は Visual Studio の C++ Build Tools と CMake、Linux は C++17 コンパイラと CMake を使用する。
ホスト試験は JPEG/zlib 等の外部 codec を有効にしないため、Android の USE_ZLIB build と同一構成ではない。
固定された単色16×8 JPEG の byte 配列と、実行時生成 TIFF/DNG だけを使う。個人写真は含まない。
寸法境界試験は JPEG SOF の寸法も変更した header fixture を使い、entropy decode の成立は主張しない。
`synthetic_jpeg.h` は RGB(40,80,120) の単色画像を標準 JPEG encoder で生成した fixture。
encoder の byte 再現性ではなく、保持した fixture の抽出前後一致を検証する。

## 製品接続前の残件

この部品は #241 の設計全体を完成させるものではない。読取専用 FD の bounded pread、
実際に抽出する snapshot の fingerprint 照合、重い入力処理との共有 admission、JNI コピー、
Android BitmapFactory bounds/decode、向き適用と世代棄却、実際の内部確保量の検証は別途必要。
入力64MiB＋JPEG8MiB＋返却8MiBという算術値を LibRaw 内部確保込みの peak とみなさない。
巨大/壊れたメタデータに対する総確保上限、Android上の取消/解放、実機性能はこのホスト試験で保証しない。
これらが確認されるまで製品へ接続しない。AVD・実機・Release はこの試験では操作しない。
