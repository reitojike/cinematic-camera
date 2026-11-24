# Android GPU 基準画像・回帰試験

関連: #290。現行 Stage Clear の出力を記録する試験で、製品の数式、CPU 既定、既存 CPU golden は変更しない。
候補生成と基準の採用は別。初回・意図した見た目の変更による新しい基準の採用には、本人の目視承認が必要。環境変更だけで既存基準を作り直さない。

## 実行する場面

CI では自動実行しない。以下の各 PR は最終候補 head で必ず実行し、commit/tree、件数、全成功・失敗・skip、環境、比較結果を記録する。

- GPU またはルックへ触れる PR のマージ前。
- CPU 経路の削除・リファクタリングの各 PR のマージ前。
- 環境の版を変えた場合。全90条件の全画素一致と他の必要試験成功を確認する。差がある場合だけ、場所・最大差・全 RGB の p99.9・差のある画素割合を示して本人確認へ回す。

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
- 環境によらず既存基準との全画素・厳密RGB8一致を確認する。AVD/driver版が変わっても全90条件が一致し、他の必要試験も成功なら環境差だけではHOLDしない。画素差があればHOLDし、場所と大きさを本人へ示す。閾値を自動で緩めず、差を消す自動baseline更新もしない。
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

4. `build/gpu-golden/<run>/` の記録と review/index.html を確認する。初回基準採用・出力差がある場合は本人へ目視確認を依頼し、既存基準との全画素一致では再目視を要求しない。失敗時はrun直下のhost.jsonにsource・取得済みAPK hash・AVD/driver版・失敗した工程を保持し、自動で再実行しない。PIDと個人パスを含むruntime-link.local.jsonはローカル証拠だけで、baseline採用には含めない。
5. 本人が対象候補を目視承認した後だけ、承認出典とともに次を実行する。出力先が既存なら上書きせず失敗する。

```text
python tools/gpu-golden/gpu_golden.py approve <candidate> <新しいbaselineディレクトリ> --approval-ref <本人の対象候補への目視承認参照>
python tools/gpu-golden/gpu_golden.py compare <承認済みbaseline> <次のcandidate> <新しい比較レポートディレクトリ>
```

比較では破損・欠落・未承認の基準・条件変更はHOLD。環境変更時の採否は下記の運用に従う。画像差は同寸法の基準/候補/差分32倍PNGと座標・最大差・p99.9を残す。承認のない基準はコミットしない。

### Mesa 候補の比較と登録

`compare` は Mesa の候補も受け付ける。`host.json` には `schema: 1`、`mesaEnabled: true`、`capture.json` と一致する空でない文字列の `runId` が必要。
`sourceCommit` と `sourceTree` は各40桁の小文字16進数、GL の4項目（`glVendor`、`glRenderer`、`glVersion`、`glslVersion`）は空白だけではない空でない文字列とする。
`host.json` の Android 用項目（`instrumentationPassed`、`sourceDirty`、`phase`、`appApkSha256`、`testApkSha256`、`environment`）と `capture.json` の `android` は、キーがあれば値に関係なく拒否する。
Android 候補では、`host.json` の Mesa 専用項目（`mesaEnabled`、`runId`、上記の GL 4項目、`javaVersion`、`osName`、`osArch`、`cpu`、`llvmpipe`、`baseLibraries`）と `capture.json` の `runId` を同じく拒否する。

環境の同一性には、描画結果に効く条件として、Mesa の版を含む `glVersion` などの GL 4項目、CPU の機種名（`cpu.modelName`）、llvmpipe の設定（`LP_NATIVE_VECTOR_WIDTH`、`GALLIUM_OVERRIDE_CPU_CAPS`）を記録する。
ライブラリの版は導入時に固定され、`host.json` の `baseLibraries` で事後に確認できるため、環境の同一性からは省く。ベクトル命令の有無は CPU の機種名と llvmpipe の設定で概ね把握できるため、`cpu.flags` も省く。
環境の同一性はレポート上の記録であり、記録項目の過不足は合否に影響しない。完全一致の合否は全画素の比較で決まる。

同じ形式の `host.json` はローカルでも作成できる。ホストの自己申告だけではクリーンなソースから採取した証明にはならず、`compare` は候補の由来を認証しない。運用では CI の成果物から取得した Mesa 候補に限って使用する。
`approve` で登録すると、`approval.json` の `approvedFiles` に `capture.json` と `host.json` のハッシュが記録され、承認が候補のファイルに結び付く。

## ツール自身の検査

```text
python -m unittest discover -s tools/gpu-golden -v
./gradlew :app:compileDebugAndroidTestKotlin
```

単体試験の架空承認文字列はツール動作の検証だけで、実GPU画像や本人承認の代わりにはならない。

## Current adopted baseline

現在の採用対象は、#355 の R7 foundation 修正後に本人が目視確認した `golden-20261112-131916-51b691ad` の90条件です。目視済み候補から原本byte不変で登録し、最新main同期後の再採取で一致を確認します。#349 で採用した既知黒化を含む旧runは履歴として保持し、現在の比較先とは区別します。[承認出典・現在の比較コマンド・実写未検証などの限界](../../docs/gpu-golden-baselines/README.md) を参照してください。自動CI採取や厳密RGB8比較の緩和は追加しません。

