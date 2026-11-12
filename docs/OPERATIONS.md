# リポジトリ運用とP0 APK配布

ロードマップv2（2026-11-12）の範囲内で本人が包括許可した一般試験・文書・CI・挙動不変整理等は、以下の「マージ許可」に含む。個別承認が必要な変更と安全停止条件は除外し、最終CI・canonical・独立レビュー・未解決0の条件は常に維持する。承認の範囲は末尾の「作業と承認の境界」を参照する。

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

**旧P0配布経路ではrunnerの一時debug署名を使う。現在の固定署名配布とは別経路。** think-canvasの固定PR鍵・internal鍵は一切流用しない。新しい固定鍵の生成・保管・Secrets登録・継続利用は別途確認が必要なため実行しない。通常製品のinternal release buildやObtainiumによる継続更新は、旧P0経路導入時の対象外だった。

同じapplicationIdでも、別runやローカル版と署名が違うと上書き更新できない。旧P0配布版の一時鍵は
継続用として保持していない。今回の旧内部テスト写真についてだけ、利用者が消失を許可した。
署名が違う候補を試す場合は、上書き不可と旧内部写真/ログが失われることを説明し、利用者自身が
旧アプリを削除・再インストールする。一般的なデータ消失の許可には広げず、こちらから端末を操作しない。
固定鍵生成/継続利用の条件は変えない。新しいMediaStore保存画像はアプリ内ファイルとは別に扱う。

## 専用固定署名の配布

現行の `internal-release.yml` は専用固定署名の配布に使用している。Environment `internal-release` / main 限定、applicationId `dev.cinematic.p0`、非 debuggable を維持し、他 project の鍵を流用しない。旧 P0 の一時 debug 署名配布は別経路である。

