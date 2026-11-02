# Research
- Decision: ISO1EV/SS慣用1/3EVを別々に採用。Rationale: ユーザー例と一致。数式から名目列を丸めて作らない。
- Sources: [SIGMA fp manual p69](https://www.sigma-global.com/en/support/download/fp_Manual_FW_Ver.5.0_EN_2.pdf)、[Canonシャッター表記](https://sg.canon/en/support/8202610000)。資料は表示段階の根拠でありPixel能力の保証ではない。
- Camera2Exposure: [露光時間](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#SENSOR_EXPOSURE_TIME)は短い側へ量子化され得る。要求=実測を受付条件にしない。[frame duration](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#SENSOR_FRAME_DURATION)と実測周期も区別する。
- Camera2CameraControl1.6.2の実runtime bytecodeではrequest内部tagの一致通知を待つ。ISO/ns実測一致待ちではない。Future.cancelは下層操作取消に伝播しない。独立tokenで旧次段を防ぐ。[公式API](https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2CameraControl)
- 原因の確定範囲: アプリの無期限awaitは確認済み。実機極端値で応答が来ない正確なHAL条件は未再現。timeoutだけをhardware freeze解決と称さない。

- 表示遅延の確認済み要因: 旧CaptureResult表示は1秒間隔だった。新要求/受付後の2.5秒だけ実sampleを200ms間隔で表示し、ログは従来1秒に抑える。センサー応答を創作せず、API期限/実際のpreview反映は独立に扱う。

- 速い側の名目範囲は[ニコンZ9公式仕様](https://onlinemanual.nikonimglib.com/z9/en/specifications_329.html)の1/32000〜30秒/1・1/3EVも参照。これは候補列の根拠のみでPixelでの対応を意味しない。
