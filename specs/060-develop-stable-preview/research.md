# 設計判断

- Compose Boxの重ね合わせとAlertDialogを採用。inlineの通知はviewport高さを変えるため使わない。
- 原本の再検証前も同一写真の直前画像を「更新中」として見せるが、失敗時は破棄する。写真切替・背景は保持しない。
- 幅基準表示で画像をcropしない。高さ超過は標準verticalScrollに任せる。Fitは左右余白を避けられず、Cropは画角を失うため選ばない。
- 公式: https://developer.android.com/develop/ui/compose/components/dialog 、https://developer.android.com/develop/ui/compose/graphics/images/customize 、https://developer.android.com/develop/ui/compose/touch-input/pointer-input/scroll