2026-11-12 の [v0.1.0-dev.1](https://github.com/reitojike/cinematic-camera/releases/tag/v0.1.0-dev.1) は versionCode 15、署名 1、既存公開証明書 SHA-256 `2E84FE61E2D9E71CD35F8D3235BE7F606E622B89F29EDCD9925E4BDA6BCE193C` と一致。公開 APK の hash / SHA256SUMS / GitHub asset digest を照合した。配布物は `cinematic-camera-internal.apk` / `SHA256SUMS.txt` / `SIGNING.txt`。この照合は端末更新、写真・設定保持、バックアップ復旧の確認を代替しない。

### 導入時の提案記録（Issue #27）

以下の未作成・未承認・未実施という記述は導入当時の状態を残したもの。現在の配布状態は上記と実 Release を参照する。新規鍵、署名設定変更、復旧操作への継続許可を意味しない。

| 対象 | 提案する具体値/条件 |
|---|---|
| repo / Environment | `reitojike/cinematic-camera` / `internal-release` |
| deployment branch policy | selected branch `main` のみ。PR branchで署名秘密を使わない |
| 専用key | 新しいPKCS12、RSA 3072 bit、有効期間10000日、仮alias `cinematic-internal`、証明書CN `Cinematic Camera Internal`。生成は承認後 |
| Environment Secrets | `CINEMATIC_INTERNAL_KEYSTORE_BASE64` / `CINEMATIC_INTERNAL_STORE_PASSWORD` / `CINEMATIC_INTERNAL_KEY_ALIAS` / `CINEMATIC_INTERNAL_KEY_PASSWORD` |
| Environment公開変数 | `CINEMATIC_INTERNAL_CERT_SHA256`。承認後に作った証明書の64桁hex。現時点では値を捏造/登録しない |
| build | `CINEMATIC_SIGNING_MODE=stable_internal` を明示した `assembleRelease --no-configuration-cache`。packageは `dev.cinematic.p0`、debuggable false |
| 配布物 | `cinematic-camera-internal.apk` / SHA256SUMS.txt / 公開証明書SIGNING.txt。既存tag上書き不可 |

default debug buildでは署名入力を要求しない。stable modeは入力不足/未知mode/configuration cache要求/ファイル欠落で停止し、
debugへ黙ってfallbackしない。署名前のlint/unitには秘密を渡さず、鍵をrunner tempへ復元してbuild後always削除する。
単一署名とpin/package/version/targetSdkを確認してからReleaseへ保存し、秘密をartifact/ログへ含めない。
固定署名jobではGradle Actionsのcacheを無効化し、署名buildはbuild cache / configuration cache / Build Scanを使わない。
checkoutの認証情報は永続化せず、Release API用tokenは必要なstepだけへ渡す。通常のDebug CIと既存debug配布は変更しない。

新しい固定証明書のfingerprintは未作成で、GitHub上のEnvironment/Secrets/変数作成・固定署名workflow実行・安定更新の実機確認も未実施。
local-onlyで既存当projectのdebug keyをfixtureとして使う場合も、それは新しい固定署名keyでも配布APKでもない。
鍵の値をAPIで読み戻したり、他projectの鍵を流用したりしない。

承認後はEnvironmentのmain限定policyを先に適用し、専用keyを安全にバックアップして4Secretsと公開pinを登録する。
値をログ/コマンド引数へ出さず、正規CLIのstdin等で登録する。新しいtag/承認済みmain SHAで手動dispatchし、
署名/ハッシュ/上書き更新を確認する。internal workflowのversionCodeはそのworkflow内のrun_numberを使う。
全ページの既存Release（draftを含む）のうち`cinematic-camera-internal.apk`を持つものを照合し、
Release本文の`<!-- cinematic-internal-release: versionCode=N certSha256=HEX -->`の最大番号以下なら署名前に停止する。
markerを持つReleaseも資産名にかかわらず検査し、履歴の証明書SHA-256が公開pinと違う場合は停止する。
本文のmarkerが欠落・不正・重複した場合やAPI取得失敗も停止する。旧runの再実行やworkflowの作り直しで番号が後退したら、
既存の最大番号を越える採番方針を別途reviewする。過去の固定署名Release・APK・番号markerを削除/改変して回避しない。

最初の固定署名版は現在の一時debug版と署名が異なるため、上書き更新できない。必要な写真/ログ/設定の退避と
初回の手動移行は本人が判断する。以後も同じ鍵と増加versionCodeを維持する。端末の削除/インストールは自動実行しない。
本Draftは夜間の通常機能merge許可だけでは取り込まず、上記の別途明示承認を待つ。

### 初回移行でデータを失わないための停止条件

同じapplicationIdの異なる署名への置換は通常の更新ではない。現行manifestは`allowBackup=false`で、
cloud backup / device transferも除外しており、Androidの自動復元を移行手段として約束しない。
旧APK・既存Release・旧アプリは準備中に削除しない。次を本人が確認できるまで初回移行を止める。

1. `Pictures/Cinematic Camera/`のJPEG/DNGと`Movies/Cinematic Camera/`の動画をアプリ外の独立した保存先へコピーし、
   件数・サイズ・hashと実際に開けることを確認する。MediaStoreへの保存とバックアップ完了は別である。
2. アプリ専用領域の旧撮影物・`files/reports/`のログが必要なら、それぞれの取り出し可否を先に確認する。
   検証レポートの共有は現在のログ末尾（最大128KiB）であり、全セッションの完全な書き出しではない。
   取り出せない必要データがある場合は、旧アプリを保持して移行方法を別途検討する。
3. `capture-probe`の端末内設定には完全なexport/import機能がない。必要な設定値を控え、手動再設定できるか確認する。
   設定の完全保持が必須なら、現提案だけでは初回移行できない。別途移行設計が必要である。
4. 退避が検証でき、データ消失の範囲と初回導入方法を本人が別途判断した後にだけ端末移行へ進む。
   以後の固定署名更新でも写真・設定を保持できたか本人が実機確認する。ここでは削除/再インストールを許可されたと扱わない。

### 鍵のバックアップ・復旧と配布停止

鍵の生成前に、本人が保管先・管理者・復旧方法を決める。提案は、repo/ソースZIP/Actions artifact/cacheから分離した
暗号化バックアップを独立した2か所へ置き、パスワードを鍵と別に管理すること。PKCS12とalias、公開証明書SHA-256、
有効期限、最後に配布したversionCodeを復旧台帳へ残す。鍵やパスワードそのものをIssue/PR/会話/コマンド引数へ書かない。
GitHub Secretsは配布用の写しであり、値を読み戻せないためバックアップの代わりにしない。

登録前の復旧確認ではバックアップのコピーを保護された一時領域へ戻し、同じaliasで開けること、証明書pinが一致すること、
ローカルの非配布fixtureを署名・検証できることを確かめる。新規の鍵/保管/復旧試験は承認後にだけ行う。
一時コピーは試験後に削除するが、原本・バックアップ・既存配布APKは削除しない。

Secretsの欠落/誤設定なら配布を止め、承認後に同じ鍵のバックアップから復旧する。pin不一致をpinの書換えで回避しない。
鍵紛失・破損で有効なバックアップがなければ既存アプリへの継続更新はできない。新規鍵を同じ鍵として扱わず、
別のアプリIDまたは初回移行を含む設計を本人と再検討する。漏えいが疑われた場合も署名・配布を止め、影響と対応を別途判断する。
Play App Signingの鍵再設定や署名rotationはこの経路には導入していない。

不具合版から戻す場合は、レビューした既知の正常ソースを同じ鍵・より大きなversionCodeで改めて配布する。
旧APKへのdowngradeやデータ消去で戻さず、データ形式の後方互換性を確認できない場合は配布を止める。
端末の初回導入・同一署名の2版間更新・写真/設定保持・復旧は各確認記録で判断する。CIや公開APK照合だけで確認済みとしない。

根拠: [Androidのアプリ署名](https://developer.android.com/studio/publish/app-signing)、
[versionCode](https://developer.android.com/studio/publish/versioning)、
[GitHub run_number / run_attempt](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts)、
[Gradle Actions cache設定](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md)。

## v0.1.0 正式版の配布方法（計画・未実装）

現行 `internal-release.yml` は `vX.Y.Z-alpha.N` / `vX.Y.Z-dev.N` のみを受け付け、常に pre-release を作る。正式版 v0.1.0 は現行のままでは配布できない。

- 既存 workflow の最小拡張として、正式タグの厳密検証と正式タグ時だけの非 pre-release 分岐を別 PR でレビューする。署名・main 限定・秘密の扱い・公開前検証は維持する。dev 版の表示だけを変更せず、APK versionName が正式タグと一致する新規 build を行う。
- 同じ固定署名・applicationId・workflow run_number 採番を維持する。全ページ・draft を含む既存配布の最大番号より大きい versionCode だけを許可する。dev.1 の 15 を根拠に次を 16 と予約しない。
- v0.1.0 はロードマップv2のCPU/診断経路削除・試験移動・GPU前提整理を対象とする。D0で設計し、GPU回帰を前提に数日の本人使用結果と致命的不具合なしを確認してCPU削除、その後D1〜Dnの小PR、続いてMesa全工程の基準画像比較CI移行へ進む。詳細は #290 / [GPU_ENGINE](GPU_ENGINE.md)。基準の採用・更新は本人判断。
- 24MP/SIGMA fpを最優先とし、別ファイルならv0.1.0と並行、競合箇所は24MPを先にする。v0.1.0は24MP完成を待たず、完成済みなら含める。24MP完成時のdev.N配布も都度承認を得る。
- 配布中dev.1の保存取消後の設定変更（#357）は今すぐ並行する。GPUの24MP保存と保存状態コードが競合する場合のみ24MPを優先し、#357の競合部分を後に回す。確認済み取消だけ新世代保存を許し、公開状態不明・cleanup未確認のHOLDを維持する。保存挙動の変更内容をマージ前に本人へ提示し、確認を受ける。
- 様子見結果、未解決 Issue の扱い、最終 head の CI/review/未解決 thread、必要回帰を確認し、対象 main SHA と残課題を提示して正式配布の承認を得る。計画や dev 配布の承認を正式配布へ広げない。
- 承認後、未使用タグと指定 SHA で実行する。公開 APK の署名・package・version・非 debuggable・hash、tag commit を再照合する。既存タグ・配布物は上書きしない。
- 問題時は既知の正常 source を同じ署名・より大きな versionCode で再配布する。旧 APK への downgrade やアプリ削除で戻さない。

正式配布前の最低限の本人実機確認はプレビューと保存を各1回とし、実機操作・配布は個別承認する。applicationIdを変えると既存アプリの更新ではなく別アプリとなり、同一IDでも署名が異なると通常の上書き更新はできない。IDや署名の変更だけで既存データが自動消失するわけではないが、アンインストールすると内部データは消失するため回避手段として行わない。

本節は方法の計画のみ。workflow、鍵、署名設定、Release、端末の操作は変更していない。

### 作業と承認の境界（ロードマップ v2）

計画内の一般試験・文書・CI、挙動不変の整理、依存を確認した診断/旧資料の整理、前提達成後のCPU削除、承認範囲で意図した挙動に戻す不具合修正は自律的に進め、結果を報告する。通常のCI・canonical/独立レビュー等のマージ条件は省略しない。
新機能・UI・見た目、GPU基準画像採用/更新、配布、実機操作、原本/保存データ影響、権限・通信・容量へ大きく影響する依存追加は都度承認する。「迷う場合は進めて報告」はこの個別承認と安全停止条件を除く範囲に限る。
原本変化、別写真混入、不正な資源解放、意図しない再試行、GPU帯/full不一致は停止して確認する。CPU参考差や旧速度目標未達と混同しない。

## Pixel 9aで受け取る

1. Androidのブラウザーで該当pre-releaseを開く。現在はpublic。Actions artifactはGitHubのrun画面から取得する。
2. 固定署名配布ではAssetsの`cinematic-camera-internal.apk`と`SHA256SUMS.txt`を取得する。旧P0配布のAPK名は`cinematic-camera-p0.apk`であり混同しない。正確なtagのページを使う。artifactを使う場合はActions runのArtifactsからZIPを取得・展開する。
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
