# 非GPU Android試験の ATD CI 移行案

## 結論と現在地

GitHub Actions の標準 Ubuntu runner で、既存 `pixel9aapi37atd` を使う限定試行は可能性が高い。
ただし、この調査では Actions 上のATD実走はまだ行っていない。task解決・build・boot・実テスト・画像回収を別々に確認し、全てが成立してから日常CIに採用する。現在の必須checkやGPUゲートは変更しない。

調査時のmain: `c428945a`。#428の製品CPU撤去、#425の画面文言変更と衝突する実装は含めない。

## 最初の明示allowlist（8 test methods）

| 対象 | 件数 | 保証と制限 |
|---|---:|---|
| `SaveTimingAndroidTest#shellExtractsFixedSnapshotButAppUidCannotReadOrWrite` | 1 | 保存時間記録のshell抽出・app UID拒否。クラスの他2件はGPU保存/benchmarkなので対象外 |
| `ManualTrialAttemptStorageAndroidTest` | 2 | Android AtomicFileでcommit前後/readback故障の永続状態 |
| `DngImportStorageAndroidTest` | 3 | 合成DNG・MediaStoreのpending/公開/取消/応答喪失。`dngImportStorage=true`必須、skipは不合格 |
| `CaptureMessagesScreenshotTest#captureMajorStates` | 1 | software Canvasでメッセージ部品の合成画像。実画面合成ではない |
| `DevelopMessagesScreenshotAndroidTest#captureProductMessageStatesWithSyntheticImage` | 1 | 合成画像付き現像画面のViewツリー・文言・操作表示。保存ownerは動かさない |

全て `isolated=true`。fresh CI VMだけで実行し、写真・URI・ユーザーデータを送らない。既存のJVM codec試験は既にCIにあるため、ATDへ重複移植しない。

名前にJournal/Fileが入るだけで非GPUとは扱わない。`GpuSaveJournalSafetyAndroidTest`は実GPUレンダリングを呼ぶ。`GpuInputFilesSafetyAndroidTest`はDocumentsUI→GPU表示まで使うため対象外。

## CI構成の最小案

1. 現行と同じ標準 `ubuntu-26.04`、JDK25、SDK37.0/BuildTools36/NDK29/CMake3.22.1、既存Gradle wrapper。
2. `/dev/kvm`が存在しrunnerから使用可能か検査し、`emulator -accel-check`を記録。使えなければskipでなく失敗。
3. APKを先にbuildし、その後1台のATDだけ起動。buildとbootのCPU負荷を重ねない。
4. 既存GMD `:app:pixel9aapi37atdDebugAndroidTest` を使い、上記class/methodだけ指定。全connected試験や全androidTestは実行しない。
5. GitHubのsoftware rendering用公式引数 `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`、`--max-workers=1`、shardなし、`--rerun`。ATDをGPU評価へ転用しない。
6. 初回はPR限定の実験workflow（必須aggregateと独立、最大30分、concurrencyで旧実験のみcancel）。成功後も同じ固定headで再現性を確認してから常設化判断。
7. JUnit XMLで8methodが各1回・failure/error/skip0かを検査。未実行/UP-TO-DATE/欠測/assumption skipは成功扱いにしない。
8. HTML/XML、起動/実行ログ、fingerprint・image revision・emulator版、生成PNGをartifactへ。PNGが実在しdecode可能で、ケース数が一致し、PNGをdecodeでき、画面画像としての最小寸法（320px）を満たすことを確認。寸法は記録するが、Windows版とのピクセル寸法一致はこの試行の条件にしない。合成画像と明記し短い保存期間（例7日）。

GMDの生成画像回収先は初回ログで確認する。既存試験は `additionalTestOutputDir` を使うため、引数/端末からhostへの回収が成立することも実測項目。GMDが管理する端末を手動read-only複製して同時使用しない。ローカル並列AVDとは運用を分ける。

## 制約・費用・採用条件

- GitHub標準Linux public runnerは4core/16GB、Android VM加速に対応。public repoの標準runner計算時間は無料。larger runnerへは変更しない。artifact/cacheの保存量と取得時間は別に管理する。
- 公式ATD資料はhardware rendering無効、hardware screenshot非対応を明記。SystemUI/IME/Settings/標準アプリに依存する試験はローカルAOSPへ残す。
- GitHub/LinuxとWindowsでfont/rasterizer差があり得るため、既存WindowsのPNGとの差を直ちに製品バグ/基準更新にしない。CI用固定image/density/fontScale/localeで内容を評価し、前後比較は同じ環境で採取。
- 公式ATDサンプルはAPI30と記載される一方、repoは提供済みAPI37 imageを使用している。SDK packageの提供・起動・AGP対応はCIで実確認が必要。
- GPU90/帯一致・実GPU保存/preview・frame lifecycle・実画面合成・実機カメラ/速度/RAMは移行しない。
- CI所要時間と安定性は未実測。成功したら非GPU選択群をCIへ段階追加し、手元AVD待ちを減らす。現時点で必須化/ローカル試験削除はしない。

## 一次資料

- Android GMD/ATD、GitHub用software rendering引数、ATDの制約: https://developer.android.com/studio/test/managed-devices
- GitHub hosted runner資源・Android加速: https://docs.github.com/en/actions/reference/runners/github-hosted-runners
- public standard runner計算時間と保存課金の区別: https://docs.github.com/en/billing/concepts/product-billing/github-actions
- repo実測制約: `docs/MANAGED_DEVICES.md`、#192
