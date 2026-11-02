# Data model
ControlSnapshotはmanualExposure（null=AUTO）、EV index、SensorOverrides、AutoLocks。ControlReceiptはepoch/連番/phase/要求/受付履歴/説明。
CaptureSampleはepoch/frame/受信時刻、CaptureRequestの値とCaptureResultの値。世代・frame順序・受付時刻・要求との対応を確認し、実測一致を受付だけから推定しない。
CaptureEditorはkey/group/epoch、ISOと時間のpair draft、MF draft、送信token。ISO/SSは同group。取消/閉じる/別group/世代変更/Recorder Startの受信を純粋な遷移で扱う。
向きは物理四半回転とアニメーション角、撮影用Surface回転を分ける。録画・静止画busy時は出力回転を固定する。
