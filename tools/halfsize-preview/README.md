# half_size の crop・向き確認（ホスト合成試験のみ）

#233 / #241 の縮小展開を製品へ接続する前に、既存の原本 geometry 検査を再利用できる範囲を確認する。
Android 製品・JNI・画面・保存・基準画像は変更しない。本ツールの LibRaw ビルドはホスト試験専用であり、
アプリに組み込む際は既存の `raw` target を再利用する。ライブラリを追加同梱しない。

## 実行

POSIX ホスト、CMake 3.22+、C++17、zlib 開発用ライブラリが必要。AVD・実機・個人写真は不要。

```sh
cmake -S tools/halfsize-preview -B build/halfsize-preview -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_CXX_FLAGS='-fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer' \
  -DCMAKE_EXE_LINKER_FLAGS='-fsanitize=address,undefined'
cmake --build build/halfsize-preview --parallel 2
ctest --test-dir build/halfsize-preview --output-on-failure
```

## 確認範囲

アプリと同じ固定 LibRaw 0.22.3（アーカイブ SHA 検証、USE_ZLIB、OpenMPなし）と、変更していない
`OriginalRasterSink::parse / applyCrop / preflightGeometry` をリンクする。
合成 Bayer DNG は既存 `SyntheticCropDng.kt` と同じ非撮影信号を生成する。

- 4 CFA × 8 向き（鏡像含む）× crop の x/y 各2偶奇 × crop 幅/高さ各2偶奇 = **512 条件**。
- 非零 ActiveArea/DefaultCrop からの half 結果と、同じ信号を独立した crop-only DNG にした half 結果の全 RGB16 画素一致。
- 向き1の出力へ独立した EXIF 座標変換を適用した結果と、向き1〜8の出力の全画素一致。
- half 出力寸法が crop 各辺の `ceil(size/2)`（回転後）であること。
- 同じ入力の full 出力寸法と現行 full geometry 検査が維持されること。
- 入力 snapshot のバイト列不変。

## 接続前に分離すべき契約

LibRaw の `half_size=1` では `dcraw_process` 後の `sizes.width/height` 自体が半分になる。
したがって、full 用 `validateProcessedGeometry` は half 結果を拒否する。これは full の保存契約にとって正しい。
試験では **half 専用の後条件**（crop origin維持、各辺のceil半分、flip維持）を別に確認する。
製品接続時も full の検査を緩めず、入力geometry・出力mode・それぞれの後条件を分ける必要がある。
`OriginalRasterSink::write/report` は原寸専用のままとする。

これは crop/向きの限定した実現性確認であり、half と full の画素一致を要求・証明しない。
GPU処理後の色/細部・自動調整値、full保存JPEGとの見た目、実機速度/ピークメモリ、取消/所有/世代の
Android接続、非整数crop、不正入力全般、SIGMA fp埋込JPEG寸法は未確認。
本人の目視確認前に half を製品既定へ切り替えない。
