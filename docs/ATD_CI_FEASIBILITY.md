# 非GPU Android試験の ATD CI 移行案

## 結論と現在地

GitHub Actions の標準 Ubuntu runner 上で、API37.0 Google ATDの限定8件が完走した。
初回成功は [run 43658584033](https://github.com/reitojike/cinematic-camera/actions/runs/43658584033) / head `7942b5a`。JUnitを実回収して8method各1回、failure/error/skip0を確認し、software Canvas PNG44枚の回収・decodeも確認した。APK buildは3分24秒、試験を含むGradleは48秒（SDK準備・起動は別）。1回の成功だけで常設・必須化したとは扱わない。

調査時のmainは `c428945a`。製品コード・既存Android CI aggregate・GPUゲートは無変更。実験workflowは関連ファイルが変わるPRと手動実行にだけ反応する限定的な任意checkで、通常の製品PR全てにATDを追加する変更ではない。

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

## 現在のCI試行構成

1. 現行と同じ標準 `ubuntu-26.04`、JDK25、SDK37.0/BuildTools36/NDK29/CMake3.22.1、既存Gradle wrapper。
2. 本人の明示承認に基づき、使い捨てGitHub hosted runner内の`/dev/kvm`へ、そのjobの実行ユーザーだけread/write ACLを付与する。world-writable化・永続group/udev変更・本人PCの権限変更は行わない。その後runnerから使用可能か検査し、`emulator -accel-check`を記録。使えなければskipでなく失敗。
3. APKを先にbuildし、その後1台のATDだけ起動。buildとbootのCPU負荷を重ねない。
4. 同じATD imageを小さいuserdata・snapshot無しで直接1台起動。`ANDROID_SERIAL=emulator-5554`を固定し、`:app:connectedDebugAndroidTest`に上記class/methodだけ指定。全androidTestは実行しない。
5. emulatorに`-gpu swiftshader_indirect`、Gradleは`--max-workers=1`、shardなし、`--rerun`。ATDをGPU評価へ転用しない。
6. 初回はPR限定の実験workflow（必須aggregateと独立、最大30分、concurrencyで旧実験のみcancel）。成功後も同じ固定headで再現性を確認してから常設化判断。
7. JUnit XMLで許可リストのメソッドが各1回・failure/error/skip0かを検査。未実行/UP-TO-DATE/欠測/assumption skipは成功扱いにしない。
8. 公開する試験証跡は、許可リストに基づく検証要約と、成功時に限り、追加出力フォルダで個別に検証した合成 Canvas PNG に分ける。検証失敗時は安全な要約のみ。生ログ、JUnit XML/HTML、端末識別情報や未許可の PNG は公開しない。PNG の復号と最小寸法（320px）を検証する。Windows との寸法・画素一致はこの試行の条件にしない。保持は7日。

画像は`app/build/outputs/connected_android_test_additional_output/`へ回収された。既存試験の`additionalTestOutputDir`はconnected runnerが供給する。GMDが管理する端末を手動複製しない。使い捨てCIでは毎回snapshot無しの独立AVDを作り、ローカルの基準AVD/read-only並列運用とは分ける。

## 制約・費用・採用条件

- GitHub標準Linux public runnerは4core/16GB、Android VM加速に対応。public repoの標準runner計算時間は無料。larger runnerへは変更しない。artifact/cacheの保存量と取得時間は別に管理する。
- 公式ATD資料はhardware rendering無効、hardware screenshot非対応を明記。SystemUI/IME/Settings/標準アプリに依存する試験はローカルAOSPへ残す。
- GitHub/LinuxとWindowsでfont/rasterizer差があり得るため、既存WindowsのPNGとの差を直ちに製品バグ/基準更新にしない。CI用固定image/density/fontScale/localeで内容を評価し、前後比較は同じ環境で採取。
- 公式ATDサンプルはAPI30と記載される一方、repoは提供済みAPI37 imageを使用している。SDK packageの提供・起動・AGP対応はCIで実確認が必要。
- GPU90/帯一致・実GPU保存/preview・frame lifecycle・実画面合成・実機カメラ/速度/RAMは移行しない。
- 上記時間は初回成功の1点であり、安定性やPixel速度の評価ではない。同じ構成の再現性を確認してから対象拡大を判断する。現時点で必須化/ローカルGPU試験削除はしない。

## 一次資料

- Android GMD/ATD、GitHub用software rendering引数、ATDの制約: https://developer.android.com/studio/test/managed-devices
- GitHub hosted runner資源・Android加速: https://docs.github.com/en/actions/reference/runners/github-hosted-runners
- public standard runner計算時間と保存課金の区別: https://docs.github.com/en/billing/concepts/product-billing/github-actions
- repo実測制約: `docs/MANAGED_DEVICES.md`、#192

## CI実走による運用変更

GMDでの初回試行は、APK buildとKVM accel-checkまでは成功したが、snapshot作成で失敗した（容量不足の可能性というAGP診断であり、容量不足確定ではない）。使い捨てCI runnerではsnapshot再利用が不要なので、同じAPI37.0 ATDを2GiB userdata / 2GiB RAM・snapshot無しで直接起動する代替を試行する。`ANDROID_SERIAL=emulator-5554`を限定し、`connectedDebugAndroidTest`で同じ8件だけ実行する。ローカル基準AVDのread-only運用とは別である。この代替は上記runで成功した。GMD snapshotの根本原因の解決やGMD採用を示すものではない。

## 取得物・プライバシー

成功runのartifactは約3.38MB、7日保持。合成画像44枚、JUnit/HTML、SDK image revision、emulator版、起動と試験のログを含む。実写真・アカウント・端末原本は使用せず、fixture名は`SYNTHETIC.dng`。ログは新規の使い捨てATDのものだけである。代表画像も開いて、保存中の中央表示とバナーの両方が合成されていることを確認した。これをUI変更の本人承認や実画面コンポジターの保証とは扱わない。

初回成功のimage: `system-images;android-37.0;google_atd;x86_64`, revision 7, extension 22。snapshot無し、RAM2048MiB、userdata2GiB、1080×2424/density420。PNGは42枚が1080×2424、リセットダイアログ2枚が840×567。JUnit method時間合計は約17.93秒（suite time=0表記のためケース値を使用）。

## #425後のmain同期

main `7615d253`へ同期。Capture fixtureへカメラ権限なし・熱拒否直後・回転通常・回転録画中の4状態（各縦横）が加わったため、期待PNGは44枚から52枚へ増やした。実行method数は8件のまま。上述の44枚の記録は同期前の成功証拠であり、新しい52枚の合格を意味しない。同期後headでCIを再実行する。
