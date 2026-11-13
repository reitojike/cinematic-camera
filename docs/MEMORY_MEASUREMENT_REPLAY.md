# メモリ計測の再実行・履歴保全（#260 / #379）

## 目的と現在の扱い

CPU・診断経路の整理後も、#379 で行った計測のソース、手順、停止条件と結果を再確認できるようにする。
これは過去の計測版を再構築する手順であり、現在の main の製品実装・安全性を検証済みとする手順ではない。
計測版を main へ一括マージせず、通常配布版の判定を record-only へ戻さない。CPU への復帰経路も作らない。

- 計測版の固定ソース：`affee9b172a51d769074da4624708ccce8238a1f`
- [元の PR #379](https://github.com/reitojike/cinematic-camera/pull/379)
- [固定版の詳しい操作・監視手順](https://github.com/reitojike/cinematic-camera/blob/affee9b172a51d769074da4624708ccce8238a1f/docs/MEMORY_DIAGNOSTIC.md)
- [固定版の採取・監視・回帰試験](https://github.com/reitojike/cinematic-camera/tree/affee9b172a51d769074da4624708ccce8238a1f/tools/memory-diagnostic)
- [Pixel 12MP/24MP・8セッションと修正版予行の公開結果](https://github.com/reitojike/cinematic-camera/issues/260#issuecomment-6847823311)
- 製品の現在の判断・追加結果：[#260](https://github.com/reitojike/cinematic-camera/issues/260)。開始時 OS lowMemory による製品判定は #383、保存復旧は #365 / #377。

固定版の文書には「未実測」や当時の768MiB方針が残る。これは当時の履歴であり、現在の製品方針や未完了状態とは区別する。
当時の失敗・欠測記録を成功へ書き換えない。旧採取器の終了記録なし消失と、修正版の実ADB予行成功も別記録である。

## 再構築の条件

既存の作業ツリーやブランチを切り替えず、未使用の専用ディレクトリに固定 commit の detached worktree を作る。
既存の dirty / untracked / stash / 計測生ログ・端末データを消さない。下記の `<...>` は操作者が確認して置き換える値であり、そのまま実行しない。

```sh
git fetch origin chore/issue260-memory-diagnostic
git cat-file -t affee9b172a51d769074da4624708ccce8238a1f
git worktree add --detach <新規の計測用ディレクトリ> affee9b172a51d769074da4624708ccce8238a1f
```

ブランチが見つからない、または固定 commit を取得できない場合は停止する。別 commit で代用しない。
閉じる際も当該ブランチと commit の参照を保全し、ブランチ削除や履歴書換えを同伴させない。

専用ディレクトリで AGENTS.md、MANAGED_DEVICES.md、元の MEMORY_DIAGNOSTIC.md を読む。
JDK / Android SDK / NDK / Python は固定ソースの wrapper・build 設定と実行環境を照合する。再構築時のツール版を記録し、
過去の APK と同じバイナリになるとは約束しない。Windows では `./gradlew` を `.\gradlew.bat` に読み替える。

```sh
python3 -B -m unittest discover -s tools/memory-diagnostic -v
./gradlew :app:testMemoryDiagnosticUnitTest --tests '*MemoryAdmissionDiagnosticTest' --tests '*DevelopEngineTest' :app:assembleMemoryDiagnostic :app:assembleMemoryDiagnosticAndroidTest :app:generateDebugBuildConfig :app:generateReleaseBuildConfig :app:processDebugMainManifest :app:processReleaseMainManifest -PcinematicMemoryDiagnostic=true --no-daemon
python3 -B tools/memory-diagnostic/verify_isolation.py app/build
```

隔離条件は別 applicationId `dev.cinematic.p0.memorydiagnostic`、label `Cinematic MEMORY DIAGNOSTIC`、
一時 debug 署名、debuggable、flag / buildType / package の三条件で record-only。通常版は flag=false。
`stable_internal` との組合せを拒否することも固定版の CI 手順どおり確認する。固定署名秘密を計測版へ渡さない。

通常アプリへの上書き、通常版の journal の移植、配布はしない。古い計測版は最新の #365 / #377 等を含むとは限らず、
現行製品の保存復旧を評価する場合は、その対象 source で別途計測実装をレビューし、履歴版で代替しない。

## 開始前の予行

同じ最終 head の APK / test APK について commit、hash、ID、署名、manifest、GPU 既定を確認する。
GPU対応の通常 AVD を共有端末の手順で予約し、専用 runner
`dev.cinematic.p0.memorydiagnostic.test/androidx.test.runner.AndroidJUnitRunner` を使う。
既存の端末/アプリを強制終了して空きを作らない。

元文書の手順で次を実行し、コンパイル成功・0件・skipを合格にしない。
- `GpuProductInputAndroidTest#memoryDiagnosticDefaultGpuEmitsTextureLedgerAndReleasesOwners`
- `ForegroundExportOwnerTest#memoryDiagnosticDocumentsUiEditsPublishesAndReleasesWithRealGpuLogs`

`isolated=true`、後者は `importUiFgsDocuments=true` が必要。AVD 合成確認は Pixel/Mali の実測を代替しない。
長時間採取の再確認が必要なら、元文書の opt-in 21分予行を使う。0/8/16分の3周期、JUnit、
監視正常終了、PSS欠落なしと回収をまとめて判定する。`am instrument` の OS exit0だけで成功としない。

## PC監視と操作の対応

インストール、素材選択、実機計測は、操作内容と所要時間を示して本人の開始確認を得てから行う。
計測のために他アプリを閉じたり、人工的にメモリを圧迫しない。原本の変更・写真の公開は行わない。

監視は対話ターミナルの寿命から独立した PC プロセスで開始する。
Windows の `Start-Process` は `-WindowStyle Hidden` を使い、既存 Python のパスを確認する。
新規出力先を使い、実機 serial はローカルだけで扱う。

```sh
python3 tools/memory-diagnostic/supervisor.py --adb <adb実行ファイル> --serial <対象serial> --out <新規ローカル出力先> --seconds 3600
python3 tools/memory-diagnostic/check_session.py <同じ出力先>
```

次操作の条件は毎回 `ready_for_operator_next_step=true`。監視 heartbeat 5秒以内、同一PIDのPSS 15秒以内、
GPU / recordOnly / decode / 正のtexture予約の前提、監視と採取の両方に停止要求なしを要求する。
一度の成功値を使い回さない。監視欠落・PSS欠測・PID変更・失敗は、後で採取が復帰しても失敗として残す。

終了は出力先直下に `stop.request` を作り、採取器を正常終了させる。端末アプリkill・ログ消去・データ削除はしない。
`status.json` と `collector/observations.jsonl` の終了状態、`COLLECTOR_STOP`、監視と採取の終了を照合する。
異常時は自動再起動・自動再試行しない。画面エラー、原本変化、公開状態不明、owner不明は操作者も停止判断する。

元の基本計測は30〜40分、再接続・照合込み45〜60分が目安。
12MPの開く→調整/比較→保存を3回、24MPを3回、単独成功後にA保存+Bオープンを各1回。
全8回を毎回義務化せず、目的に必要な条件と所要を開始前に提示する。
自然な低空きメモリでの確認は別条件として記録し、通常空き量での成功を低メモリ成功に読み替えない。

## 記録と解釈

- ActivityManager の availMem / lowMemory / threshold、判定の要求値、画素数または unknown、
  decode / preview / save の時刻、同一PIDの PSS/RSS・Java/native/Graphics、GPU台帳を対応付ける。
- 約0.5〜1秒間隔の採取最大は真の瞬間最大ではない。GPU 台帳は論理予約であり、Mali の全常駐RAMではない。
  PSS・Graphics・台帳、および異なる時刻の最大値同士を加算しない。
- OS終了の分類は自アプリの ApplicationExitInfo 等の理由・時刻と照合する。
  PID消失だけで OOM と断定せず、過去の終了履歴や試験runner終了を今回のOS killと混同しない。
- 他アプリの低メモリ終了は、OSが提供する終了理由の証拠が得られる場合のみ判定する。
  他アプリ名・内容・コマンドラインを保存/公開しない。未取得なら未確認、availMem増加だけでOS回収と断定しない。
- 写真、URI、素材名、端末識別子、個人用パス、生ログは Git/PR に載せない。公開は集計・固定分類だけ。
  非公開の元記録は勝手に削除しない。
- OS kill は捕捉可能な確保失敗とは別。公開不明時の HOLD を解除したり自動再保存しない。

## #379 を閉じる条件

1. この参照文書が main に入り、固定 commit の元手順・採取器・試験ソースを引き続き取得できる。
2. CPU/診断整理の担当が参照先と保持資産を確認し、未統合の製品変更を統合済みと記載しない。
3. #379 は「計測完了・手順保全、mainへの一括マージなし」として閉じる。#260 の未確認事項は別に維持する。
4. 本文書の作成だけで、新しい APK の再構築・予行・実機再計測を実施したことにはしない。
