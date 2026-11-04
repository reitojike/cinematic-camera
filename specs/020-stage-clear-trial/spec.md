# Stage Clear診断試験表示

Issue: #116。#019のDNG試験表示を、R7とStage Clearの実計算につなぐ。

- 選択DNGを読み取り専用で確認し、長辺512以下でdecode、診断入力準備、R7、Stage Clear強度0.5、表示転送の順に実行する。
- 入力はLibRawの撮影時WBによる暫定線形sRGB。W1、Pixel固有profile、PCとの画質一致は未成立。W1-only入力をas-shotの診断測光へ渡さない。
- #113の中央p97診断露出と明示空foundation ROIを利用する。顔検出はNOT_RUN。空maskは検出結果ではなく、顔保護を行わない試験指定である。
- 種別をSTAGE_CLEAR_TRIALとし、「Stage Clear試験」「顔保護なし」「色・明るさは暫定」「JPEG保存未対応」を選択前と表示後に示す。完成現像とは呼ばない。
- 原本hash再照合、前景限定、要求単位の中断、世代隔離、process単一permit、camera/thermal/memory開始gateを維持する。段階進行は実処理から通知し、旧要求の通知を無視する。
- Stage Clearの固定版・強度以外の要求、寸法上限外は開始しない。実機性能を保証せず、重いkernel中の即時停止も保証しない。

対象外: 顔検出/顔AE、W1/native/画素処理の再実装、新依存/権限、JPEG保存・公開、原本変更/複製、APK配布、実機画質合格。
