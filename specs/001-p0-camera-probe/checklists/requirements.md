# Specification Quality Checklist: P0カメラ成立性検証

**Purpose**: 仕様の明確さと追跡性を確認する。実機ACの合格チェックではない。
**Created**: 2026-11-01
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] 振る舞い・検証価値に集中し、言語・ライブラリ版・実装APIはplanへ分離した。
- [x] 検証者が行う操作と観測結果で説明した。
- [x] 必須のscenarios、requirements、success criteria、assumptionsを記載した。

## Requirement Completeness

- [x] 要件上の未解決の確認事項を残していない。未測定の実機値は仕様の曖昧さと区別した。
- [x] FR-001〜009が観測可能で、AC1〜7へ対応する。
- [x] DNG5件、ISO/時間各2値、停止/復帰、エラー時の結果が検証可能。
- [x] 非対応、部分保存、入力範囲、解除失敗、遅れたcallbackを挙げた。
- [x] 対象・対象外・依存・停止条件を明示した。

## Feature Readiness

- [x] US1〜3を個別に検証できる手順と期待結果がある。
- [x] 技術設計と証拠レポートへの参照がある。
- [x] 機能の実装完了を実機AC完了に読み替えていない。

## Notes

1巡で文書品質を確認。実機ACは全て未検証であり、上のチェック済み項目はその合格を意味しない。
次はplan / tasksを参照し、残る実機作業を実施する。要件上のclarify質問は追加しない。
