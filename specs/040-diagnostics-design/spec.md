# Feature Specification: 診断画面のデザイン統一

Branch: feat/design-diagnostics / Created: 2026-11-06 / Status: Draft
Input: [Issue #173](https://github.com/reitojike/cinematic-camera/issues/173)、引継ぎINSTRUCTIONS.md B。A #172の共通トークン後に実施。

## User Scenarios & Testing

### US1 状態と制御の違いを読む (P1)
理由: 要求/受付/実測を誤認しない。独立確認: 制御タブに既存3列とsource/期限、intentが揃う。
AC: EPOCH/API/PREVIEW/熱の4要約、制御表の全行、旧epoch/不対応frameの注記、ISO/SS期限2000msを確認できる。

### US2 詳細を探す (P1)
理由: 縦積みの情報を整理する。独立確認: 出力/能力/ログに切り替えて既存情報の欠落がないことを確認。
AC: 文字を選択・コピーでき、タブ切替はカメラ制御を送信しない。bind/向き/保存/録画/OS、CameraX/Camera2能力、直近ログを読める。

### US3 管理操作へ到達する (P2)
理由: 診断中の復旧を維持。独立確認: 設定戻り、Preview開始/停止、レポート保存。
AC: 既存有効条件と復旧導線を保ち、画像・音声本体や自動uploadがない旨と共有前確認の注意を残す。

### Edge Cases
古いepoch、期限切れ/未取得、UNKNOWN/REJECTED、録画開始/処理中、横持ち/拡大文字、長いログ。

## Requirements

- FR001: 52dp見出し、設定戻り、CameraX1.6.2と実version、64dp要約セル、44dp制御/出力/能力/ログタブ。
- FR002: 選択タブは背景と上辺3dp線。共通色/書体を使用し状態を文字でも示す。
- FR003: 既存全診断情報と選択コピーを保持。制御格子とISO/SSsource・2000ms期限バー、最新intent。
- FR004: 下端のPreview開始/停止と検証レポート保存、注記を固定。狭い高さではスクロールで全操作に到達。
- FR005: 状態遷移/操作guard/RAW処理/保存/原本には変更しない。

## Success Criteria

SC001: 既存情報の移動先対応表に欠落0件。SC002: 全タブ/管理操作へ縦412×924dp・横持ち・拡大文字で到達。SC003: 要求/受付/実測の意味と操作可否が既存と一致。

## Assumptions

PNGはモック。寸法はINSTRUCTIONS、字幅/行高はAndroidで確認。Aのtokenを利用するがAとPRは分離。要確認1/2/3/4の新判断はBでは不要。実装前にspec/planを報告し、その後進める。
