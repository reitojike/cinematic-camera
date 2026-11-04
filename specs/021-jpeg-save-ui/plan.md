# 実装・検証

PhotoRollJpegSave.prepareで#118のrequirePublishableと原本/modeを検証してからfactoryを呼ぶ。Android adapterはAndroidRenderedJpegOperation.get(...).publisher(...)を1回だけ取得し、そのownerを返す。rendererが返却ownerを要求ごとに保持し、retryごとにfactoryを呼ばない。isCurrentは永続要求/世代と撮影優先条件の権威を実統合側が供給する必要があり、常時trueをproductionへ登録しない。

UIはPreviewの任意ownerを受け取るが、現行両trial rendererはnullのまま。#118のproduction契約は登録しない。保存中/公開確認済み/未確認/HOLDを表示し、再照合済みPUBLISHEDだけにACTION_VIEWを許す。これは完成rendererへの接続準備であり、実保存の有効化ではない。

表示BitmapをJPEGへ圧縮するコードはUIへ追加しない。完成rendererの登録時には、private JPEGがその出力品質/解像度契約を満たすことを別途確認する。出力寸法やcodec成功だけではPixel画質合格やフル解像度を保証しない。

unitではpublisher生成前のtrial/顔/露出/未登録/原本拒否、応答喪失後の同owner再照合、UNKNOWN停止、外部削除後の成功表示取消、途中停止/旧世代の結果不採用を確認。既存#118の公開・codec・故障試験を再実装しない。lint/unit/app/test APK、最終CI/canonical/独立レビューを取得する。実機の保存・復帰・外部viewer・性能は未確認。
