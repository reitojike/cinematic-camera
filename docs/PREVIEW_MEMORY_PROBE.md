# GPU保存と次写真入力の並行安全試験

[#260](https://github.com/reitojike/cinematic-camera/issues/260) のうち、GPUで写真Aを保存している間に、別の写真Bを開く経路とBだけを取り消す経路を、異なる合成DNG（各512×770）で確認します。実写真は使用しません。

## 実行

[管理対象の仮想端末](MANAGED_DEVICES.md)と[GPUエンジンのAndroid試験手順](GPU_ENGINE.md)を参照してください。本試験はGPUを使うため、software描画のATDやPixelを指定しません。ローカルで実行が必要なときは対象の既存GPU AVD・操作・時間について都度承認を得て、予約したread-only枠だけを使用します。

通常debugのアプリ／androidTest APKを使い、承認済みAVDのserialを明示して、このクラスに限定します。serialは起動時に照合したものを設定してください。

```powershell
if ($env:ANDROID_SERIAL -notmatch '^emulator-[0-9]+$') {
  throw 'Set ANDROID_SERIAL to the reserved GPU emulator'
}
adb -s $env:ANDROID_SERIAL shell am instrument -w `
  -e class dev.cinematic.p0.jpegrender.original.GpuConcurrentInputSafetyAndroidTest `
  -e isolated true -e gpuExportBoundaries true -e gpuSaveSafety true `
  dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
```

対象の2件が実行され、失敗・スキップがないことを確認します。Aが最初のMIST帯に達した後、Bの入力解放とプレビュー、またはBだけの取消と解放を確認します。Aは同じ保存要求のまま完了し、原本不変・JPEGの再読取・GPU資源の解放を確認します。

## 記録と限界

必要に応じ、各試験の `GPU_CONCURRENT_MEMORY_PART=index/count:...` を番号順につないで、工程の境界で採取した観測を内部で確認します。公開するのは実行条件と匿名の結果要約のみです。

観測最大は真のピークとは限りません。時刻の異なる最大値は足さず、textureの割り当て、Bitmapの寸法換算値、ラスタのディスク使用量、プロセスのRAM観測を区別します。この小型試験は12／24MP、実機の必要RAMや速度、実画面・保存サービスのライフサイクル、AとBの同時RAW展開を保証しません。
