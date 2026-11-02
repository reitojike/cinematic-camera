# UI contract
- CaptureProbeの型付き状態から描画する。ログ・status・desired・statsをparseしない。
- draft→Pending→Acceptedと実測sampleの更新は別。Rejectedは旧受付を保持、Unknownは現在値不明として撮影無効。
- Cancelは受付値へ戻し選択保持。Close/別group/再bindは破棄。STILLは保持、CINEはRecorderのStart通知を受けた時だけ破棄（準備・開始失敗では保持）。
- Final v1 412×924基準: status44、ruler104（選択時）、6keys72、capture96、system insets。設定sheet344は本文scroll、previewは残領域FIT。
- bg #0A0B0C / scrim 50% / active #0D0F10 94% / text #E4E6E8 / amber #E6A35C / accepted #7CC4A5 / fail #EF8A74 / REC #E5483B。数値mono、最小11sp、主tap44dp。
- LOOK/比率保存/写真viewerはdisabled＋理由。gearは標準vector。設定/診断本文はv1では回さない。
- portrait配置固定。文字/アイコンを最短経路約250msで回す。sensor境界hysteresis。Previewは回転させない。録画開始時の出力回転を完了まで保持。

## 採用稿の透過帯とFIT
プレビューは画像自体をクロップしない。通常時はSTILLの短いフレームを状態バー下へ置き、縦長CINEでは半透明状態バーを重ねる採用仕様である。
選択時の目盛り/キーは94%のscrimを重ねる。常に操作帯と画像を完全分離する別レイアウトへは変えない。
設定シートのみプレビューと重ねず、残り領域へ縮小して全体を保持する。これはユーザー決定稿READMEの「プレビュー配置規則」「オーバーレイ規則」に対応する。

ISO/SHUTTERの編集/適用に関する後続契約は[004](../../004-live-exposure/contracts/ui.md)を優先する。旧pair draftを即時要求の受付値と混同しない。
