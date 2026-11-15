# 初回案内表示の製品接続境界（実装途中）

Refs #233 / #241。基準 main は #440 の `1ca820a6`。PPG 比較 #438 / #441 の採否を待たず、正確なプレビューは AHD を維持して進める。half は案内表示の候補であり、編集用入力の置換ではない。以下はマージ済みの動作を宣言するものではない。

## 担当と描画先

初回表示側は原本・JNI・プレビュー入口・photoroll の案内表示を担当する。D1 側の `gpu/StageClearGpu.kt` と `gpu/preview/GpuPreviewRenderer.kt` / `GpuPreviewSurface.kt` は変更しない。

案内画像は `GpuPhotoRollImage` の Compose Bitmap overlay に置く案。固定 SurfaceView は背面で生存させ、正確な GPU 表示の準備・受付・描画を妨げない。GPU の画素処理や present に案内像を流さない。撮影時の画像は「撮影時の案内画像・調整は未反映」と区別し、照合済み編集像はその旨を示す。保存・比較・編集の有効条件は exact のまま。

正確な要求値に対応する presented が観測された時点で案内像を不可逆に退場させる。選択・再試行・背景化・trim・失敗でも表示を無効化する。同一写真でも新しい ticket で旧 callback を拒否する。GPU が先着した後に遅い案内像を出さない。SurfaceView の Z-order、開閉時の Fit、原子的な切替、Compose の draw 完了と Bitmap recycle の寿命は接続段階の実走項目であり、現時点で合格とはしない。

## 候補の実態（優先順）

| 候補 | 既存の正本 | 現時点と未接続範囲 |
| --- | --- | --- |
| 保存済みキャッシュ | `PhotoRollPreviewCache` / key は原本・全編集値等を保持 | 旧 CPU Bitmap 用。GPU exact の供給元がないため、新GPU読戻しなしでは現行製品の有効hitを生成しない。順番だけ定義して完成としない |
| 保存済み JPEG | manual request の source/全recipe、publication journal の render/fingerprint、同一 `EncodedJpegSnapshot` | 読取専用lookup未実装。save queueへ待機する `restorePreparedGpu`、prepare/advance/publisher は利用しない。世代/recipe/原本とJPEG fingerprintを照合し、前後のjournalを再確認。曖昧/不整合はskip |
| 埋込 JPEG | #410 の抽出器。元SOI・元IFDの明示向き根拠を保持 | JNIとAndroid有界decodeの基盤を実装中。共有admission/原本再検証/表示は未接続。LibRaw内部確保の上限未確定が製品接続の停止条件 |
| 半解像度 RAW | 既存 `Original24PreviewSession(HALF_CANDIDATE)` / `NativeOriginalRaster.decodeHalfPreview` | 部品あり。現行AHD exact前に逐次実行するとexact開始を遅らせ、並行実行は既存単一admissionとメモリに反するため未接続。無条件fallbackはしない |

保存済み画像は同じ原本 fingerprint、全編集値と canonical recipe、向き/cropを含む原本geometryが一致した場合だけ採用する。元の画像と違う範囲を表す埋込像を、縦横比が同じという理由だけで同じ画角と認定しない。

## 第1段階の基盤

- `NativeEmbeddedJpeg` は caller所有の read-only regular FD を `pread` する。64MiB以下の単一native snapshotを作り、同じメモリのSHA-256と期待fingerprintを照合してから既存抽出器を呼ぶ。別open・入力FDのclose/seekはしない。
- 既存 LibRaw `raw` target を再利用。dng_decoder.cpp、AHD設定、保存入力・原寸出力、GPUファイルは不変。#438/#441の比較APIを製品へ混ぜない。
- snapshot/LibRawを解放してから、8MiB以下・各辺4096以下・4MP以下のJPEGを返す。未知向きは採用不可。抽出器内にRAW fallbackなし。
- `InitialPreviewJpeg` はsRGB JPEGに限定。ImageDecoderの最大64px検証decodeでpartial imageを拒否し、BitmapFactoryのsample decodeで最大1024pxにする。EXIF1〜8を一度だけ適用。decode/回転Bitmapの上限は各4MiB、同時最大8MiB（codec内部領域・encoded snapshotは別）。原寸Bitmapを作らない。
- `InitialPreviewPolicy` / `InitialPreviewGate` は候補優先順、identity/recipe適合、exact先着、ticket更新・取消の純粋な境界のみ。保存・公開・正確な入力への権限を与えない。

### 未解決の接続条件

LibRaw `open_buffer` / `unpack_thumb_ex` の内部確保総量は、このwrapperの64MiB/8MiB上限では保証できない。opaque呼出しの即時取消・deadlineも保証しない。既存 #241 の停止条件を維持し、上限確認・異常入力検証と採用判断前に製品経路へ自動接続しない。native呼出しが実際に終了するまで共有admissionを解放しない。

half案内表示は、先行候補なし時も無条件の二重decodeを追加しない。候補APIの有無だけで高速化と呼ばず、同一snapshotを再利用できる境界、逐次halfがexact開始へ加える時間、native/RGB16/縮小配列/Bitmapの重なりと単一受付枠を測って判断する。時間の上限を外側のtimeoutだけで保証したことにしない。PPGの将来差替はdecode戦略境界で扱い、採用前はAHD/既存full保存不変。

## 検証の段階

1. 基盤：純粋なidentity/recipe/priority/late/exact試験、合成JNIの向き・fingerprint・FD・取消・absent/不正、JPEGの8向き実画素・寸法・上限・partial decode拒否を確認。
2. 接続：原本/世代/recipe不一致、exact先着、選択連打・戻る・再試行・背景化・trim・native中取消・解放失敗を通す。現行の入力/保存/復旧gateを緩めない。
3. 最終：lint/unit/build、GPU90条件・帯比較、Android安全回帰、表示比較と本人承認、最終CI/canonical/独立レビュー。実写真・Pixel・配布は別許可。未実行を成功にしない。

新しい番号付きspec一式を重複生成せず、既存IssueとLiving Specを正本とする。標準plan skillの適用範囲を確認したがsetup-plan等の生成コマンドは実行していない。
