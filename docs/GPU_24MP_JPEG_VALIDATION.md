# 24MP 原寸GPU JPEG公開の合成回帰（#101 partial）

既存 `Original24GpuSafetyInstrumentationTest` の12MP試験を保持し、明示opt-inの6000×4000単発公開を追加する。製品コード、画素式、crop、64MiB/768MiB等guard、timeout、依存は変更しない。

## 検査と限界

合成DNGを共有領域へ作成し、製品の原寸保存factoryからGPUでJPEGを生成・MediaStore公開する。実EGL contextでの処理到達、snapshot/RGB16の存在と寸法、PUBLISHED journalとURI/原本/recipeの一致、JPEG fingerprint・6000×4000・sRGB、縮小decodeを確認する。原本hash不変、owner解放、snapshot/RGB16除去も検査する。

Activity/SAF/FGS全導線、回転ケース、実写画質、実機Pixel RAMや最悪同時所有の保証ではない。GPU内部texture ledgerの完全な収支は観測しない。原寸JPEGを縮小decodeすることをピクセル等価oracleとしない。

## 実行

通常GPU対応API37 AVDを別予約し、active instrumentationと関連PIDがないことを確認する。ATDは使わない。GPU診断APK/試験APKを既存データを消去せず `-r` で更新する。

```powershell
adb -s $serial shell am instrument -w -r -e isolated true -e gpuExportSafety true -e gpuExport24 true -e class dev.cinematic.p0.jpegrender.original.Original24GpuSafetyInstrumentationTest#optIn24MpGpuWritePublishesOriginalSizeAndReleasesOwners dev.cinematic.p0.gpudiagnostic.test/androidx.test.runner.AndroidJUnitRunner
```

大判引数なしではskip。既存準備120秒・保存600秒・終了60秒を緩和せず、15分枠を目安に確保する。OOM・原本変化・cleanup/公開不明は停止し、自作fixture証拠を保持する。成功時だけ正確なURI/name/owner/publishedを再検査して自作共有行を回収する。他担当の行・journalを削除しない。engine preferenceは元の値/キー有無を復旧し、終了処理の例外はprimaryにsuppressedで保持する。

実行結果はPRに最終head/条件とともに記録する。試験の追加だけを成功証拠としない。
