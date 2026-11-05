# 完了境界

- [x] 24MP優先の利用者AC、既存main/保存/編集の担当範囲を確認する。
- [x] 小型で行R7/全Lookと既存全画面の中間・最終出力を比較する。
- [x] 小型合成DNGでnative RGB16の寸法・整数crop・向き1/6・全行・原本hash不変を確認する。
- [x] 原寸R7/Look→標準JPEG生成・同じ内容の再読込をつなぐ。
- [x] 書込・close・readback改変・取消・workspace/寸法/資源gateの故障保護をunit/合成試験で確認する。
- [x] PO承認の試験品質手動保存を独立proofとし、同じ原本・世代・強度・warmth/EVへ固定する。
- [x] 専用AVDの小型合成入力で実factory→Before/After→実MediaStore手動保存→同じURI/hash再読込を確認する。
- [x] 私有JPEG生成の12MP/24MP合成縦断を測定し、実機・UI・共有保存・最終headの24MP検証と区別する。
- [ ] 画面統合headでActivity再生成・結果不明時の再挿入禁止・撮影復帰を通して確認する。
- [ ] 公開検査、lint/unit/build、Draft PR・CI・canonical/独立レビューを最終統合headで行う。
- [ ] Pixelで選択→調整→原寸JPEG保存→同じ結果の表示を確認し、画質・時間・メモリを評価する。
- [ ] 他の向き・実カメラDNG/profile・24MP実手動保存を追加検証する。未検証の互換・画質を合格としない。

合成試験は原本不変と処理・保存の接続を確認するもので、Pixelの画質・性能や顔保護の完成証明ではない。
数値比較は回帰検出に使い、見た目の方向性と資源・応答性を含めCPU改善/GPU候補を比較する。
