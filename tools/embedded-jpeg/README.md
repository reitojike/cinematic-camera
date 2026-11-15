# 埋め込み JPEG 抽出部品のホスト試験

Refs #241。固定 LibRaw 0.22.3 に対する C++ 関数と単体試験。
アプリ JNI も同じ抽出部品を利用する。製品呼出条件と検証状況は
[`INITIAL_PREVIEW_INTEGRATION.md`](../../docs/INITIAL_PREVIEW_INTEGRATION.md) を参照する。
このホスト試験の成功だけで製品採用・表示承認・マージ済みとはしない。
新しい依存を製品へ追加せず、リポジトリの公式アーカイブを hash 照合して使う。
CDDL の選択とソース提供は `../../third_party/libraw/README.md` に従う。

## 契約

- 呼出元が不変の snapshot と生存期間を所有する。64MiB を超える入力は拒否する。
- `open_buffer` → `thumbs_list` → 検査済み snapshot 範囲の直接コピー。
  `unpack_thumb_ex`、RAW `unpack()` / `dcraw_process()`、
  再圧縮、`dcraw_make_mem_thumb`、RAW からの代替サムネイル生成は行わない。
- JPEG 候補の offset/length、8MiB、4096各辺かつ4,000,000画素、JPEG SOF の寸法を確認する。
  既知の未回転 sensor 寸法以下であることも確認し、原本寸法が未知なら Unsupported とする。
  有効な候補で画素数が最大のものを選び、同数なら先の候補を選ぶ。不正候補しかなければ Invalid、
  不適合理由が容量・寸法上限だけなら Absent、未対応候補なら Unsupported、候補なしなら Absent。
  範囲・構造異常を上限超過と混同せず、取消・Memory を成功 miss に変換しない。
- 元入力の SOI・末尾 EOI・SOS までの segment 構造を確認し、検査した範囲をそのまま返す。
  LibRaw thumbnail buffer の確保・読込・SOI 上書きは行わず、コピー間の memcmp も不要となる。
  暗黙の修復や EXIF 追加は許可しない。entropy decode・完全破損検出は行わず、
  SOS 後 entropy 2 byte＋EOI のような入力の完全性を保証しない。新 codec 依存・独自 entropy parser は追加しない。
  Pixel/SIGMA fp の承認済み実データが未取得のため、新しい pixel/byte 比の下限閾値は導入しない。
- constructor flags/progress 取消と `max_raw_memory_mb=512` は現行 Original24 exact/preflight に合わせる。
  旧縮小 NativeDngDecoder の128ではない。この設定は RAW-buffer 政策で、metadata 累積 hard cap ではない。
  抽出器には AsShot WB metadata callback を登録しない。先行 preflight の callback 登録・geometry 検証は
  WB 意味検証済みを意味せず、FULL 側の従来 WB 検証と原本事後照合を維持する。
  同一 snapshot の受渡しと製品条件の詳細は上記統合文書を参照する。
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

この部品のホスト試験は #241 の製品統合全体を検証するものではない。JNI 側の読取専用 FD・
bounded pread・同じ snapshot の fingerprint 照合と、呼出側の共有 admission・世代管理・
Android ImageDecoder・向き適用・取消/解放は、統合試験で別途検証する。
入力64MiB・JPEG8MiB等の明示バッファ上限を LibRaw 内部確保込みのプロセス peak とみなさない。
直接 slice は余分な LibRaw thumbnail buffer をなくすが、open_buffer の metadata 累積 hard cap は保証しない。
旧実装も unpack 呼出前に8MiBを検査しており、既定512MiBをそのまま確保していた意味ではない。
2026-11-15 の本人判断により、旧「総 hard cap 確認まで製品接続禁止」は統合文書記載の
同一 snapshot・限定 open 条件へ更新した。標準 codec が報告するエラーは拒否するが、
回復可能な entropy 警告は成功し得る。旧契約の9成功/1失敗を新条件の合格へ読み替えない。
実機性能、画面比較・本人承認、最終レビューは別途必要。AVD・実機・Release はこのホスト試験では操作しない。

## アプリ統合のビルド境界

#233/#241 の JNI ビルドは、アプリの既存 CMake が定義する LibRaw 0.22.3 の `raw` target を再利用する。
このディレクトリのホスト試験向け独立 LibRaw build をアプリへ追加せず、二重ビルド・二重リンク/同梱を避ける。
既存の `USE_ZLIB` 等の製品設定を維持し、ABI/リンク/依存構成を確認する。half_size のプレビュー候補は抽出器の責務ではなく #233 の別経路であり、本部品内に RAW fallback を追加しない。
