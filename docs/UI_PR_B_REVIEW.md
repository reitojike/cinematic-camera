# PR-B 表示レビューの記録

対象: 撮影・設定・診断の表示整理（PR #389、Issue #384）。指示書: [DEVELOP_UI_REFACTOR_INSTRUCTIONS.md](DEVELOP_UI_REFACTOR_INSTRUCTIONS.md)。

## 本人承認の対象

本人は公開 commit 863f5819ec0293cd8beb5cdba3c4b6b064a2351d の全画面比較を確認し、表示を承認しました。

- [全画面の変更前／変更後／文言差一覧](https://raw.githubusercontent.com/reitojike/cinematic-camera/863f5819ec0293cd8beb5cdba3c4b6b064a2351d/docs/ui-review/pr-b/full-comparison.png)
- [原寸32枚と取得条件](https://github.com/reitojike/cinematic-camera/tree/863f5819ec0293cd8beb5cdba3c4b6b064a2351d/docs/ui-review/pr-b/full)
- [差替え箇所・取得方法・未確認事項](https://github.com/reitojike/cinematic-camera/blob/863f5819ec0293cd8beb5cdba3c4b6b064a2351d/docs/ui-review/pr-b/FullScreenComparison.md)

旧/新 CaptureScreen・設定/診断シートのソースを一時fixtureへ写し、カメラ映像だけ合成背景に差替えて固定状態を描画しました。画面全体を切り抜かず前後比較しています。実写真・個人情報は使っていません。

## 検証と限界

- 代表preflightでテーマ・文字色・背景・画面/シート位置・操作enabled・寸法を照合後、16状態32枚を取得。
- 期待文言のaccessibility assertと状態fingerprint不変assertを含む固定ATD 1試験成功（2m12s）。
- 初回全量はOS熱callbackによるfixture入力の上書きをassertで検出して停止。不完全な画像は不採用。試験側で熱listener解除と初期callback排出を行い、04/09代表確認後に全量成功。
- 独立コードレビュー、全16行一覧と代表原寸の独立目視確認を実施。
- software View描画です。resetは実Dialog座標・dimAmountを使った参考合成で、OSコンポジタそのものの画像ではありません。
- 実機CameraX/GPU描画、撮影・録画・保存・再接続の操作結果、System UI、IMEは未確認。表示承認をこれらの実機受入れとは扱いません。
- Codexレビューは利用上限により未取得。本人のCI＋独立レビューによる承認ルールを適用し、実際のGitHub保護を迂回しません。

レビュー専用PNG・取得用の旧画面fixtureはmain差分から除外しました。承認資料は上記固定commitで参照できます。正常系の表示組立単体試験と変更後の合成スクリーンショット試験は維持します。