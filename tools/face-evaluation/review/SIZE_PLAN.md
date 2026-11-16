# APK容量比較の次段階

## 既存実測と境界

#406の既存debug / arm64-v8a+x86_64 / 縮小なし評価APKは、baseline 2,474,357 bytes、
ML Kit 32,441,743 bytes、YuNet 89,259,083 bytes。ML Kitは同梱版のみ。
この値は製品版の増分でもPlay配信サイズでもない。モデル単体とOpenCV全体を混同しない。

## 比較条件

製品source/root Gradleへは追加せず、評価専用projectにunbundled ML Kitのflavorを追加する。
同一head/JDK/AGP/SDK、同一ABI・縮小条件でbaseline、bundled、Play、YuNetを比較する。
APK bytes、baselineとの差、圧縮前native、モデルassetsを分けて記録する。
2 ABI universalだけでなく、実機を想定したarm64-onlyを同条件の独立表にする。
Playモデルの取得後の端末容量、初回未取得時の動作/オフライン可否はAPK増分とは別測定。
未取得のため結果がない状態を「顔なし」と数えない。

Google公式の参考値はPlay版約800 KB、同梱版約6.9 MBであり、本projectの実測ではない。
2026-11-16確認: https://developers.google.com/ml-kit/vision/face-detection/android
現在の公式依存指定は同梱16.1.7、Play17.1.0。既存bundledの版は変えない。
モデルが使用前に存在する必要があることを確認し、公開素材/合成による疎通と
本人の実写精度、Pixelのcold/warm性能を分離する。

## 実行順

v0.1.0のPixel準備/Release用buildを優先。共有Windows build/AVD枠の予約後に
評価flavorの追加、manifest/通知監査、unit/build/lint、合成smokeを行う。
実機導入・配布・実写真の端末外転送はこの計画によって許可されない。
静止画比較の採用候補が決まるまで撮影中プレビューの速度・負荷は測定しない。
