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
- [x] T007 spec/plan/tasksとのconvergeと独立レビューを行う。実機未確認を明記する。

依存: T001→T002→T003、T004は表示実装に並行可能、T005→T006→T007。
分析: FR001–006とSC001–003を上記へ対応付け済み。矛盾・未解決要件なし。

- [x] T008 本人確認済みのモード色・顔保護注意を実装し指定3modeとlegacy注意保持を検証する（FR-006）。

## 検証境界
実装converge: FR001–006 / SC001–003を照合し、未実装差分なし。
機械検証: lintDebug / testDebugUnitTest / assembleDebug / assembleDebugAndroidTest とdiff検査成功。
共通GMD導入前の専用API37エミュレーターでDevelopViewAndroidTestとCaptureControlVisualsAndroidTest成功（2 tests）。
この過去の実績をGMDでの成功に読み替えず、再実行はplanの共通GMD手順に従う。
現像はfont scale 1.3でも同テスト成功。撮影は共有部品の縦/横回転fixtureであり、実カメラ起動試験ではない。
初回のa11y更新待ち・子ノード選択によるfixture失敗は、待機とclickable祖先の明示選択状態検査へ修正。
実機の見え方・操作感、実DNG、24MP保存は未検証。実装head `ef3302d` の独立レビューはOK。最終head b51e471でCI/canonical/独立レビュー成功・未解決0を確認し、PR #188をafce68fへmerge済み。ATD合成2/2成功。実機未確認は継続。

- [x] T009 [Issue #186](https://github.com/reitojike/cinematic-camera/issues/186) のPRで必須CI・canonical review・最終head独立レビュー・未解決会話0件を確認し、許可範囲でmergeする。

統合根拠: [PR #188](https://github.com/reitojike/cinematic-camera/pull/188)。
