# Quickstart validation

## 機械検証

既存JDK/Android SDK/Gradle cacheを使用する。新規keys/権限/依存は不要。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
git diff --check
```

期待: build/unit/lint errors 0。CaptureLayoutTestは各window/mode/panelでpreview寸法一定、FIT比率を確認。PreviewClickPolicyTestはtap指定座標1回とaccessibility中央1回、例外時guard復帰を確認。既存orientation/recording/Backテストを維持。

## 許可後の実機確認（今回未実施）

1. STILL/JPEG/RAWの既存出力・画角を確認。CINE cropを適用しない。
2. CINE SDRとAPIが対応としたHDRでquality/fps候補を選択し、四辺の目印がpreviewとMP4で一致するか比較。失敗時はfallback説明を確認。
3. 各0/90/180/270で設定/診断/ruler開閉。Previewの寸法/画角/sessionが一定、設定背景やClose/BackはAF/AEを要求しないことを確認。
4. touch tapは指定位置、TalkBack clickは中央AF/AEへ1回。Close/Back/録画停止の読み上げ・操作性を確認。診断中に背面撮影/modeがkeyboard/TalkBackで起動せず、録画準備開始後は診断が停止操作を覆わないことを確認。
5. STARTING/RECORDING/STOPPING/FINALIZING中の回転でtargetRotation固定、背景でstop、復帰後に最新向きを適用することを確認。
6. VIDEO_FRAMINGのbound resolution/cropRect/rotationDegreesとVIDEO_SAVEDの実MP4 metadataを別々に記録する。build成功は実機一致の証拠にしない。
