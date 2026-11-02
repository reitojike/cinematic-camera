# UI contract
- CaptureProbeの型付き状態から描画する。ログ・status・desired・statsをparseしない。
- draft→Pending→Acceptedと実測sampleの更新は別。Rejectedは旧受付を保持、Unknownは現在値不明として撮影無効。
- Cancelは受付値へ戻し選択保持。Close/別group/再bindは破棄。STILLは保持、CINEはSTARTING移行時のみ破棄。
- Final v1 412×924基準: status44、ruler104（選択時）、6keys72、capture96、system insets。設定sheet344は本文scroll、previewは残領域FIT。
- bg #0A0B0C / scrim 50% / active #0D0F10 94% / text #E4E6E8 / amber #E6A35C / accepted #7CC4A5 / fail #EF8A74 / REC #E5483B。数値mono、最小11sp、主tap44dp。
- LOOK/比率保存/写真viewerはdisabled＋理由。gearは標準vector。設定/診断本文はv1では回さない。
- portrait配置固定。文字/アイコンを最短経路約250msで回す。sensor境界hysteresis。Previewは回転させない。録画開始時の出力回転を完了まで保持。
