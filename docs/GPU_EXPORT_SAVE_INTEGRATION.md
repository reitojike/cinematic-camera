# GPU 原寸保存の接続確認（#286 Draft）

## 対象と依存

#338 の `9e36037` を基点とする stack。帯 renderer / shader は変更しない。
`Original24Attempt` の CPU/GPU 分岐、専用 GL owner、手動保存 owner の失敗と明示 retry、画面表示を対象とする。
JPEG codec、MediaStore publication、原本 fingerprint / snapshot 契約は置き換えない。

保存で保持する元の原寸 Bitmap は従来と同じ。192 MiB は engine の texture 台帳の予算であり、
Bitmap・native decode・JVM・driver を含めたプロセス全体のメモリ上限ではない。

## 再試行と停止

- GPU 画像処理中に失敗し、JPEG 未生成かつ各 owner の close を確認した時だけ `GPU_FAILED`。
- 保存ボタンの「GPU 保存失敗 · 再試行」から明示開始。通常 advance では同じ失敗を返す。
- CPU に切り替えず、開始時に固定したエンジン・同じ設定で原本の強い確認からやり直す。
- intent は維持する。process 再起動、close 不確実、intent 不一致、JPEG 後の失敗は再生成しない。
- 中断は GL 作業が source / Bitmap を使い終えるまで待ち、GL / EGL 終了後に原本と Bitmap を片付ける。
- GPU failure は固定 code と端末全体の累積 count を SharedPreferences に同期記録する。画面の count は当該 owner 内。
  aggregate の永続化失敗も再試行を許可しない。保存先・原本・exception message は記録しない。

## 試験

通常必須確認:

```sh
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
git diff --check
```

`ManualOriginalTrialSaveOwnerTest` は従来の intent / publication 試験に加え、GPU failure code/count、
明示 retry、同一 private proof の再利用、再起動、取消、世代失効、解放不確実、intent 改変を検証する。
`ManualTrialJpegSaveOwnerTest` は失敗情報を保ち、保存済みと誤表示しないことを検証する。

`Original24GpuOutputAndroidTest` は合成入力だけを使う opt-in。独立アプリ
`original24probe` または `gpudiagnostic`、emulator (`ranchu` / `goldfish`)、
instrumentation argument `gpuExportSave=true` の全条件が必要。実機では実行しない。

- 257×513、strength 0 / .75、warmth 0 / 60、EV 0 / .7 の4条件で、adapter の最終行が直接 renderer と完全一致。
- consumer failure 時の全 close と、JPEG 前の再試行可能な失敗分類。
- caller interruption 時に作業を放置せず drain し、取消として返すこと。

この Android test は DNG decode / JPEG / MediaStore / FGS / preview と保存の同時利用を検証しない。
これらは次の承認済み AVD / 実機確認の対象であり、host Mesa 結果を代用しない。

## この Draft の作業時確認

- pure Kotlin/JUnit で保存 owner 36件、固定 failure 表示3件、計39件が成功。
- UI snapshot 表示3件とGPU editor再開方針3件も、該当純粋部分を抽出して6/6成功。
- 実 renderer / GLSL を LWJGL Mesa GLES 3.2 に binding した host で adapter 3試験を実行し成功。
  thread の host binding は Java executor であり Android HandlerThread そのものではない。
- cloud の Android 一式は Gradle 配布取得不能、SDK 未配置のため未実行。公式 PR CI が必要。
- 実機操作、製品 merge は未実施。

## GPU 専用編集画面との接続

#338 に main の #339 が取り込まれた `736c8e7` に追従した。#340 は含めない。
GPU 専用 editor も同じ save owner の失敗理由・件数を表示し、同じ明示 retry を開始する。
保存開始前に preview session を close し、既存 retired-owner の終了確認を IO 上で待つ。
確認できなければ export を開始しない。プレビュー停止後も保存 handle を保持し、
GPU_FAILED の再試行・同じ publication の再照合に限って表示済みプレビューなしで続行できる。
保存中・HOLD・publication 不明・GPU_FAILEDではプレビューを自動再開せず、READY / PUBLISHED
でだけ明示「プレビューを再開」を許可する。GPU_FAILEDでは同じGPU保存の明示retryに限定する。設定・画像の自動再計算は追加しない。
この接続の Compose / foreground / GPU owner 排他は AVD の最終統合確認が必要。

Home / trim 復帰でも停止状態と保存 handle を維持する。Activity 再作成後の入力読込でも、
取得した保存 owner が READY / PUBLISHED 以外なら GPU 写真を開く前に止める。
