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

## 原寸の限定性能改善（Issue #164）

画素式・Float丸め位置を保ち、顔画素countの不要なRGB通読と拡大時の重複横補間を省く。
maskは全行を検証して取消を受け付け、0.5/50画素の判定・顔p90/再計算を変えない。
通常の拡大だけ2/4行の有界cacheを使い、1px軸・軸順切替等は従来経路を維持する。
原寸・プレビューの画像方式や品質契約、原本保全、共有保存、撮影優先は変更しない。
opt-in合成診断で段階別の単調時計時間を記録する。固定段階名・時間・区間正常終了だけを扱い、
写真/URI/path/写真別設定をイベントへ入れない。区間の完了は現像・公開の完成証明ではない。

## bloom fieldの共有走査（Issue #167）

原寸Glowの3本のbloomは、同じ入力行と同じprojectionから独立した縮小fieldを作るため、
通常縮小では走査とprojectionを共有してよい。各fieldの係数・積和順・Float丸め・順序は維持する。
共有は最大3本とし、既存accum上限と追加Double payload 512KiBを超える場合や、
1px軸・小入力・軸順切替等は走査前に従来の順次経路を選ぶ。途中失敗を再走査で隠さない。
原本・cancel・close・全画素処理・品質表示と保存gateは変えない。採否は同条件の実測で判断する。

## Foundationの2 worker採用（Issue #78 / #185）

通常の原寸手動保存だけ、statelessな明示空ROIのFoundationを2 workerで処理する。
下位renderer/attempt APIの既定と診断の比較baselineは1のまま、Glowも製品1を維持する。
外部ROI callbackは値が全てゼロでもcaller上で順次実行し、callbackのthread-safetyを仮定しない。
画素式・演算順・全行の出力順、取消/失敗時のowner保持と保存coordinator・journal・公開gateは変えない。
同一入力の診断JPEG一致を通常保存の実機合格へ読み替えず、手動保存・FGS・撮影復帰は別途確認する。
