# 1024px GPU 全工程の最小計測

#290 の CPU・診断整理後も残す、通常 `androidTest` の短い実行確認。
`GpuFullPipelineTimingAndroidTest#measure1024FullRecompute` は診断 Activity / variant を使わない。
GPU 基準画像の90条件比較とは別に実行する。画像一致、実機の速度・画質、Gate 1 の合格を代替しない。

## 計測範囲

- 既存の合成 MIXED、seed `20261110`、1024×768、強さ0.5、色温度0、EV0。
- 製品の `openPhoto` で解析し、`FULL_RECOMPUTE` の同一写真 pipeline を使う。
- warmup 1 frame + 測定3 frame。毎回同じ設定で R7 実行・Glow 準備を確認する。
- `GpuStageTap` の WARMTH から DISPLAY8 まで13工程を順序・重複を含めて検査する。
  INPUT はアップロード済み入力であり、処理工程の callback ではない。
- `renderForTest(DISPLAY8)` の前後で `glFinish`。時間は呼出し直前から完了同期後までの CPU wall。
  全工程、tap用WARMTH/最終RGBA8生成、DISPLAY8読戻しと変換を含む。
  EGL構築、入力生成、写真解析、close は含まない。工程ごとの同期や中間画像の読戻しは追加しない。
- 純 GPU 時間、通常画面への提示時間、保存時間ではない。速度閾値は設けない。
- 全frame後の写真・engine解放で台帳の bytes / texture数が0であることを検査する。

## 再実行

共有AVDの枠を取得し、既存の通常AVDを host GPU + `GuestAngle,Vulkan` で起動する。
実機は禁止。SDK/JDK/共有Gradleキャッシュは既存環境を利用する。
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
明示引数不足・非emulator・非対応GLは失敗にする（Assumeによるskipは使わない）。
クラスを指定して実行し、無関係な試験や実機試験を一括実行しない。

PRには head/tree、コマンド、APK hash、AVD/system image/emulator/host driver版、
instrumentation全文のローカル保存先と公開可能な要約を記録する。
成功条件は `OK (1 test)`、failure/error/skip 0、
`gpuPerformanceReport` の `status=complete`、samples 4（warmup 1、測定3）、
各13工程、正の `completedWallNanos`、closedBytes/closedTextures 0。
測定3件の時間を個別に記録する。0件・skip・途中終了・report欠落は合格にしない。
個人パスや端末識別子を公開記録に含めない。

別途 [GPU基準画像回帰](../tools/gpu-golden/README.md) を最終headで実施し、
性能試験の成功で基準画像比較の未実施を埋め合わせない。
