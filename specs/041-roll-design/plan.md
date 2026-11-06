# Implementation Plan: カメラロール

Branch: feat/design-camera-roll / Issue #174 / Spec: [spec.md](spec.md)

## Summary / Technical Context

既存Kotlin/Compose/Material3。A/B依存後、PhotoRollScreenの一覧分岐だけをCameraRollBrowserへ分離。権限とviewerのcallback本文は保持。CameraRollGridのAdaptive104dp/4・8dp間隔をFixed3/2dpへ。

## Constitution Check

RAW/原本/保存/lifecycle/cancel/thumbnailSlotsは無変更。既存標準Files/viewerを使用。依存追加なし。写真や個人pathをGitへ含めない。

## Project Structure / 順序

photoroll/CameraRollBrowser.kt: header/filter/access/footer/empty。
photoroll/CameraRollGrid.kt: 日付別3列、tag、最新内枠。addedSecondsを端末local日付へ変換し0は日付不明。件数は現在読み込んだ一覧のみ。画像名はaccessibilityへ保持。
photoroll/PhotoRollScreen.kt: 一覧の旧headerを置換し既存callbackを委譲。現像分岐のpaddingと全状態は維持。選択種類/フォルダ変更時の既存clearは別集合を誤表示しないため維持し、同一集合更新中/追加読込中の既存タイルを保持。
footerは64dpを基準に大文字時は必要高を確保。狭い高さでは操作帯もscroll可能にする。

## 注意文と依存

MANUAL/PRIVATEでは一覧1行の試験注記、詳細は現像表示に残す。D完成前にも診断露出/暫定色/共有可否が消えないよう一覧の説明展開を用意する。A/Bとは別PR。採用済み処理を別実装しない。

## 検証

lint/unit/assemble/diff check。既存CameraRollNavigation/権限/queryテスト維持。日付不明/日境界・件数/最新の表示変換を検証。空状態/部分権限/多件数/読込/長名/文字倍率のUI確認。実機未確認を明記しmerge/APPROVE/配布は行わない。
