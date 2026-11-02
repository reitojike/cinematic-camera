# Data model
- Fixed settings: capture ISO/ns、normal snapshot、二段階plan、common boost指定または未制御。通常preferencesへpreview pairを書かない。
- Stage: COMPENSATED / LONGER_PREVIEW。blocked/UNKNOWNは検証状態で第三描画modeではない。
- Preview receipt: epoch/sequence/request/accepted/time。通常capture設定の履歴から独立。
- Frame: epoch/number/受信時刻/intent/requested/actual ISO/ns/gain/frame周期/sensor timestamp。欠損/古いframeを成功へ変換しない。
- Run: ENTERING→READY→CAPTURING→RESTORING→READY。失敗はUNKNOWN、背景化はSUSPENDED、退出要求はexitPending。保存成功と復帰成功は独立。
