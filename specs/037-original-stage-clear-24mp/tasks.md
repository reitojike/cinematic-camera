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

## 原寸性能の限定後続（Issue #164）

- [x] mask-only countの0/49/50・0.5・不正値/取消・後段RGB破損拒否と画像回帰を確認する。
- [x] 拡大行cacheのbitwise/端/HDR/1px/任意順/並行読込・payloadを検証し、旧式と同条件で測る。
- [x] trace有無の全画素一致、primary failureとclose保持、固定段階の完了/失敗記録を検証する。
- [x] 合成12MP/24MPの段階別時間とmicrobenchmarkを測り、改善効果・支配段階・実機未確認を区別する。
- [x] 最終headで公開検査・lint/unit/両ABI build・CI/canonical/独立レビューを行い、条件を満たして通常mergeする。

## bloom fieldの共有走査（Issue #167）

- [x] 最大3本/追加payload上限と事前fallbackを備える共有grid集計を実装し、単独reduceの式を共用する。
- [x] field/復元行のraw-bit一致、HDR/端/倍率と走査/project回数を検証する。
- [x] 追加payload・取消・短い/不正raster・project/close失敗と部分成功の禁止を検証する。
- [x] 固定条件のkernel交互測定とbaseline/candidate全段測定を行い、効果/画像/観測メモリを区別する。
- [ ] 最終headの公開検査・lint/unit/両ABI/CI/canonical/独立レビューを確認し、採否と通常merge/main CIを記録する。

## Foundationの限定採用（Issue #78 / #185）

- [x] 通常原寸save callerだけFoundation=2を明示し、下位API/診断baseline=1とGlow=1を維持する。
- [x] 下位既定serial・明示2・Glow serial、stateful非空ROIの順序、既存bit一致/取消/例外/owner回帰を確認する。
- [ ] 実factoryの合成保存JPEGと明示1 workerの同一recipe出力を照合し、原本不変・同じURI再照合を確認する。
- [ ] 公開差分検査、最終headのlint/unit/build、CI/canonical/独立レビューで限定採用を判断する。
- [ ] Pixelの通常手動保存・FGS・撮影復帰で原本保持と同じ結果の表示を確認する。
