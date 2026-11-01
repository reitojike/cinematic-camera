# リポジトリ運用とP0 APK配布

2026-11-01に`reitojike/think-canvas`の現在のmain、実際のruleset、CIと配布手順を読み取りで確認した。
独立した`reitojike/cinematic-camera`をprivateで初期化し、公開前検査後に利用者の明示承認でpublicへ変更した。
think-canvasのソース・設定・署名秘密は変更・コピーしない。

## 初期化と以後の変更

初回のみ、検証済みP0ソース・最小CI・この運用手順をmainの最初のcommitとして配置する。これは空の新規リポジトリの初期化であり、既存mainへのPR mergeではない。初期commit後にmain保護を適用・確認する。

以後は`feat/...`、`fix/...`、`chore/...`などの作業branchからmainへ**Draft PR**を作る。build / unit / lint、現在のheadに対するレビューと未解決会話、mainとの差分を確認し、利用者のmerge許可がなければmerge前で停止する。APPROVEの代行、admin merge、チェック免除、force pushによる回避は行わない。

## 最終headの独立レビューとマージ条件

think-canvasの[現行収束手順](https://github.com/reitojike/think-canvas/blob/main/docs/runbooks/post-pr-convergence.md)に従う。
本P0には独自のGMD/IME quarantineを移植せず、実際のAndroid CIとserver設定にある必須チェックを使う。

開発は原則Issueを起点とし、既存の適切なIssueを再利用する。現在/直近の限定作業だけを起票し、
目的/背景/範囲/対象外/AC/検証・停止条件を定めてからbranch/PRを関連付ける。
途中からIssueを整備した場合も事実に沿って記録し、先にIssueが存在したように扱わない。

1. baseはmain、最新base/headとmergeabilityを確認する。behind・不明なら更新後のheadで再検証する。
2. 最終candidate headを固定し、実際に必須のCIが全て成功していることを確認する。
3. GitHubのPRへtop-levelの `@codex review` を依頼する。最後の依頼より後で、現行headに対応すると
   確認できるfindingなし、または指摘解消済みの結果をcanonical evidenceとする。時刻/対象が不明ならHOLD。
4. 作者自身のself-reviewを独立承認に数えない。実装担当と独立したreviewerの明示的OKを記録し、
   P1/P2等のmaterial findingを解消する。独立agentの結果とGitHubのformal APPROVEは別物であり、
   formal APPROVEを代行しない。別providerの利用は保存・privacy・署名・レビュー手順等のリスクに応じ検討する。
5. outdatedを含む全threadを確認して未解決0件にする。適用外も理由を記録し、必要な会話を勝手に消さない。
6. 上記と作業範囲内のmerge許可が揃う場合だけ通常mergeする。レビュー後のhead変更ではCI/レビューを再取得する。

canonical結果・独立OK・CI・base/threadの証拠がmissing/pending/unknown/stale/failedならmergeはHOLD。
待機中も独立して安全な別作業は続けられるが、代替経路やadmin bypassでmergeしない。
GitHub Copilot/CodeRabbitの指摘は参考であり、canonical Codex reviewの代用にはしない。

bounded correctionは原則2 roundまで。同じfamilyの指摘が繰り返される場合、または3 round目が必要な場合は、
read-onlyで関連surfaceとauthority/責務/rollback境界を確認し、限定補正・別責務のfollow-up・設計再検討を選ぶ。
回数を避けるためのfinding細分化、blind rerunや閾値緩和はしない。収束した最終headでcanonical reviewを依頼する。

## RenovateとCodeRabbit

Renovateは既存のonboarding PRを確認して有効化し、重複config/Issueを作らない。
依存PRにも最終headの独立レビュー・canonical review・必須CI等を適用する。無条件automergeは有効にしない。
CameraX 1.6.2等の合意した固定版を更新候補の存在だけで変更せず、対象依存の仕様条件を先に確認する。

CodeRabbit自動レビューは本repoの `.coderabbit.yml` の `reviews.auto_review.enabled: false` で停止する。
他repo/account-wide設定、GitHub App権限やOAuthは変更しない。設定反映と実際のbot結果を区別して確認する。

## main保護の差分と適用予定

新規repoの初期状態は保護なし。think-canvasの[active ruleset 24034192](https://github.com/reitojike/think-canvas/rules/24034192)に合わせ、次を適用する計画とする。

| 項目 | 設定 |
|---|---|
| 対象 | `refs/heads/main`、active |
| main削除 | 禁止 |
| non-fast-forward / force push | 禁止 |
| mainへの更新 | PRを要求 |
| required approval数 | 0（think-canvasと同じ。利用者のmerge承認は別の作業境界） |
| review会話 | 解決を要求 |
| status check | strict、`lint・unit・build`をGitHub Actions (app 15368)から必須化 |
| bypass actor | なし |

think-canvasではserver側のcheck名が空配列だった。初回はそれに合わせたが、その後の運用指示に従い、
本P0では実際のCIチェックをserver側でも必須にした。既存保護の弱体化やbypass actor追加はしない。
private時はプラン制約で403となり停止した。公開は別途得た利用者の明示承認に基づき、その後保護を適用した。

think-canvasのclassic branch-protection APIは接続機能の権限で403となったため、その経路での確認は未完了。上記は読み取り成功した実rulesetを根拠にしている。作成後の実際の設定状態はGitHub側で確認し、作業結果に記録する。

## 最小CI

[Android checks](../.github/workflows/android.yml)はPR、main push、mainからの手動実行に対応する。JDK 25、SDK 37.0 / Build Tools 36.0.0、Gradle wrapper、lint / unit / assembleDebugを使う点をthink-canvasに揃える。

- P0用の一時debug署名APKを`cinematic-camera-p0-debug-apk-ephemeral` artifactへ保存（7日）。SHA-256と署名証明書を記録する。
- `CINEMATIC_VERSION_NAME=<現在のprobe版>.ci.<run_number>`、`versionCode=run_number`。正確な版はworkflowとAPK metadataで確認する。
- 権限は`contents: read`。外部リポジトリの秘密を使わない。
- GMD、実機テスト、Room schema、隔離テスト、製品固有の公開境界スクリプトは移植しない。現行JVMテストの件数と保証範囲は[P05_REPORT](P05_REPORT.md)、P0は[P0_REPORT](P0_REPORT.md)を確認する。
- CI greenをPixel 9aの実機合格へ読み替えない。

## P0 pre-release

[P0 APK release](../.github/workflows/p0-release.yml)はmainからの明示的な手動実行のみ。think-canvasと同じく、新しい`vX.Y.Z-alpha.N` / `vX.Y.Z-dev.N`とmainに含まれる40桁commit SHAを指定する。既存tagの上書きは拒否する。

指定commitのlint / unit / debug buildを再確認し、署名とapplicationId / versionCodeを検証して、repoのpre-releaseへ次を添付する。

- `cinematic-camera-p0.apk`
- `SHA256SUMS.txt`
- `SIGNING.txt`（公開証明書のfingerprintのみ。秘密鍵は含まない）

versionNameはtag、versionCodeはこのrelease workflowのrun_numberとする。同じrunの再実行は同じversionCode。固定ファイル名、明示tag、commit・run・hashによる識別、`releases/latest`を使わない点をthink-canvasに揃える。release jobだけ`contents: write`を使い、GitHubが発行するそのrunの標準tokenでReleaseを保存する。

**P0ではrunnerの一時debug署名を使う。継続更新用の固定署名は未導入。** think-canvasの固定PR鍵・internal鍵は一切流用しない。新しい固定鍵の生成・保管・Secrets登録・継続利用は別途確認が必要なため実行しない。通常製品のinternal release buildやObtainiumによる継続更新も今回の対象外。

同じapplicationIdでも、別runやローカル版と署名が違うと上書き更新できない。現配布版の一時鍵は
継続用として保持していない。今回の旧内部テスト写真についてだけ、利用者が消失を許可した。
署名が違う候補を試す場合は、上書き不可と旧内部写真/ログが失われることを説明し、利用者自身が
旧アプリを削除・再インストールする。一般的なデータ消失の許可には広げず、こちらから端末を操作しない。
固定鍵生成/継続利用の条件は変えない。新しいMediaStore保存画像はアプリ内ファイルとは別に扱う。

## Draft提案: 専用固定署名（Issue #27、未承認・未適用）

この節と `internal-release.yml` は承認前の提案。現行の `p0-release.yml` は一時debug署名のまま維持する。
think-canvasの現行internal-releaseはEnvironment・専用4Secrets・証明書pin・main限定dispatch・一時鍵cleanupを使う。
その構造だけを本repoへ適合し、既存projectの鍵/Secret値/Environment設定をコピーしない。

承認対象は次の一式。mainへの本Draft取り込み、専用key生成/安全な保管、Environment設定と継続利用、初回stable配布を含む。

| 対象 | 提案する具体値/条件 |
|---|---|
| repo / Environment | `reitojike/cinematic-camera` / `internal-release` |
| deployment branch policy | selected branch `main` のみ。PR branchで署名秘密を使わない |
| 専用key | 新しいPKCS12、RSA 3072 bit、仮alias `cinematic-internal`、証明書CN `Cinematic Camera Internal`。生成は承認後 |
| Environment Secrets | `CINEMATIC_INTERNAL_KEYSTORE_BASE64` / `CINEMATIC_INTERNAL_STORE_PASSWORD` / `CINEMATIC_INTERNAL_KEY_ALIAS` / `CINEMATIC_INTERNAL_KEY_PASSWORD` |
| Environment公開変数 | `CINEMATIC_INTERNAL_CERT_SHA256`。承認後に作った証明書の64桁hex。現時点では値を捏造/登録しない |
| build | `CINEMATIC_SIGNING_MODE=stable_internal` を明示した `assembleRelease --no-configuration-cache`。packageは `dev.cinematic.p0`、debuggable false |
| 配布物 | `cinematic-camera-internal.apk` / SHA256SUMS.txt / 公開証明書SIGNING.txt。既存tag上書き不可 |

default debug buildでは署名入力を要求しない。stable modeは入力不足/未知mode/configuration cache要求/ファイル欠落で停止し、
debugへ黙ってfallbackしない。署名前のlint/unitには秘密を渡さず、鍵をrunner tempへ復元してbuild後always削除する。
単一署名とpin/package/version/targetSdkを確認してからReleaseへ保存し、秘密をartifact/ログへ含めない。

新しい固定証明書のfingerprintは未作成で、GitHub上のEnvironment/Secrets/変数作成・固定署名workflow実行・安定更新の実機確認も未実施。
local-onlyで既存当projectのdebug keyをfixtureとして使う場合も、それは新しい固定署名keyでも配布APKでもない。
鍵の値をAPIで読み戻したり、他projectの鍵を流用したりしない。

承認後はEnvironmentのmain限定policyを先に適用し、専用keyを安全にバックアップして4Secretsと公開pinを登録する。
値をログ/コマンド引数へ出さず、正規CLIのstdin等で登録する。新しいtag/承認済みmain SHAで手動dispatchし、
署名/ハッシュ/上書き更新を確認する。internal workflowのversionCodeはそのworkflow内のrun_numberで単調増加する。

最初の固定署名版は現在の一時debug版と署名が異なるため、上書き更新できない。必要な写真/ログ/設定の退避と
初回の手動移行は本人が判断する。以後も同じ鍵と増加versionCodeを維持する。端末の削除/インストールは自動実行しない。
本Draftは夜間の通常機能merge許可だけでは取り込まず、上記の別途明示承認を待つ。

## Pixel 9aで受け取る

1. Androidのブラウザーで該当pre-releaseを開く。現在はpublic。Actions artifactはGitHubのrun画面から取得する。
2. Assetsの`cinematic-camera-p0.apk`と`SHA256SUMS.txt`を取得する。正確なtagのページを使う。artifactを使う場合はActions runのArtifactsからZIPを取得・展開する。
3. 初回はダウンロードに使ったアプリの「不明なアプリのインストール」をAndroid設定で許可する。署名不一致なら、既存P0の撮影物・ログを先に退避する。
4. APKを手動インストールし、Camera permissionを許可する。P0の撮影は利用者の操作で行う。
5. [P0_REPORT.md](P0_REPORT.md)の許可・lifecycle・AUTO・RAW5回・ISO2値・露光時間2値・AUTO復帰を実施する。DNGはPC viewerで全件開く。

P0のユーザー報告と未取得証拠は[P0_REPORT](P0_REPORT.md)、P0.5の実機未確認条件は[P05_REPORT](P05_REPORT.md)を確認する。
P0のP1判断案GO_WITH_LIMITATIONSは、P0.5の実機合格やP1実装完了を意味しない。配布URLとhashは実Release / runへ記録する。

## 読み取った参照元

- [think-canvasの変更・レビュー運用](https://github.com/reitojike/think-canvas/blob/main/docs/runbooks/change-review.md)
- [main ruleset](https://github.com/reitojike/think-canvas/rules/24034192)
- [Android CI](https://github.com/reitojike/think-canvas/blob/main/.github/workflows/android.yml)
- [Internal APK release](https://github.com/reitojike/think-canvas/blob/main/.github/workflows/internal-release.yml)
- [Android実機配布](https://github.com/reitojike/think-canvas/blob/main/docs/runbooks/device-verification.md)
- [実際のpre-release v0.1.0-alpha.13](https://github.com/reitojike/think-canvas/releases/tag/v0.1.0-alpha.13)

参照時点のファイルSHA: Android CI `9094815f7dc7b6ab5ccfebd05512b65c6962e5b3`、Internal release `8266b53b714b0e5b8e743412b6b2096e2e430063`、device-verification `7dbe3cb71bd79f8d3855e00101c815d5d0bb9fe5`。
