# Stage Clearのprivate JPEG生成（#125）

## 利用者の目的

共有DNGを原本として保持し、撮影・編集制御が固定した要求から、保存経路が検査できるJPEGを生成する。
512pxの試験Bitmapを完成出力として流用しない。顔・露出の権威と公開操作は別担当の責務を維持する。

## 要件

- FR-001: 要求はcaptureId/generation/共有原本URI・hash/固定recipeとする。出力targetは同じcaptureId/generationに属するprivate attemptで、create-only writerとreadback能力だけを渡す。原本/仮JPEG/共有領域を変更しない。
- FR-002: v1はLibRawのas-shot暫定線形sRGB、長辺上限1024の専用縮小JPEGとする。full-res/Pixel profile一致を名乗らず、input/controlsの契約ID・上限・強度・qualityをrecipeへ記録する。既定強度0.5/quality95。実寸法はcodec検査値を返す。
- FR-003: 外部providerが同じdecode画像へ用意したfoundation ROI/face mask/有限EVを使う。顔NOT_RUN/FAILED、露出DIAGNOSTIC/UNKNOWN、寸法不一致・矛盾した顔状態はwriter作成前に拒否する。自動的なゼロ顔・診断EVのSUPPORTED化をしない。
- FR-004: R7→StageClearLook→線形からsRGB転送1回→標準Bitmap JPEG codecを使う。追加ARGB配列は1行とBitmapだけ。入力Exif/GPS/機種名をコピーせず、物理向き適用済みpixelsを出力する。Exif未設定/1を検査でorientation1に正規化する。
- FR-005: 原本を開始前/完了直前に再照合。JPEGはcodec成功、writerのclose成功、実readback/codec検査、write時SHA-256/bytesとの一致を確認後だけCompletedRenderを返す。符号化上限8MiB。部分ファイルは完了・公開しない。
- FR-006: 開始・各計算境界・行変換・stream read/writeで協調停止し、成功を返さない。kernel/codec内の即時停止を保証しない。失敗closeのhandleを保持し、解放確認まで新実行を拒否する。releaseはclose再試行のみ。
- FR-007: production契約登録・世代永続化・admission・UI保存有効化・MediaStore公開を行わない。公開側は#118の顔/露出/登録検査を維持する。呼出側がrenderer/target/原本照合と公開結果を分ける。

## 受入と検証

- SC-001: 合成入力でJPEG実decode/sRGB/Exif normal相当・寸法・source不変、1024上限を確認する。Pixel画質/性能合格ではない。
- SC-002: pure fault testで未知入力、codec/byte上限/close/readback/世代不一致/中断を失敗とし、保持した参照の解放前に次の実行を始めない。
- SC-003: lint/unit/bothABI app/test APK、隔離合成AVD、最終head CI/canonical/独立レビュー/thread0/保護を確認する。

実機撮影・CameraX/Compose操作・full-resolution/tile/GPU、W1/顔backend/AE/Pixel profileの新実装、JPEG公開・新権限/依存・配布は対象外。
実機残件は#78/#89で維持し、未確認を成功にしない。OOMをlargeHeap/強制GC/黙示縮小で隠さない。
