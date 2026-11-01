# 公開範囲の変更前の検査

確認日: 2026-11-01。**利用者の明示承認と、追加したSpec Kitを含む検査後にpublicへ切替済み。**

## 確認範囲

- 新規repoのmainと全取得済みremote branch、tagから到達する全Git履歴。初期mainとRenovateの追加commitを含める。
- Kotlin、テスト、Gradle、manifest、全docs、全workflow、Renovate設定、標準Gradle wrapperの内容。
- ローカルのソースZIP 2点とAPK、実ReleaseからダウンロードしたP0 APK。ZIP内のwrapper JAR、APK内のDEX・resourcesも展開して検査。
- main自然CI、Renovate PR CI、P0配布runのログ、および保存されたJUnit / lintレポート。
- このPRで追加する実績・検査文書の差分。
- 公式Spec Kit 1.2.5から生成したskills、scripts、templates、manifestと、P0のspec / plan / tasks、AGENTS.md。

秘密鍵のPEM形式、一般的な認証token / access key形式、実利用者のローカル絶対パス、ローカル利用者名、Gitに含めてはいけないファイル名を機械的に検査し、ソース・文書・workflowの内容も確認した。検出時に秘密値そのものを出力する方法は使っていない。パターン検査は、未知の形式の秘密が一切ないことを数学的に保証するものではない。

## 結果と除外

- 検査対象のソース履歴と配布物から、認証情報・署名秘密・個人用ローカル絶対パス・撮影写真/RAW/動画の実体は検出されなかった。履歴書き換えが必要な問題は見つかっていない。
- サンプル・テスト・結果記入欄に実ユーザー名や実端末の固有識別子を入れていない。実機結果は未確認の欄のまま。カメラIDやISOの数値例は検証対象を表す一般値。
- Gitの作者はGitHubアカウントの公開用noreplyアドレスとbotであり、私用メールアドレスではない。GitHubのowner名・repo URLはリポジトリを識別するための情報として扱う。
- `.android/`、ビルドcache、local.properties、端末撮影物、ローカル検証ログ、転送helper、一時検査結果はGitにもソースZIPにも含めない。これらを公開用assetとして追加しない。
- APKに含まれる署名証明書とfingerprintは**公開鍵側の検証情報**であり、秘密鍵ではない。証明書名は一般的な`Android Debug`で、実ユーザー名ではない。固定鍵の新規生成・Secrets登録・他projectの秘密のコピーは行っていない。
- GitHub-hosted runnerの一般的な作業ディレクトリや一時hostnameは、利用者PCの絶対パスやPixelの固有識別子とは区別する。CIログに端末撮影物や実機の計測ログは含まれていない。
- GitHub Secretsの値を読むAPIや、秘密値の表示は実行していない。検査中にTLS/証明書検証を無効化する操作も行っていない。

## 公開前の残件

リポジトリ全体のpublic化について承認を確認し、追加分も検査して公開設定を変更した。
他branch・Git履歴・Release・Actionsログ/成果物も見える可能性を含め、最新branchだけの検査で代用しない。
今回の結果は上記の時点・範囲に限る。以後のPR、実機ログやサンプル追加は再確認する。

今後問題が見つかった場合、最新ファイルの削除だけで過去のpush済み履歴から消えたとは扱わない。秘密の値は報告せず、影響範囲と必要な対処を提示し、破壊的な履歴変更は利用者へ相談する。

main保護のプラン制約、配布先・SHA-256、実機未検証の状態は[REPOSITORY_STATUS.md](REPOSITORY_STATUS.md)を参照する。
