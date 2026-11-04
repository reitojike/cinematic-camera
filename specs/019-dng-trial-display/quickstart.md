# 検証

## 機械的確認

- 既存lint/unit/debug APKとtest APKをbuildする。nativeはarm64-v8a/x86_64の両方を対象にする。
- `TrialDisplayPixelsTest`で標準sRGB転送・clip・原画素不変・寸法制限・中断と、資源開始policy・旧token解放の隔離を確認する。
- 合成テスト用のAPI37 emulatorで `dev.cinematic.p0.photoroll.DngTrialRendererTest` だけを明示実行する。
  写真ではない32x24 Bayer DNGをtest所有のpending MediaStore行へ生成し、ContentResolver→native→Bitmapと原本hashを確認する。
  行は公開せず、自分が当該テストで作ったURIだけをfinallyで削除する。実機へ自動インストールしない。

## 本人操作の実機確認（未確認）

1. 許可した破棄可能な端末内DNGを左下→一覧またはFilesの内部ストレージから選ぶ。
2. 64 MiB以下・16MP以下の対応DNGで原本確認→DNG処理→「試験表示・ルック未適用」と実画像を確認する。
   暫定の色/明るさ・R7/Stage Clear/顔検出/PC互換未完成を画質合格にしない。
3. 一覧へ戻る、別DNG、Filesキャンセル、背景化/復帰、回転/Activity再生成、処理中Back→撮影を確認する。
4. 不明なcamera availability・高温・低メモリでは理由が出て開始せず、解消後の再試行を確認する。
5. 端末で向き/画角・時間/観測peak/GC/熱・撮影応答、原本不変と恒久コピー/JPEG公開なしを確認する。
   原本の外部削除・改変・権限失効時に表示を停止することも確認する。

camera availabilityは確認時点の観測であり、他アプリの後発取得や非協調read/decodeの即時停止までは保証しない。
emulatorの合成成功とPixel実機の合格、表示とJPEG書き出しを区別する。
