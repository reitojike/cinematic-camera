# Tasks: PC RAW基準現像と比較PoC

**Issue**: [#49](https://github.com/reitojike/cinematic-camera/issues/49)
**Input**: spec.md / plan.md。実装・合成検証とcamera別実素材の受入を分離する。

## Phase 1: Setup

- [x] T001 Issue承認範囲を `specs/010-p1a-raw-baseline/spec.md` と `plan.md` へ定義する。
- [x] T002 `tools/raw_baseline/requirements-win-py312.txt` の公式wheel固定と `.gitignore` のprivate除外を用意し、専用venvへ導入する。

## Phase 2: Foundational

- [x] T003 `tools/raw_baseline/baseline.py` にroot境界・リンク非追跡・private出力・safe error・上書き拒否を実装する（FR001/004）。

## Phase 3: US1 - 基準現像と再生成 (P1/MVP)

**Independent Test**: 合成runの出力hash一致、入力不変、失敗時の未公開を確認する。

- [x] T004 [US1] `tools/raw_baseline/baseline.py` に固定WB/露出/色空間/levelsと基準／トーン／manifest生成を実装する（FR002/003）。
- [x] T005 [US1] `tools/raw_baseline/baseline.py` にschema/環境/入力hash/出力hashを確認するreplayを実装する（SC001/002）。
- [x] T006 [US1] `tools/raw_baseline/test_baseline.py` で境界、CLI、WB未知、I/O、重複、再生成、EXIF非複写を合成検証する（FR005/SC001–003）。

## Phase 4: US2 - cameraと評価限界の分離 (P2)

**Independent Test**: camera不一致を拒否し、合成結果／実素材未確認／Stage Clear未実装を区別する。

- [x] T007 [US2] `tools/raw_baseline/baseline.py` と `test_baseline.py` にPixel9a/SIGMAfpの厳密admission・WB係数分離を実装する。
- [x] T008 [US2] `tools/raw_baseline/README.md` と `EVALUATION.md` に公式根拠、license、階調限界、複数照明／等倍大cropSNS／技術対好みの評価枠を定義する（FR006）。
- [ ] T009 [US2] 許可した実DNGが提供された場合だけ `tools/raw_baseline/.private/` のcamera別runでdecode／原本不変／WB／色転び／clipを確認し、公開安全な最終要約をIssue49へ渡す（SC004）。素材なしは未確認。

## Final Phase: Verification and review

- [x] T010 `tools/raw_baseline/` のunit／CLI synthetic→replay／compile／依存整合を実行し、SpecKit整合・public境界を確認する。
- [ ] T011 `specs/010-p1a-raw-baseline/` と `tools/raw_baseline/` の独立レビュー・最終公開scanをrootへ引き継ぎ、Draft PRの最終headレビュー／CI／thread0を確認する。merge／配布は含めない。

## Dependencies & Execution Order

T001–003 → T004–006（US1）→ T007–008（US2）→ T010–011。T009は素材受領後だけ行い、合成MVPの実装完了と混同しない。
公開根拠の確認とUS2評価文書の整備は別ファイルとして並行可能だが、今回の実装は単一担当。独立レビューは別担当。
MVPはUS1の合成検証とCLIまで。camera別実素材合格はT009が完了するまで宣言しない。
