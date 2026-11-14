# 1024px GPU 全工程の最小計測

#290 の CPU・診断整理後も残す、通常 `androidTest` の短い実行確認。
`GpuFullPipelineTimingAndroidTest#measure1024FullRecompute` は診断 Activity / variant を使わない。
GPU 基準画像の90条件比較とは別に実行する。画像一致、実機の速度・画質、Gate 1 の合格を代替しない。

## 計測範囲

- 既存の合成 MIXED、seed `20261110`、1024×768、強さ0.5、色温度0、EV0。
- 製品の `openPhoto` で解析し、`FULL_RECOMPUTE` の同一写真 pipeline を使う。
- warmup 1 frame + 測定は既定3 frame（`measuredFrames` で1〜30を指定可能）。毎回同じ設定で R7 実行・Glow 準備を確認する。
- `GpuStageTap` の WARMTH から DISPLAY8 まで13工程を順序・重複を含めて検査する。
  INPUT はアップロード済み入力であり、処理工程の callback ではない。
- `renderForTest(DISPLAY8)` の前後で `glFinish`。時間は呼出し直前から完了同期後までの CPU wall。
  全工程、tap用WARMTH/最終RGBA8生成、DISPLAY8読戻しと変換を含む。
  EGL構築、入力生成、写真解析、close は含まない。工程ごとの同期や中間画像の読戻しは追加しない。
- 純 GPU 時間、通常画面への提示時間、保存時間ではない。速度閾値は設けない。
- 全frame後の写真・engine解放で台帳の bytes / texture数が0であることを検査する。

## 再実行

共有AVDの枠を取得し、既存の通常AVDを host GPU + `GuestAngle,Vulkan` で起動する。
既定はエミュレータのみ。実機の条件は下記を参照し、SDK/JDK/共有Gradleキャッシュは既存環境を利用する。
最終ソースをcommitし、clean headで通常debug APKとandroidTest APKを構築する。

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
# $adb は既存SDKのadb、$serial は確認済み emulator-xxxx のみ。
& $adb -s $serial install -r -t app/build/outputs/apk/debug/app-debug.apk
& $adb -s $serial install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s $serial shell am instrument -w -r `
  -e class dev.cinematic.p0.gpu.GpuFullPipelineTimingAndroidTest#measure1024FullRecompute `
  -e gpuPerformance true -e isolated true `
  dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner
```

各コマンドの失敗時は停止する。既存AVDへのAPK置換で署名不一致が出た場合はデータ消去で回避しない。
明示引数不足・許可なしの非emulator・非対応GLは失敗にする（Assumeによるskipは使わない）。
クラスを指定して実行し、無関係な試験や実機試験を一括実行しない。

### 引数と実機操作の境界

- `gpuPerformance=true` と `isolated=true` は常に必須。入力は合成画像だけで、撮影・保存・原本読取りは行わない。
- `allowDevice` は省略時false。小文字の `true` / `false` だけを許容する。
  hardwareが `ranchu` / `goldfish` 以外なら、明示 `-e allowDevice true` が必要。
  未知のhardwareも既定では拒否する。この引数は実行ガードであり、本人の操作承認を代替しない。
- **実機への接続・APKインストール・試験実行は都度、対象と手順を示して本人承認を得る。**
  この手順やコードのマージだけでは実機操作を許可しない。承認された対象serialを明示し、
  署名不一致は停止する。アンインストール・データ消去で回避しない。
- 測定数は `-e measuredFrames 2` のように指定する。省略時3、warmupは常に1。
  許容値は先頭ゼロ・符号・空白のない十進整数1〜30。短い確認試験として実行負荷とreport量を制限する。
  空文字、0、負数、小数、31以上、数値overflow、不正なbooleanはGPU確保前に失敗し、既定値へ丸めない。
- 実機でも既存のGL能力検査・13工程・再計算・資源解放検査を省略しない。
  エミュレータ上の引数試験は実機の動作・速度・画質確認には数えない。

PRには head/tree、コマンド、APK hash、AVD/system image/emulator/host driver版、
instrumentation全文のローカル保存先と公開可能な要約を記録する。
成功条件は `OK (1 test)`、failure/error/skip 0、
`gpuPerformanceReport` の `status=complete`、要求した `measuredFrames=N`、samples N+1（warmup 1）、
各13工程、正の `completedWallNanos`、closedBytes/closedTextures 0。
`executionTarget`（emulator/device）と `allowDevice` も照合する。
測定N件の時間を個別に記録する。0件・skip・途中終了・report欠落は合格にしない。
個人パスや端末識別子を公開記録に含めない。

別途 [GPU基準画像回帰](../tools/gpu-golden/README.md) を最終headで実施し、
性能試験の成功で基準画像比較の未実施を埋め合わせない。
