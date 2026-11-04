# Research
- Decision: CameraProbe既存epochと単一置換CaptureRequestOptionsを使用。Rationale: 制御間の上書きを防ぎ最小変更。Alternative: 全面ViewModel移行は不要。
- Decision: 入力Rejectedと非同期Unknownを区別。Rationale: 部分適用後の失敗では旧設定維持を保証できない。
- Decision: OrientationEventListenerとCameraX public targetRotation。Rationale: 1.6.2のImageCapture/VideoCaptureに存在し、録画中ファイルは変更されない。開始時に固定して明示する。
- Decision: PreviewView FIT_CENTERと元座標のmeteringPointFactory。Rationale: 自作座標変換/Preview回転は不要。
- Sources: [CameraX回転](https://developer.android.com/media/camera/camerax/orientation-rotation)、[VideoCapture](https://developer.android.com/reference/androidx/camera/video/VideoCapture#setTargetRotation(int))。
- 大画面/OEMはportrait指定を無視し得る。今回Pixel9a通常ウィンドウを対象とし、他端末の完全保証はしない。
