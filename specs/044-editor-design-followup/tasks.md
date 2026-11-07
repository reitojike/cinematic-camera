# Tasks: 現像デザインの差分修正

## Phase 1: Setup
- [x] T001 spec/plan、原典画像、spec042と憲法を確認する。
## Phase 2: US1
- [x] T002 CaptureControlKey / CaptureModeSwitchの入力非依存表示を共有し、TrialStrengthEditorPaneにCaptureRulerHeadingを使う。
- [x] T003 PhotoRollDevelopViewの選択表示・目盛り順とPhotoEditBarの状態色を修正する。
## Phase 3: US2
- [x] T004 PhotoRollDevelopViewの状態帯中央だけスクロールし両端を固定する。
## Phase 4: Validation
- [x] T005 DevelopViewAndroidTestへ選択・順序・情報到達回帰を追加する。
- [x] T006 lint/unit/assemble、diff検査と合成Android回帰を実行する。
- [ ] T007 spec/plan/tasksとのconvergeと独立レビューを行う。実機未確認を明記する。

依存: T001→T002→T003、T004は表示実装に並行可能、T005→T006→T007。
分析: FR001–005とSC001–003を上記へ対応付け済み。矛盾・未解決要件なし。

- [x] T008 本人確認済みのモード色・顔保護注意を実装し指定3modeとlegacy注意保持を検証する（FR-006）。

## 検証境界
実装converge: FR001–006 / SC001–003を照合し、未実装差分なし。
機械検証: lintDebug / testDebugUnitTest / assembleDebug / assembleDebugAndroidTest とdiff検査成功。
専用API37エミュレーターでDevelopViewAndroidTestとCaptureControlVisualsAndroidTest成功（2 tests）。
現像はfont scale 1.3でも同テスト成功。撮影は共有部品の縦/横回転fixtureであり、実カメラ起動試験ではない。
初回のa11y更新待ち・子ノード選択によるfixture失敗は、待機とclickable祖先の明示選択状態検査へ修正。
実機の見え方・操作感、実DNG、24MP保存は未検証。最終headの独立レビュー、GitHub CI/canonical review/公開は別途必要。
