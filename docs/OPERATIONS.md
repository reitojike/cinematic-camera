# リポジトリ運用とP0 APK配布

2026-11-01に`reitojike/think-canvas`の現在のmain、実際のruleset、CIと配布手順を読み取りで確認した。
独立した`reitojike/cinematic-camera`をprivateで初期化し、公開前検査後に利用者の明示承認でpublicへ変更した。
think-canvasのソース・設定・署名秘密は変更・コピーしない。

## 初期化と以後の変更

初回のみ、検証済みP0ソース・最小CI・この運用手順をmainの最初のcommitとして配置する。これは空の新規リポジトリの初期化であり、既存mainへのPR mergeではない。初期commit後にmain保護を適用・確認する。

以後は`feat/...`、`fix/...`、`chore/...`などの作業branchからmainへ**Draft PR**を作る。build / unit / lint、現在のheadに対するレビューと未解決会話、mainとの差分を確認し、利用者のmerge許可がなければmerge前で停止する。APPROVEの代行、admin merge、チェック免除、force pushによる回避は行わない。

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
- `CINEMATIC_VERSION_NAME=0.0.2-p0.ci.<run_number>`、`versionCode=run_number`（初版は0.0.1）。
- 権限は`contents: read`。外部リポジトリの秘密を使わない。
- GMD、実機テスト、Room schema、隔離テスト、製品固有の公開境界スクリプトは移植しない。P0は11件のJVMテストと手動実機マトリクスが対象。
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

## Pixel 9aで受け取る

1. Androidのブラウザーで該当pre-releaseを開く。現在はpublic。Actions artifactはGitHubのrun画面から取得する。
2. Assetsの`cinematic-camera-p0.apk`と`SHA256SUMS.txt`を取得する。正確なtagのページを使う。artifactを使う場合はActions runのArtifactsからZIPを取得・展開する。
3. 初回はダウンロードに使ったアプリの「不明なアプリのインストール」をAndroid設定で許可する。署名不一致なら、既存P0の撮影物・ログを先に退避する。
4. APKを手動インストールし、Camera permissionを許可する。P0の撮影は利用者の操作で行う。
5. [P0_REPORT.md](P0_REPORT.md)の許可・lifecycle・AUTO・RAW5回・ISO2値・露光時間2値・AUTO復帰を実施する。DNGはPC viewerで全件開く。

実機ACは全て未検証、P1は未判定のまま。配布URLと実測hashは、作成後にGitHubの実Release / runと照合して作業結果へ記録する。

## 読み取った参照元

- [think-canvasの変更・レビュー運用](https://github.com/reitojike/think-canvas/blob/main/docs/runbooks/change-review.md)
- [main ruleset](https://github.com/reitojike/think-canvas/rules/24034192)
- [Android CI](https://github.com/reitojike/think-canvas/blob/main/.github/workflows/android.yml)
- [Internal APK release](https://github.com/reitojike/think-canvas/blob/main/.github/workflows/internal-release.yml)
- [Android実機配布](https://github.com/reitojike/think-canvas/blob/main/docs/runbooks/device-verification.md)
- [実際のpre-release v0.1.0-alpha.13](https://github.com/reitojike/think-canvas/releases/tag/v0.1.0-alpha.13)

参照時点のファイルSHA: Android CI `9094815f7dc7b6ab5ccfebd05512b65c6962e5b3`、Internal release `8266b53b714b0e5b8e743412b6b2096e2e430063`、device-verification `7dbe3cb71bd79f8d3855e00101c815d5d0bb9fe5`。
