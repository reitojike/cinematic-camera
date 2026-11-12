# Android GPU 基準画像・回帰試験

関連: #290。現行 Stage Clear の出力を記録する試験で、製品の数式、CPU 既定、既存 CPU golden は変更しない。
候補生成と基準の採用は別。初回・意図した見た目の変更・AVD/ドライバー変更後の再生成は、本人の目視承認後だけ採用する。

## 実行する場面

CI では自動実行しない。以下の各 PR は最終候補 head で必ず実行し、commit/tree、件数、全成功・失敗・skip、環境、比較結果を記録する。

- GPU またはルックへ触れる PR のマージ前。
- CPU 経路の削除・リファクタリングの各 PR のマージ前。
- 環境の版を変えた場合。差の場所・最大差・全 RGB の p99.9・差のある画素割合を示して本人確認へ回す。

基準の初回採用前は、この試験が継続回帰を保護済みとは扱わない。shader compile、安全性、既存の帯/full・cache/fresh 試験は別に維持する。実写の Gate 2、実機性能、12MP の確認を代用しない。

## 固定した 90 条件

入力は既存 `GpuReferenceImages` の10種、seed `20261110`。別の画像生成器・ルック・シェーダーの写しは作らない。

| 群 | 条件 | 件数 |
|---|---|---:|
| 小型 | 全10種、256×192、強さ0/25/50/100、色温度0、EV0 | 40 |
| 小型調整 | DARK/SKIN/MIXED、256×192、強さ50、(色温度,EV)=(-100,0)/(100,0)/(0,-2)/(0,2)/(-100,-2)/(100,2) | 18 |
| プレビュー形状 | DARK/SKIN/MIXED、1024×768・768×1024・1024×576・886×887、強さ50、色温度0、EV0 | 12 |
| プレビュー複合 | 同3種、1024×768、強さ100、(-100,-2)/(100,2) | 6 |
| 保存 | 1600×1200・1597×1199、下記7条件 | 14 |

保存条件: GREY強さ0、DARK25、HDR_LIGHTS50、SKIN100/色温度-100/EV-2、SATURATED0、NEAR_WHITE_2 25、MIXED50/色温度100/EV2。他の色温度/EVは0。
各14条件で全画面→64行→256行を同じ実行内で処理し、最終RGB8は厳密一致。帯の画像は保存せず、差の最大・件数・最初の座標を残す。端数帯は両形状に含まれる。

プレビューは `DefaultStageClearGpu.openPhoto` による製品の一回の写真解析と既存 `ConnectedStageClearGpuPipeline` を使用する。
保存は既存 export parity と同じ固定 metered base EV=0 の合成入力を使う。全画面側に別の数式は作らず同じ GPU graph を使用し、帯側は製品 `GpuExportRenderer` を呼ぶ。
全画面保存の比較用台帳だけ512MiB、製品帯保存192MiBとプレビュー128MiBの予算は維持する。

## 保存形式・上限・比較方針

- 全画面の最終 RGB8（sRGB、左上から行順）を gzip 圧縮した `.rgb8.gz` のみ基準候補として保存する。JPEGは使わない。
- 90ファイルの合計20,000,000 bytesを上限の目安として検査し、超過したら採用を止めて条件削減を相談する。無断で条件や画質を減らさない。
- manifestに寸法・種・seed・調整値・入力解析方式・画像/file hash・帯検査結果を記録。host側に source commit/tree、実際のAPK hash、AVD構成hash、system image revision、emulator版、GPU driver、Android/ANGLE/GLES版を記録する。個人パス・端末シリアルは基準へ含めない。
- 同じ環境の比較は厳密RGB8一致。AVD/driver版が変われば環境差をHOLDにし、画素差の場所と大きさも出す。閾値を自動で緩めず、差を消す自動baseline更新もしない。
- 目視用PNGは同じRGB8から無変換で作る閲覧用の派生物。基準へ二重保存しない。ホストツールはPython標準ライブラリのみ。
- Mesa全工程の再利用・比較移行は v0.1.0 前のD0に含める。大きな別実装なら停止して相談。移行後は Mesa/driver の固定版を先に検討し、ジョブ増分約3分を実測する。現段階の Mesa CI 時間増分は0で、全工程を継続CIへ移したとは扱わない。

## 実行手順（Windows、共有AVDの使用許可が取れてから）

1. 最終ソースを commit し、作業treeをcleanにする。既存 ephemeral_debug 署名を使用し、署名鍵の作成・移動はしない。
2. 共有AVDの先行試験が終了したことを確認する。通常AVDを host GPU + `GuestAngle,Vulkan` で起動する。runner は serial からの AVD 名、実行中 QEMU の port/name/flags、AVD 内に emulator が生成した起動記録とその時刻を照合し、異なる枠・古い記録なら停止する。このツールはAVDの起動・停止・設定変更・実機操作をしない。
3. `JAVA_HOME` / `ANDROID_HOME` を既存SDK/JDKへ設定し、次を実行する。パスはローカル環境で指定する。

```powershell
./tools/gpu-golden/capture_android.ps1 -Serial emulator-5580 -AvdConfig <AVDのconfig.ini> -SdkRoot <既存SDK>
```

APKをそのclean treeから構築し、指定したエミュレーターだけに導入する。`isolated=true` と `gpuGolden=true` が必須。通常CI/通常UI試験では走らない。対象は `GpuGoldenAndroidTest#captureNinetyFullFramesAndCompareBothBandSizes` の1 method、正式90条件、帯比較28件。0件・skip・途中終了を成功にしない。

4. `build/gpu-golden/<run>/` の記録と review/index.html を確認し、全画像の目視を本人へ依頼する。失敗時はrun直下のhost.jsonにsource・取得済みAPK hash・AVD/driver版・失敗した工程を保持し、自動で再実行しない。PIDと個人パスを含むruntime-link.local.jsonはローカル証拠だけで、baseline採用には含めない。
5. 本人が対象候補を目視承認した後だけ、承認出典とともに次を実行する。出力先が既存なら上書きせず失敗する。

```text
python tools/gpu-golden/gpu_golden.py approve <candidate> <新しいbaselineディレクトリ> --approval-ref <本人の対象候補への目視承認参照>
python tools/gpu-golden/gpu_golden.py compare <承認済みbaseline> <次のcandidate> <新しい比較レポートディレクトリ>
```

比較では破損・欠落・未承認・条件変更・環境変更もHOLD。画像差は同寸法の基準/候補/差分32倍PNGと座標・最大差・p99.9を残す。承認のない基準はコミットしない。

## ツール自身の検査

```text
python -m unittest discover -s tools/gpu-golden -v
./gradlew :app:compileDebugAndroidTestKotlin
```

単体試験の架空承認文字列はツール動作の検証だけで、実GPU画像や本人承認の代わりにはならない。

## Current adopted baseline

The owner explicitly approved the unchanged 90-case current-output baseline on 2026-11-12, including known shared artifacts (#355). See [approval and limitations](../../docs/gpu-golden-baselines/README.md). Use that directory as the baseline argument to the existing manual comparator. This adds no automatic CI capture and does not relax exact RGB8 comparison.
