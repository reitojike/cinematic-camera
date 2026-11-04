# Research
- 版固定camera-camera2/core1.6.2の公式sourceを読み取り確認。UseCaseCameraRequestControlのSESSION_CONFIG→DEFAULT→CAMERA2_CAMERA_CONTROL、同ALWAYS_OVERRIDE後勝ち、CaptureConfigAdapterのsession→single順から分離候補がある。
- ImageCapture.Builder options自体はsession/capture双方に入る。runtime補償前の初回長秒混入、固定設定変更時のrebind、foreground復帰は実測が必要。公開takePictureにper-capture露出引数はない。
- [camera-camera2 source](https://dl.google.com/dl/android/maven2/androidx/camera/camera-camera2/1.6.2/camera-camera2-1.6.2-sources.jar)、[camera-core source](https://dl.google.com/dl/android/maven2/androidx/camera/camera-core/1.6.2/camera-core-1.6.2-sources.jar)。現行1.7説明を1.6.2へ遡及保証しない。
- [frame期間](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#SENSOR_FRAME_DURATION)と実露光/stream制約は物理制限。短露光とISO増加は中間調近似で、noise/clipping/tone/dynamicrange/blur/長秒蓄積を再現しない。固定1s境界を採用しない。
- postRAW gainはwritableかつrangeに100を含む場合だけ共通100を設定し、他は未制御と表示。描画gain/GPU補正は追加しない。
