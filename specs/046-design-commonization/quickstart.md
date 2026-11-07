# Validation
RAW診断を優先し、共有枠の空きと利用記録を確認する。
1. 同一合成fixtureの基準eb99733と候補をATDで撮影、spec全matrixを比較。
2. 比較キー区切りと無効切替のalpha以外の差を直す。
3. .\gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --no-daemon とgit diff --check。
4. Color(0x…)がCaptureDesign以外0、既存semantics/入力/保存の変更なしを確認。
5. 最終headのCI・canonical/独立レビュー・会話0を取得。実機は未検証、mergeは承認待ち。
現在の合成matrixは撮影共有部品2状態・ロール3状態・現像5状態。撮影全画面・設定・診断は依頼者の診断優先指示により実機確認へ残す。全画面自動比較の成功とは扱わない。
