# Tasks: Stage Clear v1（PC基準の定義）

**Issue**: [#77](https://github.com/reitojike/cinematic-camera/issues/77)、既定値の変更は[#86](https://github.com/reitojike/cinematic-camera/issues/86)
**Input**: spec.md / plan.md。PC基準の定義と合成テストまで。Android移植・編集UIは後続Issue。

## 定義と検証

- [x] T001 第10回の確定形を強度つきの`tools/raw_baseline/stage_clear.py`の`render`にまとめ、顔はマスクで受け取る。
- [x] T002 `tools/raw_baseline/test_stage_clear.py`と`golden/stage_clear_v1.json`で、強度1の一致、強度の単調性、強度0の保護、範囲外拒否、合成goldenを検証する。
- [x] T003 `tools/raw_baseline/r11_stage_clear.py`で照明条件別に基準処理と強度0/0.5/1を同じ原本・同じ露出で比べる非公開ページを作る。
- [x] T004 ユーザーが既定の強度を選ぶ（全条件で0.5、写真ごとに編集で0〜1の間で上げ下げする。#77では1としたが#86で0.5へ変更）。
- [x] T005 処理順・パラメータ・強度・限界を`spec.md` / `plan.md`へ記録し、P2/P3の引き継ぎを`handoff.md`と後続Issueへ切り出す。
- [x] T006 最終headのPC RAW checks、canonical review、独立レビューの明示的OKを得てmergeする（運用gate、PR #84）。
- [x] T007 既定の強度を0.5へ変え（`DEFAULT_STRENGTH`）、比較に0.75を足し、テストと文書を更新する（#86）。処理・golden・版は変えない。

## 未検証として残すもの

- 暗い会場の高感度の人物素材、本物のステージでのPixel 9a素材、顔測光との組み合わせ、端末での処理時間とpreview一致。
  自動テストで代用しない。
