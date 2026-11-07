# Implementation Plan: Android 13を最低対応とする
## Summary / Technical Context
#211。app/build.gradle.ktsのminSdkだけを28から33へ変更する。compileSdk/targetSdk37、既存依存、native式は不変。GPU基盤#212は別PR。
## Constitution Check
Issue先行、標準Androidの対応宣言、秘密除外、実機未検証の区別を維持する。
## Project Structure
変更はapp/build.gradle.kts、既存Release説明文の最低対応表記、specs/050-minsdk33のみ。Releaseの実行や配布設定は変更しない。画像処理や権限分岐の整理はしない。
## Verification
既存署名鍵の存在を確認してから既存JDK/SDKでlintDebug/testDebugUnitTest/assembleDebugとgit diff --checkを実施。生成APKのminSdkを確認する。新しい署名鍵は作らない。
## Stop / Convergence
golden変更・SDK下限以外の挙動変更が必要なら停止。CIとcanonical/独立レビューは最終headで確認。マージしない。APK配布・Release・実機操作はしない。
公式根拠: https://developer.android.com/guide/topics/manifest/uses-sdk-element
