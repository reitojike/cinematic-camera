# 初期配置・配布の実績

確認日: 2026-11-01。旧版のインストール/カメラ操作にはユーザー報告があるが、P0の実機AC全体は未完了、P1は未判定のまま。

## リポジトリとbranch

- [reitojike/cinematic-camera](https://github.com/reitojike/cinematic-camera)をprivateで作成し、全履歴/配布物の検査後に利用者の明示承認で**public**へ変更した。
- 初期main commit: [`189f9ca8ddd3115c7ef04adb390bf6d1b4aed21b`](https://github.com/reitojike/cinematic-camera/commit/189f9ca8ddd3115c7ef04adb390bf6d1b4aed21b)。26ファイルの新規配置であり、既存mainへのPR mergeではない。
- `default_branch=main`、`allow_auto_merge=false`、merge/squash/rebaseは許可、`delete_branch_on_merge=true`。think-canvasの通常設定と一致する。
- 初期化後のこの実績文書は`chore/p0-repository-status`からDraft PRへ出す。mainへ直接追加push・mergeしない。
- 既存のRenovate連携が自動作成した[PR #1](https://github.com/reitojike/cinematic-camera/pull/1)は今回の作業とは別で、承認・merge・設定変更を行わない。

## main保護: 適用済み

`POST /repos/reitojike/cinematic-camera/rulesets`で、[OPERATIONS.md](OPERATIONS.md)の予定どおりthink-canvas相当のrulesetを適用しようとしたが、HTTP 403で拒否された。

```text
Upgrade to GitHub Pro or make this repository public to enable this feature.
```

private時は`protected=false`を確認して停止した。その後、利用者が別途リポジトリ全体のpublic化を明示承認した。
公開前検査後にpublicへ変更し、新しい状態で[Protect main 26313219](https://github.com/reitojike/cinematic-camera/rules/26313219)を適用した。
`protected=true`、active、削除/non-fast-forward禁止、PR/thread解決必須、bypass actorなしを確認済み。
プラン変更・classic protectionへの迂回は行っていない。

初回はthink-canvas同様server check名が空配列だったが、追加の運用指示に従い
`lint・unit・build`をGitHub Actions app `15368`からの必須checkに設定した。strictも有効。
APPROVE代行やmainへのmergeは行っていない。

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

固定署名鍵の生成・Secrets登録・think-canvasの秘密のコピーは行っていない。P0は一時debug署名であり、別runのAPKとの上書き更新互換は保証しない。
Pixel 9aへは利用者がAPKを取得し、[配布・インストール手順](OPERATIONS.md#pixel-9aで受け取る)に従って手動でインストールする。

追加したSpec Kitを含む[公開前検査](PUBLICATION_REVIEW.md)後にpublic化済み。privateでの保護制約とは別の、
利用者が明示した公開方針として扱う。

## 共有保存候補の状態

PR #2に新撮影のMediaStore共有保存を追加。ローカルbuild成功、unit 11件成功、lint error 0。
独立した1巡の保存処理レビューでは具体的な不具合は見つからなかった。実機のMediaStore保存と
JPEGの標準ギャラリー表示、DNGの閲覧アプリ対応は未確認。旧写真移行・一括削除は追加していない。

正式な次pre-releaseは、PRのmain取り込み許可とmain CI確認後に作る。今回は既存の内部テスト写真の
消失許可があるが、端末の削除/インストールをこちらから実行しない。一時debug署名の候補APKは
旧配布版と証明書が異なるため、そのまま上書き更新できない。固定鍵/継続利用の新規設定は未実施。
