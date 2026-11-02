# Specification Quality Checklist: 通常二段階露出preview

**Purpose**: plan前の仕様品質確認
**Created**: 2026-11-02
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] 実装方法はplanへ分離し、利用者の目的・観測可能な動作を記述
- [x] 利用者価値と通常操作の応答へ焦点を限定
- [x] 必須セクションを記載

## Requirement Completeness

- [x] 未解決の要件質問なし
- [x] 全要件がテスト可能で曖昧でない
- [x] 成功条件は測定可能で実機未確認を区別
- [x] 成功条件は利用者の結果を記述
- [x] 主要フローと境界条件を記載
- [x] スコープ、依存、前提を明示

## Feature Readiness

- [x] 全要件に受入シナリオまたは成功条件あり
- [x] 初期/編集/撮影/復帰/失敗の主要フローを包含
- [x] 実装済み/実機合格を先取りしていない

## Notes

Issue #44と明示された境界を確認。clarifyの追加質問は不要。実機ACの合格判定を行うchecklistではない。
