# 実装計画

撮影と編集の目盛り線をControlRulerTicksへ抽出し、中央選択背景・3dp線・18sp等幅ラベル・48dp矢印を揃える。狭幅/拡大文字は撮影と同じ5/3/1個表示と文字計測を使う。
撮影の離散選択ownerを編集へ流用せず、PhotoEditBarのセッション同期dispatchを維持する。純粋なPhotoEditRulerDragで整数化前の移動を蓄積し基準値に吸着する。ComposeはAndroid View.performHapticFeedback(CLOCK_TICK, 0)を使用する。
公式リファレンス: https://developer.android.com/develop/ui/views/haptics/haptic-feedback
標準Compose semantics/key/pointer APIを維持。追加依存なし。撮影側の入力・保存は無変更。#81の永続化実装対象とは非重複。

検証: lintDebug/testDebugUnitTest/assembleDebug/assembleDebugAndroidTest、純粋入力試験、共通GMDの合成試験、diff/秘密検査。実機と触覚の体感は未検証のまま明記する。
