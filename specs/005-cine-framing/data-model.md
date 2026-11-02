# Data model

- **CINE framing**: portrait aspect=9:16、rotation=ROTATION_0、scale=FILL_CENTER。同一ViewPortをsession query/check/bindで使用。Recorder aspect=16:9。STILLには適用しない。
- **Preview geometry**: available windowからcapture rail=96dpのみ除外。settings/diagnostics/rulerはpreview寸法から除外しない。mode別top insetはSTILL=44dp/CINE=0dp。scale=FIT_CENTER固定。
- **Panel**: NONE→SETTINGS→DIAGNOSTICS。BackはDIAGNOSTICS→SETTINGS→NONE→ruler→既存exit guard。設定overlay heightは最大344dp、preview高さ内へ制限する。診断中は背面capture railの全操作を無効化しcallbackでも拒否。録画非IDLEでは診断をNONEへ閉じ、停止railを覆わない。draft破棄は実Start通知後のみ。
- **Focus click origin**: touch tap通知中はcenter clickを抑止する。accessibility clickはcenter動作。panel!=NONE/submitting中は双方拒否。既存busy/stream/control guardを維持する。
- **Recording/output rotation**: 既存IDLE→STARTING→RECORDING→STOPPING→FINALIZING→IDLE。busy中は最新向きを保留し出力targetRotationを更新しない。background stopを維持。
