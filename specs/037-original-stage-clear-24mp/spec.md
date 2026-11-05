# 24MP原寸Stage Clearの実動経路

Issue #143 / #78。PO承認の順序は24MPの選択→強度調整→原寸JPEG保存を先に成立させ、最終100MPへ拡大する。
既存保存/編集担当と接続し、契約・部品単体の実装だけを完了としない。

原寸の実有効画素をR7→Stage Clear全段へ渡し、縮小結果の拡大で代用しない。
source fingerprint・版付きrecipe/強度・世代・crop/向き・全画素・同じJPEG readbackを固定する。
24MP RGB Float一面288MBをmanaged heapへ載せず、私有行rasterとglobal解析/blur fieldを使う。
初回原寸入口はinput64MiB、格納32MP/各辺8192、有効出力24MP以下の独立policyとし、既存16MP/1024入口を緩めない。
入力policyは三色単フレームBayer DNG、整数ActiveArea/標準DefaultCrop・正方画素・対応向きに限定し、未知metadataを推測しない。

未確認の顔/露出/profileはNOT_RUN/DIAGNOSTIC/暫定のままとし、CompletedRenderへ付け替えない。
POは試験品質を明示した手動JPEG保存を承認した。専用MANUAL_TRIAL表示・独立proofを使い、自動公開はしない。
縮小プレビューは表示専用で、原寸JPEGは手動保存時だけ生成する。強度・相対色温度・露出EVを同じ版付きrecipe/世代へ固定する。
owner提示前に同じ原本fingerprintへ結び付いたnative metadata preflightで24MP上限を判定する。
通常原本保持・撮影優先・既存owner/台帳/permitを共用し、別queueを作らない。
書込/codec/readback/close/取消/容量/メモリ不足を成功扱いにせず、close不確実な資源と途中物は所有者が保持する。
実機未確認の画質・速度・互換を完成・合格と表示しない。
