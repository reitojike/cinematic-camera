# 設計判断

- Compose Boxの重ね合わせとAlertDialogを採用。inlineの通知はviewport高さを変えるため使わない。
- 原本の再検証前も同一写真の直前画像を「更新中」として見せるが、失敗時は破棄する。写真切替・背景は保持しない。
- 幅基準表示で画像をcropしない。高さ超過は標準verticalScrollに任せる。Fitは縦画像の左右余白が残るが全体を見渡せるため比較候補として維持する。Cropは画角を失うため選ばない。
- 公式: https://developer.android.com/develop/ui/compose/components/dialog 、https://developer.android.com/develop/ui/compose/graphics/images/customize 、https://developer.android.com/develop/ui/compose/touch-input/pointer-input/scroll

## 画像の収め方の比較（本人判断待ち）
縦画像は、全体Fit案と幅合わせ＋領域内縦スクロール案の両方を合成画像で比較する。横画像も比較する。既定は従来の全体Fitを維持し、本人が選ぶまで幅合わせ案を製品の既定として採用しない。比較用の表示引数を用意し、新しい永続設定は追加しない。
