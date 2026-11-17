# 本来プレビュー FULL/half 統合・合成性能入口

Issue #233。製品採用・実機操作・公開・mainマージは範囲外。

## 統合ベース

最新main `6d34d8c` に #455 `8f712d8a`、#456 `50a9e83`、#459 `7dff8100`
を独立ブランチ上で順に統合。Gitの競合マーカーは発生しなかった。
`InitialPreviewSurfaceTimingAndroidTest` は自動統合。元PRのブランチは変更なし。
GitHubのmergeable表示と実際のローカル統合結果は区別する。

新規差分は androidTest と本書のみ。製品既定・保存処理の変更は追加しない。
既存 #459 は品質比較用readback/Bitmap。今回の性能入口とは別である。

## 計測範囲

`PreviewHalfPerformanceAndroidTest` は FULL / HALF_CANDIDATE の本来入力を
ABBA順で実行する。half案内画像後にFULLを作る比較ではない。
#456と同じ `GpuPreviewTiming` を `LaunchedEffect(owner)` 開始時に作り、
`GpuPhotoRollSession` の通常画像 `onPresented`（swap成功）で終了する。
`firstFrameStarted → submit → openPhoto` も既存順序。
チェック・preflight・snapshot・native・BOX・verify・GPU初回を記録し、GUIDE未到達を検証。

ただしrepository/provider/受付/保存状態復元は使わず、私有の合成DNGで代替する。
したがって境界は対応しても製品全体の時間とは同一ではない。物理タップや表示光子、
Pixel速度・実写品質・採用の根拠にはしない。readback/Bitmap/画面撮影は実施しない。
各runで新規GL ownerを作るがOS/driver cacheは未制御。強制GC・warm/coldの断定はしない。
開始/終了thermal statusを併記。合成のみ、端末名・URI・入力画像は出力しない。

## 実行前ゲート

a5faa59 のhost assembleDebug / assembleDebugAndroidTest と、下記合成ABBAは実施済み。
同じ別ID構成のlintDebug / testDebugUnitTestも成功。既存Android全回帰は未完。
Windowsまたは共有AVDの枠は親担当と調整し、実機へは実行しない。
既存 #459 の15合成テスト成功を、この統合head・新規testの合格と読み替えない。

別IDでビルドする `-PinitialPreviewCompare=true` を必須とし、
`initialHalfGuide` と `cinematicHalfCompare` は指定しない。
実行は対象classのみ、instrumentation引数は以下：

- `class=dev.cinematic.p0.photoroll.PreviewHalfPerformanceAndroidTest`
- `isolated=true`
- `previewHalfPerformance=true`
- `previewHalfMegapixels=12`（または固定24）

emulator hardware、DEBUG、別ID、guide無効をコードで検査する。
合成入力は12MP=4000×3000、24MP=6000×4000。ABBAの4runがすべて
OPEN=PRESENTEDになるまで報告はincomplete。失敗時は不明なownerのファイルを再帰削除しない。
出力は評価アプリのexternal files内 `half-performance/synthetic-12mp.json` 等。
これは合成専用であり実写に転用しない。


## 2026-11-17 合成結果（a5faa59）

独立・read-only AOSP API37 emulator、host GPU + GuestAngle,Vulkan、4 vCPU / 4096MiB。
既存wrapperのinstance照合、90秒boot/system_server安定、ANR/crash 0、compute smoke成功。
対象の単独実行後に所有権照合して停止。実機・実写・保存経路には触れていない。
別IDのassembleDebug/AndroidTest成功（3分09秒）。instance証跡host単体試験40件成功。
12MP / 24MP 各4run、全てOPEN=PRESENTED、GUIDE=NOT_REACHED、thermal開始/終了すべて0。
以下は秒、各モード2標本のみ。p95・Pixelの推定値として使わない。

|入力|順|mode|OPEN|native decode|BOX reduce|GPU first frame|
|---|---|---|---:|---:|---:|---:|
|12MP|A1|FULL|8.8632|4.5957|2.6001|1.1290|
|12MP|B1|half|3.0165|0.8558|0.9857|0.6403|
|12MP|B2|half|2.9580|0.8443|0.9643|0.6503|
|12MP|A2|FULL|7.9695|4.5451|2.2575|0.6818|
|24MP|A1|FULL|14.8793|9.0630|3.8526|1.0727|
|24MP|B1|half|4.3682|1.3938|1.4160|0.6711|
|24MP|B2|half|4.3549|1.4189|1.3740|0.6599|
|24MP|A2|FULL|14.0752|8.9074|3.6181|0.6806|

この合成入力では差の主因はnative decodeとBOX reduce。初回GPU frameは両サイズとも
後続runより長い。cacheを制御していないためcold/warmの因果や固定倍率を断定しない。
画質差はこの性能テストでは検証しない。#455の画質比較と#459の私有評価を置き換えない。
本人の採否、承認後の実機同条件評価、統合headのCI/独立レビューは別ゲート。

同じ別ID構成のlintDebug / testDebugUnitTestは4分31秒で成功。JVM集計: 1443件、failure 0、error 0、skip 12。
通常ID構成およびprivate halfCompare構成の統合後再回帰・サーバーCIは別途必要。
