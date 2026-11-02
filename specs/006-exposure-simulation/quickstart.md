# Validation
## 自動・静的
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon とbase比較diff check。policyはstage/unknown/overflow/丸め、stateはreceipt/intent/epoch/保存と復帰/退出/背景化を検証。
## ユーザー実機（未実施）
1. STILLで合法manual pairを受付させ、設定から検証を開始。固定target/preview target/二段階/boost状態と初回bind混入を確認。
2. 動かない被写体/固定照明でpreviewとJPEG/RAWの明るさ・階調/飽和/暗部を比較。実ISO/ns/gain/frame周期を記録。compileや同じ積を一致の実測としない。
3. 検証撮影後にSTILL intentと新PREVIEW frameが別表示となるか、保存成功と復帰未確認を区別するか確認。
4. 退出/失敗/背景復帰/通常ISO設定保持、RAW原本、CINE動画・録音に回帰がないか。実機不成立なら代案/限界を報告し全面移行しない。
