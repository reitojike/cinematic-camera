# 設計・統合境界

PhotoAdjustmentPolicyが単位と値域、PhotoAdjustmentRenderStateが全値の要求・表示照合、
PhotoAdjustmentControlsが既存PhotoEditBarを再利用する。画面は操作終了とlifecycleを管理し、
コールバックで最新stateへ同期反映する。readyToSaveは値の一致のみを表し、公開許可ではない。

JpegGenerationRecipe.trial(strength, PhotoAdjustments(warmth, exposureEv))が値を固定する。
neutral時は旧payloadを維持し、非中立時は既存private envelopeへ版付きadjust/warmth/evを追加する。
復元は完全なcanonical再構築一致を要求する。旧アプリは新payloadを拒否し、黙って無調整にはしない。
このenvelope追加はprivate試験専用であり、公開rendererへの許可を拡張しない。

処理は未変更as-shotを測光→相対色温度→R7（診断EV＋手動EV）→Stage Clear→既存encoder。
相対変換にsRGBガンマやclipを追加しない。原本hashの前後検証とstream所有権は既存処理を維持。
TrialPreparedFoundationとPrivateTrialReuseの両方でadjustmentsを照合する。

## 画面担当への接続

1. PhotoAdjustmentPolicy.scales/defaultsで写真のsessionを開く。
2. changeでドラッグ値、requestで指離し時の全値を固定。
3. ticket.valuesをPhotoAdjustmentPolicy.adjustmentsへ渡し、strengthと共にrecipeを作る。
4. 検証済み同一原本・同一recipeの結果だけshown(ticket)へ渡す。
5. reset/cancelは必要な復元renderも実行。保存ではその瞬間の全値と原本を固定する。
6. 共通画面や原寸側でstrengthだけを完了キーに使わない。保存先に渡すrecipeも同一にする。

## 将来の絶対値表示

現行nativeにはAsShotWhiteXY対応と色温度計算があるが、KotlinのDngWhiteBalanceSummaryへ
撮影時xy/CCT/tintを公開していない。requestedUserWbのRGB倍率だけからKを断定しない。
以下を揃えてから、実測照明の温度と区別した「WB設定の相関色温度」として扱う。

- AsShotWhiteXY、またはAsShotNeutralと対応カメラ行列・較正情報の検証済み経路。
- 適用profile/行列補間と使用WBの由来を保持し、欠落・未対応・混合光を不明として扱う。
- CCTだけで表せない色偏差（tint/duv）と変換の有効範囲・丸め方。
- 表示された絶対設定をRAW側の実処理へ戻せる往復契約と、機種/既知メタデータの比較。
- 初期相対RGB変換から任意のKへ線形換算しない。将来の物理的WB処理は別版recipeとする。

絶対値は「光源を正確に測定した値」とは表示しない。推定しかできない場合は推定と明記し、
根拠不足なら相対表示のままにする。
