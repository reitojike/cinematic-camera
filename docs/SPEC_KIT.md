# GitHub Spec Kitの導入と使い方

## 導入した版と根拠

- 公式[GitHub Spec Kit v1.2.5](https://github.com/github/spec-kit/releases/tag/v1.2.5)。
- 取得元は`git+https://github.com/github/spec-kit.git@v1.2.5`、実commitは
  `4572a67fe0e72a6837e2d7bb0fda875bc41482e5`。`specify version`でCLI 1.2.5を確認した。
- 既存のglobal CLIや認証設定は変更せず、uvxで固定版を一時実行した。導入時のコマンドは次のとおり。

```text
uvx --from git+https://github.com/github/spec-kit.git@v1.2.5 specify version
specify init --here --force --non-interactive --integration codex --integration-options="--skills --events false" --script ps --ignore-agent-tools
```

2行目は同じ固定版のCLIで実行した。`--force`は管理対象ファイルを上書きし得るため、初期mainと
作業branchのreview可能なbaselineを確保してから実行した。日常作業ごとにinitを再実行するものではない。
初回取得後のhelp/initは取得済みCLIと同梱assetを使い、証明書検証の回避は行っていない。

## 現在の運用：Living Spec と変更記録を分ける

現在の製品契約の入口は [specs/README](../specs/README.md) です。機能の振る舞いが変わる場合は既存の機能仕様を更新します。
変更目的・改修計画・差分・実装タスク・進捗・検証証拠は Issue / PR に残します。
長期的な技術設計と再実行する運用手順だけを対応する docs に残し、改修ごとの作業記録一式は恒久的な正本にしません。

### 標準手順の使い方

- Constitution と関連 Living Spec、既存実装を確認してから Issue に範囲・対象外・完了条件・停止条件を定めます。
- specify / clarify / plan / checklist / tasks / analyze / implement / converge は、その作業に必要なものを使います。
  小さい変更のために重複する文書を機械的に追加しません。利用しない手順がある場合は、その理由を Issue / PR に簡潔に記録します。
- 標準 skill が spec / plan / tasks を必要とする場合は、未追跡の作業用 feature ディレクトリなどで一時的に扱えます。
  実際に使うパスを `SPECIFY_FEATURE_DIRECTORY` で明示し、各コマンドの前提を満たします。実行していない手順を実行済みと書きません。
- 機能の現行契約は対象の Living Spec へ反映し、一時成果物の差分・検証・完了状態は Issue / PR へ移します。
  長期的に必要な情報の取りこぼしがないことを確認するまで一時資料を削除しません。
- 生成された `.agents/skills/speckit-*`、標準テンプレート、scripts をこの運用のために直接改変しません。
  独自の同期フレームワーク・大量の索引・常駐処理・権限追加は導入しません。
- PRの仕様変更はマージまで提案です。mainに検証基盤が入ることと、製品の既定経路に採用されることも別です。

### 既存資料の移行

旧番号付き spec / plan / tasks は [Issue #231](https://github.com/reitojike/cinematic-camera/issues/231) で棚卸しします。
現在の契約、技術設計、再実行手順、過去のタスク状態を分類し、参照先を更新してから不要な重複を整理します。
旧 `Draft` や承認待ちが残っていても、その表示だけで現在の実装状態を判定しません。未マージの提案も現行へ混ぜません。
P0導入時の記録は歴史的資料であり、後続のRAW現像・UI等が未着手であるという現在の宣言ではありません。

### 検証と完了

変更に応じた静的確認・テストを実行し、仕様と実装の不一致を報告します。文書だけの変更でも必須CI・レビュー・マージ条件は維持します。
実機未確認の項目を、convergeやCI成功だけで合格へ変えません。完了証拠と残る実機確認は Issue / PR に残します。

公式資料: [Installation](https://github.github.com/spec-kit/installation.html)、
[Existing projects](https://github.github.com/spec-kit/guides/existing-projects.html)、
[Codex integration](https://github.github.io/spec-kit/integrations/codex.html)。
