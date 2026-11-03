# Tasks: PC RAW 4案比較

## Setup / Foundation

- [x] T001 Issue #54とspec/planをspecs/011-raw-look-comparison/へ整理。
- [x] T002 baseline.pyと実DNGの独立監査をprivate記録へ保存。

## User Story 1

独立受入: 同じsceneの4案を全体/等倍/肌/衣装/照明で比較。

- [x] T003 [US1] tools/raw_baseline/look_compare.pyに明示的明度土台と4案/手動領域処理を追加。
- [x] T004 [P] [US1] tools/raw_baseline/compare_web/へ同期比較と選択メモUIを追加。
- [x] T005 [US1] tools/raw_baseline/LOOK_COMPARISON.mdに基準/限界/操作を記載。

## User Story 2

独立受入: 再生成画像hash一致、原本不変、unsafe入力拒否。

- [x] T006 [US2] tools/raw_baseline/look_compare.pyにprivate recipe/manifestと安全な出力を追加。
- [x] T007 [P] [US2] tools/raw_baseline/test_look_compare.pyに合成/境界/再生成/metadata/path拒否検証。

## Verification / Review

- [x] T008 tools/raw_baseline/の合成suite/JS構文/座標/HTML参照/公開scanを実行。
- [x] T009 入力外private出力へ代表3照明と4案を生成し画像目視と再生成hash/原本不変検証。
- [ ] T010 独立レビュー/Draft PR/canonical review/CIを最終headで確認。
- [ ] T011 実描画と本人の好みを確認（file拒否の迂回なし、未確認を保持）。

## Dependencies / Execution

T001→T002→T003/T004→T005/T006→T007/T008→T009→T010。T011は本人確認を含み独立。
並列例: T004の静的UIとT003の数値処理、T007の合成検証とT005の手順は別ファイル。
MVPはUS1の1代表から始め、監査後に3代表へ広げる。