## 環境変更時の採否（#428 本人承認）

system image（API/ABI/revision/build）、emulator版、ホストGPUドライバーを必ず記録する。
全90条件の全画素が承認済み基準と厳密一致し、帯28比較・変更に必要な安全試験等も成功した場合、環境変更だけを理由にHOLDせず進める。
これは試験の欠落・skip・環境不成立を許容せず、基準更新・閾値緩和・未承認の製品変更を許可しない。
差がある場合だけ、その場所と大きさを提示して本人確認を得る。

比較ツールは全90条件の厳密一致で `status=pass` / 終了0を返し、環境変更だけではHOLDしない。
`environmentChanged` と比較前後のAndroid/host環境をレポートに残す。画素差は `hold` / 終了1、
欠落・破損・未承認基準・条件変更・環境情報不足・採取/帯試験失敗は非ゼロ終了のまま維持する。
これは画像比較の合格のみであり、他の必要安全試験・CI・レビューの成功を代替しない。PRで別々に照合する。
過去に出力されたHOLDレポートは変更しない。
本人承認と #428 の実証記録: https://github.com/reitojike/cinematic-camera/pull/428#issuecomment-6885931885

### Windows: 同一 AVD の read-only 並列起動（#192 / #290 / #429）

PowerShell 7 で、担当間の起動枠・容量確認後に、各 worktree から次を実行する。
実機は対象外。GPU は検証対象の AOSP + `host` / `GuestAngle,Vulkan` の通常起動であり、snapshot は使わない。

```powershell
pwsh -NoProfile -File tools/gpu-golden/start_gpu_instance.ps1 `
  -AvdConfig <基準AVDのconfig.ini> -AvdHome <AVD登録ディレクトリ> `
  -SdkRoot <AndroidSDK> -Port 5620
# 表示された INSTANCE_RECORD を、この serial の採取にだけ渡す
pwsh -NoProfile -File tools/gpu-golden/capture_android.ps1 `
  -Serial emulator-5620 -AvdConfig <同じconfig.ini> -SdkRoot <同じAndroidSDK> `
  -InstanceRecord <表示されたinstance.local.json>
```

- もう一台は別 worktree・別の偶数 port（例 5622）。各操作で `ANDROID_SERIAL` / `-s` を指定する。
- wrapper は AVD ごとの名前付き mutex で、起動から emulator 自身が書いた launch 原本の保全までを排他する。boot 待ちは排他の外。並列起動は全担当がこの wrapper を使い、基準更新は全 instance 停止後に行う。
- 原本は書き換えない。私有の `build/gpu-launch/<serial>-<UUID>/` に原本、PID・開始時刻・実コマンド、config の hash、guest boot UUID を保存し、完成時に digest ファイルを atomic rename する。完成前の記録は採取に使えない。
- 採取は開始時・build 後・instrumentation 後・pull 後に live process/config/boot と照合する。未登録、別 serial、曖昧な process/引数、PID 再利用、再起動、記録や config の変更は停止する。共有 launch ファイルが後の正規起動で上書きされても、保全した原本で照合する。
- digest は欠損・偶発的改変の検出であり、署名でも認証境界でもない。書き込み権限を持つ攻撃者の再計算を防ぐものではない。
- ローカル証拠には個人パス・PID を含むため公開しない。公開する baseline のメタデータには追加しない。`-InstanceRecord` を省略した従来の逐次採取では、共有原本と時刻の既存 gate をそのまま使う。
- 登録成功は boot 安定や GPU 正常の保証ではない。別途 system_server/ANR gate、最小 compute、既存 golden との全画素比較を実行する。両 instance の正規採取を実証するまでは並列準備完了としない。速度測定は単独で行う。
- read-only でも一時差分やログの容量は必要。通常は同時 2 台から始め、RAM・ディスク空き・CPU 負荷を確認する。登録失敗時は同一 PID/開始時刻/引数の自分の instance だけ停止する。自動再試行はしない。

AVD を起動しない単体試験:

```powershell
pwsh -NoProfile -File tools/gpu-golden/test_instance_evidence.ps1
```

#### instance 別採取の実証（2026-11-15）

`b82a9cd9` で、同じ AOSP 基準を read-only cold boot した 2 台について、
後起動による共有 launch 原本の上書き後にも、それぞれ保全済み `-InstanceRecord` から正規採取できた。
run ID は `golden-20261115-010325-b82a9cd9` と `golden-20261115-010400-b82a9cd9`。
両方90条件・帯28件・pull後のruntime照合成功、既存golden比較 `pass` / `environmentChanged=true` / 全画素差0。
基準更新なし。boot安定・guest隔離・終了確認と負荷の範囲は
[AVD運用記録](../../docs/AVD_READ_ONLY_BASELINES.md#gpu-read-only-2台の成立条件と実証)を参照。
並列時の所要時間は性能比較値として使わない。後続mainへの同期後の全画素実走を意味しない。
