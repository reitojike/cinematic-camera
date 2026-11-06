# Implementation Plan: 設定シートのデザイン統一

**Branch**: `docs/design-settings-handoff` | **Date**: 2026-11-06 | **Spec**: [spec.md](spec.md)
**Issue**: [#172](https://github.com/reitojike/cinematic-camera/issues/172)

## Summary

CaptureScreen/CapturePanelsの表示を組み替えCameraProbeの制御を継続利用。共有トークン→overlay本文/固定footer→選択群→確認パネルの順。実装前報告済み。個別承認を待つ指定はDの要確認3のみ。

## Technical Context

Kotlin Compose plugin2.4.21、AGP9.5.1、CameraX1.6.2、既存Material3。min28/target37。新依存・永続化なし。既存JVMテストとlint/build/UI確認。設定開閉によるpreview再生成・不要rebindを防ぎ、新たな速度目標は追加しない。

## Constitution Check

設計前後ともIssue起点、原本不変、標準Compose、指定/受付/実測分離、実機未検証の明示に適合。RAW/native/保存の改変なし。リセットは標準Dialog＋Surfaceで指定の形にし、独自window管理を追加しない。merge許可なし。

## Project Structure

対象は `app/src/main/java/dev/cinematic/p0/` のCaptureScreen.kt/CapturePanels.kt/CaptureLayout.ktと新規共通トークン、対応する既存test。
PhotoRollActivityは既にCaptureTheme適用済み。Aでは診断本体・photorollを変更しない。
本spec配下にresearch/data-model/quickstart/contracts/settings-ui/checklists/requirements。tasksで実装と検証の完了を区別する。

## 実装との差分・順序

1. CaptureScreenのprivate色とCapturePanelsの重複色を共通トークン化。色・書体・寸法のみを共有し、撮影動作を変えない。
2. CaptureLayout.sheetHeightの344上限を504へ。previewHeight=available−96は維持し、既存sibling shieldとPreviewView配置を保持。
3. 設定専用headerと固定footerを外側Columnへ、本文のみscroll。短辺/拡大文字でも到達性を検証。可用高240dp未満は全体scrollへ退避し管理操作への到達を優先。
4. FilterChip/Checkboxを等幅選択へ。enabled/selected semanticsを保持。ON/OFFは値が異なる場合だけ既存toggleを呼び、同値再選択で反転させない。
5. 効果タグ/注記/無効表示は実データから。CINEの「現在」はboundとactiveVideoChoice一致から導出。非対応と一時busyを区別。
6. selectFormatは要求を先に更新してbindするため、旧値は操作直前の表示snapshotに保持する。busy一般を再接続とみなさず、既存busy/bound/epochとの対応を実装時に検証。snapshotは再作成/別epoch/終了時に破棄し、旧値不明の矢印を捏造しない。成功を推測する表示が避けられなければ当該表示を保留して報告する。
7. 標準Dialog＋角なしSurfaceに6項目3列。Back/外側/取消は閉じるだけ。全リセットは現在のunlocked確認とresetAllControlsへ委譲。

## 検証

[quickstart.md](quickstart.md)参照。CaptureLayoutTestの高さ期待値だけ仕様に合わせ、preview不変/短辺上限を保持。表示射影にテストを足す場合は正常/失敗/古いepochの意味を検証する。制御テストを見た目の都合で弱めない。
実装後にlint/unit/assemble/diff checkと可能なUI確認。Pixelの見え方・RAW/24MP性能を合成試験だけで合格にしない。

## 確認事項・依存

Aは504dp既定。Bは共通トークン、C/DはPhotoRollScreen共有のため開始時に最新差分を確認。Dの調整UIはPhotoEditBar入力契約を保つ描画変更を暫定推奨するが、詳細比較とPO判断待ちはDで扱う。RAW試作/現像/cacheには編集を広げず、並行作業再開時は負荷と担当範囲を再確認する。

## Complexity Tracking

新規基盤・依存なし。既存overlayと標準Dialogを使い、HTML/runtimeは移植しない。
