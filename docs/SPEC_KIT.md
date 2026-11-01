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

## 採用した運用

think-canvasと同じく、Constitutionと機能ごとのspec / plan / tasks、Codex向けの
`.agents/skills/speckit-*`を使う。標準skills・テンプレート・PowerShell scripts・workflowを
公式CLIから生成し、生成skillsの本文は改変していない。上流のMITライセンスは`.specify/LICENSE`に保持した。

今回の機能は[001-p0-camera-probe](../specs/001-p0-camera-probe/spec.md)。合意済みP0ブリーフを
specへ整理し、planに採用APIと最小設計、tasksに実装と実機残件を分けて記録した。
P0のtasksは実装/機械的検証と、受入条件の資料・実機確認を区別する。日々の進捗はIssue等で扱う。
後続方針は[002-live-capture](../specs/002-live-capture/spec.md)のspec/planで定義し、実装taskは限定作業ごとに作る。
Constitutionは共有保存の1.1.0からライブ品質・原本保持を追加した1.2.0へ更新。CLIの版1.2.5とは別である。

現在のfeatureのローカルpointerは標準どおり`.specify/feature.json`に置くが、machine-local stateとして
Gitでは追跡しない。別checkoutでは対象featureを明示できる。

```powershell
$env:SPECIFY_FEATURE_DIRECTORY = 'specs/001-p0-camera-probe'
```

Codexの会話で`$speckit-specify`、`$speckit-plan`、`$speckit-tasks`などを順に使う。
今回も標準template resolver、setup-plan、setup-tasksを実行し、文書品質とspec/plan/tasksの整合を確認した。
機械的検証は通常のAndroid CIに任せ、P0の実機計測が完了するまでconvergedとは扱わない。

## P0に合わせて省いたもの

- 実装済みP0をSpec Kitのために作り直さず、実装前にこれら文書が存在したとも主張しない。
- 要件上の追加の曖昧さがないため、新しいclarify質問や別の要件checklistを増やさない。
- researchとデータ構造はplanへ統合、quickstartはREADME / P0_REPORTを参照する。外部APIがないためcontractsを増やさない。
- Git拡張・GitHub拡張・外部catalog/preset・追加の実行workflowを導入しない。branch/PRは既存の運用を使う。
- Codex runtime event handlersは無効。新たな常駐処理・MCP接続・認証・継続アクセスを設定しない。
- think-canvas固有のPRD/モック、Canvas/Room/Ink、古いIssue、GMD/quarantineや実機合格記録は移植しない。

## 検証とauthority

P0の定義はspec、実測の状態は[レポート](P0_REPORT.md)、repo/配布実績は[設定実績](REPOSITORY_STATUS.md)を参照する。
このDraft PRのSpec Kit文書はmerge前の整理であり、mainの契約を暗黙に変更しない。
後続P1〜P4は未着手。インストールとカメラ操作にはユーザー報告があるが、実機ACは未完了で、
P1のGo/No-Goは未判定のまま。

公式資料: [Installation](https://github.github.com/spec-kit/installation.html)、
[Existing projects](https://github.github.com/spec-kit/guides/existing-projects.html)、
[Codex integration](https://github.github.io/spec-kit/integrations/codex.html)。
