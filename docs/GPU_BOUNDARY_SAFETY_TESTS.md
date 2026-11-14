# GPU 保存境界の取消・再試行試験

#290 の診断整理に先立ち、`Original24GpuBoundaryCancellationAndroidTest` の2試験と
`GpuExportBoundaryFixture` を通常 `androidTest` に置く。診断variantの試験からも同じfixtureを参照する。
CPU実行・診断variantそのものの削除とは別の段階であり、共有型・V1値・旧保存データ識別は変えない。

## 保持する保証

- 合成512×770 DNGの2番目のB1帯完了、または2番目のFINAL帯内MISTで実際の保存所有者に取消を要求する。
- 指定した境界に到達し、その後のGPU帯callbackが進まないことを検証する。
- 取消時は未公開、原本fingerprint・固定intent不変、作業領域/テクスチャ/GL thread/所有者解放を検証する。
- 通常のprepare/advanceだけでは再開せず、明示retryだけが同世代の保存を完了する。
  次保存のエンジン選択変更で既存世代のGPU契約を変えず、再照合で二重処理・二重公開しない。
- 観測は工程名・帯座標・集計メモリだけ。テクスチャ・画素・URI・所有者を観測者へ渡さない。

## 実行条件と隔離

通常debugと既存gpuDiagnosticだけで観測登録を許可する。release/internal/rawDiagnostic等では拒否する。
未登録なら観測callbackはない。登録の置換は拒否し、scope終了で次の試験へ解放する。
`Original24GpuObservationGateTest` は各build typeの登録可否と登録所有権を検証する。

試験には `isolated=true` と `gpuExportBoundaries=true`、ranchu/goldfish emulatorが必須。
条件不足は失敗とし、Assumeでskipしない。**実機では実行しない。**
通常debugでは下記クラスだけを新しいinstrumentation processで実行し、全suiteや他クラスと混ぜない。
通常試験は専用の一時private領域とUUID付きprefsを使用し、既存アプリの保存領域・設定を復旧対象にしない。
合成MediaStore行だけをUUID名/owner/id/pending状態とfingerprintで検証して成功時に除去する。
失敗・不明時は合成行や記録を保持する。既存写真の消去、アプリdata clear、署名不一致の回避は行わない。
private領域の合成journalは証拠としてcache配下に保持し、公開ログに個人pathやURIを転載しない。

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
# 共有laneを取得し、対象がemulatorであることと署名を確認してAPKをinstall -r -tする。
& $adb -s $serial shell am instrument -w -r `
  -e class dev.cinematic.p0.jpegrender.original.Original24GpuBoundaryCancellationAndroidTest `
  -e isolated true -e gpuExportBoundaries true `
  dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
```

成功は2件、failure/error/skip 0と両境界の到達・retry完了・所有権検査を満たすこと。
0件、skip、report欠落、cleanup不明は合格にしない。head/tree/APK hash、コマンド、件数、時間、
失敗時の非公開証拠の場所を記録する。24MP受入・実機メモリ・画質・速度の保証とは区別する。
別途1024px最小計測とAndroid全工程90条件GPU基準画像回帰を最終headで実行する。
