# 初期配置・配布の実績

確認日: 2026-11-01。P0の実機ACは未検証、P1は未判定のまま。

## リポジトリとbranch

- [reitojike/cinematic-camera](https://github.com/reitojike/cinematic-camera)を**private**で作成。
- 初期main commit: [`189f9ca8ddd3115c7ef04adb390bf6d1b4aed21b`](https://github.com/reitojike/cinematic-camera/commit/189f9ca8ddd3115c7ef04adb390bf6d1b4aed21b)。26ファイルの新規配置であり、既存mainへのPR mergeではない。
- `default_branch=main`、`allow_auto_merge=false`、merge/squash/rebaseは許可、`delete_branch_on_merge=true`。think-canvasの通常設定と一致する。
- 初期化後のこの実績文書は`chore/p0-repository-status`からDraft PRへ出す。mainへ直接追加push・mergeしない。
- 既存のRenovate連携が自動作成した[PR #1](https://github.com/reitojike/cinematic-camera/pull/1)は今回の作業とは別で、承認・merge・設定変更を行わない。

## main保護: BLOCKED

`POST /repos/reitojike/cinematic-camera/rulesets`で、[OPERATIONS.md](OPERATIONS.md)の予定どおりthink-canvas相当のrulesetを適用しようとしたが、HTTP 403で拒否された。

```text
Upgrade to GitHub Pro or make this repository public to enable this feature.
```

一般のmain branch情報でも`protected=false`を確認した。**server側の保護は未適用**。PR要求・会話解決・force push/削除禁止がサーバーで有効とは主張しない。privateを維持し、公開化・課金プラン変更・classic protectionへの迂回・bypass追加・同じ設定の再試行はしていない。

残る判断は、利用者自身がGitHub Proへ変更した後で保護を適用するか、当面は文書上のDraft PR運用で続けるかである。このタスクではプランや権限を変更しない。

## 自然CI

[main push / Android checks / run 41310544523](https://github.com/reitojike/cinematic-camera/actions/runs/41310544523)は、上記main SHAで成功。再実行は行っていない。

- lint / unit / assembleDebugと署名・applicationId・versionCodeの確認を通過。
- 取得したJUnit XML: 4 tests / failures 0 / errors 0 / skipped 0。
- 取得したlint XML: error 0、Gradle更新案内のwarning 1。
- [一時署名APK artifact](https://github.com/reitojike/cinematic-camera/actions/runs/41310544523/artifacts/13085307306)（7日保存）。artifact ZIPのdigestとAPK自体のSHA-256は別物として扱う。
- [テスト・lintレポート artifact](https://github.com/reitojike/cinematic-camera/actions/runs/41310544523/artifacts/13084719864)。

## P0配布

[配布run 41310943801](https://github.com/reitojike/cinematic-camera/actions/runs/41310943801)をmainから1回起動し、成功。tag `v0.0.1-dev.1`は上記main SHAのcommitを指すことを確認した。再実行・tag上書きは行っていない。

- [pre-release v0.0.1-dev.1](https://github.com/reitojike/cinematic-camera/releases/tag/v0.0.1-dev.1)
- [配布APK](https://github.com/reitojike/cinematic-camera/releases/download/v0.0.1-dev.1/cinematic-camera-p0.apk): 39,785,722 bytes
- APK SHA-256: `f55715cf80fbaa1e7ef058ef034c50706a722ca00c14d6879f0ce59cc8f1f9a1`
- [SHA256SUMS.txt](https://github.com/reitojike/cinematic-camera/releases/download/v0.0.1-dev.1/SHA256SUMS.txt)とGitHub asset digest、実際にダウンロードしたAPKのhashが一致。
- ダウンロードしたAPKをapksignerで検証し、v2署名が有効。証明書は`C=US, O=Android, CN=Android Debug`、証明書SHA-256は`6bfb144c67497aa10d26f36f8590e975d71efc4812b7b09eee8e6d899330122e`。
- aaptで`dev.cinematic.p0`、versionName `v0.0.1-dev.1`、versionCode `1`、minSdk `28` / targetSdk `37`を確認。

固定署名鍵の生成・Secrets登録・think-canvasの秘密のコピーは行っていない。P0は一時debug署名であり、別runのAPKとの上書き更新互換は保証しない。Pixel 9aへは利用者がGitHubにログインしてAPKを取得し、[配布・インストール手順](OPERATIONS.md#pixel-9aで受け取る)に従って手動でインストールする。

公開範囲は利用者の確認待ちで、現時点ではprivateを維持している。[公開前検査](PUBLICATION_REVIEW.md)を追加し、サーバー保護を有効にするためだけの公開化は行っていない。
